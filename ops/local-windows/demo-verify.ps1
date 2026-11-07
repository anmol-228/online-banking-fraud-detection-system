# Verifies the whole system end to end on a FRESH scratch database, then throws it away.
#
#   1. create an empty scratch database
#   2. run the migrator on it (V1 + V2 on an empty MySQL - the same path a new installation takes)
#   3. start a second backend (port 18081) on it as the DML-only account; its seeder creates the demo data
#   4. run verify_runtime.py against it (auth, fraud rules, holds, verification, analyst decision,
#      disputes, administration, reports)
#   5. stop that backend and drop the scratch database
#
# Why a fresh database and not a copy of the live one: the checks assert exact balances and report totals
# and the velocity rule looks at the last ten minutes, so they only hold on data nothing else has touched.
# The live database is never used here. Its backups are proven separately by backup.ps1 (restore +
# CHECKSUM TABLE on every table).
param(
    [int]$Port = 18081,
    [string]$VerifyScript = (Join-Path $PSScriptRoot 'verify_runtime.py')
)
. "$PSScriptRoot\common.ps1"
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $VerifyScript)) { throw "verify_runtime.py not found at $VerifyScript" }
$python = (Get-Command python -ErrorAction Stop).Source
$scratch = 'obfds_verify'
$code = 1
$summary = ''

Write-Host "== 1. empty scratch database $scratch =="
$ddlUser = (Get-DbCredentials)['MYSQL_USERNAME']
Invoke-Mysql -Sql "drop database if exists $scratch; create database $scratch character set utf8mb4; GRANT ALL PRIVILEGES ON ``$scratch``.* TO '$ddlUser'@'localhost'; GRANT SELECT, INSERT, UPDATE, DELETE ON ``$scratch``.* TO 'obfds_app'@'localhost';" -Database mysql -AsRoot

$backendId = $null
try {
    Write-Host '== 2. run the migrator on the empty database =='
    & "$PSScriptRoot\migrate.ps1" -Command migrate -Database $scratch -NoBackup
    if ($LASTEXITCODE -ne 0) { throw 'The migrator failed on an empty database.' }

    Write-Host "== 3. start a backend on it (port $Port); the seeder creates the demo data =="
    & "$PSScriptRoot\backend-start.ps1" -DatabaseName $scratch -Port $Port
    $backendId = [int](Get-Content (Join-Path $script:Logs "backend-$Port.pid"))

    Write-Host '== 4. run the end-to-end checks =='
    $env:OBFDS_BASE_URL = "http://127.0.0.1:$Port"
    # Python writes progress and any traceback to stdout/stderr; under 'Stop' the first stderr line would
    # abort this script before the output is captured, hiding the very thing we need to see.
    $ErrorActionPreference = 'Continue'
    $out = & $python $VerifyScript 2>&1 | ForEach-Object { $_.ToString() }
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $out | ForEach-Object { Write-Host $_ }
    $summary = ($out | Where-Object { $_ -match 'passed|checks' } | Select-Object -Last 1)
} finally {
    Remove-Item Env:\OBFDS_BASE_URL -ErrorAction SilentlyContinue
    Write-Host '== 5. clean up =='
    if ($backendId) {
        $proc = Get-Process -Id $backendId -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -eq 'java') { Stop-Process -Id $backendId -Force }
    }
    Remove-Item (Join-Path $script:Logs "backend-$Port.pid") -ErrorAction SilentlyContinue
    Invoke-Mysql -Sql "drop database if exists $scratch;" -Database mysql -AsRoot
}
Write-Host "verify_runtime.py exit code: $code   $summary"
exit $code
