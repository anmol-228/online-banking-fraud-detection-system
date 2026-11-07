# Stops only the frontend process this folder started (PID file).
. "$PSScriptRoot\common.ps1"
$f = Join-Path $script:Logs 'frontend.pid'
if (Test-Path $f) {
    $id = [int](Get-Content $f)
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -eq 'node') { Stop-Process -Id $id; Start-Sleep 1 }
    Remove-Item $f
}
Write-Host ("Frontend port 5173 listening: " + (Test-Port 5173))
