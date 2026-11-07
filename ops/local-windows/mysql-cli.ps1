# Interactive MySQL shell as the application user (read/write on obfds only). Password is taken from the local env file.
param([string]$Database = 'obfds', [switch]$Root)
. "$PSScriptRoot\common.ps1"
$cred = if ($Root) { Get-RootCredentials } else { Get-DbCredentials }
$user = if ($Root) { 'root' } else { $cred['MYSQL_USERNAME'] }
$env:MYSQL_PWD = if ($Root) { $cred['MYSQL_ROOT_PASSWORD'] } else { $cred['MYSQL_PASSWORD'] }
try { & (Join-Path $script:MysqlBase 'bin\mysql.exe') --no-defaults --user=$user --host=127.0.0.1 --database=$Database --table } finally { Remove-Item Env:\MYSQL_PWD }
