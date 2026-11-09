# Starts the local MySQL 8.4 server (loopback only) using config\my.ini. Idempotent.
. "$PSScriptRoot\common.ps1"
if (Test-Port 3306) { Write-Host 'Port 3306 is already listening - MySQL (or another program) is already running.'; exit 0 }
$mysqld = Join-Path $script:MysqlBase 'bin\mysqld.exe'
$ini = Join-Path $script:Root 'config\my.ini'
$p = Start-Process -FilePath $mysqld -ArgumentList @("--defaults-file=`"$ini`"", '--console') -PassThru -WindowStyle Hidden `
     -RedirectStandardOutput (Join-Path $script:Logs 'mysqld.stdout') -RedirectStandardError (Join-Path $script:Logs 'mysqld.stderr')
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    if ($p.HasExited) { throw "mysqld exited early; see $($script:Logs)\mysqld-error.log" }
    if (Test-Port 3306) { break }
}
if (-not (Test-Port 3306)) { throw 'MySQL did not start listening on 3306 within 30 s.' }
$env:MYSQL_PWD = (Get-RootCredentials)['MYSQL_ROOT_PASSWORD']
$ErrorActionPreference = 'Continue'   # mysqladmin writes to stderr while the server is still warming up
for ($i = 0; $i -lt 20; $i++) { & (Join-Path $script:MysqlBase 'bin\mysqladmin.exe') --no-defaults --user=root --host=127.0.0.1 ping 2>$null | Out-Null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep -Milliseconds 500 }
Remove-Item Env:\MYSQL_PWD
Write-Host "MySQL is up on 127.0.0.1:3306 (pid $($p.Id))."
