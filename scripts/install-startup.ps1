# Register Pasta to start at sign-in. Uses Task Scheduler when possible,
# and falls back to a Startup-folder shortcut if Task Scheduler is restricted.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$script = Join-Path $root 'scripts\start-pasta.ps1'
$argument = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$script`""
$taskName = 'Pasta'

try {
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argument -WorkingDirectory $root
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERNAME"
    $settings = New-ScheduledTaskSettingsSet `
        -StartWhenAvailable `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
        -Description 'Starts the Pasta clipboard and its Cloudflare tunnel at sign-in.' `
        -RunLevel Limited -Force | Out-Null
    Write-Host "Scheduled task '$taskName' registered."
    $startup = [Environment]::GetFolderPath('Startup')
    $oldShortcut = Join-Path $startup 'Pasta.lnk'
    if (Test-Path $oldShortcut) { Remove-Item $oldShortcut -Force }
} catch {
    Write-Warning "Task Scheduler registration failed: $($_.Exception.Message)"
    Write-Host 'Falling back to a Startup-folder shortcut.'
    $startup = [Environment]::GetFolderPath('Startup')
    $shortcutPath = Join-Path $startup 'Pasta.lnk'
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $shortcut.Arguments = $argument
    $shortcut.WorkingDirectory = $root
    $shortcut.Description = 'Start Pasta clipboard and tunnel'
    $shortcut.Save()
    Write-Host "Startup shortcut written: $shortcutPath"
}

Write-Host 'Starting Pasta now (safe if it is already running)…'
& $script
