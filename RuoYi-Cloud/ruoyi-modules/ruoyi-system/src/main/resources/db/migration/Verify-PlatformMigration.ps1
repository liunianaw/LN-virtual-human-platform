param(
    [string]$MigrationPath = (Join-Path $PSScriptRoot 'V1__platform_schema_and_admin_seed.sql')
)

$expectedTables = @(
    'sys_dept', 'sys_user', 'sys_post', 'sys_role', 'sys_menu', 'sys_user_role', 'sys_role_menu', 'sys_role_dept', 'sys_user_post', 'sys_oper_log', 'sys_dict_type', 'sys_dict_data', 'sys_config', 'sys_logininfor', 'sys_job', 'sys_job_log',
    'p_email_token', 'p_access_key', 'p_secret', 'p_official_service', 'p_relay_service', 'p_relay_version', 'p_relay_grant', 'p_avatar', 'p_avatar_version', 'p_avatar_action', 'p_voice', 'p_voice_version', 'p_skill', 'p_skill_version', 'p_application', 'p_app_config', 'p_app_skill', 'p_resource_reference', 'p_file', 'p_generation_task', 'p_generation_step', 'p_generation_attempt', 'p_account_limit', 'p_quota_balance', 'p_quota_reservation', 'p_quota_entry', 'p_call_record', 'p_usage_daily', 'p_webhook_endpoint', 'p_webhook_delivery', 'p_webhook_attempt', 'p_api_idempotency', 'p_outbox', 'p_inbox', 'p_job_lease'
)

if (-not (Test-Path -LiteralPath $MigrationPath)) {
    throw "Migration file is missing: $MigrationPath"
}

$sql = Get-Content -Raw -LiteralPath $MigrationPath
$actualTables = [regex]::Matches($sql, '(?im)^CREATE TABLE IF NOT EXISTS `([^`]+)`') | ForEach-Object { $_.Groups[1].Value }
$unexpectedTables = @($actualTables | Where-Object { $_ -notin $expectedTables })
$missingTables = @($expectedTables | Where-Object { $_ -notin $actualTables })
$duplicateTables = @($actualTables | Group-Object | Where-Object Count -gt 1 | ForEach-Object Name)
$dangerousStatements = [regex]::Matches($sql, '(?im)^\s*(CREATE\s+(DATABASE|USER)|DROP\s+(DATABASE|TABLE|USER)|TRUNCATE\s+TABLE|FLYWAY\s+(CLEAN|REPAIR))\b') | ForEach-Object Value
$requiredSeedTargets = @('sys_user', 'sys_role', 'sys_menu', 'sys_user_role', 'sys_role_menu')
$missingSeedTargets = @($requiredSeedTargets | Where-Object { $sql -notmatch ("(?im)^\s*INSERT\s+INTO\s+{0}\b" -f [regex]::Escape($_)) })

if ($actualTables.Count -ne $expectedTables.Count -or $missingTables -or $unexpectedTables -or $duplicateTables -or $dangerousStatements -or $missingSeedTargets) {
    throw ("Invalid platform migration. expected={0}; actual={1}; missing=[{2}]; unexpected=[{3}]; duplicate=[{4}]; dangerous=[{5}]; seedMissing=[{6}]" -f $expectedTables.Count, $actualTables.Count, ($missingTables -join ','), ($unexpectedTables -join ','), ($duplicateTables -join ','), ($dangerousStatements -join ';'), ($missingSeedTargets -join ','))
}

Write-Output "Platform migration static validation passed: $($actualTables.Count) expected tables; administrator and permission seed targets present; no dangerous statements."
