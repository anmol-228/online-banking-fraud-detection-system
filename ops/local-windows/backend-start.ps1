# Starts the backend for the offline demonstration.
#
#   profiles   mysql,localdemo   (persistent MySQL; the default profile is in-memory H2 and forgets everything)
#   database   the least-privilege account obfds_app (SELECT/INSERT/UPDATE/DELETE only - it cannot alter the schema)
#   secret     a random per-install JWT secret kept in ..\config\jwt.secret (created on first start, outside Git)
#
# It refuses to start, loudly, if the port is taken by something that is not this backend, and it waits for
# READINESS (the database answers and the seeder has finished) without writing a login row to the audit trail.
param(
    [string]$DatabaseName = 'obfds',
    [int]$Port = 8080,
    [int]$ReadyTimeoutSeconds = 180
)
. "$PSScriptRoot\common.ps1"

$owner = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($owner) {
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($owner.OwningProcess)" -ErrorAction SilentlyContinue).CommandLine
    if ($cmd -match 'obfds-backend') {
        Write-Host "The OBFDS backend is already running on port $Port (pid $($owner.OwningProcess))."
        exit 0
    }
    throw "Port $Port is in use by another program (pid $($owner.OwningProcess)). Refusing to start: it could be an in-memory H2 build that would silently forget your data."
}
if (-not (Test-Port 3306)) { throw 'MySQL is not running. Run mysql-start.ps1 first.' }

$jar = Join-Path $script:Root 'app\obfds-backend-1.0.0.jar'
if (-not (Test-Path $jar)) { throw "Backend jar not found: $jar" }
$appEnv = Join-Path $script:Tools 'obfds-app-db.env'
if (-not (Test-Path $appEnv)) { throw "Missing $appEnv. Run create-app-db-user.ps1 once (it creates the least-privilege runtime account)." }
$app = Read-EnvFile $appEnv

# Environment for the child process only.
$env:SPRING_PROFILES_ACTIVE = 'mysql,localdemo'
$env:MYSQL_USERNAME = $app['MYSQL_USERNAME']
$env:MYSQL_PASSWORD = $app['MYSQL_PASSWORD']
$env:OBFDS_DB_NAME = $DatabaseName
$env:SERVER_PORT = "$Port"
$env:OBFDS_JWT_SECRET_FILE = Join-Path $script:Root 'config\jwt.secret'

$uds = Join-Path $env:USERPROFILE 'obfds-uds'   # workaround for the Windows 8.3 temp-path quirk (the JDK selector pipe cannot connect through a short temp path)
New-Item -ItemType Directory -Force $uds | Out-Null
$java = Find-Java
$stdout = Join-Path $script:Logs 'backend.stdout'
$stderr = Join-Path $script:Logs 'backend.stderr'
try {
    $p = Start-Process -FilePath $java -PassThru -WindowStyle Hidden `
        -ArgumentList @("-Djdk.net.unixdomain.tmpdir=`"$uds`"", '-Xmx256m', '-jar', "`"$jar`"") `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
} finally {
    Remove-Item Env:\SPRING_PROFILES_ACTIVE, Env:\MYSQL_USERNAME, Env:\MYSQL_PASSWORD, Env:\OBFDS_DB_NAME, Env:\SERVER_PORT, Env:\OBFDS_JWT_SECRET_FILE -ErrorAction SilentlyContinue
}
# One PID file PER PORT: a second backend (for example the verification clone) must never overwrite the record
# of the live one, or the live one can no longer be stopped by its script.
$p.Id | Out-File (Join-Path $script:Logs "backend-$Port.pid") -Encoding ascii

$started = Get-Date
$ready = $false
for ($i = 0; $i -lt ($ReadyTimeoutSeconds * 2); $i++) {
    Start-Sleep -Milliseconds 500
    if ($p.HasExited) {
        Write-Host '--- last lines of the backend log ---'
        Get-Content $stdout -Tail 25 | ForEach-Object { Write-Host $_ }
        throw "The backend exited during start-up (exit code $($p.ExitCode)). The startup safety guard prints its reasons above."
    }
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://127.0.0.1:$Port/actuator/health/readiness"
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
}
if (-not $ready) { throw "The backend did not become ready within $ReadyTimeoutSeconds seconds." }

# Fingerprint: prove WHICH process and WHICH database we are talking to.
$bind = (Get-NetTCPConnection -LocalPort $Port -State Listen | Select-Object -First 1).LocalAddress
$conn = Invoke-Mysql -Sql "select concat(user,' -> ',db) from information_schema.processlist where user='$($app['MYSQL_USERNAME'])' limit 1;" -Database mysql -AsRoot | Select-Object -Last 1
$version = Invoke-Mysql -Sql 'select max(cast(version as unsigned)) from flyway_schema_history where success=1;' -Database $DatabaseName -AsRoot | Select-Object -Last 1
Write-Host ("Backend ready after {0:n0}s: pid {1}, listening on {2}:{3}" -f ((Get-Date) - $started).TotalSeconds, $p.Id, $bind, $Port)
Write-Host "  profiles mysql,localdemo | database connection: $conn | schema version: $version"
if ($bind -ne '127.0.0.1') { Write-Warning "The backend is bound to $bind, not 127.0.0.1." }
