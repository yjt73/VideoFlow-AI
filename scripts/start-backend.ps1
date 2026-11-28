$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/load-env.ps1"
Push-Location (Join-Path (Split-Path $PSScriptRoot -Parent) 'server')
try {
    & .\mvnw.cmd spring-boot:run
    if ($LASTEXITCODE) { throw 'Backend startup failed. See the Maven output above.' }
} finally {
    Pop-Location
}
