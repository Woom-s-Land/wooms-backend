#!/usr/bin/env node
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fork, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WORKER_PATH = path.join(__dirname, 'worker.mjs');
const SAMPLER_PATH = path.join(__dirname, 'sample-resources.ps1');
const WORKER_COUNT = 4;
const ROOMS_PER_WORKER = 25;
const USERS_PER_ROOM = 10;
const WARMUP_SECONDS = 5;
const MEASUREMENT_SECONDS = 20;
const DRAIN_SECONDS = 10;
const PREPARATION_TIMEOUT_MS = 120_000;
const WATCHDOG_MS = 90_000;

function parseArgs(argv) {
  const result = { mode: null, fixture: null, workload: 'chat', rate: 1000, output: null, serverPids: [] };
  const valued = new Set(['mode', 'fixture', 'workload', 'rate', 'output', 'server-pids']);
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token === '--help' || token === '-h') { result.help = true; continue; }
    if (!token.startsWith('--')) throw new Error(`Unexpected argument: ${token}`);
    const [key, inlineValue] = token.slice(2).split('=', 2);
    if (!valued.has(key)) throw new Error(`Unknown option: --${key}`);
    const value = inlineValue ?? argv[++i];
    if (value == null || value.startsWith('--')) throw new Error(`Missing value for --${key}`);
    if (key === 'rate') result.rate = Number(value);
    else if (key === 'server-pids') result.serverPids = value.split(',').map((pid) => Number(pid));
    else result[key.replaceAll('-', '')] = value;
  }
  return result;
}

function help() {
  return `Usage: node coordinator.mjs --mode stomp|native --fixture TEMP.json --workload chat|move|sketch --rate N --output FILE [--server-pids PID,PID]\n\nRuns four workers, each owning 25 whole rooms (250 connections). Fixed profile: warmup ${WARMUP_SECONDS}s, measurement ${MEASUREMENT_SECONDS}s, drain ${DRAIN_SECONDS}s.`;
}

function validate(options) {
  if (options.help) return;
  if (!['stomp', 'native'].includes(options.mode)) throw new Error('--mode must be stomp or native');
  if (!options.fixture || !options.output) throw new Error('--fixture and --output are required');
  if (!['chat', 'move', 'sketch'].includes(options.workload)) throw new Error('--workload must be chat, move, or sketch');
  if (!Number.isFinite(options.rate) || options.rate <= 0) throw new Error('--rate must be positive');
  if (options.serverPids.some((pid) => !Number.isInteger(pid) || pid <= 0)) throw new Error('--server-pids must be comma-separated positive PIDs');
}

function delay(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }
async function withTimeout(promise, timeoutMs, message) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(message)), timeoutMs);
    })]);
  } finally { clearTimeout(timer); }
}
function nearestRank(values, percentile) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return Number(sorted[Math.min(sorted.length - 1, Math.ceil(percentile * sorted.length) - 1)].toFixed(4));
}

async function readLatencySamples(workerSummaries) {
  const all = [];
  const missing = [];
  for (const summary of workerSummaries) {
    const file = summary?.rawLatencyFileTemp;
    if (!file) continue;
    try {
      const contents = await fs.readFile(file, 'utf8');
      for (const line of contents.split(/\r?\n/).slice(1)) {
        if (!line) continue;
        const value = Number(line);
        if (Number.isFinite(value)) all.push(value);
      }
    } catch { missing.push(file); }
  }
  return { all, missingFiles: missing };
}

async function readResourceSamples(file) {
  try {
    const lines = (await fs.readFile(file, 'utf8')).replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean);
    return lines.map((line) => JSON.parse(line));
  } catch { return []; }
}

