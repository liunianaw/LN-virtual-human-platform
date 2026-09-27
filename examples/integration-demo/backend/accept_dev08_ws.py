"""Small manual DEV-08 runtime probe. Pass a one-time ticket in DEV08_WS_TICKET."""

import json
import os
import time
import uuid

import websocket


def main() -> None:
    ticket = os.environ["DEV08_WS_TICKET"]
    url = os.environ.get("DEV08_WS_URL", "ws://127.0.0.1:8080/api/v1/realtime")
    prompt = os.environ.get("DEV08_CHAT_PROMPT", "请用一句中文介绍你自己。")
    socket = websocket.create_connection(
        url,
        subprotocols=["ln-avatar.v1"],
        origin=os.environ.get("DEV08_WS_ORIGIN", "http://127.0.0.1"),
        timeout=10,
    )
    socket.settimeout(20)
    try:
        socket.send(json.dumps({"v": 1, "type": "connection.auth", "requestId": uuid.uuid4().hex,
                                "data": {"ticket": ticket}}))
        first = socket.recv()
        if not first:
            raise RuntimeError("WebSocket closed before connection.ready")
        ready = json.loads(first)
        if ready.get("type") != "connection.ready":
            raise RuntimeError(f"Connection failed: {ready.get('type')}")
        epoch = ready["connectionEpoch"]
        session = ready["sessionId"]
        print(f"ready session={session} epoch={epoch} caps={ready['data'].get('capabilities')}")
        request_id = uuid.uuid4().hex
        socket.send(json.dumps({"v": 1, "type": "chat.create", "requestId": request_id,
                                "sessionId": session, "connectionEpoch": epoch, "data": {"text": prompt}}))
        types = []
        text = []
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            event = json.loads(socket.recv())
            kind = event.get("type")
            types.append(kind)
            data = event.get("data") or {}
            if kind == "text.delta":
                text.append(data.get("text", ""))
            elif kind == "tool.result":
                print(f"tool.result name={data.get('toolName')} fields={data.get('fields')}")
            elif kind in ("turn.completed", "turn.failed", "request.error"):
                print(f"terminal={kind} data={data}")
                break
        print(f"events={types}")
        print(f"answer={''.join(text)[:500]}")
    finally:
        socket.close()


if __name__ == "__main__":
    main()
