[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$SkipRelay,
    [switch]$SkipNacosPublish,
    [ValidateRange(30, 300)]
    [int]$TimeoutSeconds = 120
)

<#
.SYNOPSIS
Starts the complete Windows development stack without duplicating healthy processes.

.DESCRIPTION
Starts MySQL, Redis, Nacos, RabbitMQ, system, session, auth, gateway, media API,
media Worker, the local Relay, and Vue in their dependency order. Existing listeners
are reused. Provider secrets are read only from ignored local configuration files and
injected into child-process environments; they are never logged. The local official
service master key is protected with the current Windows user's DPAPI so encrypted
official credentials remain readable after a launcher restart.

Use -SkipBuild when one or more Java services are already running, or when their
current JARs are intentionally the desired build. Use -SkipNacosPublish only when
the current Nacos configuration has already been verified for this source revision.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$cloudRoot = Join-Path $repositoryRoot 'RuoYi-Cloud'
$mediaRoot = Join-Path $repositoryRoot 'ruoyi-media'
$relayRoot = Join-Path $repositoryRoot 'tools\local-relay'
$webRoot = Join-Path $repositoryRoot 'RuoYi-Cloud-Vue3'
$runtimeLogRoot = Join-Path $repositoryRoot '.m1-runtime-logs'
$nacosBin = 'D:\BigData\Nacos3.2.4\nacos-server-3.2.4\nacos\bin'
$redisRoot = 'D:\BigData\Redis-x64-3.0.504'
$rabbitMqSbin = 'D:\BigData\RabbitMQ\rabbitmq_server-3.13.7\sbin'
$nacosAddress = '127.0.0.1:8848'
$nacosBaseUrl = "http://$nacosAddress"

New-Item -ItemType Directory -Force -Path $runtimeLogRoot | Out-Null

function Test-TcpPort {
    param([Parameter(Mandatory)] [int]$Port)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(750)) {
            return $false
        }
        $client.EndConnect($connect)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Wait-TcpPort {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [int]$Port
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-TcpPort $Port) {
            Write-Host "[ready] $Name ($Port)"
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "$Name did not listen on port $Port within $TimeoutSeconds seconds."
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [string]$Url
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                Write-Host "[ready] $Name ($($response.StatusCode))"
                return
            }
        }
        catch {
            # The per-service log tail is shown by the catch handler if readiness times out.
        }
        Start-Sleep -Milliseconds 750
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "$Name did not become healthy: $Url"
}

function Get-LogTail {
    param([Parameter(Mandatory)] [string]$Name)

    $log = Join-Path $runtimeLogRoot "$Name.stderr.log"
    if (Test-Path -LiteralPath $log) {
        Write-Host "--- $Name stderr (last 40 lines) ---"
        Get-Content -LiteralPath $log -Tail 40
    }
}