function resourceAggregate(samples, serverPids, workerPids, startEpochMs) {
  const sum = (items, key) => items.reduce((total, item) => total + (Number(item[key]) || 0), 0);
  const serverPeakPrivate = samples.reduce((peak, sample) => Math.max(peak, sample.serverPrivateBytesTotal || 0), 0);
  const serverPeakWorking = samples.reduce((peak, sample) => Math.max(peak, sample.serverWorkingSetBytesTotal || 0), 0);
  const workerPeakPrivate = samples.reduce((peak, sample) => Math.max(peak, sample.workerPrivateBytesTotal || 0), 0);
  const workerPeakWorking = samples.reduce((peak, sample) => Math.max(peak, sample.workerWorkingSetBytesTotal || 0), 0);
  const serverCpuSeconds = sum(samples, 'serverCpuDeltaSeconds');
  const workerCpuSeconds = sum(samples, 'workerCpuDeltaSeconds');
  const host = samples.map((sample) => sample.hostBusyPercentOfLogicalProcessors).filter(Number.isFinite);
  const processMap = new Map();
  for (const sample of samples) for (const process of sample.processes ?? []) {
    const current = processMap.get(process.pid) ?? { pid: process.pid, role: process.role, cpuDeltaSeconds: 0,
      peakWorkingSetBytes: 0, peakPrivateBytes: 0, missingSamples: 0 };
    current.cpuDeltaSeconds += Number(process.cpuDeltaSeconds) || 0;
    current.peakWorkingSetBytes = Math.max(current.peakWorkingSetBytes, process.workingSetBytes || 0);
    current.peakPrivateBytes = Math.max(current.peakPrivateBytes, process.privateBytes || 0);
    if (process.missing) current.missingSamples++;
    processMap.set(process.pid, current);
  }
  const firstSampleEpochMs = samples[0]?.epochMs ?? null;
  const lastSampleEpochMs = samples.at(-1)?.epochMs ?? null;
  return {
    sampleCount: samples.length,
    sampleWindowStartEpochMs: startEpochMs,
    sampleWindowEndEpochMs: startEpochMs == null ? null : startEpochMs + MEASUREMENT_SECONDS * 1000,
    firstSampleEpochMs,
    lastSampleEpochMs,
    firstSampleOffsetFromStartMs: firstSampleEpochMs == null || startEpochMs == null ? null : firstSampleEpochMs - startEpochMs,
    lastSampleOffsetFromEndMs: lastSampleEpochMs == null || startEpochMs == null ? null : lastSampleEpochMs - (startEpochMs + MEASUREMENT_SECONDS * 1000),
    logicalProcessors: samples.at(-1)?.logicalProcessors ?? os.availableParallelism(),
    cpuNormalization: 'Process CPU: 100% equals one logical core. Host busy percent is normalized across all reported logical processors; hostBusyOneCoreEquivalentPercent multiplies it by the logical processor count.',
    serverPids,
    workerPids,
    serverProcessCpuSeconds: Number(serverCpuSeconds.toFixed(4)),
    workerProcessCpuSeconds: Number(workerCpuSeconds.toFixed(4)),
    serverPeakPrivateBytes: serverPeakPrivate,
    serverPeakWorkingSetBytes: serverPeakWorking,
    workerPeakPrivateBytes: workerPeakPrivate,
    workerPeakWorkingSetBytes: workerPeakWorking,
    serverPeakAggregation: 'maximum same-sample sum across listed server PIDs',
    processSamples: [...processMap.values()].map((process) => ({ ...process,
      cpuDeltaSeconds: Number(process.cpuDeltaSeconds.toFixed(4)) })),
    hostBusyPercentOfLogicalProcessorsAverage: host.length ? Number((sum(host.map((value) => ({ value })), 'value') / host.length).toFixed(2)) : null,
    hostBusyPercentOfLogicalProcessorsMax: host.length ? Number(Math.max(...host).toFixed(2)) : null,
    hostBusyOneCoreEquivalentPercentMax: samples.length ? Math.max(...samples.map((sample) => sample.hostBusyOneCoreEquivalentPercent || 0)) : null,
    rawSamplesTemp: samples.length ? undefined : null,
  };
}

