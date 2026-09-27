"""Offline contract checks for the DEV-08 demo adapter; no provider request is made."""

import base64
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import dev08_relay


class DemoRelayTest(unittest.TestCase):
    def test_key_file_overrides_stale_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            key_file = Path(directory) / "key.txt"
            key_file.write_text("new-key\n", encoding="utf-8")
            with patch.dict(os.environ, {
                "DEV08_PROVIDER_KEY_FILE": str(key_file), "DASHSCOPE_API_KEY": "stale-key",
                "DEV08_RELAY_TOKEN": "relay-token", "DEV08_TOOL_TOKEN": "tool-token",
                "DEV08_ALLOWED_USER": "user-1", "DEV08_APPLICATION_ID": "123",
            }):
                self.assertEqual("new-key", dev08_relay.Settings.from_environment().provider_key)

    def setUp(self):
        self.client = dev08_relay.create_app(dev08_relay.Settings(
            "relay-token", "tool-token", "provider-key", "user-1", "123"
        )).test_client()
        self.headers = {
            "Authorization": "Bearer relay-token",
            "X-LN-Protocol-Version": "1",
            "X-Request-Id": "req-1",
        }

    def request(self, turn):
        return {
            "requestId": "req-1", "applicationId": "123", "sessionId": "456",
            "turnId": str(turn), "externalUserId": "user-1", "model": "qwen-flash",
            "stream": True, "messages": [{"role": "system", "content": "be brief"},
                                       {"role": "user", "content": "hello"}],
            "tools": [], "parameters": {},
        }

    def test_capabilities_and_identity_bound_tool(self):
        self.assertEqual(401, self.client.get("/ln-relay/v1/capabilities").status_code)
        capability = self.client.get("/ln-relay/v1/capabilities", headers=self.headers)
        self.assertTrue(capability.json["capabilities"]["llm"])
        headers = {
            "Authorization": "Bearer tool-token",
            "X-LN-External-User-Id-B64": base64.urlsafe_b64encode(b"user-1").decode().rstrip("="),
            "X-LN-Application-Id": "123", "X-LN-Session-Id": "456",
        }
        self.assertEqual(7, self.client.get("/tool/lookup?item=demo-1", headers=headers).json["stock"])
        headers["X-LN-Application-Id"] = "999"
        self.assertEqual(403, self.client.get("/tool/lookup?item=demo-1", headers=headers).status_code)
        headers["X-LN-Application-Id"] = "123"
        headers["X-LN-External-User-Id-B64"] = base64.urlsafe_b64encode(
            b"__ln_debug__:456"
        ).decode().rstrip("=")
        self.assertEqual(200, self.client.get("/tool/lookup?item=demo-1", headers=headers).status_code)

    def test_provider_stream_and_cross_turn_memory(self):
        seen = []

        def fake_urlopen(request, timeout):
            seen.append(json.loads(request.data))
            self.assertEqual("Bearer provider-key", request.headers["Authorization"])
            self.assertEqual(30, timeout)
            return io.BytesIO(
                b'data: {"id":"provider-1","choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}\n\n'
                b'data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}\n\n'
                b'data: [DONE]\n\n'
            )

        with patch.object(dev08_relay, "urlopen", fake_urlopen):
            for turn in (1, 2):
                response = self.client.post("/ln-relay/v1/chat/completions", json=self.request(turn), headers=self.headers)
                self.assertEqual(200, response.status_code)
                self.assertIn(b'event: text.delta\ndata: {"text":"Hi"}', response.data)
                self.assertIn(b'"inputTokens":3', response.data)
        self.assertEqual(2, len(seen))
        self.assertEqual(["system", "user", "assistant", "user"],
                         [message["role"] for message in seen[1]["messages"]])
        self.assertEqual(256, seen[0]["max_tokens"])

    def test_invalid_application_never_calls_provider(self):
        body = self.request(1)
        body["applicationId"] = "999"
        with patch.object(dev08_relay, "urlopen") as upstream:
            self.assertEqual(400, self.client.post(
                "/ln-relay/v1/chat/completions", json=body, headers=self.headers
            ).status_code)
            upstream.assert_not_called()

    def test_provider_call_budget(self):
        def fake_urlopen(request, timeout):
            return io.BytesIO(
                b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'
                b'data: [DONE]\n\n'
            )

        with patch.object(dev08_relay, "urlopen", side_effect=fake_urlopen) as upstream:
            for turn in range(1, dev08_relay.MAX_PROVIDER_CALLS + 1):
                response = self.client.post(
                    "/ln-relay/v1/chat/completions", json=self.request(turn), headers=self.headers
                )
                self.assertEqual(200, response.status_code)
                self.assertIn(b"event: response.completed", response.data)
            self.assertEqual(429, self.client.post(
                "/ln-relay/v1/chat/completions", json=self.request(9), headers=self.headers
            ).status_code)
            self.assertEqual(dev08_relay.MAX_PROVIDER_CALLS, upstream.call_count)


if __name__ == "__main__":
    unittest.main()
