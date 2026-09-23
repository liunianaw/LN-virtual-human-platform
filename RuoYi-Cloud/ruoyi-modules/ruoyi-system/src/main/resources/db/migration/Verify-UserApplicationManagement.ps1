param([string]$Root = (Join-Path $PSScriptRoot '..\..\..\..\..\..\..'))

$required = @(
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/controller/ApplicationController.java',
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/service/impl/ApplicationServiceImpl.java',
  'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/ApplicationRevocationWorker.java',
  'ruoyi-modules/ruoyi-system/src/main/resources/mapper/application/ApplicationMapper.xml',
  'ruoyi-modules/ruoyi-system/src/main/resources/db/migration/V11__user_application_management.sql',
  'ruoyi-modules/ruoyi-system/src/main/resources/db/migration/V15__expand_resource_reference_operation_id.sql',
  'config/nacos/ruoyi-gateway-dev.yml'
)
foreach ($relative in $required) { if (-not (Test-Path (Join-Path $Root $relative))) { throw "Missing required application artifact: $relative" } }
$service = Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/service/impl/ApplicationServiceImpl.java')
$controller = Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/controller/ApplicationController.java')
$worker = Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/application/ApplicationRevocationWorker.java')
$gateway = Get-Content -Raw (Join-Path $Root 'config/nacos/ruoyi-gateway-dev.yml')
$v15 = Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/resources/db/migration/V15__expand_resource_reference_operation_id.sql')
[xml](Get-Content -Raw (Join-Path $Root 'ruoyi-modules/ruoyi-system/src/main/resources/mapper/application/ApplicationMapper.xml')) | Out-Null
foreach ($needle in @('SPEAK_ONLY', 'llmRelayVersionId', 'asrRelayVersionId', 'releaseCurrentReferences', 'insertCurrentReference', 'insertIdempotency', 'insertOutbox')) { if ($service -notmatch [regex]::Escape($needle)) { throw "Missing application invariant: $needle" } }
foreach ($needle in @('"application:" + applicationId + ":" + key', '[\\x21-\\x7e]{1,64}')) { if ($service -notmatch [regex]::Escape($needle)) { throw "Missing application idempotency length guard: $needle" } }
foreach ($needle in @('/config-versions', '/status', 'platform:application:read', 'platform:application:write')) { if ($controller -notmatch [regex]::Escape($needle)) { throw "Missing application endpoint or permission: $needle" } }
if ($worker -notmatch 'APPLICATION_STATUS_CHANGED' -or $worker -notmatch 'sessions\.close') { throw 'Disabled application events do not revoke DEBUG sessions' }
if ($gateway -notmatch '/api/v1/applications/\*\*') { throw 'Gateway does not forward application API routes' }
if ($v15 -notmatch '(?is)MODIFY\s+COLUMN\s+operation_id\s+varchar\(128\)\s+NOT\s+NULL') { throw 'V15 must expand p_resource_reference.operation_id to varchar(128)' }
Write-Output 'User application management static structure check passed.'
