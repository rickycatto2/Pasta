# Stop the Pasta server and its dedicated Cloudflare tunnel only.
# Other cloudflared tunnels on this machine are left alone.

$ErrorActionPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$tunnelConfig = Join-Path $root 'cloudflared.yml'

$listener = Get-NetTCPConnection -LocalPort 4317 -State Listen
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
