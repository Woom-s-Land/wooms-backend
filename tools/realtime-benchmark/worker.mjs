#!/usr/bin/env node
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { performance, monitorEventLoopDelay } from 'node:perf_hooks';
import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import { classifyDeliveryTime, fixedReadyRecipients, isSuccessfulRun } from './metrics.mjs';

const MAX_BUFFERED_BYTES = 64 * 1024 * 1024;
const ROOM_SIZE = 10;
const SKETCH_PROBE = { x: 31, y: 31, colorIndex: 11 };

function optionsFromArgs(argv) {
  const result = {
    fixture: null,
    mode: null,
    workload: 'chat',
    url: null,
    origin: 'http://localhost:5173',
    roomStart: 0,
    rooms: 25,
    rate: 1000,
    warmup: 5,
    duration: 20,
    drain: 10,
    workerId: '0',
    output: null,
    connectTimeout: 30,
    connectBatchSize: 16,
    connectBatchDelayMs: 25,
    subscribeBatchDelayMs: 100,
    ipc: false,
    help: false,
  };
  const valued = new Set(['fixture', 'mode', 'workload', 'url', 'origin', 'room-start', 'rooms', 'rate',
    'warmup', 'duration', 'drain', 'worker-id', 'output', 'connect-timeout', 'connect-batch-size',
    'connect-batch-delay-ms', 'subscribe-batch-delay-ms']);
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token === '--help' || token === '-h') { result.help = true; continue; }
    if (token === '--ipc') { result.ipc = true; continue; }
    if (!token.startsWith('--')) throw new Error(`Unexpected argument: ${token}`);
    const [rawKey, inlineValue] = token.slice(2).split('=', 2);
    if (!valued.has(rawKey)) throw new Error(`Unknown option: --${rawKey}`);
    const value = inlineValue ?? argv[++i];
    if (value == null || value.startsWith('--')) throw new Error(`Missing value for --${rawKey}`);
    const key = ({ 'room-start': 'roomStart', 'worker-id': 'workerId', 'connect-timeout': 'connectTimeout',
      'connect-batch-size': 'connectBatchSize', 'connect-batch-delay-ms': 'connectBatchDelayMs',
      'subscribe-batch-delay-ms': 'subscribeBatchDelayMs' })[rawKey] ?? rawKey;
    result[key] = ['roomStart', 'rooms', 'rate', 'warmup', 'duration', 'drain', 'connectTimeout',
      'connectBatchSize', 'connectBatchDelayMs', 'subscribeBatchDelayMs'].includes(key)
      ? Number(value) : value;
  }
  if (!result.url && result.mode === 'stomp') result.url = 'ws://localhost:8080/ws';
  if (!result.url && result.mode === 'native') result.url = 'ws://localhost:8090/ws';
  return result;
}

function help() {
  return `Usage: node worker.mjs --fixture TEMP.json --mode stomp|native [options]

Options:
  --workload chat|move|sketch       Default: chat
  --url URL                         Default: localhost:8080/ws or :8090/ws by mode
  --origin URL                      Default: http://localhost:5173
  --room-start N --rooms N          Contiguous whole-room slice; each room must contain 10 users
  --rate N                           Total messages/second assigned to this worker
  --warmup N --duration N --drain N Seconds; defaults 5, 20, 10
  --worker-id ID --output FILE       Optional summary JSON destination
  --connect-timeout N                Seconds; default 30
  --connect-batch-size N             Concurrent preparation connects per worker; default 16
  --connect-batch-delay-ms N         Delay between preparation waves; default 25
  --subscribe-batch-delay-ms N       Delay between subscription waves; default 100
  --ipc                               Wait for coordinator ready/start IPC messages

The worker uses the fixture's actual Authorization cookies and never prints them.
Raw latency samples are written only to the system TEMP directory.`;
}

function validateOptions(options) {
  if (options.help) return;
  if (!options.fixture) throw new Error('--fixture is required');
  if (!['stomp', 'native'].includes(options.mode)) throw new Error('--mode must be stomp or native');
  if (!['chat', 'move', 'sketch'].includes(options.workload)) throw new Error('--workload must be chat, move, or sketch');
  for (const name of ['roomStart', 'rooms', 'rate', 'warmup', 'duration', 'drain', 'connectTimeout',
    'connectBatchDelayMs', 'subscribeBatchDelayMs']) {
    if (!Number.isFinite(options[name]) || options[name] < 0) throw new Error(`--${name} must be a non-negative number`);
  }
  if (!Number.isInteger(options.roomStart) || !Number.isInteger(options.rooms) || options.rooms < 1 || options.rooms > 25)
    throw new Error('--room-start and --rooms must be integers; a worker may own at most 25 complete rooms');
  if (!Number.isInteger(options.connectBatchSize) || options.connectBatchSize < 1 || options.connectBatchSize > 128)
    throw new Error('--connect-batch-size must be an integer from 1 to 128');
  if (options.rate <= 0 || options.duration <= 0 || options.warmup < 0 || options.drain < 0 || options.connectTimeout <= 0)
    throw new Error('--rate and --duration must be positive; warmup/drain may be zero');
  if (!options.url) throw new Error('--url is required when mode is omitted');
}

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

async function connectInBatches(clients, timeoutMs, batchSize, delayMs) {
  const results = [];
  for (let offset = 0; offset < clients.length; offset += batchSize) {
    const batch = clients.slice(offset, offset + batchSize);
    results.push(...await Promise.allSettled(batch.map((client) => client.connect(timeoutMs))));
    if (offset + batchSize < clients.length && delayMs > 0) await sleep(delayMs);
  }
  return results;
}

function sendIpc(message) {
  if (typeof process.send !== 'function') return Promise.reject(new Error('IPC channel unavailable'));
  return new Promise((resolve, reject) => process.send(message, (error) => error ? reject(error) : resolve()));
}

