[CmdletBinding()]
param(
    [string]$NacosBaseUrl = 'http://127.0.0.1:8848',
    [string]$GroupName = 'DEFAULT_GROUP',
    [string]$NamespaceId = 'public',
    [string[]]$DataId,
    [switch]$Apply,
    [ValidateRange(1, 60)]
    [int]$TimeoutSeconds = 15
)

<#
.SYNOPSIS
Plans or guardedly publishes root Nacos YAML configuration files.

.DESCRIPTION
Without -Apply, this script only lists the selected root *.yml files and their
SHA-256 values. With -Apply, it reads NACOS_ACCESS_TOKEN from the process
environment, publishes each selected file through the Nacos 3.2 Admin API,
then reads it through the Client API and compares the returned content hash.
The script never accepts a token as a parameter and never prints tokens or
configuration content.

.EXAMPLE
.\publish-nacos-config.ps1

.EXAMPLE
.\publish-nacos-config.ps1 -DataId application-dev.yml,ruoyi-system-dev.yml

.EXAMPLE
$env:NACOS_ACCESS_TOKEN = 'obtain-this-from-your-secret-store'
.\publish-nacos-config.ps1 -Apply -DataId application-dev.yml
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 does not load System.Net.Http by default, whereas
# PowerShell 7 does. Load it only when the HTTP client type is unavailable.
if ($null -eq ('System.Net.Http.HttpClient' -as [type])) {
    Add-Type -AssemblyName System.Net.Http
}

function Get-TextSha256 {
    param([Parameter(Mandatory)] [string]$Text)

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function New-NacosUrl {
    param(
        [Parameter(Mandatory)] [string]$BaseUrl,
        [Parameter(Mandatory)] [string]$Path
    )

    return "$($BaseUrl.TrimEnd('/'))/$($Path.TrimStart('/'))"
}

function ConvertTo-FormUrlEncoded {
    param([Parameter(Mandatory)] [hashtable]$Values)

    return (($Values.GetEnumerator() | ForEach-Object {
        '{0}={1}' -f [System.Uri]::EscapeDataString($_.Key), [System.Uri]::EscapeDataString([string]$_.Value)
    }) -join '&')
}

function Invoke-NacosRequest {
    param(
        [Parameter(Mandatory)] [ValidateSet('GET', 'POST')] [string]$Method,
        [Parameter(Mandatory)] [string]$Url,
        [Parameter(Mandatory)] [string]$AccessToken,
        [string]$FormBody,
        [Parameter(Mandatory)] [int]$TimeoutSeconds
    )

    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $httpMethod = if ($Method -eq 'POST') { [System.Net.Http.HttpMethod]::Post } else { [System.Net.Http.HttpMethod]::Get }
    $request = [System.Net.Http.HttpRequestMessage]::new($httpMethod, $Url)
    [void]$request.Headers.TryAddWithoutValidation('accessToken', $AccessToken)
    if ($Method -eq 'POST') {
        $request.Content = [System.Net.Http.StringContent]::new(
            $FormBody,
            [System.Text.Encoding]::UTF8,
            'application/x-www-form-urlencoded'
        )
    }

    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            }
        }
        finally {
            $response.Dispose()
        }
    }
    finally {
        $request.Dispose()
        $client.Dispose()
    }
}

