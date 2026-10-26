param([switch]$Demo)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$pythonRuntime = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
if (-not (Test-Path -LiteralPath $pythonRuntime)) { $pythonRuntime = (Get-Command python).Source }
$previousPythonPath = $env:PYTHONPATH
$previousKey = $env:MERITZ_APP_KEY
$previousSecret = $env:MERITZ_APP_SECRET
try {
    if (Test-Path -LiteralPath '.deps') { $env:PYTHONPATH = Join-Path $PSScriptRoot '.deps' }
    if ($Demo) {
        & $pythonRuntime trading_server.py --demo
    } else {
        $keyInput = Read-Host '메리츠 App Key (화면에 표시되지 않습니다)' -AsSecureString
        $secretInput = Read-Host '메리츠 App Secret (화면에 표시되지 않습니다)' -AsSecureString
        $env:MERITZ_APP_KEY = [System.Net.NetworkCredential]::new('', $keyInput).Password
        $env:MERITZ_APP_SECRET = [System.Net.NetworkCredential]::new('', $secretInput).Password
        & $pythonRuntime trading_server.py --meritz
    }
} finally {
    $env:MERITZ_APP_KEY = $previousKey
    $env:MERITZ_APP_SECRET = $previousSecret
    $env:PYTHONPATH = $previousPythonPath
}
