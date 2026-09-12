"""Dependency-free health API for the M1 media-service skeleton."""

from __future__ import annotations

import os

from fastapi import FastAPI


def health_payload() -> dict[str, str]:
    """Return local process readiness without contacting external services."""
    return {"service": "ruoyi-media", "status": "ready"}


app = FastAPI(title="ruoyi-media", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    """Expose a probe suitable for M1 process-level checks."""
    return health_payload()


def run() -> None:
    """Run the HTTP process without importing provider or worker dependencies."""
    import uvicorn

    uvicorn.run(
        app,
        host=os.getenv("RUOYI_MEDIA_HOST", "127.0.0.1"),
        port=int(os.getenv("RUOYI_MEDIA_PORT", "8002")),
    )