try {
    $configDirectory = Join-Path $PSScriptRoot '..\config\nacos'
    $configDirectory = (Resolve-Path -LiteralPath $configDirectory).Path
    $availableFiles = @(Get-ChildItem -LiteralPath $configDirectory -File -Filter '*.yml' | Sort-Object Name)
    if ($availableFiles.Count -eq 0) {
        throw "No root .yml files found in $configDirectory."
    }

    $selectedFiles = if ($DataId.Count -gt 0) {
        $matches = @($availableFiles | Where-Object { $DataId -contains $_.Name })
        $unknownIds = @($DataId | Where-Object { $_ -notin $availableFiles.Name })
        if ($unknownIds.Count -gt 0) {
            throw "No root configuration file matches DataId: $($unknownIds -join ', ')."
        }
        $matches
    }
    else {
        $availableFiles
    }

    $plan = foreach ($file in $selectedFiles) {
        $content = Get-Content -LiteralPath $file.FullName -Raw
        [pscustomobject]@{
            DataId = $file.Name
            Group = $GroupName
            Namespace = $NamespaceId
            Sha256 = Get-TextSha256 $content
            Action = if ($Apply) { 'PublishAndVerify' } else { 'DryRun' }
        }
    }
    $plan | Format-Table -AutoSize | Out-Host

    if (-not $Apply) {
        Write-Host 'Dry run only. Re-run with -Apply and NACOS_ACCESS_TOKEN to publish.'
        exit 0
    }

    $accessToken = $env:NACOS_ACCESS_TOKEN
    if ([string]::IsNullOrWhiteSpace($accessToken)) {
        throw 'Apply requires the NACOS_ACCESS_TOKEN environment variable. No write was attempted.'
    }

    $adminUrl = New-NacosUrl $NacosBaseUrl 'nacos/v3/admin/cs/config'
    $clientUrl = New-NacosUrl $NacosBaseUrl 'nacos/v3/client/cs/config'
    $verifiedDataIds = [System.Collections.Generic.List[string]]::new()

    foreach ($file in $selectedFiles) {
        $content = Get-Content -LiteralPath $file.FullName -Raw
        $localHash = Get-TextSha256 $content
        $formBody = ConvertTo-FormUrlEncoded @{
            dataId = $file.Name
            groupName = $GroupName
            namespaceId = $NamespaceId
            content = $content
            type = 'yaml'
        }
        $publishResult = Invoke-NacosRequest -Method POST -Url $adminUrl -AccessToken $accessToken -FormBody $formBody -TimeoutSeconds $TimeoutSeconds
        if ($publishResult.StatusCode -lt 200 -or $publishResult.StatusCode -ge 300) {
            throw "Publish failed for $($file.Name): HTTP $($publishResult.StatusCode). Verified before failure: $($verifiedDataIds -join ', ')."
        }

        $query = ConvertTo-FormUrlEncoded @{
            dataId = $file.Name
            groupName = $GroupName
            namespaceId = $NamespaceId
        }
        # Nacos Client reads can briefly expose the prior value after a successful
        # Admin publish. Re-read the same scope until it becomes visible, bounded
        # by the caller's existing timeout and without changing failure semantics.
        $verificationDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        $verificationSucceeded = $false
        do {
            $verifyResult = Invoke-NacosRequest -Method GET -Url "$clientUrl`?$query" -AccessToken $accessToken -TimeoutSeconds $TimeoutSeconds
            if ($verifyResult.StatusCode -ne 200) {
                throw "Verification failed for $($file.Name): HTTP $($verifyResult.StatusCode). Verified before failure: $($verifiedDataIds -join ', ')."
            }
            try {
                $verifyPayload = $verifyResult.Content | ConvertFrom-Json
            }
            catch {
                throw "Verification failed for $($file.Name): Nacos returned invalid JSON. Verified before failure: $($verifiedDataIds -join ', ')."
            }
            if ($verifyPayload.code -ne 0 -or $null -eq $verifyPayload.data -or $verifyPayload.data.success -ne $true -or $null -eq $verifyPayload.data.content) {
                throw "Verification failed for $($file.Name): Nacos did not return successful configuration content. Verified before failure: $($verifiedDataIds -join ', ')."
            }
            $verificationSucceeded = (Get-TextSha256 ([string]$verifyPayload.data.content)) -eq $localHash
            if (-not $verificationSucceeded -and [DateTime]::UtcNow -lt $verificationDeadline) {
                Start-Sleep -Milliseconds 250
            }
        } while (-not $verificationSucceeded -and [DateTime]::UtcNow -lt $verificationDeadline)
        if (-not $verificationSucceeded) {
            throw "Verification hash mismatch for $($file.Name). Verified before failure: $($verifiedDataIds -join ', ')."
        }

        $verifiedDataIds.Add($file.Name)
    }

    Write-Host "Published and verified $($verifiedDataIds.Count) Nacos configuration file(s)."
    exit 0
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
