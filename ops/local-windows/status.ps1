# Shows what is running and which sockets each demo process owns (loopback-only check).
. "$PSScriptRoot\common.ps1"
foreach ($pt in 3306, 8080, 5173) {
    $c = Get-NetTCPConnection -LocalPort $pt -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    $addr = if ($c) { "LISTENING on $($c.LocalAddress):$pt" } else { 'not running' }
    Write-Host ("port {0,-5} {1}" -f $pt, $addr)
}
$x = Get-NetTCPConnection -LocalPort 33060 -State Listen -ErrorAction SilentlyContinue
Write-Host ("MySQL X protocol port 33060 listening: " + [bool]$x + " (must be False)")
if (Test-Port 3306) {
    Write-Host ('database tables: ' + ((Invoke-Mysql -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='obfds'" -Database 'obfds') | Select-Object -Last 1))
}
