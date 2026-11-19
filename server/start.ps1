$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$env:PYTHONUTF8 = '1'
$env:PYTHONUNBUFFERED = '1'
$env:UV_CACHE_DIR = Join-Path $PSScriptRoot '.cache'
$env:UV_PYTHON_INSTALL_DIR = Join-Path $PSScriptRoot '.python'

function Run-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Program (exit $LASTEXITCODE). Check internet access and the message above." }
}

try {
    $pythonPath = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
    $stampPath = Join-Path $PSScriptRoot '.requirements.sha256'
    $requirementsPath = Join-Path $PSScriptRoot 'requirements.txt'
    $requirementsHash = (Get-FileHash -LiteralPath $requirementsPath -Algorithm SHA256).Hash
    $installedHash = if (Test-Path -LiteralPath $stampPath) { (Get-Content -LiteralPath $stampPath -Raw).Trim() } else { '' }
    if (!(Test-Path -LiteralPath $pythonPath) -or $installedHash -ne $requirementsHash) {
        Write-Host 'First setup: downloading Python 3.12 and local AI dependencies. This may take several minutes.' -ForegroundColor Cyan
        $bootstrapPython = Join-Path $PSScriptRoot '.bootstrap\Scripts\python.exe'
        if (!(Test-Path -LiteralPath $bootstrapPython)) {
            if (Get-Command py -ErrorAction SilentlyContinue) {
                Run-Checked -Program 'py' -Arguments @('-3', '-m', 'venv', (Join-Path $PSScriptRoot '.bootstrap'))
            } elseif (Get-Command python -ErrorAction SilentlyContinue) {
                Run-Checked -Program 'python' -Arguments @('-m', 'venv', (Join-Path $PSScriptRoot '.bootstrap'))
            } else {
                throw 'Python is required for first setup. Install Python 3.12 from https://www.python.org/downloads/windows/ and run START.cmd again.'
            }
        }
        $uvPath = Join-Path $PSScriptRoot '.bootstrap\Scripts\uv.exe'
        if (!(Test-Path -LiteralPath $uvPath)) {
            Run-Checked -Program $bootstrapPython -Arguments @('-m', 'pip', 'install', '--disable-pip-version-check', 'uv==0.9.9')
        }
        if (!(Test-Path -LiteralPath $pythonPath)) {
            Run-Checked -Program $uvPath -Arguments @('venv', '--python', '3.12', (Join-Path $PSScriptRoot '.venv'))
        }
        Run-Checked -Program $uvPath -Arguments @('pip', 'install', '--python', $pythonPath, '-r', $requirementsPath)
        Set-Content -LiteralPath $stampPath -Value $requirementsHash -Encoding ASCII
    }
    Write-Host 'Open http://127.0.0.1:8765/setup in Chrome to get your connection code.' -ForegroundColor Green
    Write-Host 'Keep this window open. On the first run the models will download automatically.'
    Run-Checked -Program $pythonPath -Arguments @((Join-Path $PSScriptRoot 'app.py'))
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
