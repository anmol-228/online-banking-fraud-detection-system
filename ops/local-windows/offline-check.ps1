# Evidence that the demo needs no network: lists every TCP socket owned by the three demo processes.
# Passing condition: every LISTEN is on 127.0.0.1/::1 and every ESTABLISHED remote address is loopback.
. "$PSScriptRoot\common.ps1"
$ErrorActionPreference = 'Continue'
$pids = @{}
foreach ($pt in 3306, 8080, 5173) {
    $c = Get-NetTCPConnection -LocalPort $pt -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { $pids[$c.OwningProcess] = $pt }
}
$bad = 0
foreach ($procId in $pids.Keys) {
    $name = (Get-Process -Id $procId -ErrorAction SilentlyContinue).ProcessName
    Write-Host "--- $name (pid $procId, serves port $($pids[$procId]))"
    # include child processes of mysqld's monitor and java's single process
    $conns = Get-NetTCPConnection -OwningProcess $procId -ErrorAction SilentlyContinue | Where-Object { $_.State -in 'Listen', 'Established', 'SynSent' }
    foreach ($c in $conns) {
        $loop = $c.RemoteAddress -in '127.0.0.1', '::1', '0.0.0.0', '::' -and $c.LocalAddress -in '127.0.0.1', '::1'
        if (-not $loop) { $bad++ }
        Write-Host ("  {0,-12} {1}:{2} -> {3}:{4}  {5}" -f $c.State, $c.LocalAddress, $c.LocalPort, $c.RemoteAddress, $c.RemotePort, $(if ($loop) { 'loopback' } else { 'NOT LOOPBACK' }))
    }
}
Write-Host ("`nNon-loopback sockets owned by the demo processes: $bad  =>  " + $(if ($bad -eq 0) { 'PASS' } else { 'FAIL' }))
$online = Test-NetConnection -ComputerName 1.1.1.1 -Port 443 -WarningAction SilentlyContinue -InformationLevel Quiet
Write-Host ("Internet reachable from this PC right now (informational): $online")
