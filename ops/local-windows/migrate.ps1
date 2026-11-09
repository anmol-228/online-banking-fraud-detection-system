# The ONLY thing that changes the database schema: runs the migrator (Flyway) with DDL credentials.
#
#   migrate.ps1 -Command info                       show the migration state (read-only)
#   migrate.ps1 -Command validate                   check the history against the bundled migrations (read-only)
#   migrate.ps1 -Command migrate [-Database obfds]  apply pending migrations (takes a VERIFIED backup first)
#   migrate.ps1 -Command baseline -Confirm          adopt an EXISTING pre-Flyway database at version 1 (backup first)
#
# Safety: `clean` does not exist; `migrate` over existing tables without history is refused by Flyway;
# `baseline` needs -Confirm and writes only a history row. The running service uses a different, DML-only account.
param(
    [ValidateSet('info', 'validate', 'migrate', 'baseline')][string]$Command = 'info',
    [string]$Database = 'obfds',
    [switch]$Confirm,
    [switch]$NoBackup
)
. "$PSScriptRoot\common.ps1"
if (-not (Test-Port 3306)) { throw 'MySQL is not running.' }
$jar = Join-Path $script:Root 'app\obfds-backend-1.0.0.jar'
$creds = Get-DbCredentials   # the DDL-capable account; used only here

if ($Command -in 'migrate', 'baseline' -and -not $NoBackup) {
    Write-Host '== taking a verified backup before changing the schema =='
    & "$PSScriptRoot\backup.ps1" -DatabaseName $Database
    if ($LASTEXITCODE -ne 0) { throw 'The backup could not be verified; refusing to change the schema.' }
}
$env:MIGRATE_URL = "jdbc:mysql://localhost:3306/${Database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:MIGRATE_USERNAME = $creds['MYSQL_USERNAME']
$env:MIGRATE_PASSWORD = $creds['MYSQL_PASSWORD']
if ($Command -eq 'baseline' -and $Confirm) { $env:OBFDS_MIGRATE_CONFIRM_BASELINE = 'yes' }
try {
    & (Find-Java) -cp $jar '-Dloader.main=com.sepro.obfds.migrate.Migrator' org.springframework.boot.loader.launch.PropertiesLauncher $Command
    exit $LASTEXITCODE
} finally {
    Remove-Item Env:\MIGRATE_URL, Env:\MIGRATE_USERNAME, Env:\MIGRATE_PASSWORD, Env:\OBFDS_MIGRATE_CONFIRM_BASELINE -ErrorAction SilentlyContinue
}