async function waitForCoordinatedStart(options, connectionCount) {
  if (!options.ipc) return null;
  const startMessage = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      process.off('message', onMessage);
      reject(new Error('coordinator-start-timeout'));
    }, 120_000);
    function onMessage(message) {
      if (message?.type === 'stop') {
        clearTimeout(timeout);
        process.off('message', onMessage);
        reject(new Error('coordinator-stopped-worker'));
      } else if (message?.type === 'start' && Number.isFinite(message.startEpochMs)) {
        clearTimeout(timeout);
        process.off('message', onMessage);
        resolve(message.startEpochMs);
      }
    }
    process.on('message', onMessage);
  });
  startMessage.catch(() => {});
  await sendIpc({ type: 'ready', pid: process.pid, workerId: options.workerId,
    roomStart: options.roomStart, roomCount: options.rooms, connections: connectionCount });
  return startMessage;
}

function quantiles(values) {
  if (values.length === 0) return { p50: null, p95: null, p99: null, max: null };
  const sorted = [...values].sort((a, b) => a - b);
  const at = (p) => sorted[Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)];
  return { p50: at(0.5), p95: at(0.95), p99: at(0.99), max: sorted.at(-1) };
}

function parseStompFrames(client, chunk) {
  client.stompBuffer += chunk.toString('utf8');
  const frames = [];
  while (client.stompBuffer.length) {
    client.stompBuffer = client.stompBuffer.replace(/^(?:\r?\n)+/, ''); // heartbeats
    const end = client.stompBuffer.indexOf('\0');
    if (end < 0) break;
    const raw = client.stompBuffer.slice(0, end);
    client.stompBuffer = client.stompBuffer.slice(end + 1);
    if (!raw.trim()) continue;
    const boundary = raw.indexOf('\n\n');
    const crlfBoundary = raw.indexOf('\r\n\r\n');
    const splitAt = boundary < 0 ? crlfBoundary : crlfBoundary < 0 ? boundary : Math.min(boundary, crlfBoundary);
    if (splitAt < 0) continue;
    const delimiterLength = raw.startsWith('\r\n\r\n', splitAt) ? 4 : 2;
    const lines = raw.slice(0, splitAt).replace(/\r/g, '').split('\n');
    const headers = {};
    for (const line of lines.slice(1)) {
      const colon = line.indexOf(':');
      if (colon > 0) headers[line.slice(0, colon)] = line.slice(colon + 1);
    }
    frames.push({ command: lines[0], headers, body: raw.slice(splitAt + delimiterLength),
      protocolTextBytes: Buffer.byteLength(raw, 'utf8') + 1 });
  }
  return frames;
}

const SAFE_NATIVE_ERROR_MESSAGES = new Set([
  'command failed', 'frame too large', 'invalid command', 'unsupported operation',
  'invalid subscription id', 'subscription limit exceeded', 'destination not allowed',
  'invalid room id', 'missing message body', 'invalid message body', 'message body must be an object',
  'room authorization failed', 'access token expired', 'invalid chat content', 'invalid move message',
]);

function safeNativeErrorLabel(message) {
  const normalized = typeof message === 'string' ? message.toLowerCase().slice(0, 100) : '';
  if (!SAFE_NATIVE_ERROR_MESSAGES.has(normalized)) return 'native-error-other';
  return `native-error-${normalized.replace(/[^a-z0-9]+/g, '-')}`;
}

class BenchmarkClient {
  constructor({ mode, url, origin, fixtureUser, room, globalIndex, onApplication, onProtocolError, onClosed }) {
    this.mode = mode;
    this.url = url;
    this.origin = origin;
    this.user = fixtureUser;
    this.room = room;
    this.globalIndex = globalIndex;
    this.onApplication = onApplication;
    this.onProtocolError = onProtocolError;
    this.onClosed = onClosed;
    this.ws = null;
    this.stompBuffer = '';
    this.opened = false;
    this.protocolReady = false;
    this.readyProbeSenders = new Set();
    this.subscriptionSequence = 0;
    this.heartbeat = null;
    this.closeCode = null;
  }

  get id() { return this.user.userUuid; }
  get nickname() { return this.user.nickname; }

  async connect(timeoutMs) {
    const socket = new WebSocket(this.url, {
      headers: { Cookie: this.user.cookie, Origin: this.origin },
      handshakeTimeout: timeoutMs,
      perMessageDeflate: false,
      maxPayload: 1024 * 1024,
    });
    this.ws = socket;
    let resolveReady;
    let rejectReady;
    const ready = new Promise((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
    const timeout = setTimeout(() => rejectReady(new Error('protocol-ready-timeout')), timeoutMs);
    socket.on('open', () => {
      this.opened = true;
      if (this.mode === 'stomp') {
        this.sendRaw('CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nhost:localhost\n\n\0', (error) => {
          if (error) rejectReady(new Error('stomp-connect-write-failed'));
        });
      }
    });
    socket.on('message', (data) => {
      if (this.mode === 'stomp') {
        for (const frame of parseStompFrames(this, Buffer.isBuffer(data) ? data : Buffer.from(data))) {
          if (frame.command === 'CONNECTED') {
            this.protocolReady = true;
            resolveReady();
          } else if (frame.command === 'MESSAGE') {
            this.onApplication(this, frame.headers.destination, frame.body, frame.protocolTextBytes, 'stomp');
          } else if (frame.command === 'ERROR') {
            this.onProtocolError('stomp-error-frame');
            if (!this.protocolReady) rejectReady(new Error('stomp-error-frame'));
          } else if (frame.command === 'RECEIPT') {
            // Receipts are accepted for diagnostics, but readiness is confirmed by application probes.
          }
        }
      } else {
        let envelope;
        try { envelope = JSON.parse(data.toString()); }
        catch { this.onProtocolError('invalid-native-json'); return; }
        if (envelope.op === 'CONNECTED') {
          this.protocolReady = true;
          resolveReady();
        } else if (envelope.op === 'MESSAGE') {
          this.onApplication(this, envelope.destination, envelope.body, Buffer.byteLength(data), 'native');
        } else if (envelope.op === 'ERROR') {
          this.onProtocolError(safeNativeErrorLabel(envelope.message));
          if (!this.protocolReady) rejectReady(new Error('native-error-envelope'));
        } else if (envelope.op === 'PONG') {
          // Keepalive only.
        }
      }
    });
    socket.on('unexpected-response', (_request, response) => {
      rejectReady(new Error(`websocket-http-${response.statusCode ?? 'unknown'}`));
    });
    socket.on('error', (error) => {
      const code = error?.code || error?.name || 'websocket-error';
      this.onProtocolError(String(code).slice(0, 80));
      if (!this.protocolReady) rejectReady(new Error(String(code).slice(0, 80)));
    });
    socket.on('close', (code) => {
      this.closeCode = code;
      if (!globalThis.cleanupStarted) this.onClosed(this, code);
      if (!this.protocolReady) rejectReady(new Error(`websocket-closed-${code}`));
    });
    try {
      await ready;
      clearTimeout(timeout);
      this.heartbeat = setInterval(() => {
        if (this.ws?.readyState !== WebSocket.OPEN) return;
        if (this.mode === 'stomp') this.sendRaw('\n');
        else this.sendControl({ op: 'PING' });
      }, 10_000);
    } catch (error) {
      clearTimeout(timeout);
      throw error;
    }
  }

  sendRaw(text, callback) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      callback?.(new Error('socket-not-open'));
      return;
    }
    this.ws.send(text, callback);
  }

