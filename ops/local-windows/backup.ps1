# Backs up a database and PROVES the backup restores.
#   1. mysqldump (consistent snapshot, routines/triggers) to ..\backups\<db>_<timestamp>.sql
#   2. SHA-256 of the file next to it
#   3. restore into a scratch database and compare CHECKSUM TABLE for every table
#   4. drop the scratch database, write <file>.verified.txt
# A backup that has never been restored is a hope, not a backup. Existing backups are never overwritten.
param([string]$DatabaseName = 'obfds', [switch]$SkipRestoreCheck)
. "$PSScriptRoot\common.ps1"
if (-not (Test-Port 3306)) { throw 'MySQL is not running.' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backups = Join-Path $script:Root 'backups'
$file = Join-Path $backups "${DatabaseName}_$stamp.sql"
if (Test-Path $file) { throw "Refusing to overwrite $file" }
$root = Get-RootCredentials
$mysqlBin = Join-Path $script:MysqlBase 'bin'

$env:MYSQL_PWD = $root['MYSQL_ROOT_PASSWORD']
try {
    & (Join-Path $mysqlBin 'mysqldump.exe') --no-defaults --user=root --host=127.0.0.1 --single-transaction --routines --triggers `
        --no-tablespaces --default-character-set=utf8mb4 --result-file="$file" $DatabaseName
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $file) -or (Get-Item $file).Length -lt 1000) { throw 'mysqldump failed or produced an implausibly small file.' }
} finally { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }
$hash = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
"$hash  $(Split-Path $file -Leaf)" | Set-Content "$file.sha256" -Encoding ascii
Write-Host "Backup written: $file"
Write-Host "SHA-256: $hash"
if ($SkipRestoreCheck) { Write-Host 'Restore check skipped (-SkipRestoreCheck).'; return }

$scratch = "${DatabaseName}_restorecheck"
Invoke-Mysql -Sql "drop database if exists $scratch; create database $scratch character set utf8mb4;" -Database mysql -AsRoot
$env:MYSQL_PWD = $root['MYSQL_ROOT_PASSWORD']
try {
    & (Join-Path $mysqlBin 'mysql.exe') --no-defaults --user=root --host=127.0.0.1 --database=$scratch --execute="source $($file -replace '\\','/')"
    if ($LASTEXITCODE -ne 0) { throw 'Restore into the scratch database failed.' }
} finally { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }
$tables = @(Invoke-Mysql -Sql "select table_name from information_schema.tables where table_schema='$DatabaseName' and table_type='BASE TABLE' order by 1;" -Database mysql -AsRoot | Select-Object -Skip 1)
$equal = 0
foreach ($t in $tables) {
    $a = ((Invoke-Mysql -Sql "checksum table $DatabaseName.$t;" -Database mysql -AsRoot | Select-Object -Skip 1) -split '\s+')[-1]
    $b = ((Invoke-Mysql -Sql "checksum table $scratch.$t;" -Database mysql -AsRoot | Select-Object -Skip 1) -split '\s+')[-1]
    if (-not [string]::IsNullOrWhiteSpace($a) -and $a -ne 'NULL' -and $a -eq $b) { $equal++ } else { Write-Host "  MISMATCH or empty checksum: $t ($a vs $b)" }
}
Invoke-Mysql -Sql "drop database $scratch;" -Database mysql -AsRoot
$verdict = if ($tables.Count -gt 0 -and $equal -eq $tables.Count) { 'VERIFIED' } else { 'NOT VERIFIED' }
$line = "$verdict $(Get-Date -Format s) $(Split-Path $file -Leaf) sha256=$hash tables_equal=$equal/$($tables.Count)"
$line | Set-Content "$file.verified.txt" -Encoding ascii
Write-Host $line
if ($verdict -ne 'VERIFIED') { exit 1 }