function aggregateRun(options, runId, startEpochMs, workers, resources, latency) {
  const summaries = workers.map((worker) => worker.summary).filter(Boolean);
  const sumPath = (pathParts) => summaries.reduce((total, summary) => {
    let value = summary;
    for (const key of pathParts) value = value?.[key];
    return total + (Number(value) || 0);
  }, 0);
  const attemptedConnections = WORKER_COUNT * ROOMS_PER_WORKER * USERS_PER_ROOM;
  const targetMessages = options.rate * MEASUREMENT_SECONDS;
  const cohortMessages = sumPath(['ingress', 'successfulCohortMessages']);
  const expectedFanout = sumPath(['delivery', 'expectedFanoutDeliveryCount']);
  const uniqueThroughDrain = sumPath(['delivery', 'uniqueDeliveriesTotalThroughDrain']);
  const missing = sumPath(['delivery', 'missingAfterDrain']);
  const duplicates = sumPath(['delivery', 'duplicateDeliveries']);
  const late = sumPath(['delivery', 'lateBeyondDrain']);
  const unmatched = sumPath(['errors', 'unmatchedDeliveries']);
  const workersReady = workers.filter((worker) => worker.ready).length;
  const workersCompleted = workers.filter((worker) => worker.summary != null).length;
  const workerOk = workersCompleted === WORKER_COUNT && workers.every((worker) => worker.summary?.ok === true);
  const targetAttainment = targetMessages > 0 ? cohortMessages / targetMessages : 0;
  const ok = workerOk && workersReady === WORKER_COUNT && !resources.watchdogTriggered
    && summaries.every((summary) => summary.connections?.attempted === 250
      && summary.connections?.readinessProbeComplete === 250
      && summary.connections?.liveBeforeCleanup === 250
      && summary.connections?.closedDuringRun === 0
      && summary.roomCount === ROOMS_PER_WORKER
      && summary.subscriptions?.totalReadyFanoutSlots === 250)
    && targetAttainment >= 0.98 && missing === 0 && uniqueThroughDrain === expectedFanout
    && duplicates === 0 && late === 0 && unmatched === 0 && latency.missingFiles.length === 0
    && resources.samples.length > 0;
  const processCpu = {
    userMs: sumPath(['generator', 'cpuUserMs']),
    systemMs: sumPath(['generator', 'cpuSystemMs']),
    totalMs: sumPath(['generator', 'processCpuMs']),
  };
  const serverPids = resources.serverPids;
  const workerPids = workers.map((worker) => worker.pid).filter(Number.isInteger);
  const resourceSummary = resourceAggregate(resources.samples, serverPids, workerPids, startEpochMs);
  resourceSummary.rawSamplesTemp = resources.rawPath;
  resourceSummary.samplerExitCode = resources.samplerExitCode;

  return {
    ok,
    runId,
    mode: options.mode,
    workload: options.workload,
    endpoint: summaries[0]?.endpoint ?? (options.mode === 'native' ? 'ws://127.0.0.1:8090/ws' : 'ws://localhost:8080/ws'),
    profile: { workerCount: WORKER_COUNT, roomsPerWorker: ROOMS_PER_WORKER, usersPerRoom: USERS_PER_ROOM,
      connections: attemptedConnections, totalTargetMsgPerSecond: options.rate, workerTargetMsgPerSecond: options.rate / WORKER_COUNT,
      warmupSeconds: WARMUP_SECONDS, measurementSeconds: MEASUREMENT_SECONDS, drainSeconds: DRAIN_SECONDS },
    coordination: { startEpochMs, workersReady, workersCompleted, readinessTimeoutMs: PREPARATION_TIMEOUT_MS,
      watchdogMs: WATCHDOG_MS, watchdogTriggered: resources.watchdogTriggered },
    connections: {
      attempted: attemptedConnections,
      readinessProbeComplete: sumPath(['connections', 'readinessProbeComplete']),
      liveBeforeCleanup: sumPath(['connections', 'liveBeforeCleanup']),
      closedDuringRun: sumPath(['connections', 'closedDuringRun']),
      readyRoomSlots: sumPath(['subscriptions', 'totalReadyFanoutSlots']),
    },
    ingress: {
      targetMessages,
      successfulCohortMessages: cohortMessages,
      actualMsgPerSecond: Number((cohortMessages / MEASUREMENT_SECONDS).toFixed(4)),
      targetAttainment: Number(targetAttainment.toFixed(5)),
      applicationBodyBytes: sumPath(['ingress', 'applicationBodyBytes']),
      protocolTextBytes: sumPath(['ingress', 'protocolTextBytes']),
      protocolTextBytesDefinition: 'UTF-8 application-protocol text; excludes WebSocket and TCP framing/headers',
      workerPacingLagMs: workers.map((worker) => ({ workerId: worker.workerId, pid: worker.pid,
        lag: worker.summary?.ingress?.pacingLagMs ?? null })),
    },
    delivery: {
      expectedFanoutDeliveryCount: expectedFanout,
      uniqueDeliveriesInMeasurementWindow: sumPath(['delivery', 'uniqueDeliveriesInMeasurementWindow']),
      uniqueDeliveriesAfterMeasureEndDuringDrain: sumPath(['delivery', 'uniqueDeliveriesAfterMeasureEndDuringDrain']),
      uniqueDeliveriesTotalThroughDrain: uniqueThroughDrain,
      missingAfterDrain: missing,
      lateBeyondDrain: late,
      duplicateDeliveries: duplicates,
      unexpectedRecipients: sumPath(['delivery', 'unexpectedRecipients']),
      sequenceReversals: sumPath(['delivery', 'sequenceReversals']),
      unmatchedDeliveries: unmatched,
      correlatedProtocolTextBytesReceived: sumPath(['delivery', 'correlatedProtocolTextBytesReceived']),
      latencyMsPooledFromAllWorkerSamples: {
        samples: latency.all.length,
        p50: nearestRank(latency.all, 0.50),
        p95: nearestRank(latency.all, 0.95),
        p99: nearestRank(latency.all, 0.99),
        max: latency.all.length ? Number(latency.all.reduce((peak, value) => Math.max(peak, value), -Infinity).toFixed(4)) : null,
        missingWorkerFiles: latency.missingFiles,
      },
    },
    generator: {
      workerProcessCpuMs: processCpu,
      workerSummaries: workers.map((worker) => ({ workerId: worker.workerId, pid: worker.pid, ok: worker.summary?.ok ?? false,
        connections: worker.summary?.connections ?? null, ingress: worker.summary?.ingress ?? null,
        delivery: worker.summary?.delivery ?? null, generator: worker.summary?.generator ?? null,
        failure: worker.failure ?? null, exitCode: worker.exitCode ?? null })),
    },
    resources: resourceSummary,
    failures: workers.filter((worker) => worker.failure || worker.exitCode !== 0 || !worker.summary?.ok)
      .map((worker) => ({ workerId: worker.workerId, pid: worker.pid, failure: worker.failure, exitCode: worker.exitCode,
        summaryOk: worker.summary?.ok ?? null })),
  };
}