  sendControl(envelope) { this.sendRaw(JSON.stringify(envelope)); }

  async subscribe() {
    const destinations = [`/ws/wooms/${workloadTopic(this.room.workload)}/${this.room.roomId}`];
    if (this.room.workload === 'sketch') destinations.push('/user/queue/sketch/init');
    const writes = destinations.map((destination) => new Promise((resolve, reject) => {
      const id = `bench-${this.globalIndex}-${this.subscriptionSequence++}`;
      const frame = this.mode === 'stomp'
        ? `SUBSCRIBE\nid:${id}\ndestination:${destination}\nack:auto\n\n\0`
        : JSON.stringify({ op: 'SUBSCRIBE', id, destination });
      this.sendRaw(frame, (error) => error ? reject(error) : resolve());
    }));
    await Promise.all(writes);
  }

  publish(destination, bodyText, bodyObject, callback) {
    const encoded = encodeProtocolSend(this.mode, destination, bodyText, bodyObject);
    this.sendRaw(encoded.text, callback);
    return encoded.protocolTextBytes;
  }

  close() {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
    if (this.ws && this.ws.readyState < WebSocket.CLOSING) this.ws.close(1000, 'benchmark complete');
  }
}

function workloadTopic(workload) {
  return workload === 'sketch' ? 'sketch/draw' : workload;
}

function encodeProtocolSend(mode, destination, bodyText, body) {
  if (mode === 'stomp') {
    const contentLength = Buffer.byteLength(bodyText, 'utf8');
    const text = `SEND\ndestination:${destination}\ncontent-type:text/plain;charset=UTF-8\ncontent-length:${contentLength}\n\n${bodyText}\0`;
    return { text, protocolTextBytes: Buffer.byteLength(text, 'utf8') };
  }
  const text = JSON.stringify({ op: 'SEND', destination, body });
  return { text, protocolTextBytes: Buffer.byteLength(text, 'utf8') };
}

function parseApplicationBody(raw, mode) {
  if (mode === 'native') {
    if (typeof raw === 'string') return JSON.parse(raw);
    if (raw && typeof raw === 'object') return raw;
    throw new Error('invalid-native-message-body');
  }
  return JSON.parse(raw);
}

function isSketchProbe(body, roomClientNames) {
  return body && body.tool === 'PEN' && body.x === SKETCH_PROBE.x && body.y === SKETCH_PROBE.y
    && body.colorIndex === SKETCH_PROBE.colorIndex && roomClientNames.has(body.nickname);
}

function createKey(workload, body) {
  if (workload === 'chat') {
    try {
      const inner = JSON.parse(body.content);
      if (inner.kind === 'probe') return { probePublisher: inner.publisherId };
      if (!inner.runId || !inner.publisherId || !Number.isInteger(inner.seq)) return null;
      return { key: JSON.stringify(['chat', inner.runId, inner.publisherId, inner.seq]), seq: inner.seq,
        publisherId: inner.publisherId };
    } catch { return null; }
  }
  if (workload === 'move') {
    if (Number.isInteger(body.stepId) && body.stepId < 0) return { probeNickname: body.nickname };
    if (typeof body.nickname !== 'string' || typeof body.x !== 'string' || typeof body.y !== 'string'
      || !Number.isInteger(body.direction) || !Number.isInteger(body.stepId)) return null;
    const seq = Number(body.x) - 100;
    if (!Number.isInteger(seq) || seq < 0) return null;
    return { key: JSON.stringify(['move', body.nickname, body.x, body.y, body.direction, body.stepId]), seq,
      publisherId: body.nickname };
  }
  if (isSketchProbe(body, new Set([body?.nickname]))) return { probeNickname: body.nickname };
  if (body && body.tool === 'PEN' && typeof body.nickname === 'string'
      && Number.isInteger(body.x) && Number.isInteger(body.y) && Number.isInteger(body.colorIndex)) {
    const seq = body.colorIndex * 1024 + body.y * 32 + body.x;
    if (seq >= 12_287) return null; // reserved probe tuple / wrap is not part of the measured workload
    return { key: JSON.stringify(['sketch', body.nickname, body.x, body.y, body.colorIndex, body.tool]), seq,
      publisherId: body.nickname };
  }
  return null;
}

function sampleStats(values) {
  const q = quantiles(values);
  return Object.fromEntries(Object.entries(q).map(([key, value]) => [key, value == null ? null : Number(value.toFixed(4))]));
}

async function readFixture(options) {
  const fixture = JSON.parse(await fs.readFile(options.fixture, 'utf8'));
  if (!Array.isArray(fixture.rooms)) throw new Error('Fixture file has no rooms array');
  const selected = fixture.rooms.slice(options.roomStart, options.roomStart + options.rooms);
  if (selected.length !== options.rooms) throw new Error('Requested room slice is outside the fixture file');
  const rooms = [];
  let globalIndex = 0;
  for (let i = 0; i < selected.length; i++) {
    const room = selected[i];
    if (!Array.isArray(room.users) || room.users.length !== ROOM_SIZE) {
      throw new Error(`Fixture room ${options.roomStart + i} does not have exactly ${ROOM_SIZE} users`);
    }
    const clients = room.users.map((user) => {
      if (!user.userUuid || !user.nickname || !user.cookie?.startsWith('Authorization='))
        throw new Error('Fixture user is missing expected authentication fields');
      return { fixtureUser: user, globalIndex: globalIndex++, room: null };
    });
    const roomRecord = { roomId: room.roomId, clients, workload: options.workload, probeNameSet: new Set(room.users.map((u) => u.nickname)) };
    for (const client of clients) client.room = roomRecord;
    rooms.push(roomRecord);
  }
  return { fixture, rooms, connectionCount: globalIndex };
}

