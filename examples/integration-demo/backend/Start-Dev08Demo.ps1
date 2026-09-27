param(
    [string]$ProviderKeyFile = 'D:\BigData\LN-virtual‑human‑platform\key.txt',
    [string]$SshPasswordFile = 'D:\BigData\LN-virtual‑human‑platform\sshkey.txt'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$python = Join-Path $repo 'tools\local-relay\.venv\Scripts\python.exe'
$logs = Join-Path $repo '.m1-runtime-logs'

function Unseal([string]$name) {
    $path = Join-Path $logs $name
    if (-not (Test-Path $path)) { throw "Missing local encrypted credential: $path" }
    $secure = ConvertTo-SecureString ((Get-Content $path -Raw).Trim())
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

if (-not (Test-Path $python) -or -not (Test-Path $ProviderKeyFile) -or -not (Test-Path $SshPasswordFile)) {
    throw 'Python venv, provider Key file, or SSH password file is missing.'
}

$relay = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -like 'python*' -and $_.CommandLine -like '*dev08_relay.py*'
} | Select-Object -First 1
if (-not $relay) {
    $env:DEV08_RELAY_TOKEN = Unseal 'dev08-relay-token.dpapi'
    $env:DEV08_TOOL_TOKEN = Unseal 'dev08-tool-token.dpapi'
    $env:DEV08_PROVIDER_KEY_FILE = $ProviderKeyFile
    $env:DEV08_ALLOWED_USER = 'dev08-user-a'
    $env:DEV08_APPLICATION_ID = '102089642576183297'
    try {
        $relay = Start-Process -FilePath $python -ArgumentList (Join-Path $PSScriptRoot 'dev08_relay.py') `
            -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logs 'dev08-relay.stdout.log') `
            -RedirectStandardError (Join-Path $logs 'dev08-relay.stderr.log')
    }
    finally {
        Remove-Item Env:DEV08_RELAY_TOKEN,Env:DEV08_TOOL_TOKEN,Env:DEV08_PROVIDER_KEY_FILE,Env:DEV08_ALLOWED_USER,Env:DEV08_APPLICATION_ID -ErrorAction SilentlyContinue
    }
}

$tunnel = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -like 'python*' -and $_.CommandLine -like '*dev08_tunnel.py*'
} | Select-Object -First 1
if (-not $tunnel) {
    $env:DEV08_SSH_PASSWORD_FILE = $SshPasswordFile
    try {
        $tunnel = Start-Process -FilePath $python -ArgumentList (Join-Path $PSScriptRoot 'dev08_tunnel.py') `
            -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logs 'dev08-tunnel.stdout.log') `
            -RedirectStandardError (Join-Path $logs 'dev08-tunnel.stderr.log')
    }
    finally { Remove-Item Env:DEV08_SSH_PASSWORD_FILE -ErrorAction SilentlyContinue }
}

Start-Sleep -Seconds 2
$health = Invoke-RestMethod 'http://127.0.0.1:8030/health'
if (-not $health.ok) { throw 'DEV-08 Relay health check failed.' }
$public = & curl.exe --silent --show-error --fail --max-time 8 --resolve 'liunianaw.online:443:124.220.61.136' 'https://liunianaw.online/dev08/health'
if ($LASTEXITCODE -ne 0 -or -not ($public | ConvertFrom-Json).ok) { throw 'DEV-08 public HTTPS tunnel health check failed.' }
Write-Output "DEV-08 Relay and public HTTPS tunnel ready; relay PID $($relay.ProcessId ?? $relay.Id), tunnel PID $($tunnel.ProcessId ?? $tunnel.Id)."
