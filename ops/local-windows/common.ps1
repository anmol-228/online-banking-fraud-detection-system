# Shared helpers for the OBFDS local demonstration scripts (dot-source this file).
$ErrorActionPreference = 'Stop'
$script:Root  = Split-Path $PSScriptRoot -Parent          # ...\_TOOLS\obfds-local
$script:Tools = Split-Path $script:Root -Parent           # ...\_TOOLS
$script:MysqlBase = Join-Path $script:Tools 'mysql-8.4.13-winx64'
$script:Logs  = Join-Path $script:Root 'logs'
New-Item -ItemType Directory -Force $script:Logs, (Join-Path $script:Root 'backups') | Out-Null

function Read-EnvFile([string]$Path) {
    $h = @{}
    foreach ($line in Get-Content $Path) {
        if ($line -match '^\s*([A-Z0-9_]+)\s*=\s*(.*)$') { $h[$Matches[1]] = $Matches[2].Trim() }
    }
    $h
}
function Get-DbCredentials { Read-EnvFile (Join-Path $script:Tools 'obfds-local-db.env') }
function Get-RootCredentials { Read-EnvFile (Join-Path $script:Tools 'mysql-root.env') }

function Find-Java {
    # JAVA_HOME first, then PATH, then any installed JDK 21 or newer in the usual vendor folders.
    if ($env:JAVA_HOME) { $j = Join-Path $env:JAVA_HOME 'bin\java.exe'; if (Test-Path $j) { return $j } }
    $c = Get-Command java -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($vendor in 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Microsoft') {
        if (Test-Path $vendor) {
            $j = Get-ChildItem $vendor -Directory -Filter 'jdk-*' | Sort-Object Name -Descending |
                 ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
            if ($j) { return $j }
        }
    }
    throw 'Java 21+ was not found. Install a JDK 21 or newer, or set JAVA_HOME.'
}
function Test-Port([int]$Port) {
    [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

# Runs the mysql client as the application user (or root with -AsRoot). Password goes via MYSQL_PWD, not argv.
function Invoke-Mysql {
    param([string]$Sql, [string]$Database = 'obfds', [switch]$AsRoot, [switch]$Table)
    $cred = if ($AsRoot) { Get-RootCredentials } else { Get-DbCredentials }
    $user = if ($AsRoot) { 'root' } else { $cred['MYSQL_USERNAME'] }
    $pw   = if ($AsRoot) { $cred['MYSQL_ROOT_PASSWORD'] } else { $cred['MYSQL_PASSWORD'] }
    $env:MYSQL_PWD = $pw
    try {
        $cli = @('--no-defaults', "--user=$user", '--host=127.0.0.1', "--database=$Database", "--execute=$Sql")
        if ($Table) { $cli += '--table' }
        & (Join-Path $script:MysqlBase 'bin\mysql.exe') @cli
    } finally { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }
}
