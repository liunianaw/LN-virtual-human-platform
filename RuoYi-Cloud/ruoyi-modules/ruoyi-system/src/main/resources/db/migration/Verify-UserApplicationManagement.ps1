param([string]$Root = (Join-Path $PSScriptRoot '..\..\..\..\..\..\..'))
$required = @(
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/controller/ApplicationController.java',
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/service/impl/ApplicationServiceImpl.java',
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/ApplicationRevocationWorker.java',
  'ruoyi-modules/ruoyi-system/src/main/resources/mapper/application/ApplicationMapper.xml',
  'ruoyi-modules/ruoyi-system/src/main/resources/db/migration/V23__simplify_developer_integration.sql',
  'config/nacos/ruoyi-gateway-dev.yml'
)
foreach ($relative in $required) { if (-not (Test-Path (Join-Path $Root $relative))) { throw "Missing current Application artifact: $relative" } }
$service = Get-Content -Raw (Join-Path $Root $required[1])
$controller = Get-Content -Raw (Join-Path $Root $required[0])
$worker = Get-Content -Raw (Join-Path $Root $required[2])
$mapper = Get-Content -Raw (Join-Path $Root $required[3])
[xml]$mapper | Out-Null
foreach ($needle in @('updateCurrent', 'releaseCurrentReferences', 'insertCurrentReference', 'insertIdempotency', 'insertOutbox')) {
  if ($service -notmatch [regex]::Escape($needle)) { throw "Missing Application invariant: $needle" }
}
foreach ($needle in @('/status', 'platform:application:read', 'platform:application:write', 'private static LoginUser developer')) {
  if ($controller -notmatch [regex]::Escape($needle)) { throw "Missing current Application boundary: $needle" }
}
foreach ($obsolete in @('config-versions', 'llmRelayVersionId', 'asrRelayVersionId', 'p_app_config', 'current_policy')) {
  if (($controller + $service + $mapper) -match [regex]::Escape($obsolete)) { throw "Obsolete Application contract remains: $obsolete" }
}
if ($worker -notmatch 'APPLICATION_STATUS_CHANGED' -or $worker -notmatch 'sessions\.close') { throw 'Application disable must close business Sessions' }
Write-Output 'Current Application static structure check passed.'
