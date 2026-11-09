# Serves the production frontend build (web\dist) on http://localhost:5173 with SPA fallback (vite preview, offline).
. "$PSScriptRoot\common.ps1"
if (Test-Port 5173) { Write-Host 'Port 5173 is already in use - frontend (or another program) is running.'; exit 0 }
$web = Join-Path $script:Root 'web'
$node = (Get-Command node -ErrorAction Stop).Source
$p = Start-Process -FilePath $node -WorkingDirectory $web -PassThru -WindowStyle Hidden `
     -ArgumentList @('node_modules/vite/bin/vite.js', 'preview', '--host', 'localhost', '--port', '5173', '--strictPort') `
     -RedirectStandardOutput (Join-Path $script:Logs 'frontend.stdout') -RedirectStandardError (Join-Path $script:Logs 'frontend.stderr')
$p.Id | Out-File (Join-Path $script:Logs 'frontend.pid') -Encoding ascii
for ($i = 0; $i -lt 40 -and -not (Test-Port 5173); $i++) { Start-Sleep -Milliseconds 400 }
if (-not (Test-Port 5173)) { throw 'Frontend did not start on 5173.' }
Write-Host "Frontend ready on http://localhost:5173 (pid $($p.Id))."
