<#
.SYNOPSIS
Creates the three local-only M1 browser-acceptance accounts after Flyway V2 has applied.

.DESCRIPTION
The account identifiers are fixed: m1devread, m1devops, and m1disabled. Passwords are
requested as SecureString values at execution time, BCrypt-hashed in memory, and never
written to source, output, or configuration. PLATFORM_DB_PASSWORD must be present only in
the invoking process environment. MySQL client discovery accepts -MySqlClientPath, then
uses PATH, then checks the directory of a running mysqld.exe Windows service.
#>
[CmdletBinding()]
param(
    [System.Security.SecureString]$DeveloperReadPassword,
    [System.Security.SecureString]$DeveloperOperatorPassword,
    [System.Security.SecureString]$DisabledAccountPassword,
    [string]$MySqlHost = ${env:PLATFORM_DB_HOST},
    [int]$MySqlPort = $(if (${env:PLATFORM_DB_PORT}) { [int]${env:PLATFORM_DB_PORT} } else { 3306 }),
    [string]$MySqlDatabase = $(if (${env:PLATFORM_DB_NAME}) { ${env:PLATFORM_DB_NAME} } else { 'platform_db' }),
    [string]$MySqlUser = $(if (${env:PLATFORM_DB_USER}) { ${env:PLATFORM_DB_USER} } else { 'root' }),
    [string]$MySqlClientPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($MySqlHost)) {
    $MySqlHost = '127.0.0.1'
}
if ([string]::IsNullOrWhiteSpace(${env:PLATFORM_DB_PASSWORD})) {
    throw 'PLATFORM_DB_PASSWORD must be supplied through the process environment.'
}
if ($MySqlPort -lt 1 -or $MySqlPort -gt 65535 -or $MySqlDatabase -notmatch '^[A-Za-z0-9_]+$' -or $MySqlUser -notmatch '^[A-Za-z0-9_]+$') {
    throw 'MySQL connection settings contain an unsupported value.'
}

function Read-M1Password {
    param(
        [System.Security.SecureString]$Value,
        [string]$Prompt
    )
    if ($null -ne $Value) {
        return $Value
    }
    return Read-Host -Prompt $Prompt -AsSecureString
}

function ConvertTo-M1PlainText {
    param([System.Security.SecureString]$Value)
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Get-M1JavaExecutable {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaHomeCandidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $javaHomeCandidate -PathType Leaf) {
            return $javaHomeCandidate
        }
    }
    foreach ($commandName in @('java.exe', 'java')) {
        $command = Get-Command $commandName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $command -and (Test-Path -LiteralPath $command.Source -PathType Leaf)) {
            return $command.Source
        }
    }
    throw 'A JDK with java.exe is required to generate local M1 test-account BCrypt hashes. Set JAVA_HOME or add java.exe to PATH.'
}

function Get-M1MySqlClient {
    param([string]$ConfiguredPath)

    if (-not [string]::IsNullOrWhiteSpace($ConfiguredPath)) {
        if (Test-Path -LiteralPath $ConfiguredPath -PathType Leaf) {
            return (Resolve-Path -LiteralPath $ConfiguredPath).Path
        }
        throw "The explicit MySQL client path does not exist: $ConfiguredPath"
    }
    $pathCommand = Get-Command mysql.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $pathCommand -and (Test-Path -LiteralPath $pathCommand.Source -PathType Leaf)) {
        return $pathCommand.Source
    }
    $mySqlServices = Get-CimInstance Win32_Service -ErrorAction SilentlyContinue | Where-Object { $_.PathName -match '(?i)mysqld\.exe' }
    foreach ($service in $mySqlServices) {
        $serverMatch = [regex]::Match($service.PathName, '(?i)(?<server>[A-Z]:\\.+?\\mysqld\.exe)')
        if (-not $serverMatch.Success) {
            continue
        }
        $clientCandidate = Join-Path (Split-Path -Parent $serverMatch.Groups['server'].Value) 'mysql.exe'
        if (Test-Path -LiteralPath $clientCandidate -PathType Leaf) {
            return $clientCandidate
        }
    }
    throw 'mysql.exe was not found. Supply -MySqlClientPath, add mysql.exe to PATH, or run a MySQL service with mysql.exe next to mysqld.exe.'
}

