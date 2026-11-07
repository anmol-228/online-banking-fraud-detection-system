# Reverse order; MySQL is shut down gracefully last.
& "$PSScriptRoot\frontend-stop.ps1"
& "$PSScriptRoot\backend-stop.ps1"
& "$PSScriptRoot\mysql-stop.ps1"
