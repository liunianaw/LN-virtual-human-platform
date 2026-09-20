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
Plans or guardedly publishes root Nacos YAML and Sentinel JSON configuration files.

.DESCRIPTION
Without -Apply, this script only lists the selected root *.yml and *.json files
and their SHA-256 values. YAML file names are their Data IDs; JSON file names
without the .json suffix are their Data IDs, matching the Sentinel bootstrap.
With -Apply, it reads NACOS_ACCESS_TOKEN from the process
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

function Get-NacosConfigDescriptor {
    param([Parameter(Mandatory)] [System.IO.FileInfo]$File)

    switch ($File.Extension.ToLowerInvariant()) {
        '.yml' {
            return [pscustomobject]@{
                File = $File
                DataId = $File.Name
                ContentType = 'yaml'
            }
        }
        '.json' {
            return [pscustomobject]@{
                File = $File
                DataId = $File.BaseName
                ContentType = 'json'
            }
        }
        default {
            throw "Unsupported Nacos configuration file type: $($File.Name)."
        }
    }
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
    $availableConfigs = @(Get-ChildItem -LiteralPath $configDirectory -File |
            Where-Object { $_.Extension -in @('.yml', '.json') } |
            ForEach-Object { Get-NacosConfigDescriptor $_ } |
            Sort-Object DataId)
    if ($availableConfigs.Count -eq 0) {
        throw "No root .yml or .json files found in $configDirectory."
    }

    # A missing [string[]] parameter is $null under StrictMode, so normalize it
    # before accessing Count. Keep the caller's multiple-DataId semantics intact.
    $requestedDataIds = @($DataId | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $selectedConfigs = if ($requestedDataIds.Count -gt 0) {
        $matches = @($availableConfigs | Where-Object { $requestedDataIds -contains $_.DataId })
        $unknownIds = @($requestedDataIds | Where-Object { $_ -notin $availableConfigs.DataId })
        if ($unknownIds.Count -gt 0) {
            throw "No root configuration file matches DataId: $($unknownIds -join ', ')."
        }
        $matches
    }
    else {
        $availableConfigs
    }

    $plan = foreach ($config in $selectedConfigs) {
        $content = Get-Content -LiteralPath $config.File.FullName -Raw
        [pscustomobject]@{
            DataId = $config.DataId
            Type = $config.ContentType
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

    foreach ($config in $selectedConfigs) {
        $content = Get-Content -LiteralPath $config.File.FullName -Raw
        $localHash = Get-TextSha256 $content
        $formBody = ConvertTo-FormUrlEncoded @{
            dataId = $config.DataId
            groupName = $GroupName
            namespaceId = $NamespaceId
            content = $content
            type = $config.ContentType
        }
        $publishResult = Invoke-NacosRequest -Method POST -Url $adminUrl -AccessToken $accessToken -FormBody $formBody -TimeoutSeconds $TimeoutSeconds
        if ($publishResult.StatusCode -lt 200 -or $publishResult.StatusCode -ge 300) {
            throw "Publish failed for $($config.DataId): HTTP $($publishResult.StatusCode). Verified before failure: $($verifiedDataIds -join ', ')."
        }

        $query = ConvertTo-FormUrlEncoded @{
            dataId = $config.DataId
            groupName = $GroupName
            namespaceId = $NamespaceId
        }
        # Nacos Client reads can briefly expose the prior value after a successful
        # Admin publish. Re-read the same scope until it becomes visible, bounded
        # by the caller's existing timeout. HTTP and malformed JSON still fail
        # immediately because they are not an eventual-consistency condition.
        $verificationDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        $verificationSucceeded = $false
        $verificationReady = $false
        do {
            $verifyResult = Invoke-NacosRequest -Method GET -Url "$clientUrl`?$query" -AccessToken $accessToken -TimeoutSeconds $TimeoutSeconds
            if ($verifyResult.StatusCode -ne 200) {
                throw "Verification failed for $($config.DataId): HTTP $($verifyResult.StatusCode). Verified before failure: $($verifiedDataIds -join ', ')."
            }
            try {
                $verifyPayload = $verifyResult.Content | ConvertFrom-Json
            }
            catch {
                throw "Verification failed for $($config.DataId): Nacos returned invalid JSON. Verified before failure: $($verifiedDataIds -join ', ')."
            }

            # Nacos may return a valid 200/JSON response before the Client API has
            # exposed the just-published value. That is a not-ready read, not a
            # terminal error; keep polling within the established deadline.
            $verificationReady = $verifyPayload.code -eq 0 -and
                $null -ne $verifyPayload.data -and
                $verifyPayload.data.success -eq $true -and
                $null -ne $verifyPayload.data.content
            if ($verificationReady) {
                $verificationSucceeded = (Get-TextSha256 ([string]$verifyPayload.data.content)) -eq $localHash
            }
            if (-not $verificationSucceeded -and [DateTime]::UtcNow -lt $verificationDeadline) {
                Start-Sleep -Milliseconds 250
            }
        } while (-not $verificationSucceeded -and [DateTime]::UtcNow -lt $verificationDeadline)
        if (-not $verificationSucceeded) {
            if (-not $verificationReady) {
                throw "Verification failed for $($config.DataId): Nacos did not return successful configuration content before the timeout. Verified before failure: $($verifiedDataIds -join ', ')."
            }
            throw "Verification hash mismatch for $($config.DataId). Verified before failure: $($verifiedDataIds -join ', ')."
        }

        $verifiedDataIds.Add($config.DataId)
    }

    Write-Host "Published and verified $($verifiedDataIds.Count) Nacos configuration file(s)."
    exit 0
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
