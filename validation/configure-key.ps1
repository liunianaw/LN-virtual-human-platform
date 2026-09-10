# Run locally; the secret is entered without appearing in shell history.
$ErrorActionPreference = 'Stop'
$secretValue = Read-Host '请输入百炼北京地域 API Key（输入隐藏）' -AsSecureString
$secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secretValue)
try {
    $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
    if ([string]::IsNullOrWhiteSpace($plainKey)) { throw 'Key 不能为空' }
    $localConfig = @{ api_key = $plainKey; api_host = 'https://dashscope.aliyuncs.com' }
    $configFile = Join-Path $PSScriptRoot 'config.local.json'
    $localConfig | ConvertTo-Json | Set-Content -LiteralPath $configFile -Encoding utf8
    Write-Host '已保存到本地忽略文件 config.local.json；不要分享此文件。'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
    $plainKey = $null
    $localConfig = $null
}
