#!/usr/bin/env python3
"""Anonymous STOMP chat load generator for the Wooms /ws endpoint."""
import argparse
import json
import math
import threading
import time
import uuid
from collections import defaultdict

import websocket

NUL = "\x00"


class Frames:
    """Accumulate websocket text chunks until complete STOMP NUL frames."""
    def __init__(self):
        self.buffer = ""

    def feed(self, chunk):
        self.buffer += chunk
        result = []
        while NUL in self.buffer:
            frame, self.buffer = self.buffer.split(NUL, 1)
            frame = frame.lstrip("\r\n")
            if frame:
                result.append(frame)
        return result


def command(name, headers=(), body=""):
    return name + "\n" + "".join(f"{k}:{v}\n" for k, v in headers) + "\n" + body + NUL


def parse(frame):
    head, _, body = frame.partition("\n\n")
    lines = head.splitlines()
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k] = v
    return (lines[0] if lines else ""), headers, body


def percentile(values, pct):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[min(len(ordered)-1, math.ceil(pct*len(ordered))-1)], 3)


class Client:
    def __init__(self, idx, room, args, run_id, errors, cv, received, latency, receive_ns, probe):
        self.idx, self.room, self.args, self.run_id = idx, room, args, run_id
        self.errors, self.cv, self.received, self.latency, self.receive_ns, self.probe = errors, cv, received, latency, receive_ns, probe
        self.ws = None
        self.frames = Frames()
        self.stop = threading.Event()
        self.thread = None

    def connect(self):
        self.ws = websocket.create_connection(self.args.url, timeout=10, origin=self.args.origin)
        self.ws.settimeout(1)
        self.ws.send(command("CONNECT", [("accept-version", "1.2"), ("heart-beat", "0,0")]))
        self._await("CONNECTED", 10)
        self.ws.send(command("SUBSCRIBE", [("id", f"sub-{self.idx}"), ("destination", f"/ws/wooms/chat/{self.room}"), ("ack", "auto")]))
        self.thread = threading.Thread(target=self.receive_loop, daemon=True)
        self.thread.start()

    def _await(self, expected, timeout):
        deadline = time.perf_counter()+timeout
        while time.perf_counter() < deadline:
            for f in self.frames.feed(self.ws.recv()):
                if parse(f)[0] == expected:
                    return
        raise TimeoutError(f"waiting for {expected}")

    def send(self, body):
        self.ws.send(command("SEND", [("destination", f"/ws/send/chat/{self.room}"), ("content-type", "text/plain;charset=UTF-8")], body))

    def receive_loop(self):
        while not self.stop.is_set():
            try:
                chunk = self.ws.recv()
                if not chunk:
                    return
                now = time.perf_counter_ns()
                for frame in self.frames.feed(chunk):
                    kind, _, body = parse(frame)
                    if kind != "MESSAGE":
                        if kind == "ERROR":
                            self.errors.append(body[:300])
                        continue
                    try:
                        msg = json.loads(body)
                        content = json.loads(msg["content"])
                        rid, seq, sent_ns = content["runId"], content["sequence"], content["sentNs"]
                    except Exception:
                        self.errors.append("invalid chat payload")
                        continue
                    key = (rid, seq, self.idx)
                    with self.cv:
                        self.received[key] += 1
                        self.latency.setdefault(key, (now-sent_ns)/1e6)
                        self.receive_ns.setdefault(key, now)
                        self.cv.notify_all()
                    if rid == self.run_id and seq == "probe":
                        self.probe.set()
            except websocket.WebSocketTimeoutException:
                continue
            except Exception as e:
                if not self.stop.is_set():
                    self.errors.append(f"client {self.idx}: {e}")
                return

    def close(self):
        self.stop.set()
        try:
            self.ws.send(command("DISCONNECT"))
            self.ws.close()
        except Exception:
            pass
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=3)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", required=True, help="WebSocket STOMP URL, e.g. ws://localhost:8081/ws")
    ap.add_argument("--origin", default="http://localhost:5173")
    ap.add_argument("--connections", type=int, default=10)
    ap.add_argument("--room-size", type=int, default=5, help="subscribers per room")
    ap.add_argument("--rate", type=float, default=100, help="target total SEND frames per second")
    ap.add_argument("--warmup", type=float, default=5)
    ap.add_argument("--duration", type=float, default=15)
    ap.add_argument("--drain", type=float, default=10)
    ap.add_argument("--connect-timeout", type=float, default=15)
    ap.add_argument("--output", help="write JSON result to this path")
    args = ap.parse_args()
    if args.connections < 2 or args.room_size < 1 or args.rate <= 0:
        ap.error("connections must be >=2, room-size and rate must be positive")

    run_id = str(uuid.uuid4())
    errors, received, latency, receive_ns = [], defaultdict(int), {}, {}
    cv = threading.Condition()
    nrooms = math.ceil(args.connections/args.room_size)
    clients = [Client(i, str(uuid.uuid5(uuid.NAMESPACE_URL, f"{run_id}:{i//args.room_size}")), args,
                      run_id, errors, cv, received, latency, receive_ns, threading.Event()) for i in range(args.connections)]
    ingress = []
    seq_room = defaultdict(int)
    try:
        for c in clients:
            c.connect()
        # Probe each connection individually to verify each subscription receives.
        for room in sorted({c.room for c in clients}):
            for probe in (c for c in clients if c.room == room):
                probe.send(json.dumps({"content": json.dumps({"runId": run_id, "sequence": "probe", "sentNs": time.perf_counter_ns(), "publisherId": probe.idx}), "nickname": "benchmark"}))
                if not probe.probe.wait(args.connect_timeout):
                    raise TimeoutError(f"subscription probe failed for client {probe.idx}")

        start = time.perf_counter()
        warm_end, measure_end = start+args.warmup, start+args.warmup+args.duration
        next_send = start
        total = max(1, round(args.rate*(args.warmup+args.duration)))
        interval = 1.0/args.rate
        for i in range(total):
            target = next_send
            delay = target-time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            now = time.perf_counter()
            scheduled_lag_ms = max(0, (now-target)*1000)
            c = clients[i % args.connections]
            room = c.room
            seq = seq_room[room]
            seq_room[room] += 1
            sent_ns = time.perf_counter_ns()
            body = json.dumps({"content": json.dumps({"runId": run_id, "sequence": seq, "sentNs": sent_ns, "publisherId": c.idx}), "nickname": f"bench-{c.idx}"}, separators=(",", ":"))
            try:
                c.send(body)
                if now >= warm_end and now < measure_end:
                    ingress.append((room, seq, sent_ns, c.idx, scheduled_lag_ms))
            except Exception as e:
                errors.append(f"send {i}: {e}")
            next_send += interval
            if now >= measure_end:
                break

        actual_end = time.perf_counter()
        time.sleep(args.drain)
        expected = sum(sum(1 for c in clients if c.room == room) for room, *_ in ingress)
        # deliveries are tracked per recipient; unique delivery and duplicate counts are separate.
        keys = {(run_id, seq, c.idx) for room, seq, *_ in ingress for c in clients if c.room == room}
        with cv:
            received_snapshot, latency_snapshot, receive_snapshot = dict(received), dict(latency), dict(receive_ns)
        unique = sum(1 for k in keys if received_snapshot.get(k, 0) > 0)
        duplicates = sum(max(0, received_snapshot.get(k, 0)-1) for k in keys)
        missing = max(0, expected-unique)
        measure_lo, measure_hi = int(warm_end*1e9), int(measure_end*1e9)
        measured_unique = sum(1 for k in keys if measure_lo <= receive_snapshot.get(k, 0) < measure_hi)
        measured_lat = [latency_snapshot[k] for k in keys if measure_lo <= receive_snapshot.get(k, 0) < measure_hi]
        lat = [latency_snapshot[k] for k in keys if k in latency_snapshot]
        sender_lag = [x[4] for x in ingress]
        clock = time.get_clock_info("perf_counter")
        result = {"runId": run_id, "url": args.url, "anonymous": True, "clock": {"name": clock.implementation, "resolutionSeconds": clock.resolution, "monotonic": clock.monotonic, "adjustable": clock.adjustable}, "connections": args.connections,
                  "connected": sum(c.ws is not None for c in clients), "roomSize": args.room_size,
                  "rooms": nrooms, "targetIngressMsgPerSec": args.rate, "warmupSeconds": args.warmup,
                  "measureSeconds": args.duration, "drainSeconds": args.drain,
                  "actualSendWindowSeconds": round(max(0, actual_end-warm_end), 3),
                  "ingressMessages": len(ingress), "ingressMsgPerSec": round(len(ingress)/args.duration, 3),
                  "expectedFanoutDeliveries": expected, "measurementWindowUniqueDeliveries": measured_unique,
                  "measurementWindowFanoutDeliveriesPerSec": round(measured_unique/args.duration, 3),
                  "drainCompleteUniqueReceived": unique, "missingAfterDrain": missing,
                  "duplicatesAfterDrain": duplicates, "errors": list(errors),
                  "latencyMsAfterDrain": {"p50": percentile(lat,.50), "p95": percentile(lat,.95), "p99": percentile(lat,.99)},
                  "measurementWindowLatencyMs": {"p50": percentile(measured_lat,.50), "p95": percentile(measured_lat,.95), "p99": percentile(measured_lat,.99)},
                  "senderScheduleLagMs": {"max": round(max(sender_lag),3) if sender_lag else None, "p50": percentile(sender_lag,.50), "p95": percentile(sender_lag,.95), "p99": percentile(sender_lag,.99)}}
        rendered = json.dumps(result, indent=2)
        print(rendered)
        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                f.write(rendered+"\n")
    finally:
        for c in clients:
            c.close()


if __name__ == "__main__":
    main()
