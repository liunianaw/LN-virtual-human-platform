"""Authenticated, bounded internal HTTP server. Redirects are never followed."""
import hmac
import json
import os
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from .executor import Executor, Problem
from .providers import installed_providers


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def callback(path, body):
    base, token = os.environ["LN_VOICE_SESSION_URL"], os.environ["LN_VOICE_CALLBACK_BEARER"]
    request = urllib.request.Request(base.rstrip("/") + path, json.dumps(body).encode(),
        {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=5) as response:
        deadline = time.monotonic() + 5
        data = bytearray()
        while True:
            if time.monotonic() >= deadline: raise Problem("VOICE_OUTCOME_UNKNOWN", 502)
            chunk = response.read1(min(4096, 65537-len(data)))
            if not chunk: break
            data.extend(chunk)
            if len(data) > 65536: raise Problem("VOICE_OUTCOME_UNKNOWN", 502)
        return json.loads(data)


class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, address, executor, token):
        if len(token) < 32:
            raise ValueError("A dedicated internal bearer of at least 32 characters is required")
        self.executor, self.token = executor, token
        self.http_slots = threading.BoundedSemaphore(16)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.http_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        super().process_request(request, address)

    def process_request_thread(self, request, address):
        try:
            request.settimeout(5)
            super().process_request_thread(request, address)
        finally:
            self.http_slots.release()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # No request bodies, dispatch tokens, or credentials in access logs.

    def do_GET(self):
        self.handle_api()

    def do_POST(self):
        self.handle_api()

    def handle_api(self):
        try:
            if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.server.token):
                raise Problem("INTERNAL_AUTH_REQUIRED", 401)
            prefix = "/internal/voice/v1/"
            if not self.path.startswith(prefix) or "?" in self.path:
                raise Problem("NOT_FOUND", 404)
            path = self.path[len(prefix):].split("/")
            engine = self.server.executor
            if self.command == "GET" and path == ["capabilities"]:
                return self.reply(200, [p.capability for p in engine.providers.values()])
            if self.command == "GET" and path == ["readiness"]:
                probes = {name: p.readiness() if hasattr(p, "readiness") else {"modelReady": p.ready()}
                          for name, p in engine.providers.items()}
                return self.reply(200, {"apiReachable": True, "modelReady": bool(probes) and all(p["modelReady"] for p in probes.values()),
                    "workerBootId": engine.boot, "resultBytes": engine.used,
                    "providers": {name: p["modelReady"] for name, p in probes.items()},
                    "endpoints": {r["endpoint"]: r for r in probes.values() if r.get("endpoint")}})
            if self.command == "POST" and path == ["attempts"]:
                return self.reply(202, engine.submit(self.body(), self.headers.get("Idempotency-Key")))
            if len(path) in (2, 3) and path[0] == "attempts" and path[1].isascii() and path[1].isdigit():
                if self.command == "GET" and len(path) == 2:
                    return self.reply(200, engine.status(path[1]))
                if self.command == "POST" and path[2:] == ["cancel"]:
                    return self.reply(200, engine.cancel(path[1]))
                if self.command == "GET" and path[2:] == ["audio"]:
                    return self.reply(200, engine.read_audio(path[1]), "audio/wav")
            raise Problem("NOT_FOUND", 404)
        except Problem as error:
            self.reply(error.status, {"code": error.code, "message": error.code})
        except (ValueError, KeyError, TypeError, UnicodeError):
            self.reply(400, {"code": "VOICE_PARAMETER_INVALID"})
        except Exception:
            self.reply(503, {"code": "VOICE_OUTCOME_UNKNOWN"})

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= 16384 or self.headers.get("Transfer-Encoding"):
            raise Problem("VOICE_PARAMETER_INVALID", 413)
        deadline = time.monotonic() + 5
        data = bytearray()
        while len(data) < length:
            remaining = deadline - time.monotonic()
            if remaining <= 0: raise Problem("VOICE_PARAMETER_INVALID", 408)
            self.connection.settimeout(remaining)
            chunk = self.rfile.read1(min(4096, length - len(data)))
            if not chunk: raise Problem("VOICE_PARAMETER_INVALID")
            data.extend(chunk)
        return json.loads(data)

    def reply(self, status, value, content_type="application/json"):
        data = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)


def main():
    providers = installed_providers()
    engine = Executor(providers,
        lambda key, body: callback(f"/internal/v1/voice-attempts/{key}/authorize-dispatch", body),
        lambda key, body: callback(f"/internal/v1/voice-attempts/{key}/events", body),
        int(os.getenv("LN_VOICE_CONCURRENCY", "1")), int(os.getenv("LN_VOICE_QUEUE_CAPACITY", "8")),
        int(os.getenv("LN_VOICE_RESULT_TTL_SECONDS", "900")), int(os.getenv("LN_VOICE_MAX_RESULT_BYTES", "134217728")), int(os.getenv("LN_VOICE_QUEUE_TIMEOUT_SECONDS", "10")))
    server = Server((os.getenv("LN_VOICE_BIND", "127.0.0.1"), int(os.getenv("LN_VOICE_PORT", "8003"))),
        engine, os.environ["LN_VOICE_INTERNAL_BEARER"])
    stop = threading.Event()
    def maintain():
        while not stop.wait(5):
            engine.maintain()
    threading.Thread(target=maintain, daemon=True, name="voice-retention").start()
    try:
        server.serve_forever()
    finally:
        stop.set()
        server.server_close()
        engine.close()


if __name__ == "__main__":
    main()
