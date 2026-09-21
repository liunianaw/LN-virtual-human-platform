param([string]$Root = (Join-Path $PSScriptRoot '..\..\..\..\..\..\..'))

$required = @(
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/controller/ApplicationController.java',
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/service/impl/ApplicationServiceImpl.java',
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/ApplicationRevocationWorker.java',
  'ruoyi-modules/ruoyi-system/src/main/resources/mapper/application/ApplicationMapper.xml',
  'ruoyi-modules/ruoyi-system/src/main/resources/db/migration/V11__user_application_management.sql',
  'config/nacos/ruoyi-gateway-dev.yml'
)
foreach ($relative in $required) { if (-not (Test-Path (Join-Path $Root $relative))) { throw "Missing required application artifact: $relative" } }
$service = Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/service/impl/ApplicationServiceImpl.java')
$controller = Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/controller/ApplicationController.java')
$worker = Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/ApplicationRevocationWorker.java')
$gateway = Get-Content -Raw (Join-Path $Root 'config/nacos/ruoyi-gateway-dev.yml')
[xml](Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/resources/mapper/application/ApplicationMapper.xml')) | Out-Null
foreach ($needle in @('SPEAK_ONLY', 'llmRelayVersionId', 'asrRelayVersionId', 'releaseCurrentReferences', 'insertCurrentReference', 'insertIdempotency', 'insertOutbox')) { if ($service -notmatch [regex]::Escape($needle)) { throw "Missing application invariant: $needle" } }
foreach ($needle in @('/config-versions', '/status', 'platform:application:read', 'platform:application:write')) { if ($controller -notmatch [regex]::Escape($needle)) { throw "Missing application endpoint or permission: $needle" } }
if ($worker -notmatch 'APPLICATION_STATUS_CHANGED' -or $worker -notmatch 'sessions\.close') { throw 'Disabled application events do not revoke DEBUG sessions' }
if ($gateway -notmatch '/api/v1/applications/\*\*') { throw 'Gateway does not forward application API routes' }
Write-Output 'User application management static structure check passed.'
