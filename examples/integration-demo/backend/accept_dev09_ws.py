"""One bounded BUSINESS speech probe; pass only a short Session Token through the environment."""

import json
import os
from pathlib import Path
import time
import uuid
from urllib.request import Request, urlopen

import websocket


BASE = "http://127.0.0.1:8080"


def main():
    token = os.environ["DEV09_BUSINESS_TOKEN"]
    headers = {"Authorization": f"Bearer {token}"}
    ticket_request = Request(BASE + "/api/v1/runtime/connection-tickets",
                             json.dumps({"purpose": "CONNECT"}).encode(),
                             {**headers, "Content-Type": "application/json"}, method="POST")
    with urlopen(ticket_request, timeout=10) as response:
        ticket = json.load(response)["data"]
    socket = websocket.create_connection(ticket["webSocketUrl"], subprotocols=["ln-avatar.v1"],
                                         origin="http://127.0.0.1", timeout=12)
    socket.settimeout(40)
    try:
        socket.send(json.dumps({"v": 1, "type": "connection.auth", "requestId": uuid.uuid4().hex,
                                "data": {"ticket": ticket["ticket"]}}))
        ready = json.loads(socket.recv())
        assert ready["type"] == "connection.ready", ready["type"]
        epoch, session_id = ready["connectionEpoch"], ready["sessionId"]
        request_id = uuid.uuid4().hex
        mode = os.environ.get("DEV09_MODE", "SPEECH")
        speech = os.environ.get("DEV09_SPEECH_TEXT", "你好。测试完毕。")
        expected = list(range(int(os.environ.get("DEV09_EXPECTED_SEGMENTS", "1"))))
        socket.send(json.dumps({"v": 1, "type": "chat.create" if mode == "CHAT" else "speech.create", "requestId": request_id,
                                "sessionId": session_id, "connectionEpoch": epoch,
                                "data": {"text": "DEV09_SENTENCES_TEST" if mode == "CHAT" else speech}}))
        events, delivered = [], []
        text_parts = []
        turn_id = None
        deadline = time.monotonic() + 110
        while time.monotonic() < deadline:
            event = json.loads(socket.recv())
            kind = event["type"]
            events.append(kind)
            if kind == "request.ack" and event.get("requestId") == request_id:
                turn_id = event["turnId"]
                if mode == "STOP":
                    socket.send(json.dumps({"v": 1, "type": "turn.stop", "requestId": uuid.uuid4().hex,
                                            "sessionId": session_id, "connectionEpoch": epoch,
                                            "turnId": turn_id, "data": {"reason": "USER_STOP"}}))
            elif kind == "text.delta":
                text_parts.append(event["data"]["text"])
            elif kind == "audio.segment":
                assert event["turnId"] == turn_id
                data = event["data"]
                assert data["ordinal"] == len(delivered), data["ordinal"]
                audio_request = Request(BASE + "/api/v1/runtime/media/" + data["mediaId"], headers=headers)
                with urlopen(audio_request, timeout=12) as response:
                    audio = response.read()
                    assert audio[:4] == b"RIFF" and audio[8:12] == b"WAVE"
                if os.environ.get("DEV09_SAVE_AUDIO_PATH"):
                    Path(os.environ["DEV09_SAVE_AUDIO_PATH"]).write_bytes(audio)
                delivered.append(data["ordinal"])
                for state in ("STARTED", "ENDED"):
                    socket.send(json.dumps({"v": 1, "type": "playback.report", "requestId": uuid.uuid4().hex,
                                            "sessionId": session_id, "connectionEpoch": epoch,
                                            "turnId": turn_id, "data": {"segmentId": data["segmentId"],
                                                                         "state": state}}))
            elif kind == "turn.completed":
                assert delivered == expected, delivered
                if mode == "CHAT":
                    assert "text.completed" in events and ''.join(text_parts) == "你好。测试完毕。", text_parts
                print(f"mode={mode} completed turn={turn_id} delivered={delivered} events={events}")
                return
            elif kind == "turn.stopped" and mode == "STOP":
                socket.settimeout(8)
                try:
                    while True:
                        later = json.loads(socket.recv())
                        assert later["type"] != "audio.segment", later["type"]
                except websocket.WebSocketTimeoutException:
                    pass
                print(f"mode=STOP turn={turn_id} delivered={delivered} events={events}")
                return
            elif kind in ("turn.failed", "request.error", "audio.failed"):
                raise RuntimeError(f"speech failed: {kind} {event.get('data')}")
        raise TimeoutError(f"speech timeout: {events}")
    finally:
        socket.close()


if __name__ == "__main__":
    main()
