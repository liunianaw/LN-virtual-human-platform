# ruoyi-media

`ruoyi-media` is the independent Python media-service project. M1 provides two separately started processes only:

- API: `GET /health` returns `{"service":"ruoyi-media","status":"ready"}` and does not contact a broker, database, provider, or secret store.
- Worker: emits JSON `ready` then periodic `heartbeat` events. It consumes no queue in M1.

The package boundaries are intentional: `api` owns HTTP entrypoints, `worker` owns future background consumption, `providers` holds future external adapters, and `media` holds future local CPU processing.

## Reproducible setup

Use Python 3.12 or newer. The checked-in `requirements.lock` pins the complete M1 runtime dependency set.

```powershell
Set-Location -LiteralPath 'D:\BigData\LN-virtual‑human‑platform\LN-virtual‑human‑platform\ruoyi-media'
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --requirement .\requirements.lock
```

## Run and verify

Start these in separate PowerShell windows:

```powershell
# API
Set-Location -LiteralPath 'D:\BigData\LN-virtual‑human‑platform\LN-virtual‑human‑platform\ruoyi-media'
.\.venv\Scripts\python.exe -m uvicorn ruoyi_media.api.app:app --app-dir .\src --host 127.0.0.1 --port 8002

# Worker
Set-Location -LiteralPath 'D:\BigData\LN-virtual‑human‑platform\LN-virtual‑human‑platform\ruoyi-media'
$env:PYTHONPATH = (Resolve-Path .\src).Path
.\.venv\Scripts\python.exe -m ruoyi_media.worker --interval 30
```

Then validate the API and a finite Worker probe:

```powershell
Invoke-RestMethod http://127.0.0.1:8002/health
$env:PYTHONPATH = (Resolve-Path .\src).Path
.\.venv\Scripts\python.exe -m ruoyi_media.worker --interval 0 --max-heartbeats 1
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

Stop the API or persistent Worker with `Ctrl+C`. M1 deliberately excludes real providers, MQ consumption, database access, and all credentials.
