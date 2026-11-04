# STOMP vs Native realtime benchmark

Run profile: 1,000 connections, 100 rooms × 10 users, four workers × 25 whole rooms, 5 s warmup, 20 s measurement, 10 s drain. Rates are total input targets. Latency is pooled from individual delivery samples. `ok` requires ≥98% target input, complete preparation, full expected fanout, zero loss/duplicates, and aligned resource sampling.

## Results

| Transport | Workload | Target input/s | Observed input/s | Delivery p99 (ms) | Missing | ok / note |
|---|---:|---:|---:|---:|---:|---|
| STOMP | Chat | 1,000 | 1,000.40 | 2.72 | 0 | true |
| STOMP | Move | 1,000 | 999.80 | 2.83 | 0 | true |
| STOMP | Sketch | 1,000 | 999.40 | 2.63 | 0 | true |
| Native | Chat | 1,000 | 1,000.05 | 6.06 | 0 | true; warmed diagnostic rerun; initial cold preparation timed out |
| Native | Move | 1,000 | 1,000.10 | 3.67 | 0 | true |
| Native | Sketch | 1,000 | 999.60 | 7.42 | 0 | true |
| STOMP | Chat | 5,000 | 4,999.90 | 10.28 | 0 | true |
| STOMP | Move | 5,000 | 4,999.85 | 10.65 | 0 | true |
| STOMP | Sketch | 5,000 | 5,000.00 | 30.76 | 0 | true |
| Native | Chat | 5,000 | 4,999.80 | 109.25 | 0 | true |
| Native | Move | 5,000 | 5,000.05 | 7.61 | 0 | true |
| Native | Sketch | 5,000 | 4,998.50 | 86.83 | 0 | true |
| STOMP | Chat | 10,000 | 6,367.65 | 93.38 | 0 | false; target not reached |
| STOMP | Move | 10,000 | 6,366.30 | 220.98 | 0 | false; target not reached |
| STOMP | Sketch | 10,000 | 6,828.10 | 114.86 | 0 | false; target not reached |
| Native | Chat | 10,000 | 4,691.50 | 5,199.21 | 636,282 | false; host/generator saturation and socket OOM observed |
| Native | Move | 10,000 | — | — | — | false; preparation timeout after preceding Native 10k chat run; no performance sample |
| Native | Sketch | 10,000 | — | — | — | false; 0/250 connections per worker after preceding Native 10k chat run; no performance sample |

The Native 1,000 chat row uses `diag-native-chat-1000-warmed.json`; the original cold-start matrix attempt (`matrix-native-chat-1000.json`) is retained and marked as preparation timeout with no performance metric. The 10k Native move/sketch failures share a JVM with the preceding 10k chat run and may have been affected by its state; their error results are retained, not treated as throughput.

At 1k and 5k inputs both transports delivered all expected messages without loss or duplication. These results do not show a general Native performance advantage: Native was faster on move at 5k, while STOMP had lower p99 for chat and sketch at that rate. At 10k neither transport reached its target. STOMP workers had multi-second scheduler lag and the host was busy 80–89% of 18 logical processors. Native chat showed 79.65% average host busy (94% peak), worker p99 scheduler lag around 10–12 s, high delivery latency, and a large delivery shortfall. Do not interpret the 10k observations as a server capacity limit; the generator/host could not maintain the requested pacing, and Native also logged thread-related OOM errors.

## Environment and caveats

- Same machine and 18 logical processors; test-only H2 in-memory database, 100 seeded rooms and 1,000 unique accepted users/JWT cookies. The fixture files were removed after testing.
- Heap budget was 1,536 MiB in both modes: STOMP backend 1,536 MiB; Native backend 1,024 MiB plus socket JVM 512 MiB.
- Native additionally revalidates authorization every 5 s and flushes sketch state every 5 s; the STOMP sketch persistence interval is 10 s. These are whole-application path differences and are not normalized away.
- One fresh Native JVM's first 1,000-client chat preparation timed out after all WebSocket handshakes and client-side SUBSCRIBE writes. A later warmed 1,000-client run passed. The server emitted generic authorization errors; exact HTTP failure status was not logged. The initial error is kept as a preparation failure, not a performance row.
- After Native chat at 10k, the socket stderr recorded 70 `OutOfMemoryError` uncaught-handler messages from `HttpClient-1-Worker-*` (thread numbers through 579). The JVM remained alive and heap use was about 171 MiB of 512 MiB; this was not Java heap exhaustion. The following move/sketch preparation failures occurred in the same JVM and may have been affected by its state; causality was not established. No product source was changed.
- Browser QA was blocked by the computer-use policy; it was not retried or bypassed. See [browser-qa.md](browser-qa.md).

## Reproduction

Run `tools/realtime-benchmark/start-test-servers.ps1 -Mode stomp -NoFrontend` or `-Mode native -NoFrontend`; use the fixture path and server PIDs printed by the launcher. Then run the coordinator once per workload/rate, for example:

```powershell
node tools/realtime-benchmark/coordinator.mjs --mode native --fixture $fixture --workload chat --rate 5000 --server-pids $backendPid,$socketPid --output tools/realtime-benchmark/results/reproduction.json
```

Repeat with modes `stomp`/`native`, workloads `chat`/`move`/`sketch`, and target rates `1000`, `5000`, `10000`. Preserve every output JSON, including `ok:false` and preparation failures. The coordinator records the exact endpoint, target attainment, delivery accounting, pooled latency, worker pacing, and resource sample alignment.

## Result files

Per-run JSON files are in `tools/realtime-benchmark/results/`. The detailed readiness/OS diagnosis is in [DIAGNOSIS.md](DIAGNOSIS.md). No product source or configuration was changed for the benchmark.
