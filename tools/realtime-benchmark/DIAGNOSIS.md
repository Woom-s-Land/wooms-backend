# Native 1,000-connection preparation diagnosis

Date: 2026-11-03

## Outcome

The current Native pilot completed successfully after pacing connection and subscription preparation. No product source or server configuration was changed.

`pilot-chat-native-1000-sampler-warmed.json` records 1,000 attempted connections, 1,000 completed readiness probes, 1,000 live connections before cleanup, and 1,000 ready room slots. The 20-second cohort sent 20,012 messages at 1,000.6 msg/s (target 1,000). Expected fanout was 200,120 deliveries; all were received, with zero missing, duplicate, or beyond-drain deliveries. Pooled latency was p50 1.0396 ms, p95 2.2912 ms, and p99 4.1865 ms. There were no worker failures. The sampler captured 20 measurements; its first sample was 401 ms after the window start and its last was 612 ms before the end.

## Failure classification

- `pilot-chat-native-1000-backlog-failed-20261103.json`: all four workers received `ECONNREFUSED`; 393 of 1,000 WebSocket connections completed protocol setup, while 607 were refused. The Native listener was configured with `SO_BACKLOG=128` in `wooms-socket/src/main/java/com/ee06/mychat/realtime/RealtimeServer.java`. The server process/listener remained present. This points to an unpaced burst of client connects interacting with the small accept backlog, not message processing throughput.
- `pilot-chat-native-1000-batch-failed-20261103.json`: after using the IPv4 endpoint and connection batches, all 1,000 sessions progressed far enough to attempt protocol preparation, but repeated readiness probes and subscriptions timed out. The worker was retrying its probe while authorization/subscription work was still in flight. The backend and socket error logs contained no corresponding error-level server failure.
- `pilot-chat-native-1000-probe-throttled.json`: all 1,000 connections and probes completed, with no delivery loss or worker errors. The run was marked `ok:false` only because the first PowerShell resource sample arrived 10,067 ms into the 20-second window. The cold first WMI/process-provider call delayed that sample; subsequent samples were about one second apart.

## Changes limited to benchmark tooling

`worker.mjs` now staggers connection setup, batches subscription setup, and rate-limits readiness probes while authorization is pending. `sample-resources.ps1` prewarms process/WMI providers before the synchronized window. The warmed pilot above verifies both changes. The benchmark endpoint is explicitly `ws://127.0.0.1:8090/ws`; the active Netty JVM PID 45876 listened on `127.0.0.1:8090`, and backend PID 22684 listened on port 8080. No test server exited during the successful pilot.

## Product-change assessment

No product change is needed for the paced 1,000-connection workload: the same server completed the pilot with all rooms ready and zero delivery loss. If a future requirement is to accept 1,000 simultaneous connection attempts rather than a paced ramp, the smallest product-side option would be to make Netty's `SO_BACKLOG` configurable or increase its default in `RealtimeServer.java`; the OS may still cap it. That burst-startup scenario is outside the current paced benchmark and should not be presented as steady-state throughput.

## Fresh Native JVM readiness follow-up

The first matrix run against the freshly launched Native test JVM (`matrix-native-chat-1000.json`) reached the readiness-probe stage. The worker code reaches that stage only after all 250 WebSocket handshakes and all local SUBSCRIBE writes succeed, so 1,000/1,000 protocol connections and client-side subscription writes completed across the four workers. The application protocol has no SUBACK, so that does not prove all server-side subscriptions were installed. The readiness barrier then timed out: the worker reported 0/250 fully ready clients, 2,176 missing sender-recipient probe slots, 167 safe `room-authorization-failed` envelopes, and 27,289 probe SEND write failures. The coordinator-level ready count is 0 because the worker failed before the barrier and emitted no success summary. No request-level backend exception or socket error log was present.

After the initial failure, small actual Native chat preparations on the same fresh fixture passed: 20/20 connections and probes (2 rooms), then 100/100 (10 rooms), both with no protocol or send errors. A warmed 1,000-connection chat re-run passed 1,000/1,000 probes and rooms, at 1,000.05 msg/s; all 200,010 expected deliveries arrived, with zero missing/duplicates (pooled p50/p95/p99 1.1574/3.2572/6.0607 ms). The 1,000-connection move and sketch runs on that server also passed. Direct backend authorization endpoint sweeps of 20/100/1,000 concurrent requests returned HTTP 200 for every request; p95 was 45/85/842 ms and maximum 857 ms.

This localizes the symptom to a transient burst of authorization/probe preparation immediately after the new JVM was started, not TCP establishment, invalid/expired fixture cookies, or steady-state message delivery. It does not identify the exact backend HTTP failure status for the 167 envelopes: the bridge intentionally reduces status details to a generic authorization error, and the configured server logs contain no request error. The evidence does not justify a product change. To distinguish cold ORM/JIT startup from HTTP client/servlet queueing exactly would require sanitized status/timing instrumentation in the test harness or server; this run continues only after the 20/100/1,000 preparation checks have warmed and verified the path.

## 10,000 msg/s Native cascade

The initial Native `matrix-native-chat-10000.json` was a measurement run (1,000/1,000 ready), but not a valid server-limit comparison: input attainment was 46.9%, worker p99 pacing lag was 10.36–12.08 seconds, host busy averaged 79.65% and peaked at 94%, while p50 delivery latency reached 2.73 seconds and 636,282 expected deliveries were missing after drain. The run produced a very large outbound backlog under host pressure.

The following same-JVM Native move/sketch 10,000 results are cascade-invalid and retained only as failure evidence. The move run hit the 120-second readiness timeout without a worker summary. The sketch run then saw 0/250 protocol connections in each worker with `ECONNREFUSED`. The socket `.err.log` contains 70 `OutOfMemoryError` uncaught-handler messages from `HttpClient-1-Worker-*` threads (numbered through 579); no credential or application payload was emitted in these lines. The JVM/listener remained present, and a post-event `jcmd GC.heap_info` reported about 171 MiB heap used out of 512 MiB committed. This is not evidence of Java heap exhaustion. It is consistent with HTTP-client worker/native-thread resource exhaustion during concurrent authorization revalidation, followed by process/OS connection failures; exact OOME subtype is not printed in the captured stderr. Subsequent 0/250 ECONNREFUSED outcomes must not be counted as workload throughput.

Relevant product paths for review only (no changes made): `wooms-socket/src/main/java/com/ee06/mychat/realtime/NativeHub.java` schedules authorization revalidation every five seconds for every session and calls `BackendBridge.authorize`; `wooms-socket/src/main/java/com/ee06/mychat/realtime/BackendBridge.java` builds a default-executor `HttpClient` and uses `sendAsync`. If a product fix is later authorized, the smallest direction to investigate is bounding/coalescing refresh work (with jitter/backpressure) and using a bounded executor or shared asynchronous HTTP pool; first capture the original OOME subtype and request status without logging cookies.

The remaining 10,000 move and sketch conditions are being run against separate fresh Native JVM/H2 fixture instances, with 20-, 100-, then 1,000-connection preparation checks before each target run. This isolates each result from the above cascade.