function Start-DetachedProcess {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [string]$FilePath,
        [Parameter(Mandatory)] [string]$WorkingDirectory,
        [string[]]$ArgumentList = @(),
        [hashtable]$Environment = @{}
    )

    $stdout = Join-Path $runtimeLogRoot "$Name.stdout.log"
    $stderr = Join-Path $runtimeLogRoot "$Name.stderr.log"
    $priorEnvironment = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $priorEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }

    try {
        $startParameters = @{
            FilePath = $FilePath
            WorkingDirectory = $WorkingDirectory
            WindowStyle = 'Hidden'
            RedirectStandardOutput = $stdout
            RedirectStandardError = $stderr
            PassThru = $true
        }
        if ($ArgumentList.Count -gt 0) {
            $startParameters.ArgumentList = $ArgumentList
        }
        $process = Start-Process @startParameters
        Write-Host "[started] $Name (pid $($process.Id))"
        return $process
    }
    finally {
        foreach ($entry in $priorEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
}

function Invoke-ProcessWithEnvironment {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [Parameter(Mandatory)] [string]$WorkingDirectory,
        [Parameter(Mandatory)] [string[]]$ArgumentList,
        [hashtable]$Environment = @{}
    )

    $priorEnvironment = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $priorEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
    try {
        $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory -Wait -PassThru
        return $process.ExitCode
    }
    finally {
        foreach ($entry in $priorEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
}

function Start-RequiredService {
    param(
        [Parameter(Mandatory)] [string]$ServiceName,
        [Parameter(Mandatory)] [int]$Port
    )

    if (Test-TcpPort $Port) {
        Write-Host "[reuse] $ServiceName ($Port)"
        return
    }
    $service = Get-Service -Name $ServiceName -ErrorAction Stop
    if ($service.Status -ne 'Running') {
        Start-Service -Name $ServiceName
        Write-Host "[started] Windows service $ServiceName"
    }
    Wait-TcpPort -Name $ServiceName -Port $Port
}

function Get-LocalYamlValue {
    param(
        [Parameter(Mandatory)] [string]$Text,
        [Parameter(Mandatory)] [string]$Key
    )

    $match = [regex]::Match($Text, "(?m)^\s*$([regex]::Escape($Key)):\s*(?<value>[^#\r\n]+)")
    if (-not $match.Success) {
        return $null
    }
    return $match.Groups['value'].Value.Trim().Trim("'`"")
}

function Get-LocalProtectedKey {
    param([Parameter(Mandatory)] [string]$FileName)
    $keyPath = Join-Path $runtimeLogRoot $FileName
    if (Test-Path -LiteralPath $keyPath) {
        try {
            $protected = Get-Content -LiteralPath $keyPath -Raw -Encoding ascii
            $secure = ConvertTo-SecureString -String $protected
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try {
                $key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
            }
            finally {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
            }
        }
        catch {
            throw "Local protected key cannot be read: $keyPath"
        }
    }
    else {
        $bytes = [byte[]]::new(32)
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $generator.GetBytes($bytes)
        }
        finally {
            $generator.Dispose()
        }
        $key = [Convert]::ToBase64String($bytes)
        $secure = ConvertTo-SecureString -String $key -AsPlainText -Force
        ConvertFrom-SecureString -SecureString $secure | Set-Content -LiteralPath $keyPath -Encoding ascii -NoNewline
    }
    try {
        if ([Convert]::FromBase64String($key).Length -ne 32) { throw 'invalid length' }
    }
    catch {
        throw "Local protected key is invalid: $keyPath"
    }
    return $key
}

function Get-LocalServiceToken {
    param([Parameter(Mandatory)] [string]$FileName)

    $tokenPath = Join-Path $runtimeLogRoot $FileName
    if (Test-Path -LiteralPath $tokenPath) {
        try {
            $secure = ConvertTo-SecureString -String (Get-Content -LiteralPath $tokenPath -Raw -Encoding ascii)
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try { $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        }
        catch { throw "Local service token cannot be read: $tokenPath" }
    }
    else {
        $token = [guid]::NewGuid().ToString('N')
        ConvertFrom-SecureString (ConvertTo-SecureString -String $token -AsPlainText -Force) |
            Set-Content -LiteralPath $tokenPath -Encoding ascii -NoNewline
    }
    if ($token -notmatch '^[0-9a-f]{32}$') { throw "Local service token is invalid: $tokenPath" }
    return $token
}

function Get-NacosAccessToken {
    param(
        [Parameter(Mandatory)] [string]$Username,
        [Parameter(Mandatory)] [string]$Password
    )

    $loginUrls = @(
        'http://127.0.0.1:8081/v3/auth/user/login',
        'http://127.0.0.1:8081/nacos/v3/auth/user/login',
        "$nacosBaseUrl/nacos/v3/auth/user/login",
        "$nacosBaseUrl/nacos/v1/auth/login"
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        foreach ($url in $loginUrls) {
            try {
                $reply = Invoke-RestMethod -Method Post -Uri $url -ContentType 'application/x-www-form-urlencoded' `
                    -Body @{ username = $Username; password = $Password } -TimeoutSec 10
                $token = $reply.accessToken
                if ([string]::IsNullOrWhiteSpace($token) -and $null -ne $reply.data) {
                    $token = $reply.data.accessToken
                }
                if ([string]::IsNullOrWhiteSpace($token) -and $null -ne $reply.data) {
                    $token = $reply.data.token
                }
                if ([string]::IsNullOrWhiteSpace($token)) {
                    $token = $reply.token
                }
                if (-not [string]::IsNullOrWhiteSpace($token)) {
                    return [string]$token
                }
            }
            catch {
                # Nacos 3.2 deployments differ in whether the console has a path prefix.
            }
        }
        Start-Sleep -Milliseconds 750
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Unable to obtain a short-lived Nacos access token from the local Nacos server.'
}

function Test-MediaWorkerRunning {
    param([Parameter(Mandatory)] [string]$PythonPath)

    return $null -ne (Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.ExecutablePath -eq $PythonPath -and $_.CommandLine -match 'ruoyi_media\.worker' } |
            Select-Object -First 1)
}

function Assert-File {
    param([Parameter(Mandatory)] [string]$Path, [Parameter(Mandatory)] [string]$Description)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description is missing: $Path"
    }
}

try {
    $cosConfigPath = Join-Path $cloudRoot 'ruoyi-modules\ruoyi-system\src\main\resources\application-local.yml'
    $providerConfigPath = Join-Path $repositoryRoot 'validation\config.local.json'
    Assert-File -Path $cosConfigPath -Description 'Ignored local COS configuration'
    Assert-File -Path $providerConfigPath -Description 'Ignored local provider configuration'

    $cosConfig = Get-Content -LiteralPath $cosConfigPath -Raw
    $providerConfig = Get-Content -LiteralPath $providerConfigPath -Raw | ConvertFrom-Json
    $cosEnvironment = @{
        RUOYI_MEDIA_COS_REGION = Get-LocalYamlValue -Text $cosConfig -Key 'region'
        RUOYI_MEDIA_COS_BUCKET = Get-LocalYamlValue -Text $cosConfig -Key 'bucket'
        RUOYI_MEDIA_COS_SECRET_ID = Get-LocalYamlValue -Text $cosConfig -Key 'secret-id'
        RUOYI_MEDIA_COS_SECRET_KEY = Get-LocalYamlValue -Text $cosConfig -Key 'secret-key'
        RUOYI_MEDIA_COS_SESSION_TOKEN = Get-LocalYamlValue -Text $cosConfig -Key 'session-token'
    }
    $requiredCosValues = @(
        $cosEnvironment.RUOYI_MEDIA_COS_REGION,
        $cosEnvironment.RUOYI_MEDIA_COS_BUCKET,
        $cosEnvironment.RUOYI_MEDIA_COS_SECRET_ID,
        $cosEnvironment.RUOYI_MEDIA_COS_SECRET_KEY
    )
    if (@($requiredCosValues | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
        throw 'Ignored local COS configuration is incomplete; no services were started.'
    }
    if ([string]::IsNullOrWhiteSpace([string]$providerConfig.api_key)) {
        throw 'Ignored local provider configuration does not contain an API key; no services were started.'
    }

    $officialServiceMasterKey = Get-LocalProtectedKey -FileName 'official-service-master-key.dpapi'
    $accessKeyPepper = Get-LocalProtectedKey -FileName 'access-key-pepper.dpapi'

    $nacosUsername = if ($env:NACOS_USERNAME) { $env:NACOS_USERNAME } else { 'nacos' }
    $nacosPassword = if ($env:NACOS_PASSWORD) { $env:NACOS_PASSWORD } else { 'nacos' }
    $internalToken = Get-LocalServiceToken -FileName 'media-internal-token.dpapi'
    $relayToken = Get-LocalServiceToken -FileName 'relay-access-token.dpapi'
    $systemToSessionBearer = Get-LocalServiceToken -FileName 'system-to-session-bearer.dpapi'
    $sessionToSystemBearer = Get-LocalServiceToken -FileName 'session-to-system-bearer.dpapi'
    $runtimeTokenSecret = Get-LocalServiceToken -FileName 'session-runtime-token-secret.dpapi'
    $commonEnvironment = @{
        NACOS_ADDR = $nacosAddress
        SPRING_CLOUD_NACOS_DISCOVERY_IP = '127.0.0.1'
        MANAGEMENT_HEALTH_SENTINEL_ENABLED = 'false'
        MANAGEMENT_HEALTH_MAIL_ENABLED = 'false'
        NACOS_USERNAME = $nacosUsername
        NACOS_PASSWORD = $nacosPassword
        REDIS_HOST = '127.0.0.1'
        REDIS_PORT = '6379'
        REDIS_PASSWORD = ''
        RABBITMQ_HOST = '127.0.0.1'
        RABBITMQ_PORT = '5672'
        RABBITMQ_USERNAME = 'dev'
        RABBITMQ_PASSWORD = '123456'
        RABBITMQ_VHOST = '/dev_vhost'
    }

    Start-RequiredService -ServiceName 'MYSQL80' -Port 3306

    if (Test-TcpPort 6379) {
        Write-Host '[reuse] Redis (6379)'
    }
    else {
        Start-DetachedProcess -Name 'redis' -FilePath (Join-Path $redisRoot 'redis-server.exe') -WorkingDirectory $redisRoot `
            -ArgumentList @('redis.windows.conf') | Out-Null
        Wait-TcpPort -Name 'Redis' -Port 6379
    }

    if (Test-TcpPort 8848) {
        Write-Host '[reuse] Nacos (8848)'
    }
    else {
        Assert-File -Path (Join-Path $nacosBin 'startup.cmd') -Description 'Nacos startup command'
        Start-DetachedProcess -Name 'nacos-bootstrap' -FilePath (Join-Path $nacosBin 'startup.cmd') -WorkingDirectory $nacosBin `
            -ArgumentList @('-m', 'standalone', '-d', 'server') | Out-Null
        Wait-TcpPort -Name 'Nacos' -Port 8848
    }

    if (-not $SkipNacosPublish) {
        $accessToken = Get-NacosAccessToken -Username $nacosUsername -Password $nacosPassword
        try {
            $publishExitCode = Invoke-ProcessWithEnvironment -FilePath 'powershell.exe' -WorkingDirectory $cloudRoot `
                -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $cloudRoot 'bin\publish-nacos-config.ps1'), '-Apply') `
                -Environment @{ NACOS_ACCESS_TOKEN = $accessToken }
        }
        finally {
            Remove-Variable accessToken -ErrorAction SilentlyContinue
        }
        if ($publishExitCode -ne 0) {
            throw 'Nacos configuration publish or verification failed.'
        }
    }

    if (Test-TcpPort 5672) {
        Write-Host '[reuse] RabbitMQ (5672)'
    }
    else {
        try {
            Start-RequiredService -ServiceName 'RabbitMQ' -Port 5672
        }
        catch {
            Write-Warning 'RabbitMQ Windows service could not be started; falling back to the installed server command.'
            Assert-File -Path (Join-Path $rabbitMqSbin 'rabbitmq-server.bat') -Description 'RabbitMQ server command'
            Start-DetachedProcess -Name 'rabbitmq' -FilePath (Join-Path $rabbitMqSbin 'rabbitmq-server.bat') -WorkingDirectory $rabbitMqSbin | Out-Null
            Wait-TcpPort -Name 'RabbitMQ' -Port 5672
        }
    }

    $javaPorts = 9200, 9201, 9202, 8080
    if (-not $SkipBuild -and -not ($javaPorts | Where-Object { Test-TcpPort $_ })) {
        Write-Host '[build] Packaging system, session, auth, and gateway without tests.'
        Push-Location -LiteralPath $cloudRoot
        try {
            & mvn -B -ntp -DskipTests -pl 'ruoyi-modules/ruoyi-system,ruoyi-modules/ruoyi-session,ruoyi-auth,ruoyi-gateway' -am package
            if ($LASTEXITCODE -ne 0) {
                throw 'Maven package failed; no Java service was started.'
            }
        }
        finally {
            Pop-Location
        }
    }
    elseif (-not $SkipBuild) {
        Write-Warning 'One or more Java service ports are already in use; reusing current processes and skipping Maven package.'
    }

    $systemEnvironment = @{} + $commonEnvironment + @{
        PLATFORM_DB_URL = 'jdbc:mysql://127.0.0.1:3306/platform_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
        PLATFORM_DB_USER = 'root'
        PLATFORM_DB_PASSWORD = '123456'
        RUOYI_MEDIA_INTERNAL_TOKEN = $internalToken
        LN_OFFICIAL_SERVICE_MASTER_KEY = $officialServiceMasterKey
        LN_OFFICIAL_SERVICE_MASTER_KEY_VERSION = 'local-dpapi-v1'
        LN_ACCESS_KEY_PEPPER = $accessKeyPepper
        LN_SYSTEM_TO_SESSION_URL = 'http://127.0.0.1:9202'
        LN_SYSTEM_TO_SESSION_INTERNAL_BEARER = $systemToSessionBearer
        LN_SESSION_TO_SYSTEM_INTERNAL_BEARER = $sessionToSystemBearer
    }
    $sessionEnvironment = @{} + $commonEnvironment + @{
        SESSION_DB_URL = 'jdbc:mysql://127.0.0.1:3306/session_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
        SESSION_DB_USER = 'root'
        SESSION_DB_PASSWORD = '123456'
        LN_SESSION_TTS_RELAY_ENABLED = 'true'
        LN_SESSION_TTS_RELAY_ENDPOINT = 'http://127.0.0.1:8024/ln-relay/v1'
        LN_SESSION_TTS_RELAY_ACCESS_TOKEN = $relayToken
        LN_SESSION_TO_SYSTEM_URL = 'http://127.0.0.1:9201'
        LN_SESSION_TO_SYSTEM_INTERNAL_BEARER = $sessionToSystemBearer
        LN_SYSTEM_TO_SESSION_INTERNAL_BEARER = $systemToSessionBearer
        LN_SESSION_RUNTIME_TOKEN_SECRET = $runtimeTokenSecret
    }

    $javaServices = @(
        @{ Name = 'system'; Port = 9201; Directory = Join-Path $cloudRoot 'ruoyi-modules\ruoyi-system\target'; Jar = 'ruoyi-modules-system.jar'; Environment = $systemEnvironment; Health = 'http://127.0.0.1:9201/actuator/health' },
        @{ Name = 'session'; Port = 9202; Directory = Join-Path $cloudRoot 'ruoyi-modules\ruoyi-session\target'; Jar = 'ruoyi-modules-session.jar'; Environment = $sessionEnvironment; Health = 'http://127.0.0.1:9202/actuator/health' },
        @{ Name = 'auth'; Port = 9200; Directory = Join-Path $cloudRoot 'ruoyi-auth\target'; Jar = 'ruoyi-auth.jar'; Environment = $commonEnvironment; Health = 'http://127.0.0.1:9200/actuator/health' },
        @{ Name = 'gateway'; Port = 8080; Directory = Join-Path $cloudRoot 'ruoyi-gateway\target'; Jar = 'ruoyi-gateway.jar'; Environment = $commonEnvironment; Health = 'http://127.0.0.1:8080/actuator/health' }
    )
    foreach ($service in $javaServices) {
        if (Test-TcpPort $service.Port) {
            Write-Host "[reuse] $($service.Name) ($($service.Port))"
        }
        else {
            Assert-File -Path (Join-Path $service.Directory $service.Jar) -Description "$($service.Name) JAR"
            Start-DetachedProcess -Name $service.Name -FilePath 'java.exe' -WorkingDirectory $service.Directory `
                -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $service.Jar) -Environment $service.Environment | Out-Null
        }
        try {
            Wait-HttpOk -Name $service.Name -Url $service.Health
        }
        catch {
            Get-LogTail -Name $service.Name
            throw
        }
    }

    $mediaPython = Join-Path $mediaRoot '.venv\Scripts\python.exe'
    Assert-File -Path $mediaPython -Description 'Media virtual-environment Python'
    if (Test-TcpPort 8002) {
        Write-Host '[reuse] media-api (8002)'
    }
    else {
        Start-DetachedProcess -Name 'media-api' -FilePath $mediaPython -WorkingDirectory $mediaRoot `
            -ArgumentList @('-m', 'uvicorn', 'ruoyi_media.api.app:app', '--app-dir', 'src', '--host', '127.0.0.1', '--port', '8002') | Out-Null
    }
    try {
        Wait-HttpOk -Name 'media-api' -Url 'http://127.0.0.1:8002/health'
    }
    catch {
        Get-LogTail -Name 'media-api'
        throw
    }

    $mediaEnvironment = @{} + $commonEnvironment + $cosEnvironment + @{
        PYTHONPATH = (Join-Path $mediaRoot 'src')
        RUOYI_MEDIA_INTERNAL_PLATFORM_URL = 'http://127.0.0.1:9201'
        RUOYI_MEDIA_INTERNAL_TOKEN = $internalToken
        RUOYI_MEDIA_WORKER_ID = 'local-media-worker'
        RUOYI_MEDIA_QWEN_API_KEY = [string]$providerConfig.api_key
        RUOYI_MEDIA_QWEN_ENDPOINT = if ($providerConfig.api_host) { [string]$providerConfig.api_host } else { 'https://dashscope.aliyuncs.com' }
        M2_IMAGE_PROVIDER_ENABLED = 'true'
    }
    if (-not (Test-MediaWorkerRunning -PythonPath $mediaPython)) {
        Start-DetachedProcess -Name 'media-worker' -FilePath $mediaPython -WorkingDirectory $mediaRoot `
            -ArgumentList @('-m', 'ruoyi_media.worker', '--consume-rabbit', '--interval', '30') -Environment $mediaEnvironment | Out-Null
        Start-Sleep -Milliseconds 750
        Write-Host '[started] media-worker; inspect .m1-runtime-logs/media-worker.stdout.log for ready/heartbeat.'
    }
    else {
        Write-Host '[reuse] media-worker Python process'
    }

    if (-not $SkipRelay) {
        $relayPython = Join-Path $relayRoot '.venv\Scripts\python.exe'
        Assert-File -Path $relayPython -Description 'Relay virtual-environment Python'
        if (Test-TcpPort 8024) {
            Write-Host '[reuse] ln-relay (8024)'
        }
        else {
            $relayEnvironment = @{
                RELAY_ACCESS_TOKEN = $relayToken
                DASHSCOPE_API_KEY = [string]$providerConfig.api_key
                LN_RELAY_PORT = '8024'
            }
            Start-DetachedProcess -Name 'ln-relay' -FilePath $relayPython -WorkingDirectory $relayRoot `
                -ArgumentList @('app.py') -Environment $relayEnvironment | Out-Null
            Wait-TcpPort -Name 'ln-relay' -Port 8024
        }
    }

    if (-not $SkipFrontend) {
        if (Test-TcpPort 80) {
            Write-Host '[reuse] Vue frontend (80)'
        }
        else {
            Start-DetachedProcess -Name 'vue' -FilePath 'cmd.exe' -WorkingDirectory $webRoot `
                -ArgumentList @('/c', 'npm.cmd run dev -- --host 127.0.0.1') | Out-Null
            Wait-TcpPort -Name 'Vue frontend' -Port 80
        }
    }

    & (Join-Path $cloudRoot 'bin\check-m1-services.ps1') -TimeoutSeconds 15
    if ($LASTEXITCODE -ne 0) {
        throw 'The final M1 HTTP health check failed.'
    }
    Write-Host "`nStartup completed. Frontend: http://127.0.0.1/  Gateway: http://127.0.0.1:8080/"
    Write-Host "Logs: $runtimeLogRoot"
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