function makeWorkloadBody(options, runId, client, seq, sentAt) {
  const user = client.user;
  if (options.workload === 'chat') {
    return {
      nickname: user.nickname,
      content: JSON.stringify({ runId, publisherId: user.userUuid, seq, sentAt, kind: 'data' }),
    };
  }
  if (options.workload === 'move') {
    return {
      nickname: user.nickname,
      x: String(100 + seq),
      y: '200',
      direction: seq % 4,
      costume: user.costume,
      stepId: seq % 4,
    };
  }
  const x = seq % 32;
  const y = Math.floor(seq / 32) % 32;
  const colorIndex = Math.floor(seq / 1024) % 12;
  if (x === SKETCH_PROBE.x && y === SKETCH_PROBE.y && colorIndex === SKETCH_PROBE.colorIndex)
    throw new Error('Sketch sequence reached reserved preparation probe tuple');
  return { nickname: user.nickname, tool: 'PEN', x, y, colorIndex };
}

function makeProbeBody(options, client, round) {
  const user = client.user;
  if (options.workload === 'chat') {
    return { nickname: user.nickname, content: JSON.stringify({ runId: 'bench-probe', publisherId: user.userUuid,
      seq: -1, round, kind: 'probe' }) };
  }
  if (options.workload === 'move') {
    return { nickname: user.nickname, x: '1000', y: '700', direction: 0, costume: user.costume, stepId: -1 };
  }
  return { nickname: user.nickname, tool: 'PEN', ...SKETCH_PROBE };
}

