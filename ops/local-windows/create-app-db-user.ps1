# Creates the least-privilege RUNTIME database account `obfds_app` on the LOCAL MySQL (idempotent, additive).
#   obfds_user  - already exists, ALL on the OBFDS schemas: used ONLY by the migrator and the test suite.
#   obfds_app   - SELECT, INSERT, UPDATE, DELETE only: used by the running service. It cannot create,
#                 alter or drop a table, so a flaw in the application cannot damage the schema.
# The generated password is written to an ACL-restricted env file OUTSIDE the repository and is never printed.
param([string[]]$Databases = @('obfds'))
. "$PSScriptRoot\common.ps1"
$envFile = Join-Path $script:Tools 'obfds-app-db.env'
if (-not (Test-Path $envFile)) {
    $chars = [char[]](48..57 + 65..90 + 97..122)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32; $rng.GetBytes($bytes)
    $password = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
    "MYSQL_USERNAME=obfds_app`nMYSQL_PASSWORD=$password" | Set-Content $envFile -Encoding ascii
    icacls $envFile /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
}
$password = (Read-EnvFile $envFile)['MYSQL_PASSWORD']
$sql = "CREATE USER IF NOT EXISTS 'obfds_app'@'localhost' IDENTIFIED BY '$password'; ALTER USER 'obfds_app'@'localhost' IDENTIFIED BY '$password';"
foreach ($db in $Databases) { $sql += " GRANT SELECT, INSERT, UPDATE, DELETE ON ``$db``.* TO 'obfds_app'@'localhost';" }
Invoke-Mysql -Sql $sql -Database mysql -AsRoot
Write-Host "obfds_app ready with DML-only grants on: $($Databases -join ', ')"
Invoke-Mysql -Sql "SHOW GRANTS FOR 'obfds_app'@'localhost';" -Database mysql -AsRoot | ForEach-Object { $_ -replace 'IDENTIFIED BY.*','' }
