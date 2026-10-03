"""One bounded native inference process; no business database, billing or retries."""
import hmac
import json
import os
import threading
import time
from pathlib import Path
from .executor import audio_metadata, Problem
from .model_backends import load_backend
from .native_providers import capability
from .providers import ProviderFailure
from .server import Server, Handler


class ModelHandler(Handler):
    def handle_api(self):
        try:
            if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.server.token):
                return self.reply(401, {"code": "INTERNAL_AUTH_REQUIRED"})
            if self.command == "GET" and self.path == "/internal/model/v1/readiness":
                return self.reply(200, {"apiReachable": True, "modelReady": self.server.backend is not None,
                    "capability": self.server.capability, "sourceCommit": self.server.lock["source"]["commit"],
                    "weightRevision": self.server.lock["weights"]["revision"], "overlayVersion": "1",
                    "loadedCodePath": self.server.code_path, "loadError": self.server.load_error,
                    "inferenceConcurrency": 1, "cancelMode": "QUEUED_ONLY", "lastSynthesis": self.server.last})
            if self.command != "POST" or self.path != "/internal/model/v1/synthesize":
                return self.reply(404, {"code": "NOT_FOUND"})
            request = self.body()
            required = {"schemaVersion", "text", "binding", "deadlineAt", "referenceAudioUrl", "referenceAudioSha256", "referenceAudioBytes"}
            if set(request) != required or request["schemaVersion"] != 1 or type(request["deadlineAt"]) not in (int, float) \
                    or not time.time() < request["deadlineAt"] <= time.time() + 180:
                raise ProviderFailure("VOICE_PARAMETER_INVALID")
            binding, cap = request["binding"], self.server.capability
            if not isinstance(request["text"], str) or not request["text"].strip() or len(request["text"]) > cap["maxInputChars"]:
                raise ProviderFailure("VOICE_PARAMETER_INVALID")
            if any(binding.get(field) != cap[field] for field in ("providerType", "modelId", "modelRevision", "capabilityVersion")) \
                    or binding.get("language") not in cap["languages"]:
                raise ProviderFailure("VOICE_CAPABILITY_UNSUPPORTED")
            from .executor import validate_binding
            validate_binding(binding, cap)
            if self.server.backend is None:
                raise ProviderFailure("VOICE_PROVIDER_NOT_READY")
            if not self.server.inference_slot.acquire(blocking=False):
                raise ProviderFailure("VOICE_QUEUE_FULL")
            started = time.monotonic()
            try:
                try:
                    data = self.server.backend.synthesize(request)
                except (ProviderFailure, TimeoutError):
                    raise
                except Exception:
                    raise ProviderFailure("VOICE_PROVIDER_FAILED", "DEFINITIVE") from None
                metadata = audio_metadata(data, 5242880)
                self.server.last = {"status": "SUCCEEDED", "elapsedMs": int((time.monotonic() - started)*1000),
                                    "durationMs": metadata["durationMs"]}
                return self.reply(200, data, "audio/wav")
            finally:
                # Disconnection never releases the model slot while native inference is still running.
                self.server.inference_slot.release()
        except ProviderFailure as error:
            self.reply(503 if error.code in ("VOICE_QUEUE_FULL", "VOICE_PROVIDER_NOT_READY") else 400,
                       {"code": error.code, "failureStage": error.stage})
        except Problem as error:
            self.reply(400, {"code": error.code, "failureStage": "DEFINITIVE"})
        except (TimeoutError, ConnectionError, BrokenPipeError):
            self.reply(504, {"code": "VOICE_OUTCOME_UNKNOWN", "failureStage": "DISPATCHING"})
        except (KeyError, TypeError, ValueError):
            self.reply(400, {"code": "VOICE_PARAMETER_INVALID", "failureStage": "BEFORE_DISPATCH"})
        except Exception:
            # Do not expose native errors: they may contain text, prompts or signed URLs.
            self.reply(500, {"code": "VOICE_PROVIDER_FAILED", "failureStage": "DEFINITIVE"})


class ModelServer(Server):
    def __init__(self, address, token, descriptor, lock, backend=None, code_path=None, load_error=None):
        super().__init__(address, None, token)
        self.RequestHandlerClass = ModelHandler
        self.capability, self.lock, self.backend = descriptor, lock, backend
        self.code_path, self.load_error, self.last = code_path, load_error, {"status": "NOT_TESTED"}
        self.inference_slot = threading.BoundedSemaphore(1)


def main():
    name = os.environ["LN_VOICE_MODEL_PROVIDER"]
    if name not in ("COSYVOICE3", "KOKORO"):
        raise ValueError("Unsupported native model")
    lock = json.loads(Path(os.environ["LN_VOICE_SOURCE_LOCK"]).read_text(encoding="utf-8"))
    descriptor = capability(name)
    backend, code_path, error = None, None, None
    try:
        backend, code_path = load_backend(name, os.environ["LN_VOICE_OFFICIAL_SOURCE"],
            os.environ["LN_VOICE_MODEL_DIR"], lock, descriptor)
    except Exception as failure:
        error = type(failure).__name__  # Safe startup diagnostic; ready remains false.
    server = ModelServer((os.getenv("LN_VOICE_MODEL_BIND", "127.0.0.1"), int(os.getenv("LN_VOICE_MODEL_PORT", "8011"))),
                         os.environ["LN_VOICE_MODEL_BEARER"], descriptor, lock, backend, code_path, error)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
