"""Manual free-of-charge stop/replacement probe against the DEV-08 Relay test hooks."""

import json
import os
import time
import uuid

import websocket


def send(socket, kind, session, epoch, data, turn_id=None):
    request_id = uuid.uuid4().hex
    frame = {"v": 1, "type": kind, "requestId": request_id, "sessionId": session,
             "connectionEpoch": epoch, "data": data}
    if turn_id:
        frame["turnId"] = turn_id
    socket.send(json.dumps(frame))
    return request_id


def receive_until(socket, predicate, seconds=10):
    deadline = time.monotonic() + seconds
    events = []
    while time.monotonic() < deadline:
        event = json.loads(socket.recv())
        events.append(event)
        if predicate(event):
            return event, events
    raise RuntimeError("Timed out")


def main():
    socket = websocket.create_connection(
        os.environ.get("DEV08_WS_URL", "ws://127.0.0.1:8080/api/v1/realtime"),
        subprotocols=["ln-avatar.v1"], origin="http://127.0.0.1", timeout=10)
    socket.settimeout(10)
    try:
        socket.send(json.dumps({"v": 1, "type": "connection.auth", "requestId": uuid.uuid4().hex,
                                "data": {"ticket": os.environ["DEV08_WS_TICKET"]}}))
        ready = json.loads(socket.recv())
        assert ready["type"] == "connection.ready", ready["type"]
        session, epoch = ready["sessionId"], ready["connectionEpoch"]

        request = send(socket, "chat.create", session, epoch, {"text": "DEV08_SLOW_TEST"})
        ack, _ = receive_until(socket, lambda e: e["type"] == "request.ack" and e["requestId"] == request)
        stopped_id = ack["turnId"]
        send(socket, "turn.stop", session, epoch, {"reason": "USER_STOP"}, stopped_id)
        stopped, _ = receive_until(socket, lambda e: e["type"] == "turn.stopped" and e["turnId"] == stopped_id)
        assert stopped["data"]["alreadyStopped"] is False
        print(f"stop turn={stopped_id} event=turn.stopped")

        request = send(socket, "chat.create", session, epoch, {"text": "DEV08_SLOW_TEST"})
        ack, _ = receive_until(socket, lambda e: e["type"] == "request.ack" and e["requestId"] == request)
        replaced_id = ack["turnId"]
        request = send(socket, "chat.create", session, epoch, {"text": "DEV08_FAST_TEST"})
        ack, _ = receive_until(socket, lambda e: e["type"] == "request.ack" and e["requestId"] == request)
        new_id = ack["turnId"]
        completed, seen = receive_until(socket, lambda e: e["type"] == "turn.completed" and e["turnId"] == new_id)
        assert not any(e["type"] == "turn.completed" and e["turnId"] == replaced_id for e in seen)
        assert completed["data"]["textStatus"] == "COMPLETED"
        print(f"replace old={replaced_id} new={new_id} newCompleted=yes")
    finally:
        socket.close()


if __name__ == "__main__":
    main()