function consoleSummary(summary) {
  return {
    ok: summary.ok,
    mode: summary.mode,
    workload: summary.workload,
    endpoint: summary.endpoint,
    profile: summary.profile,
    coordination: summary.coordination,
    connections: summary.connections,
    ingress: { targetMessages: summary.ingress.targetMessages,
      successfulCohortMessages: summary.ingress.successfulCohortMessages,
      actualMsgPerSecond: summary.ingress.actualMsgPerSecond, targetAttainment: summary.ingress.targetAttainment,
      applicationBodyBytes: summary.ingress.applicationBodyBytes, protocolTextBytes: summary.ingress.protocolTextBytes,
      workerPacingLagMs: summary.ingress.workerPacingLagMs },
    delivery: { expectedFanoutDeliveryCount: summary.delivery.expectedFanoutDeliveryCount,
      uniqueDeliveriesInMeasurementWindow: summary.delivery.uniqueDeliveriesInMeasurementWindow,
      uniqueDeliveriesAfterMeasureEndDuringDrain: summary.delivery.uniqueDeliveriesAfterMeasureEndDuringDrain,
      uniqueDeliveriesTotalThroughDrain: summary.delivery.uniqueDeliveriesTotalThroughDrain,
      missingAfterDrain: summary.delivery.missingAfterDrain, lateBeyondDrain: summary.delivery.lateBeyondDrain,
      duplicateDeliveries: summary.delivery.duplicateDeliveries,
      latencyMsPooledFromAllWorkerSamples: summary.delivery.latencyMsPooledFromAllWorkerSamples },
    generator: { workerProcessCpuMs: summary.generator.workerProcessCpuMs },
    resources: { sampleCount: summary.resources.sampleCount, firstSampleOffsetFromStartMs: summary.resources.firstSampleOffsetFromStartMs,
      lastSampleOffsetFromEndMs: summary.resources.lastSampleOffsetFromEndMs,
      logicalProcessors: summary.resources.logicalProcessors, serverProcessCpuSeconds: summary.resources.serverProcessCpuSeconds,
      workerProcessCpuSeconds: summary.resources.workerProcessCpuSeconds,
      serverPeakPrivateBytes: summary.resources.serverPeakPrivateBytes,
      workerPeakPrivateBytes: summary.resources.workerPeakPrivateBytes,
      hostBusyPercentOfLogicalProcessorsAverage: summary.resources.hostBusyPercentOfLogicalProcessorsAverage },
    resourceSamplerOk: summary.resourceSamplerOk,
    failures: summary.failures,
    preparationFailure: summary.preparationFailure,
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) { process.stdout.write(`${help()}\n`); return; }
  validate(options);
  const fixturePath = path.resolve(options.fixture);
  const fixture = JSON.parse(await fs.readFile(fixturePath, 'utf8'));
  if (!Array.isArray(fixture.rooms) || fixture.rooms.length < 100
      || fixture.rooms.slice(0, 100).some((room) => !Array.isArray(room.users) || room.users.length !== USERS_PER_ROOM)) {
    throw new Error('Fixture must provide at least 100 complete rooms with 10 users each');
  }

  const runId = randomUUID();
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), `realtimecompare-coordinator-${runId}-`));
  const resourcePath = path.join(tempDir, 'resources.jsonl');
  const startSignalPath = path.join(tempDir, 'measurement-start.epoch');
  const samplerReadyPath = path.join(tempDir, 'sampler.ready');
  const workers = Array.from({ length: WORKER_COUNT }, (_, index) => ({
    workerId: String(index + 1), roomStart: index * ROOMS_PER_WORKER, pid: null, child: null,
    ready: false, summary: null, failure: null, exitCode: null, exited: false,
  }));
  let startEpochMs = null;
  let sampler = null;
  let samplerExitCode = null;
  let watchdogTriggered = false;
  let readyResolve;
  let readyReject;
  let exitResolve;
  let readySettled = false;
  const readyBarrier = new Promise((resolve, reject) => { readyResolve = resolve; readyReject = reject; });
  readyBarrier.catch(() => {});
  const allExited = new Promise((resolve) => { exitResolve = resolve; });
  const maybeReady = () => {
    if (workers.every((worker) => worker.ready) && !readySettled) { readySettled = true; readyResolve(); }
  };
  const maybeExited = () => { if (workers.every((worker) => worker.exited)) exitResolve(); };

  for (const worker of workers) {
    const args = [WORKER_PATH, '--fixture', fixturePath, '--mode', options.mode, '--workload', options.workload,
      '--url', options.mode === 'stomp' ? 'ws://localhost:8080/ws' : 'ws://127.0.0.1:8090/ws',
      '--room-start', String(worker.roomStart), '--rooms', String(ROOMS_PER_WORKER),
      '--rate', String(options.rate / WORKER_COUNT), '--warmup', String(WARMUP_SECONDS),
      '--duration', String(MEASUREMENT_SECONDS), '--drain', String(DRAIN_SECONDS),
      '--connect-timeout', '120', '--connect-batch-size', '16', '--connect-batch-delay-ms', '25',
      '--subscribe-batch-delay-ms', '100',
      '--worker-id', worker.workerId, '--ipc'];
    worker.child = fork(args[0], args.slice(1), { stdio: ['ignore', 'ignore', 'pipe', 'ipc'], windowsHide: true });
    worker.pid = worker.child.pid;
    worker.child.stderr.on('data', (chunk) => {
      // Worker errors are already credential-redacted; retain only a short diagnostic tail.
      worker.failure = `${worker.failure ?? ''}${chunk.toString('utf8')}`.slice(-1200).replace(/Authorization=[^\s,;]+/gi, 'Authorization=[REDACTED]');
    });
    worker.child.on('message', (message) => {
      if (message?.type === 'ready') {
        worker.ready = message.connections === ROOMS_PER_WORKER * USERS_PER_ROOM
          && message.roomCount === ROOMS_PER_WORKER && message.roomStart === worker.roomStart;
        if (!worker.ready && !readySettled) { readySettled = true; readyReject(new Error(`worker-${worker.workerId}-ready-shape-invalid`)); }
        maybeReady();
      } else if (message?.type === 'result') {
        worker.summary = message.summary;
      } else if (message?.type === 'failed') {
        worker.failure = String(message.message ?? 'worker failed').slice(0, 500);
        if (!readySettled) { readySettled = true; readyReject(new Error(`worker-${worker.workerId}-failed-before-ready`)); }
      }
    });
    worker.child.on('error', (error) => {
      worker.failure = `child-process-error:${error.code ?? 'unknown'}`;
      if (!readySettled) { readySettled = true; readyReject(new Error(`worker-${worker.workerId}-process-error`)); }
    });
    worker.child.on('exit', (code, signal) => {
      worker.exitCode = code;
      worker.exited = true;
      if (code !== 0 && !worker.failure) worker.failure = `child-exit:${code ?? signal ?? 'unknown'}`;
      if (!worker.ready && !readySettled) { readySettled = true; readyReject(new Error(`worker-${worker.workerId}-exited-before-ready`)); }
      maybeExited();
    });
  }

  sampler = spawn('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', SAMPLER_PATH, '-ServerPids', options.serverPids.join(','),
    '-WorkerPids', workers.map((worker) => String(worker.pid)).join(','),
    '-StartSignalPath', startSignalPath, '-ReadyPath', samplerReadyPath,
    '-DurationSeconds', String(MEASUREMENT_SECONDS),
    '-OutputPath', resourcePath], { windowsHide: true, stdio: 'ignore' });
  sampler.on('error', () => { samplerExitCode = -1; });

  let prepFailure = null;
  const preparationStartedAt = Date.now();
  try {
    const samplerReady = (async () => {
      while (true) {
        try { await fs.access(samplerReadyPath); return; } catch { }
        if (sampler.exitCode != null || samplerExitCode === -1) throw new Error('resource-sampler-failed-to-start');
        await delay(100);
      }
    })();
    await withTimeout(samplerReady, PREPARATION_TIMEOUT_MS, 'preparation-timeout-120s');
    const remainingPreparationMs = Math.max(1, PREPARATION_TIMEOUT_MS - (Date.now() - preparationStartedAt));
    await withTimeout(readyBarrier, remainingPreparationMs, 'preparation-timeout-120s');
  } catch (error) {
    prepFailure = error.message;
    for (const worker of workers) if (!worker.exited) worker.child.send({ type: 'stop' });
    await Promise.race([allExited, delay(3000)]);
    for (const worker of workers) if (!worker.exited) worker.child.kill();
    await Promise.race([allExited, delay(3000)]);
  }

  if (!prepFailure) {
    startEpochMs = Date.now() + 2000;
    await fs.writeFile(startSignalPath, String(startEpochMs), 'utf8');
    for (const worker of workers) worker.child.send({ type: 'start', startEpochMs });

    let runOutcome;
    try { await withTimeout(allExited, WATCHDOG_MS, 'watchdog'); runOutcome = 'completed'; }
    catch { runOutcome = 'watchdog'; }
    if (runOutcome === 'watchdog') {
      watchdogTriggered = true;
      for (const worker of workers) if (!worker.exited) worker.child.kill();
      await Promise.race([allExited, delay(5000)]);
    }
  }

  for (const worker of workers) if (!worker.exited) worker.child.kill();
  if (workers.some((worker) => !worker.exited)) await Promise.race([allExited, delay(3000)]);
  if (sampler) {
    if (prepFailure && sampler.exitCode == null) sampler.kill();
    if (sampler.exitCode == null) {
      try { await withTimeout(new Promise((resolve) => sampler.once('exit', (code) => { samplerExitCode = code; resolve(); })), 25_000, 'sampler-timeout'); }
      catch { samplerExitCode = -1; }
    }
    if (sampler.exitCode == null) sampler.kill();
    if (samplerExitCode == null) samplerExitCode = sampler.exitCode;
  }

  const samples = await readResourceSamples(resourcePath);
  const latency = await readLatencySamples(workers.map((worker) => worker.summary).filter(Boolean));
  const summary = aggregateRun(options, runId, startEpochMs, workers, {
    samples, serverPids: options.serverPids, rawPath: resourcePath, samplerExitCode, watchdogTriggered,
  }, latency);
  summary.preparationFailure = prepFailure;
  summary.resourceSamplerOk = samplerExitCode === 0 && samples.length >= MEASUREMENT_SECONDS - 1
    && Math.abs(summary.resources.firstSampleOffsetFromStartMs ?? Infinity) <= 1000
    && Math.abs(summary.resources.lastSampleOffsetFromEndMs ?? Infinity) <= 1500;
  summary.ok = summary.ok && summary.resourceSamplerOk && prepFailure == null;
  await fs.mkdir(path.dirname(path.resolve(options.output)), { recursive: true });
  await fs.writeFile(options.output, `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
  process.stdout.write(`${JSON.stringify(consoleSummary(summary), null, 2)}\n`);
}

main().catch((error) => {
  const safe = String(error?.message ?? 'coordinator failed')
    .replace(/Authorization=[^\s,;]+/gi, 'Authorization=[REDACTED]')
    .replace(/(?:eyJ[A-Za-z0-9_-]+\.){2}[A-Za-z0-9_.-]+/g, '[JWT-REDACTED]');
  process.stderr.write(`coordinator failed: ${safe}\n`);
  process.exitCode = 1;
});