function New-M1BcryptHash {
    param([System.Security.SecureString]$Password)

    $java = Get-M1JavaExecutable
    $cryptoJar = Get-ChildItem -Path (Join-Path $env:USERPROFILE '.m2\repository\org\springframework\security\spring-security-crypto') -Recurse -Filter 'spring-security-crypto-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if ([string]::IsNullOrWhiteSpace($cryptoJar)) {
        throw 'spring-security-crypto is missing from the local Maven cache. Build ruoyi-system once before provisioning M1 login accounts.'
    }

    $sourcePath = Join-Path ([IO.Path]::GetTempPath()) ("M1Bcrypt_{0}.java" -f [Guid]::NewGuid().ToString('N'))
    $javaSource = @'
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.security.crypto.bcrypt.BCrypt;
class M1Bcrypt {
  public static void main(String[] args) throws Exception {
    String password = new BufferedReader(new InputStreamReader(System.in)).readLine();
    System.out.print(BCrypt.hashpw(password, BCrypt.gensalt(10)));
  }
}
'@
    [IO.File]::WriteAllText($sourcePath, $javaSource, [Text.UTF8Encoding]::new($false))
    try {
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $java
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardInput = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        [void]$startInfo.ArgumentList.Add('--class-path')
        [void]$startInfo.ArgumentList.Add($cryptoJar)
        [void]$startInfo.ArgumentList.Add($sourcePath)
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        [void]$process.Start()
        $plainText = ConvertTo-M1PlainText $Password
        try {
            $process.StandardInput.WriteLine($plainText)
            $process.StandardInput.Close()
        }
        finally {
            $plainText = $null
        }
        $hash = $process.StandardOutput.ReadToEnd().Trim()
        $stderr = $process.StandardError.ReadToEnd().Trim()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0 -or $hash -notmatch '^\$2[aby]\$10\$[./A-Za-z0-9]{53}$') {
            throw "Unable to generate a BCrypt password hash for M1 login acceptance. $stderr"
        }
        return $hash
    }
    finally {
        Remove-Item -LiteralPath $sourcePath -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-M1MySql {
    param([string]$Sql)
    $mysql = Get-M1MySqlClient -ConfiguredPath $MySqlClientPath
    $previousMySqlPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $env:PLATFORM_DB_PASSWORD
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $mysql
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardInput = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        foreach ($argument in @('--protocol=TCP', "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUser", "--database=$MySqlDatabase", '--batch', '--skip-column-names', '--silent')) {
            [void]$startInfo.ArgumentList.Add($argument)
        }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        [void]$process.Start()
        $process.StandardInput.Write($Sql)
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd().Trim()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "MySQL rejected the M1 login account operation. $stderr"
        }
        return $stdout.Trim()
    }
    finally {
        $env:MYSQL_PWD = $previousMySqlPassword
    }
}

$DeveloperReadPassword = Read-M1Password -Value $DeveloperReadPassword -Prompt 'M1 read-only developer password'
$DeveloperOperatorPassword = Read-M1Password -Value $DeveloperOperatorPassword -Prompt 'M1 operator developer password'
$DisabledAccountPassword = Read-M1Password -Value $DisabledAccountPassword -Prompt 'M1 disabled-account password'

$accounts = @(
    [pscustomobject]@{ Id = 910101; Username = 'm1devread'; Status = '0'; PasswordHash = New-M1BcryptHash $DeveloperReadPassword; RoleId = 910001; NickName = 'M1只读开发者' },
    [pscustomobject]@{ Id = 910102; Username = 'm1devops'; Status = '0'; PasswordHash = New-M1BcryptHash $DeveloperOperatorPassword; RoleId = 910002; NickName = 'M1运维开发者' },
    [pscustomobject]@{ Id = 910103; Username = 'm1disabled'; Status = '1'; PasswordHash = New-M1BcryptHash $DisabledAccountPassword; RoleId = $null; NickName = 'M1停用账号' }
)

$existingOutput = Invoke-M1MySql "SELECT CONCAT(user_id, '|', user_name, '|', status, '|', del_flag) FROM sys_user WHERE user_id IN (910101, 910102, 910103) OR user_name IN ('m1devread', 'm1devops', 'm1disabled') ORDER BY user_id;"
$existing = @($existingOutput -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$expectedExisting = @('910101|m1devread|0|0', '910102|m1devops|0|0', '910103|m1disabled|1|0')
if ($existing.Count -ne 0 -and (@(Compare-Object -ReferenceObject $expectedExisting -DifferenceObject $existing)).Count -ne 0) {
    throw 'Existing M1 login acceptance account identifiers do not match the reserved test-account boundary. No data was changed.'
}

$values = $accounts | ForEach-Object {
    "($($_.Id), NULL, '$($_.Username)', '$($_.NickName)', '00', '$($_.Username)@local.invalid', '$($_.Username)@local.invalid', NOW(3), 1, NULL, '', '2', '', '$($_.PasswordHash)', '$($_.Status)', '0', '', NULL, NOW(), 'm1-login-bootstrap', NOW(), '', NULL, 'M1 本地验收账号')"
}
$roleValues = $accounts | Where-Object { $null -ne $_.RoleId } | ForEach-Object { "($($_.Id), $($_.RoleId))" }
$sql = @"
START TRANSACTION;
INSERT INTO sys_user
    (user_id, dept_id, user_name, nick_name, user_type, email, email_normalized,
     email_verified_at, auth_epoch, avatar_file_id, phonenumber, sex, avatar,
     password, status, del_flag, login_ip, login_date, pwd_update_date,
     create_by, create_time, update_by, update_time, remark)
VALUES
    $($values -join ",`n    ")
ON DUPLICATE KEY UPDATE user_id = sys_user.user_id;
INSERT INTO sys_user_role (user_id, role_id)
VALUES
    $($roleValues -join ",`n    ")
ON DUPLICATE KEY UPDATE user_id = sys_user_role.user_id;
COMMIT;
"@
[void](Invoke-M1MySql $sql)
$accounts | ForEach-Object { $_.PasswordHash = $null }
Write-Output 'M1 login acceptance accounts are present: m1devread (read-only), m1devops (operation-log), and m1disabled (disabled). Passwords and hashes were not written to the repository or output.'
