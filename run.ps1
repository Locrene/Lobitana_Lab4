# Start the shop. Reads STUDENT_ID and API_KEY from .env if they are not already set.
$ErrorActionPreference = "Stop"

# The database lives in .\data next to this script. Always start from here, or the app would open a new,
# empty database somewhere else and read the Tiangge feed from the beginning again.
Set-Location $PSScriptRoot

if (Test-Path ".env") {
    Get-Content ".env" | ForEach-Object {
        if ($_ -match "^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.+?)\s*$") {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2], "Process")
        }
    }
}

if (-not $env:STUDENT_ID -or -not $env:API_KEY) {
    Write-Error "STUDENT_ID and API_KEY must be set (see .env.example)"
}

Write-Host "starting as client $env:STUDENT_ID"
mvn -q -DskipTests package
java -jar target/lab4-tiangge-1.0.0.jar
