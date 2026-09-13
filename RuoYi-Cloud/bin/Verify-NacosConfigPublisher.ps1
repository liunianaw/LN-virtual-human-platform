[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$publisherPath = Join-Path $PSScriptRoot 'publish-nacos-config.ps1'
$configDirectory = Join-Path $PSScriptRoot '..\config\nacos'
$sentinelPath = Join-Path $configDirectory 'sentinel-ruoyi-gateway.json'

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($publisherPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    throw "Nacos publisher has PowerShell parse errors: $($parseErrors.Message -join '; ')"
}

$sentinelRules = Get-Content -LiteralPath $sentinelPath -Raw | ConvertFrom-Json
if ($null -eq $sentinelRules -or @($sentinelRules).Count -eq 0) {
    throw 'Sentinel gateway configuration must be non-empty JSON.'
}

$publisherSource = Get-Content -LiteralPath $publisherPath -Raw
if ($publisherSource -notmatch 'DataId\s*=\s*\$File\.BaseName' -or
    $publisherSource -notmatch "ContentType\s*=\s*'json'" -or
    $publisherSource -notmatch 'type\s*=\s*\$config\.ContentType') {
    throw 'Nacos publisher must map JSON files to base-name Data IDs and publish their JSON type.'
}

$hostExecutable = Join-Path $PSHOME $(if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh.exe' } else { 'powershell.exe' })
if (-not (Test-Path -LiteralPath $hostExecutable)) {
    throw "Unable to locate the current PowerShell host executable: $hostExecutable"
}
$escapedPublisherPath = $publisherPath.Replace("'", "''")
$dryRunCommand = "& { & '$escapedPublisherPath' -DataId @('application-dev.yml', 'sentinel-ruoyi-gateway'); exit `$LASTEXITCODE }"
& $hostExecutable -NoProfile -Command $dryRunCommand
if ($LASTEXITCODE -ne 0) {
    throw "Nacos publisher dry run failed with exit code $LASTEXITCODE."
}

Write-Output 'Nacos publisher static verification passed: YAML and Sentinel JSON Data IDs are selected with type-aware publishing.'
