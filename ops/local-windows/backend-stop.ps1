# Stops only the backend this folder started on the given port (PID file per port). Never stops processes by name,
# and refuses to stop a process whose command line is not the OBFDS backend.
param([int]$Port = 8080)
. "$PSScriptRoot\common.ps1"
$file = Join-Path $script:Logs "backend-$Port.pid"
if (-not (Test-Path $file) -and $Port -eq 8080) {
    # a record written by an older version of the scripts
    $legacy = Join-Path $script:Logs 'backend.pid'
    if (Test-Path $legacy) { $file = $legacy }
}
if (Test-Path $file) {
    $id = [int](Get-Content $file)
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    $cmd = if ($proc) { (Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue).CommandLine } else { '' }
    if ($proc -and $proc.ProcessName -eq 'java' -and $cmd -match 'obfds-backend') { Stop-Process -Id $id; Start-Sleep 2 }
    elseif ($proc) { Write-Warning "pid $id is not the OBFDS backend; leaving it alone." }
    Remove-Item $file
}
Write-Host ("Backend port $Port listening: " + (Test-Port $Port))
