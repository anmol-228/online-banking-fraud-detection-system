# One command: MySQL -> backend -> frontend -> open the browser.
param([switch]$NoBrowser)
& "$PSScriptRoot\mysql-start.ps1"
& "$PSScriptRoot\backend-start.ps1"
& "$PSScriptRoot\frontend-start.ps1"
& "$PSScriptRoot\status.ps1"
if (-not $NoBrowser) { Start-Process 'http://localhost:5173' }
