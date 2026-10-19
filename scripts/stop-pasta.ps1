# Stop the Pasta server and its dedicated Cloudflare tunnel only.
# Other cloudflared tunnels on this machine are left alone.

$ErrorActionPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$tunnelConfig = Join-Path $root 'cloudflared.yml'

$port = 4317
$envFile = Join-Path $root 'pasta.env'
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*PASTA_PORT\s*=\s*(\d+)\s*$') { $port = [int]$Matches[1]; break }
    }
}

$listener = Get-NetTCPConnection -LocalPort $port -State Listen
if ($listener) {
    foreach ($processId in ($listener | Select-Object -ExpandProperty OwningProcess -Unique)) {
        Stop-Process -Id $processId -Force
        Write-Host "Stopped Pasta server process $processId."
    }
}

Get-CimInstance Win32_Process -Filter "Name = 'cloudflared.exe'" |
    Where-Object { $_.CommandLine -like "*$tunnelConfig*" } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force
        Write-Host "Stopped Pasta tunnel process $($_.ProcessId)."
    }
