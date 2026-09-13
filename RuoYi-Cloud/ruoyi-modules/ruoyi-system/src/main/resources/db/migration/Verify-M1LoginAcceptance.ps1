param(
    [string]$MigrationPath = (Join-Path $PSScriptRoot 'V2__m1_login_role_boundaries.sql'),
    [string]$ProvisionerPath = (Join-Path $PSScriptRoot '..\..\..\..\..\..\..\bin\Initialize-M1LoginAcceptanceAccounts.ps1'),
    [string]$GatewayBootstrapPath = (Join-Path $PSScriptRoot '..\..\..\..\..\..\..\ruoyi-gateway\src\main\resources\bootstrap.yml')
)

foreach ($path in @($MigrationPath, $ProvisionerPath, $GatewayBootstrapPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required M1 login acceptance file is missing: $path"
    }
}

$sql = Get-Content -Raw -LiteralPath $MigrationPath
$provisioner = Get-Content -Raw -LiteralPath $ProvisionerPath
$gatewayBootstrap = Get-Content -Raw -LiteralPath $GatewayBootstrapPath

$requiredRoleKeys = @('m1-developer-readonly', 'm1-developer-operator')
$requiredRoleMappings = @(
    '(910001, 1)', '(910001, 100)', '(910001, 1000)',
    '(910002, 1)', '(910002, 108)', '(910002, 500)'
)
$missingRoleKeys = @($requiredRoleKeys | Where-Object { $sql -notmatch [regex]::Escape("'$_'") })
$missingRoleMappings = @($requiredRoleMappings | Where-Object { $sql -notmatch [regex]::Escape($_) })
$accountIdentifiers = @('m1devread', 'm1devops', 'm1disabled')
$missingAccountIdentifiers = @($accountIdentifiers | Where-Object { $provisioner -notmatch [regex]::Escape("'$_'") })

if ($sql -match '(?im)^\s*INSERT\s+INTO\s+sys_user\b') {
    throw 'The versioned role migration must not commit M1 login account credentials.'
}
if ($missingRoleKeys -or $missingRoleMappings -or $missingAccountIdentifiers) {
    throw "M1 login boundary verification failed. roleKeys=[$($missingRoleKeys -join ',')]; roleMappings=[$($missingRoleMappings -join ',')]; accountIdentifiers=[$($missingAccountIdentifiers -join ',')]"
}
if ($provisioner -notmatch 'Read-Host\s+.*-AsSecureString' -or $provisioner -notmatch 'BCrypt\.hashpw' -or $provisioner -notmatch 'ON DUPLICATE KEY UPDATE') {
    throw 'The M1 account provisioner must prompt securely, BCrypt-hash in memory, and be repeat-safe.'
}
if ($gatewayBootstrap -notmatch '(?s)sentinel:.*?datasource:.*?username:\s*\$\{NACOS_USERNAME:\}.*?password:\s*\$\{NACOS_PASSWORD:\}') {
    throw 'Sentinel Nacos datasource credentials must inherit the environment-provided Nacos credentials.'
}

Write-Output 'M1 login acceptance static verification passed: role/menu boundaries, runtime-only BCrypt account provisioning, and Sentinel Nacos credentials are present.'
