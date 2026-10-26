param()
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$pythonRuntime = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
if (-not (Test-Path -LiteralPath $pythonRuntime)) { $pythonRuntime = (Get-Command python).Source }
$previousPythonPath = $env:PYTHONPATH
$previousKey = $env:MERITZ_APP_KEY
$previousSecret = $env:MERITZ_APP_SECRET
try {
    if (Test-Path -LiteralPath '.deps') { $env:PYTHONPATH = Join-Path $PSScriptRoot '.deps' }
    Write-Host 'Meritz read-only market data check. Keys stay in this process; no orders or account queries.'
    $keyInput = Read-Host 'Meritz App Key (hidden)' -AsSecureString
    $secretInput = Read-Host 'Meritz App Secret (hidden)' -AsSecureString
    $env:MERITZ_APP_KEY = [System.Net.NetworkCredential]::new('', $keyInput).Password
    $env:MERITZ_APP_SECRET = [System.Net.NetworkCredential]::new('', $secretInput).Password
    & $pythonRuntime tools/check_meritz_data.py
} finally {
    $env:MERITZ_APP_KEY = $previousKey
    $env:MERITZ_APP_SECRET = $previousSecret
    $env:PYTHONPATH = $previousPythonPath
    if ($null -ne $keyInput) { $keyInput.Dispose() }
    if ($null -ne $secretInput) { $secretInput.Dispose() }
    Remove-Variable keyInput, secretInput -ErrorAction SilentlyContinue
}
Write-Host 'Finished. You can close this window.'
