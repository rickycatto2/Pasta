# Start Pasta if it is not already running. Safe to run repeatedly.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null

$port = 4317
$envFile = Join-Path $root 'pasta.env'
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*PASTA_PORT\s*=\s*(\d+)\s*$') { $port = [int]$Matches[1]; break }
    }
}

$node = 'C:\Program Files\nodejs\node.exe'
if (-not (Test-Path $node)) {
    $command = Get-Command node -ErrorAction SilentlyContinue
    if ($command) { $node = $command.Source } else { throw 'Node.js was not found.' }
}

$cloudflared = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
if (-not (Test-Path $cloudflared)) {
    $command = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($command) { $cloudflared = $command.Source } else { throw 'cloudflared was not found.' }
}

function Test-Pasta {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

$startedServer = $false
if (-not (Test-Pasta)) {
    $existing = Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*server/server.mjs*' }
    if (-not $existing) {
        Start-Process -FilePath $node `
            -ArgumentList 'server/server.mjs' `
            -WorkingDirectory $root `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logs 'server.log') `
            -RedirectStandardError (Join-Path $logs 'server-error.log')
        $startedServer = $true
    }
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Pasta) { break }
    }
}

$tunnelConfig = Join-Path $root 'cloudflared.yml'
$tunnel = Get-CimInstance Win32_Process -Filter "Name = 'cloudflared.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*$tunnelConfig*" }
$startedTunnel = $false
if (-not $tunnel) {
    Start-Process -FilePath $cloudflared `
        -ArgumentList @('tunnel', '--config', $tunnelConfig, 'run') `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'tunnel.log') `
        -RedirectStandardError (Join-Path $logs 'tunnel-error.log')
    $startedTunnel = $true
}

$message = "$(Get-Date -Format s) start-pasta port=$port serverStarted=$startedServer healthy=$(Test-Pasta) tunnelStarted=$startedTunnel"
Add-Content -Path (Join-Path $logs 'startup.log') -Value $message
Write-Host $message
