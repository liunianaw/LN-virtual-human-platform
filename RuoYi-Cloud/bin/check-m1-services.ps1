[CmdletBinding()]
param(
    [string]$GatewayBaseUrl = 'http://127.0.0.1:8080',
    [string]$AuthBaseUrl = 'http://127.0.0.1:9200',
    [string]$SystemBaseUrl = 'http://127.0.0.1:9201',
    [string]$SessionBaseUrl = 'http://127.0.0.1:9202',
    [string]$MediaBaseUrl = 'http://127.0.0.1:8002',
    [ValidateRange(1, 60)]
    [int]$TimeoutSeconds = 5
)

<#
.SYNOPSIS
Reads the M1 HTTP health endpoints without changing service state.

.DESCRIPTION
Checks the configured gateway, auth, system, session, and media API endpoints,
plus the gateway-to-system OpenAPI route. Exit code 0 means only that every
listed HTTP endpoint returned a 2xx response. It does not establish complete
M1 acceptance: Worker readiness/heartbeat and Redis, Nacos, and RabbitMQ need
their own runtime evidence and are intentionally not probed here.

.EXAMPLE
.\check-m1-services.ps1

.EXAMPLE
.\check-m1-services.ps1 -GatewayBaseUrl 'http://localhost:8080' -MediaBaseUrl 'http://localhost:8002'
#>

Set-StrictMode -Version Latest

function Join-EndpointUrl {
    param(
        [Parameter(Mandatory)] [string]$BaseUrl,
        [Parameter(Mandatory)] [string]$Path
    )

    return "$($BaseUrl.TrimEnd('/'))/$($Path.TrimStart('/'))"
}

function Get-HttpCheckResult {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [string]$Url,
        [Parameter(Mandatory)] [int]$TimeoutSeconds
    )

    $statusCode = $null
    $detail = $null
    $request = [System.Net.WebRequest]::Create($Url)
    $request.Method = 'GET'
    $request.Timeout = $TimeoutSeconds * 1000
    $request.ReadWriteTimeout = $TimeoutSeconds * 1000

    try {
        $response = $request.GetResponse()
        try {
            $statusCode = [int]$response.StatusCode
        }
        finally {
            $response.Dispose()
        }
    }
    catch [System.Net.WebException] {
        if ($null -ne $_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            $_.Exception.Response.Dispose()
            $detail = "HTTP $statusCode"
        }
        else {
            $detail = $_.Exception.Status.ToString()
        }
    }
    catch {
        $detail = $_.Exception.GetType().Name
    }

    $passed = $null -ne $statusCode -and $statusCode -ge 200 -and $statusCode -lt 300
    if ($passed) {
        $detail = "HTTP $statusCode"
    }
    elseif ($null -eq $detail) {
        $detail = "HTTP $statusCode"
    }

    return [pscustomobject]@{
        Service = $Name
        Url = $Url
        Result = if ($passed) { 'Ready' } else { 'NotReady' }
        Detail = $detail
    }
}

$checks = @(
    @{ Name = 'gateway'; Url = Join-EndpointUrl $GatewayBaseUrl 'actuator/health' },
    @{ Name = 'auth'; Url = Join-EndpointUrl $AuthBaseUrl 'actuator/health' },
    @{ Name = 'system'; Url = Join-EndpointUrl $SystemBaseUrl 'actuator/health' },
    @{ Name = 'session'; Url = Join-EndpointUrl $SessionBaseUrl 'actuator/health' },
    @{ Name = 'media-api'; Url = Join-EndpointUrl $MediaBaseUrl 'health' },
    @{ Name = 'gateway-to-system-openapi'; Url = Join-EndpointUrl $GatewayBaseUrl 'system/v3/api-docs' }
)

$results = foreach ($check in $checks) {
    Get-HttpCheckResult -Name $check.Name -Url $check.Url -TimeoutSeconds $TimeoutSeconds
}

$results | Format-Table -AutoSize | Out-Host
Write-Host 'Note: Redis, Nacos, and RabbitMQ are intentionally not probed by this HTTP-only script.'
Write-Host 'Note: media Worker readiness/heartbeat is not inferable from the media API; inspect its process output separately.'

$failed = @($results | Where-Object { $_.Result -ne 'Ready' })
if ($failed.Count -gt 0) {
    Write-Error "$($failed.Count) HTTP check(s) are not ready. M1 is not established by this result."
    exit 1
}

Write-Host 'All configured HTTP checks are ready. This alone does not establish complete M1 acceptance.'
exit 0