async function main() {
  const options = optionsFromArgs(process.argv.slice(2));
  if (options.help) { process.stdout.write(help()); return; }
  validateOptions(options);
  const { fixture, rooms, connectionCount } = await readFixture(options);
  const runId = randomUUID();
  const clientByNickname = new Map();
  const allClients = [];
  const perRoom = new Map();
  const errors = new Map();
  const entries = new Map();
  const latencySamples = [];
  const sequenceLastSeen = new Map();
  const counters = {
    attemptedSends: 0, sendSuccess: 0, sendFailures: 0, successfulWarmupSends: 0,
    cohortSendSuccess: 0, protocolErrors: 0, connectionCloseEvents: 0, probeSendFailures: 0,
    duplicates: 0, unexpectedRecipients: 0, sequenceReversals: 0, unmatchedDeliveries: 0,
    lateBeyondDrain: 0, deliveryBeforeMeasurement: 0,
    bufferLimitExceeded: false, probeMessagesSent: 0, setupProbeBodyBytesSent: 0,
    setupProbeProtocolTextBytesSent: 0,
  };
  let measurementStart = null;
  let warmEnd = null;
  let measurementEnd = null;
  let drainEnd = null;
  let maxBufferedAmount = 0;
  let aggregateBufferedNow = 0;
  let maxAggregateBufferedAmount = 0;
  let maxRssBytes = process.memoryUsage().rss;
  let maxHeapBytes = process.memoryUsage().heapUsed;
  let cpuStart = null;
  let cpuEnd = null;
  let measurementMetricsActive = false;
  let measurementMemoryAtEnd = null;
  const eventLoopDelay = monitorEventLoopDelay({ resolution: 10 });
  let eventLoopStats = { p50: null, p95: null, p99: null, max: null };
  let liveDuringRun = 0;
  const sampleTimer = setInterval(() => {
    if (!measurementMetricsActive) return;
    const memory = process.memoryUsage();
    maxRssBytes = Math.max(maxRssBytes, memory.rss);
    maxHeapBytes = Math.max(maxHeapBytes, memory.heapUsed);
    aggregateBufferedNow = 0;
    for (const client of allClients) {
      if (client.ws) aggregateBufferedNow += client.ws.bufferedAmount;
    }
    maxAggregateBufferedAmount = Math.max(maxAggregateBufferedAmount, aggregateBufferedNow);
  }, 100);

  function beginMeasurementMetrics() {
    if (measurementMetricsActive) return;
    measurementMetricsActive = true;
    eventLoopDelay.reset();
    cpuStart = process.cpuUsage();
    const memory = process.memoryUsage();
    maxRssBytes = memory.rss;
    maxHeapBytes = memory.heapUsed;
    maxBufferedAmount = 0;
    maxAggregateBufferedAmount = 0;
  }

  function endMeasurementMetrics() {
    if (!measurementMetricsActive) return;
    cpuEnd = process.cpuUsage(cpuStart);
    measurementMemoryAtEnd = process.memoryUsage();
    measurementMetricsActive = false;
    eventLoopStats = {
      p50: Number((eventLoopDelay.percentile(50) / 1e6).toFixed(4)),
      p95: Number((eventLoopDelay.percentile(95) / 1e6).toFixed(4)),
      p99: Number((eventLoopDelay.percentile(99) / 1e6).toFixed(4)),
      max: Number((eventLoopDelay.max / 1e6).toFixed(4)),
    };
    eventLoopDelay.disable();
  }

  function incError(label) {
    counters.protocolErrors++;
    errors.set(label, (errors.get(label) ?? 0) + 1);
  }

  function addProbeSeen(recipient, publisherId) {
    if (!publisherId) return;
    recipient.readyProbeSenders.add(publisherId);
  }

  function parseSenderKey(workload, body, mode) {
    let normalized = body;
    if (options.workload === 'chat') {
      if (mode === 'native' && typeof body === 'string') normalized = JSON.parse(body);
      return createKey('chat', normalized);
    }
    return createKey(workload, normalized);
  }

  function onApplicationMessage(recipient, destination, rawBody, protocolTextBytes, mode) {
    const expectedDestination = `/ws/wooms/${workloadTopic(options.workload)}/${recipient.room.roomId}`;
    if (destination !== expectedDestination && !(options.workload === 'sketch' && destination === '/user/queue/sketch/init')) return;
    if (options.workload === 'sketch' && destination === '/user/queue/sketch/init') return;
    let body;
    try { body = parseApplicationBody(rawBody, mode); }
    catch { incError('invalid-application-json'); return; }

    let parsedKey;
    try { parsedKey = parseSenderKey(options.workload, body, mode); }
    catch { incError('invalid-correlation-payload'); return; }
    if (!parsedKey) { counters.unmatchedDeliveries++; return; }
    if (parsedKey.probePublisher) { addProbeSeen(recipient, parsedKey.probePublisher); return; }
    if (parsedKey.probeNickname) {
      const sender = clientByNickname.get(parsedKey.probeNickname);
      if (sender && sender.room.roomId === recipient.room.roomId) addProbeSeen(recipient, sender.id);
      return;
    }
    const entry = entries.get(parsedKey.key);
    if (!entry) { counters.unmatchedDeliveries++; return; }
    const receivedAt = performance.now();
    const delivery = { recipientId: recipient.id, recipientIndex: recipient.globalIndex, receivedAt, protocolTextBytes,
      nickname: body.nickname };
    if (!entry.sendSettled) entry.pendingDeliveries.push(delivery);
    else if (entry.sendSuccessful) processDelivery(entry, delivery);
  }

  function processDelivery(entry, delivery) {
    if (!entry.isCohort) return;
    const phase = classifyDeliveryTime(delivery.receivedAt, measurementStart, measurementEnd, drainEnd);
    if (phase === 'lateBeyondDrain') {
      counters.lateBeyondDrain++;
      return;
    }
    if (phase === 'beforeMeasurement') {
      counters.deliveryBeforeMeasurement++;
      return;
    }
    if (delivery.nickname !== entry.senderNickname) incError('nickname-mismatch');
    if (!entry.expectedRecipients.has(delivery.recipientId)) {
      counters.unexpectedRecipients++;
      return;
    }
    if (entry.receivedBy.has(delivery.recipientId)) {
      counters.duplicates++;
      return;
    }
    entry.receivedBy.set(delivery.recipientId, delivery.receivedAt);
    if (phase === 'measurement') entry.windowUnique++;
    if (phase === 'drain') entry.drainUnique++;
    latencySamples.push(delivery.receivedAt - entry.actualSentAt);
    entry.receivedProtocolTextBytes += delivery.protocolTextBytes;
    if (entry.seq != null) {
      const orderKey = `${delivery.recipientId}|${entry.publisherId}`;
      const previous = sequenceLastSeen.get(orderKey);
      if (previous != null && entry.seq < previous) counters.sequenceReversals++;
      if (previous == null || entry.seq > previous) sequenceLastSeen.set(orderKey, entry.seq);
    }
  }

  function destinationForSend(roomId) {
    const kind = options.workload === 'sketch' ? 'sketch/draw' : options.workload;
    return `/ws/send/${kind}/${roomId}`;
  }

  for (const room of rooms) {
    const roomClients = [];
    for (const selected of room.clients) {
      const client = new BenchmarkClient({
        mode: options.mode,
        url: options.url,
        origin: options.origin,
        fixtureUser: selected.fixtureUser,
        room,
        globalIndex: selected.globalIndex,
        onApplication: onApplicationMessage,
        onProtocolError: incError,
        onClosed: () => { counters.connectionCloseEvents++; },
      });
      selected.client = client;
      roomClients.push(client);
      allClients.push(client);
      clientByNickname.set(client.nickname, client);
    }
    perRoom.set(room.roomId, roomClients);
  }

  try {
    const connectionResults = await connectInBatches(allClients, options.connectTimeout * 1000,
      options.connectBatchSize, options.connectBatchDelayMs);
    const connectionFailures = connectionResults.filter((result) => result.status === 'rejected');
    connectionFailures.forEach((result) => incError(`connection-${String(result.reason?.message ?? 'failed').slice(0, 100)}`));
    const connectedClients = allClients.filter((client) => client.protocolReady && client.ws?.readyState === WebSocket.OPEN);
    if (connectedClients.length !== connectionCount) {
      const failureCounts = new Map();
      for (const failure of connectionFailures) {
        const code = String(failure.reason?.message ?? 'failed').slice(0, 100);
        failureCounts.set(code, (failureCounts.get(code) ?? 0) + 1);
      }
      throw new Error(`Only ${connectedClients.length}/${connectionCount} clients completed protocol connection; failures=${JSON.stringify(Object.fromEntries(failureCounts))}`);
    }

    for (let offset = 0; offset < connectedClients.length; offset += options.connectBatchSize) {
      const wave = connectedClients.slice(offset, offset + options.connectBatchSize);
      await Promise.all(wave.map((client) => client.subscribe()));
      if (offset + options.connectBatchSize < connectedClients.length && options.subscribeBatchDelayMs > 0)
        await sleep(options.subscribeBatchDelayMs);
    }

    const probeDeadline = performance.now() + options.connectTimeout * 1000;
    let probeRound = 0;
    const retryAfterByPublisher = new Map();
    const probeRetryIntervalMs = 750;
    while (performance.now() < probeDeadline) {
      const incompleteRooms = rooms.filter((room) => {
        const clients = perRoom.get(room.roomId);
        const expected = new Set(clients.map((client) => client.id));
        return clients.some((client) => client.readyProbeSenders.size !== expected.size);
      });
      if (incompleteRooms.length === 0) break;
      probeRound++;
      const sends = [];
      const probeNow = performance.now();
      for (const room of incompleteRooms) {
        const clients = perRoom.get(room.roomId);
        for (const client of clients) {
          // A client being ready as a receiver says nothing about whether every peer saw its probe.
          if (clients.every((recipient) => recipient.readyProbeSenders.has(client.id))) continue;
          if (probeNow < (retryAfterByPublisher.get(client.id) ?? 0)) continue;
          retryAfterByPublisher.set(client.id, probeNow + probeRetryIntervalMs);
          const body = makeProbeBody(options, client, probeRound);
          const bodyText = JSON.stringify(body);
          const payloadKey = options.workload === 'chat'
            ? JSON.stringify(['chat-probe', client.id])
            : options.workload === 'move'
              ? JSON.stringify(['move-probe', client.id])
              : JSON.stringify(['sketch-probe', client.id]);
          counters.probeMessagesSent++;
          counters.setupProbeBodyBytesSent += Buffer.byteLength(bodyText);
          sends.push(new Promise((resolve) => {
            const protocolTextBytes = client.publish(destinationForSend(room.roomId), bodyText, body, (error) => {
              if (error) { counters.probeSendFailures++; incError('probe-send-failed'); }
              resolve(payloadKey);
            });
            counters.setupProbeProtocolTextBytesSent += protocolTextBytes;
          }));
        }
      }
      await Promise.all(sends);
      await sleep(100);
    }
    for (const room of rooms) {
      const clients = perRoom.get(room.roomId);
      const expected = new Set(clients.map((client) => client.id));
      if (clients.some((client) => client.readyProbeSenders.size !== expected.size)) {
        const readyRecipients = rooms.reduce((sum, candidate) => sum
          + perRoom.get(candidate.roomId).filter((client) => client.readyProbeSenders.size === ROOM_SIZE).length, 0);
        const missingProbeSlots = rooms.reduce((sum, candidate) => sum
          + perRoom.get(candidate.roomId).reduce((roomSum, client) => roomSum + (ROOM_SIZE - client.readyProbeSenders.size), 0), 0);
        throw new Error(`Readiness probe barrier timed out; readyRecipients=${readyRecipients}/${connectionCount}; missingProbeSlots=${missingProbeSlots}; protocolErrors=${JSON.stringify(Object.fromEntries(errors))}; unmatched=${counters.unmatchedDeliveries}`);
      }
    }

    const roomExpectedRecipients = new Map();
    for (const room of rooms) {
      const clients = perRoom.get(room.roomId);
      roomExpectedRecipients.set(room.roomId, fixedReadyRecipients(clients, ROOM_SIZE));
    }
    const readyConnections = allClients.filter((client) => client.readyProbeSenders.size === ROOM_SIZE).length;
    const actualRoomSubscriptions = Object.fromEntries(rooms.map((room) => [room.roomId,
      roomExpectedRecipients.get(room.roomId).size]));
    const coordinatedStartEpochMs = await waitForCoordinatedStart(options, connectionCount);
    const runOrigin = coordinatedStartEpochMs == null
      ? performance.now()
      : performance.now() + (coordinatedStartEpochMs - Date.now());
    warmEnd = runOrigin + options.warmup * 1000;
    measurementStart = warmEnd;
    measurementEnd = measurementStart + options.duration * 1000;
    drainEnd = measurementEnd + options.drain * 1000;
    const runOriginEpochMs = coordinatedStartEpochMs ?? Math.round(runOrigin + (Date.now() - performance.now()));
    const epochOffsetMs = runOriginEpochMs - runOrigin;
    const measurementStartEpochMs = Math.round(measurementStart + epochOffsetMs);
    const measurementEndEpochMs = Math.round(measurementEnd + epochOffsetMs);
    eventLoopDelay.enable();
    let schedulerAborted = false;
    let schedulerAbortReason = null;
    const seqByPublisher = new Map();
    let sendCursor = 0;
    const interval = 1000 / options.rate;
    let nextScheduled = runOrigin;

    function onSendSettled(entry, error) {
      entry.sendSettled = true;
      if (error) {
        entry.sendSuccessful = false;
        counters.sendFailures++;
        errors.set('socket-send-failed', (errors.get('socket-send-failed') ?? 0) + 1);
        entries.delete(entry.key);
        return;
      }
      entry.sendSuccessful = true;
      counters.sendSuccess++;
      if (entry.actualSentAt >= runOrigin && entry.actualSentAt < warmEnd) counters.successfulWarmupSends++;
      if (entry.isCohort) {
        counters.cohortSendSuccess++;
        entry.expectedDeliveryCount = entry.expectedRecipients.size;
        entry.inputBodyBytes = Buffer.byteLength(entry.bodyText);
        entry.windowUnique = 0;
        entry.drainUnique = 0;
        entry.receivedProtocolTextBytes = 0;
        entry.receivedBy = new Map();
        for (const delivery of entry.pendingDeliveries) processDelivery(entry, delivery);
      }
      entry.pendingDeliveries.length = 0;
    }

    const sendInterval = async () => {
      while (true) {
        const loopNow = performance.now();
        if (loopNow >= measurementStart && !measurementMetricsActive) beginMeasurementMetrics();
        if (loopNow >= measurementEnd) {
          endMeasurementMetrics();
          break;
        }
        if (schedulerAborted) {
          await sleep(Math.min(25, measurementEnd - loopNow));
          continue;
        }
        const tickStart = performance.now();
        let sentThisTick = 0;
        while (performance.now() >= nextScheduled && sentThisTick < 100
          && performance.now() - tickStart < 2 && performance.now() < measurementEnd) {
          const sendNow = performance.now();
          if (sendNow >= measurementStart && !measurementMetricsActive) beginMeasurementMetrics();
          const actualSentAt = performance.now();
          const sender = connectedClients[sendCursor++ % connectedClients.length];
          if (sender.ws?.readyState !== WebSocket.OPEN) {
            nextScheduled += interval;
            continue;
          }
          aggregateBufferedNow = 0;
          for (const client of allClients) aggregateBufferedNow += client.ws?.bufferedAmount ?? 0;
          maxBufferedAmount = Math.max(maxBufferedAmount, aggregateBufferedNow);
          maxAggregateBufferedAmount = Math.max(maxAggregateBufferedAmount, aggregateBufferedNow);
          if (aggregateBufferedNow > MAX_BUFFERED_BYTES) {
            counters.bufferLimitExceeded = true;
            schedulerAborted = true;
            schedulerAbortReason = 'buffer-limit-exceeded';
            break;
          }
          const seq = seqByPublisher.get(sender.id) ?? 0;
          if (seq >= 12_287 && options.workload === 'sketch') {
            schedulerAborted = true;
            schedulerAbortReason = 'sketch-correlation-capacity-exhausted';
            errors.set(schedulerAbortReason, 1);
            break;
          }
          if (seq >= 251 && options.workload === 'move') {
            schedulerAborted = true;
            schedulerAbortReason = 'move-coordinate-correlation-capacity-exhausted';
            errors.set(schedulerAbortReason, 1);
            break;
          }
          seqByPublisher.set(sender.id, seq + 1);
          const body = makeWorkloadBody(options, runId, sender, seq, actualSentAt);
          if (options.workload === 'move' && body.x !== String(100 + seq)) throw new Error('Move correlation encoding invariant failed');
          const bodyText = JSON.stringify(body);
          const correlation = createKey(options.workload, body);
          if (!correlation?.key) throw new Error('Could not construct unique workload correlation key');
          if (entries.has(correlation.key)) throw new Error('Correlation key collision before send');
          const expectedRecipients = roomExpectedRecipients.get(sender.room.roomId);
          const scheduledAt = nextScheduled;
          const pacingLag = actualSentAt - scheduledAt;
          const entry = {
            key: correlation.key,
            senderNickname: sender.nickname,
            publisherId: sender.id,
            seq,
            expectedRecipients,
            expectedDeliveryCount: expectedRecipients.size,
            actualSentAt,
            scheduledAt,
            pacingLag,
            bodyText,
            protocolTextBytes: encodeProtocolSend(options.mode, destinationForSend(sender.room.roomId), bodyText, body).protocolTextBytes,
            phase: actualSentAt < warmEnd ? 'warmup' : actualSentAt < measurementEnd ? 'measurement' : 'outside',
            isCohort: actualSentAt >= measurementStart && actualSentAt < measurementEnd,
            sendSettled: false,
            sendSuccessful: false,
            pendingDeliveries: [],
          };
          entries.set(entry.key, entry); // Register before socket.send so fast echoes can be correlated.
          counters.attemptedSends++;
          sender.publish(destinationForSend(sender.room.roomId), bodyText, body,
            (error) => onSendSettled(entry, error));
          sentThisTick++;
          nextScheduled += interval;
          aggregateBufferedNow = 0;
          for (const client of allClients) aggregateBufferedNow += client.ws?.bufferedAmount ?? 0;
          maxBufferedAmount = Math.max(maxBufferedAmount, aggregateBufferedNow);
          maxAggregateBufferedAmount = Math.max(maxAggregateBufferedAmount, aggregateBufferedNow);
          if (aggregateBufferedNow > MAX_BUFFERED_BYTES) {
            counters.bufferLimitExceeded = true;
            schedulerAborted = true;
            schedulerAbortReason = 'buffer-limit-exceeded';
            break;
          }
        }
        if (performance.now() >= measurementEnd) continue;
        if (schedulerAborted) continue;
        await sleep(1);
      }
    };

    await sendInterval();
    const measureCompletedAt = performance.now();

    const pendingWrites = [...entries.values()].filter((entry) => !entry.sendSettled);
    if (pendingWrites.length) {
      await Promise.race([
        Promise.all(pendingWrites.map((entry) => new Promise((resolve) => {
          const poll = setInterval(() => { if (entry.sendSettled) { clearInterval(poll); resolve(); } }, 5);
        }))),
        sleep(Math.max(0, (drainEnd - performance.now()) + 500)),
      ]);
    }
    while (performance.now() < drainEnd) await sleep(Math.min(100, drainEnd - performance.now()));
    const lateObservationMs = 100;
    await sleep(lateObservationMs);
    const liveBeforeCleanup = allClients.filter((client) => client.protocolReady && client.ws?.readyState === WebSocket.OPEN).length;
    liveDuringRun = liveBeforeCleanup;

    const cohortEntries = [...entries.values()].filter((entry) => entry.sendSuccessful && entry.isCohort);
    const expectedDeliveries = cohortEntries.reduce((sum, entry) => sum + entry.expectedDeliveryCount, 0);
    const receivedUnique = cohortEntries.reduce((sum, entry) => sum + entry.receivedBy.size, 0);
    const inWindowUnique = cohortEntries.reduce((sum, entry) => sum + entry.windowUnique, 0);
    const drainUnique = cohortEntries.reduce((sum, entry) => sum + entry.drainUnique, 0);
    const measurementSeconds = options.duration;
    const incomingProtocolTextBytes = cohortEntries.reduce((sum, entry) => sum + entry.receivedProtocolTextBytes, 0);
    const outgoingProtocolTextBytes = cohortEntries.reduce((sum, entry) => sum + (entry.sendSuccessful ? entry.protocolTextBytes : 0), 0);
    const outgoingBodyBytes = cohortEntries.reduce((sum, entry) => sum + Buffer.byteLength(entry.bodyText), 0);
    const senderLag = sampleStats(cohortEntries.map((entry) => entry.pacingLag));
    const actualIngressRate = counters.cohortSendSuccess / measurementSeconds;
    const inWindowRate = inWindowUnique / measurementSeconds;
    const targetMessages = options.rate * options.duration;
    const targetAttainment = targetMessages > 0 ? counters.cohortSendSuccess / targetMessages : 0;
    const missingAfterDrain = Math.max(0, expectedDeliveries - receivedUnique);
    const latencyPath = path.join(os.tmpdir(), `realtimecompare-latency-${runId}-${options.mode}-${options.workload}-worker${options.workerId}.csv`);
    await fs.writeFile(latencyPath, `latency_ms\n${latencySamples.map((value) => value.toFixed(4)).join('\n')}\n`, 'utf8');

    const measurementWindowCompleted = measureCompletedAt >= measurementEnd;
    const result = {
      ok: isSuccessfulRun({
        attemptedConnections: connectionCount,
        readyConnections,
        liveConnections: liveBeforeCleanup,
        closedDuringRun: counters.connectionCloseEvents,
        schedulerAborted,
        targetAttainment,
        measurementWindowCompleted,
        protocolErrors: counters.protocolErrors,
        sendFailures: counters.sendFailures,
        bufferLimitExceeded: counters.bufferLimitExceeded,
        missingAfterDrain,
        duplicates: counters.duplicates,
        unexpectedRecipients: counters.unexpectedRecipients,
        sequenceReversals: counters.sequenceReversals,
        unmatchedDeliveries: counters.unmatchedDeliveries,
        lateBeyondDrain: counters.lateBeyondDrain,
      }),
      runId,
      mode: options.mode,
      workload: options.workload,
      endpoint: options.url,
      workerId: options.workerId,
      roomStart: options.roomStart,
      roomCount: options.rooms,
      connections: {
        attempted: connectionCount,
        connected: connectedClients.length,
        readinessProbeComplete: readyConnections,
        liveBeforeCleanup: liveDuringRun,
        closedDuringRun: counters.connectionCloseEvents,
      },
      subscriptions: {
        perRoomReady: Object.values(actualRoomSubscriptions),
        totalReadyFanoutSlots: Object.values(actualRoomSubscriptions).reduce((sum, count) => sum + count, 0),
      },
      scheduler: {
        aborted: schedulerAborted,
        abortReason: schedulerAbortReason,
        targetMessages,
        targetAttainment: Number(targetAttainment.toFixed(5)),
        measurementWindowCompleted,
      },
      timingSeconds: { warmup: options.warmup, measurement: options.duration, drain: options.drain,
        measurementStartMonotonicMs: Number(measurementStart.toFixed(3)),
        measurementEndMonotonicMs: Number(measurementEnd.toFixed(3)),
        measurementStartEpochMs,
        measurementEndEpochMs,
        measurementActualWallSeconds: Number(((Math.min(measureCompletedAt, measurementEnd) - measurementStart) / 1000).toFixed(4)),
        lateObservationMs,
        deadlineSemantics: '[measurementStart, measurementEnd) and [measurementEnd, drainEnd)' },
      ingress: {
        targetMsgPerSecond: options.rate,
        successfulCohortMessages: counters.cohortSendSuccess,
        actualCohortMsgPerSecond: Number(actualIngressRate.toFixed(4)),
        attemptedMessagesAllPhases: counters.attemptedSends,
        successfulMessagesAllPhases: counters.sendSuccess,
        failedSends: counters.sendFailures,
        pacingLagMs: senderLag,
        applicationBodyBytes: outgoingBodyBytes,
        protocolTextBytes: outgoingProtocolTextBytes,
        protocolTextBytesDefinition: 'UTF-8 application-protocol text; excludes WebSocket and TCP framing/headers',
        sampleApplicationBodyBytes: cohortEntries[0] ? Buffer.byteLength(cohortEntries[0].bodyText) : 0,
        sampleProtocolTextBytes: cohortEntries[0]?.protocolTextBytes ?? 0,
      },
      delivery: {
        expectedFanoutDeliveryCount: expectedDeliveries,
        uniqueDeliveriesInMeasurementWindow: inWindowUnique,
        measurementWindowUniqueDeliveryPerSecond: Number(inWindowRate.toFixed(4)),
        uniqueDeliveriesAfterMeasureEndDuringDrain: drainUnique,
        uniqueDeliveriesTotalThroughDrain: receivedUnique,
        missingAfterDrain: missingAfterDrain,
        lateBeyondDrain: counters.lateBeyondDrain,
        deliveryBeforeMeasurement: counters.deliveryBeforeMeasurement,
        duplicateDeliveries: counters.duplicates,
        unexpectedRecipients: counters.unexpectedRecipients,
        sequenceReversals: counters.sequenceReversals,
        latencyMs: sampleStats(latencySamples),
        correlatedProtocolTextBytesReceived: incomingProtocolTextBytes,
        protocolTextBytesDefinition: 'UTF-8 application-protocol text; excludes WebSocket and TCP framing/headers',
      },
      generator: {
        cpuUserMs: cpuEnd ? Math.round(cpuEnd.user / 1000) : null,
        cpuSystemMs: cpuEnd ? Math.round(cpuEnd.system / 1000) : null,
        processCpuMs: cpuEnd ? Math.round((cpuEnd.user + cpuEnd.system) / 1000) : null,
        measurementWindow: true,
        rssBytesAtMeasurementEnd: measurementMemoryAtEnd?.rss ?? null,
        rssBytesMaxSampledDuringMeasurement: maxRssBytes,
        heapUsedBytesAtMeasurementEnd: measurementMemoryAtEnd?.heapUsed ?? null,
        heapUsedBytesMaxSampledDuringMeasurement: maxHeapBytes,
        eventLoopDelayMs: eventLoopStats,
        aggregateBufferedAmountMaxBytes: maxBufferedAmount,
        aggregateBufferedAmountMaxSampledBytes: maxAggregateBufferedAmount,
        bufferedAmountLimitBytes: MAX_BUFFERED_BYTES,
        bufferLimitExceeded: counters.bufferLimitExceeded,
        probeMessagesSent: counters.probeMessagesSent,
        probeSendFailures: counters.probeSendFailures,
        setupProbeBodyBytesSent: counters.setupProbeBodyBytesSent,
        setupProbeProtocolTextBytesSent: counters.setupProbeProtocolTextBytesSent,
      },
      errors: { total: counters.protocolErrors, bySafeCode: Object.fromEntries(errors), unmatchedDeliveries: counters.unmatchedDeliveries,
        sendFailures: counters.sendFailures },
      rawLatencyFileTemp: latencyPath,
    };
    if (options.output) {
      await fs.mkdir(path.dirname(path.resolve(options.output)), { recursive: true });
      await fs.writeFile(options.output, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
    }
    if (options.ipc) await sendIpc({ type: 'result', workerId: options.workerId, pid: process.pid, summary: result });
    else process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  } finally {
    globalThis.cleanupStarted = true;
    clearInterval(sampleTimer);
    for (const client of allClients) client.close();
    await sleep(100);
  }
}

function installRuntimeCallbackState() {
  globalThis.cleanupStarted = false;
}

installRuntimeCallbackState();
main().catch(async (error) => {
  // Error text is intentionally generic: never print cookie, JWT, frame body, or fixture contents.
  const message = String(error?.message ?? 'benchmark failed')
    .replace(/Authorization=[^\s,;]+/gi, 'Authorization=[REDACTED]')
    .replace(/(?:eyJ[A-Za-z0-9_-]+\.){2}[A-Za-z0-9_.-]+/g, '[JWT-REDACTED]');
  if (typeof process.send === 'function') {
    await new Promise((resolve) => process.send({ type: 'failed', workerId: process.argv.includes('--worker-id')
      ? process.argv[process.argv.indexOf('--worker-id') + 1] : null, message }, () => resolve()));
  }
  process.stderr.write(`worker failed: ${message}\n`);
  process.exitCode = 1;
});
