# ruoyi-media

`ruoyi-media` is the independent Python media-service project. M1 provides two separately started processes only:

- API: `GET /health` returns `{"service":"ruoyi-media","status":"ready"}` and does not contact a broker, database, provider, or secret store.
- Worker: emits JSON `ready` then periodic `heartbeat` events. It consumes no queue in M1.

The package boundaries are intentional: `api` owns HTTP entrypoints, `worker` owns future background consumption, `providers` holds future external adapters, and `media` holds local CPU processing.

## M2 local action processing

`ruoyi_media.media.process_action_board(source, target, action)` accepts one 1536 x 1536 generated action board, removes a saturated chroma background from its six fixed 512 x 768 cells, and emits six RGBA PNGs, a deterministic 1536 x 1536 transparent 3 x 2 `atlas.png`, and `manifest.json`.  The action is limited to `idle`, `speaking`, `listening`, `thinking`, `nod`, `shake_head`, `wave`, or `happy`; the first four loop at 6 fps and the remaining four do not. `validate_action_package(target)` checks the fixed layout, dimensions, alpha-capable PNGs, and every frame/atlas SHA-256 before later COS/database code attaches file IDs.

This adapts the in-repository `validation/avatar_lab/media.py` CPU matte and six-frame checks into the formal action-atlas contract from `接口设计说明书01.md` §5.4. It deliberately excludes provider calls, local config/secrets, RabbitMQ, database access, preview/GIF generation, and version-wide foot-anchor normalization; those require the task orchestration and review stages.

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
