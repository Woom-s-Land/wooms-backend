#!/usr/bin/env python3
"""Authenticated end-to-end smoke checks for the standalone Netty transport."""
import argparse
import http.cookiejar
import json
import os
import queue
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import websocket


class SocketClient:
    def __init__(self, url, cookie=None, origin="http://localhost:5173", timeout=10):
        headers = []
        if cookie:
            headers.append(f"Cookie: {cookie}")
        self.ws = websocket.create_connection(url, timeout=timeout, origin=origin, header=headers)
        self.events = queue.Queue()
        self.pending = []
        self.subscriptions = {}
        self.errors = []
        self.closed = False
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        frame = self.wait(lambda m: m.get("op") == "CONNECTED", timeout=timeout)
        assert frame["op"] == "CONNECTED"

    def _read(self):
        try:
            while True:
                raw = self.ws.recv()
                if not raw:
                    return
                try:
                    message = json.loads(raw)
                except Exception as exc:
                    self.errors.append(f"invalid server JSON: {exc}")
                    continue
                self.events.put(message)
        except Exception as exc:
            if not self.closed:
                self.events.put({"op": "_READER_ERROR", "message": str(exc)})

    def send(self, op, **fields):
        self.ws.send(json.dumps({"op": op, **fields}, separators=(",", ":")))

    def subscribe(self, destination, sub_id=None):
        sub_id = sub_id or f"sub-{uuid.uuid4()}"
        self.subscriptions[sub_id] = destination
        self.send("SUBSCRIBE", id=sub_id, destination=destination)
        return sub_id

    def publish(self, destination, body):
        self.send("SEND", destination=destination, body=body if isinstance(body, str) else json.dumps(body))

    def wait(self, predicate, timeout=8):
        deadline = time.monotonic() + timeout
        while True:
            for index, message in enumerate(self.pending):
                if predicate(message):
                    return self.pending.pop(index)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError("timed out waiting for expected socket message")
            try:
                message = self.events.get(timeout=remaining)
            except queue.Empty as exc:
                raise AssertionError("timed out waiting for expected socket message") from exc
            if message.get("op") == "_READER_ERROR":
                raise AssertionError(f"socket reader failed: {message.get('message')}")
            if message.get("op") == "ERROR":
                self.errors.append(message.get("message", "socket error"))
            if predicate(message):
                return message
            self.pending.append(message)

    def wait_message(self, destination, timeout=8):
        return self.wait(lambda m: m.get("op") == "MESSAGE" and m.get("destination") == destination, timeout)

    def has_message(self, predicate):
        """Inspect queued and pending events without discarding any unmatched event."""
        if any(predicate(message) for message in self.pending):
            return True
        while True:
            try:
                message = self.events.get_nowait()
            except queue.Empty:
                return False
            if predicate(message):
                return True
            self.pending.append(message)

    def wait_closed(self, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self.ws.connected:
                return
            time.sleep(0.025)
        raise AssertionError("socket was not closed after authorization denial")

    def close(self):
        self.closed = True
        try:
            self.ws.close()
        except Exception:
            pass


def login(backend, email, password):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    request = urllib.request.Request(backend.rstrip("/") + "/api/auth", data=json.dumps(
        {"email": email, "password": password}).encode(), headers={"Content-Type": "application/json"}, method="POST")
    with opener.open(request, timeout=15) as response:
        body = response.read()
        if response.status not in (200, 204):
            raise RuntimeError(f"login failed for {email}: HTTP {response.status}")
    cookie = "; ".join(f"{c.name}={c.value}" for c in jar)
    if not cookie:
        raise RuntimeError(f"login did not return cookies for {email}")
    return cookie


def body(message):
    value = message.get("body", "")
    return json.loads(value) if isinstance(value, str) else value


def http_json(backend, path, cookie, method="GET", payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"Cookie": cookie, "Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(backend.rstrip("/") + path, data=data,
                                     headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.loads(response.read())


def subscribe_and_wait(client, destination, timeout=8):
    sid = client.subscribe(destination)
    # Current native protocol has no SUBSCRIBED ack; a test SEND/probe or init is used.
    return sid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default="http://localhost:18080")
    parser.add_argument("--socket-url", default="ws://localhost:18090/ws")
    args = parser.parse_args()
    password = os.environ.get("WOOMS_TEST_PASSWORD")
    if not password:
        parser.error("WOOMS_TEST_PASSWORD environment variable is required")

    emails = ["admin@wooms.com", "admin2@wooms.com", "admin3@wooms.com"]
    cookies = [login(args.backend_url, email, password) for email in emails]
    clients = []
    checks = []
    room = "00000000-0000-0000-0000-000000000001"
    try:
        a = SocketClient(args.socket_url, cookies[0]); clients.append(a)
        b = SocketClient(args.socket_url, cookies[1]); clients.append(b)

        chat = f"/ws/wooms/chat/{room}"
        send_chat = f"/ws/send/chat/{room}"
        a.subscribe(chat); b.subscribe(chat)
        nonce = f"chat-{uuid.uuid4()}"
        a.publish(send_chat, {"content": nonce, "nickname": "spoofed"})
        received = body(b.wait_message(chat))
        assert received["content"] == nonce and received["nickname"] == "admin"
        checks.append("authenticated chat broadcasts with server nickname")

        move = f"/ws/wooms/move/{room}"
        disc = f"/ws/wooms/disconnect/{room}"
        init = "/user/queue/init"
        a.subscribe(init); a.subscribe(move); b.subscribe(move); b.subscribe(disc)
        first_pose_message = a.wait(lambda m: m.get("op") == "MESSAGE" and m.get("destination") == init
                                    and isinstance(body(m), dict) and body(m).get("nickname") == "admin")
        first_pose = body(first_pose_message)
        move_body = {"x": "1040", "y": "760", "direction": 1, "stepId": 2, "nickname": "lie", "costume": 99}
        a.publish(f"/ws/send/move/{room}", move_body)
        pose = body(b.wait_message(move))
        assert pose["nickname"] == "admin" and pose["costume"] != 99 and pose["x"] == "1040"
        checks.append("move presence init, server identity, and broadcast")

        draw_topic = f"/ws/wooms/sketch/draw/{room}"
        sketch_init = "/user/queue/sketch/init"
        a.subscribe(sketch_init); b.subscribe(sketch_init)
        a.subscribe(draw_topic); b.subscribe(draw_topic)
        init_canvas = body(a.wait_message(sketch_init))
        assert init_canvas["size"] == 32 and len(init_canvas["pixels"]) == 32
        draw_y, draw_x, expected_color = next(
            ((y, x, color) for y, row in enumerate(init_canvas["pixels"])
             for x, value in enumerate(row) for color in (3, 4) if value != color),
            (None, None, None))
        if draw_x is None:
            raise AssertionError("seed wall has no cell that can be changed to test palette color")
        stroke = {"tool":"PEN","x":draw_x,"y":draw_y,"colorIndex":expected_color}
        a.publish(f"/ws/send/sketch/draw/{room}", stroke)
        got_stroke = body(b.wait_message(draw_topic))
        assert (got_stroke["nickname"] == "admin" and got_stroke["x"] == draw_x
                and got_stroke["y"] == draw_y and got_stroke["colorIndex"] == expected_color)

        c = SocketClient(args.socket_url, cookies[2]); clients.append(c)
        c.subscribe(sketch_init)
        c.subscribe(draw_topic)
        late = body(c.wait_message(sketch_init))
        assert late["pixels"][draw_y][draw_x] == expected_color
        a.publish(f"/ws/send/sketch/save/{room}", {"pixels": late["pixels"]})
        reset = body(a.wait(lambda m: m.get("op") == "MESSAGE" and m.get("destination") == draw_topic
                            and body(m).get("tool") == "RESET"))
        assert reset.get("tool") == "RESET"
        gallery_req = urllib.request.Request(args.backend_url.rstrip("/") + f"/api/sketch/{urllib.parse.quote(room, safe='')}/murals", headers={"Cookie": cookies[0]})
        with urllib.request.urlopen(gallery_req, timeout=15) as response:
            gallery = json.loads(response.read())
        assert gallery and gallery[0]["savedBy"] == "admin" and gallery[0]["pixels"][draw_y][draw_x] == expected_color
        checks.append("sketch drawing, late-join snapshot, archive, gallery persistence")

        flush_y, flush_x = next((y, x) for y in range(32) for x in range(32)
                                if (y, x) != (draw_y, draw_x))
        flush_color = next(color for color in (3, 4, 5) if color != late["pixels"][flush_y][flush_x])
        a.publish(f"/ws/send/sketch/draw/{room}",
                  {"tool": "PEN", "x": flush_x, "y": flush_y, "colorIndex": flush_color})
        b.wait(lambda m: m.get("op") == "MESSAGE" and m.get("destination") == draw_topic
               and body(m).get("tool") == "PEN" and body(m).get("x") == flush_x
               and body(m).get("y") == flush_y and body(m).get("colorIndex") == flush_color)
        flush_deadline = time.monotonic() + 8
        persisted = None
        while time.monotonic() < flush_deadline:
            persisted = http_json(args.backend_url, f"/api/realtime/rooms/{room}/wall", cookies[0])
            if persisted.get("pixels", [])[flush_y][flush_x] == flush_color:
                break
            time.sleep(0.25)
        assert persisted and persisted["pixels"][flush_y][flush_x] == flush_color
        checks.append("periodic service-authenticated wall flush")

        created = http_json(args.backend_url, "/api/wooms", cookies[2], method="POST",
                            payload={"woomsTitle": "Netty isolated smoke"})
        isolated_room = str(created["woomsInviteCode"])
        isolated_chat = f"/ws/wooms/chat/{isolated_room}"
        isolated_send = f"/ws/send/chat/{isolated_room}"
        isolated = SocketClient(args.socket_url, cookies[2]); clients.append(isolated)
        isolated.subscribe(isolated_chat)
        isolated_nonce = f"isolated-{uuid.uuid4()}"
        isolated.publish(isolated_send, {"content": isolated_nonce})
        isolated_echo = body(isolated.wait(lambda m: m.get("op") == "MESSAGE"
                                           and m.get("destination") == isolated_chat
                                           and body(m).get("content") == isolated_nonce))
        assert isolated_echo.get("nickname") == "admin3"
        assert not a.has_message(lambda m: m.get("op") == "MESSAGE"
                                 and m.get("destination") == isolated_chat
                                 and body(m).get("content") == isolated_nonce)
        assert not b.has_message(lambda m: m.get("op") == "MESSAGE"
                                 and m.get("destination") == isolated_chat
                                 and body(m).get("content") == isolated_nonce)
        checks.append("new room creation and isolated chat delivery")

        outsider = SocketClient(args.socket_url, cookies[0]); clients.append(outsider)
        outsider.subscribe(isolated_chat)
        outsider_error = outsider.wait(lambda m: m.get("op") == "ERROR", timeout=8)
        assert outsider_error.get("message")
        outsider.wait_closed(timeout=5)
        checks.append("existing but unjoined room subscription denied and closed")

        anonymous = SocketClient(args.socket_url, None); clients.append(anonymous)
        anonymous.subscribe(chat)
        denied = anonymous.wait(lambda m: m.get("op") == "ERROR")
        assert denied.get("message")
        checks.append("anonymous public subscription denied")

        try:
            bad = SocketClient(args.socket_url, cookies[0], origin="http://evil.invalid", timeout=5)
            clients.append(bad)
            raise AssertionError("bad Origin unexpectedly connected")
        except (websocket.WebSocketBadStatusException, websocket.WebSocketConnectionClosedException):
            checks.append("bad Origin rejected")

        mafia_room = "00000000-0000-0000-0000-000000000001"
        mafia_clients = [a, b, c]
        topics = {name: f"/ws/wooms/sketchmafia/{name}/{mafia_room}" for name in ("state","reveal","result","chat")}
        for client in mafia_clients:
            client.subscribe("/user/queue/sketchmafia/role")
            client.subscribe("/user/queue/sketchmafia/error")
            for destination in topics.values(): client.subscribe(destination)
        join = f"/ws/send/sketchmafia/join/{mafia_room}"
        start = f"/ws/send/sketchmafia/start/{mafia_room}"
        for client, nickname in zip(mafia_clients, ("admin","admin2","admin3")):
            client.publish(join, {"nickname":"spoof", "costume":999})
        state_dest=topics["state"]
        lobby=a.wait(lambda m:m.get("op")=="MESSAGE" and m.get("destination")==state_dest
                      and body(m).get("phase")=="LOBBY" and len(body(m).get("players",[]))==3,timeout=8)
        lobby_body=body(lobby)
        assert not any(k in lobby_body for k in ("word","mafiaNickname","roles"))
        host_client=next(client for client,nickname in zip(mafia_clients, ("admin","admin2","admin3"))
                         if nickname==lobby_body.get("host"))
        host_client.publish(start, {"drawSeconds":60})
        role_data=[]
        for client in mafia_clients:
            msg=client.wait(lambda m: m.get("op") == "MESSAGE" and m.get("destination") in
                            ("/user/queue/sketchmafia/role", "/user/queue/sketchmafia/error"), timeout=8)
            if msg.get("destination") == "/user/queue/sketchmafia/error":
                raise AssertionError(f"mafia command rejected: {body(msg).get('message', 'unknown error')}")
            role_data.append(body(msg))
        mafia_index=next((idx for idx,role in enumerate(role_data) if role.get("mafia")),None)
        assert mafia_index is not None
        assert all(role.get("role") and (idx==mafia_index or role.get("mafia") is False) for idx,role in enumerate(role_data))
        state_msg=a.wait(lambda m:m.get("op")=="MESSAGE" and m.get("destination")==state_dest
                          and body(m).get("phase")=="DRAW",timeout=8)
        assert not any(k in body(state_msg) for k in ("word","mafiaNickname","roles"))
        empty_pixels=[[-1]*32 for _ in range(32)]
        submit=f"/ws/send/sketchmafia/submit/{mafia_room}"
        for client in mafia_clients: client.publish(submit,{"pixels":empty_pixels})
        reveal_msg=a.wait(lambda m:m.get("op")=="MESSAGE" and m.get("destination")==topics["reveal"],timeout=10)
        reveal=body(reveal_msg)
        assert not any(k in reveal for k in ("word","role","mafiaNickname","roles"))
        vote_state=a.wait(lambda m:m.get("op")=="MESSAGE" and m.get("destination")==state_dest and body(m).get("phase")=="VOTE",timeout=10)
        assert not any(k in body(vote_state) for k in ("word","mafiaNickname","roles"))
        vote=f"/ws/send/sketchmafia/vote/{mafia_room}"
        mafia_nickname=("admin","admin2","admin3")[mafia_index]
        for client in mafia_clients: client.publish(vote,{"target":mafia_nickname})
        guess_state=a.wait(lambda m:m.get("op")=="MESSAGE" and m.get("destination")==state_dest and body(m).get("phase")=="MAFIA_GUESS",timeout=10)
        mafia_clients[mafia_index].publish(f"/ws/send/sketchmafia/guess/{mafia_room}",{"word":"definitely-wrong"})
        result=body(a.wait_message(topics["result"],timeout=10))
        assert result.get("outcome") == "CITIZEN_WIN"
        assert not any(k in body(state_msg) for k in ("word","mafiaNickname","roles"))
        checks.append("mafia private roles, phases, vote, guess, result secrecy")

        invalid_room = "00000000-0000-0000-0000-000000000099"
        denied_client = SocketClient(args.socket_url, cookies[0]); clients.append(denied_client)
        denied_client.subscribe(f"/ws/wooms/chat/{invalid_room}")
        invalid_error = denied_client.wait(lambda m: m.get("op") == "ERROR", timeout=8)
        assert invalid_error.get("message")
        denied_client.wait_closed(timeout=5)
        checks.append("nonexistent room subscription denied")

        a.close()
        disconnect = body(b.wait(lambda m: m.get("op") == "MESSAGE" and m.get("destination") == disc
                                  and body(m).get("nickname") == "admin", timeout=8))
        assert disconnect.get("nickname") == "admin"
        checks.append("disconnect removes the owning room presence")

        print(json.dumps({"ok":True,"checks":checks,"rooms":{"chatMoveSketch":room,"mafia":mafia_room,
                          "isolated":isolated_room},"clients":len(clients)},indent=2))
    finally:
        for client in clients:
            client.close()


if __name__ == "__main__":
    main()
