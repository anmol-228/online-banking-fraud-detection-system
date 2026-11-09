# Graceful MySQL shutdown (flushes InnoDB). Never kill mysqld.
. "$PSScriptRoot\common.ps1"
if (-not (Test-Port 3306)) { Write-Host 'MySQL is not running.'; exit 0 }
$env:MYSQL_PWD = (Get-RootCredentials)['MYSQL_ROOT_PASSWORD']
& (Join-Path $script:MysqlBase 'bin\mysqladmin.exe') --no-defaults --user=root --host=127.0.0.1 shutdown
Remove-Item Env:\MYSQL_PWD
for ($i = 0; $i -lt 40 -and (Test-Port 3306); $i++) { Start-Sleep -Milliseconds 500 }
Write-Host ("MySQL running: " + (Test-Port 3306))
