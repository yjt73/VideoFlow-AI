param([switch]$InfrastructureOnly, [switch]$FullStack)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

if (!(Test-Path .env)) { throw 'Missing .env. Configure it before starting.' }

$compose = @('compose', '--env-file', '.env', '-f', 'compose.yaml')
if ($InfrastructureOnly -and $FullStack) { throw 'Choose either -InfrastructureOnly or -FullStack.' }
if (!$FullStack) { $compose += @('-f', 'compose.dev.yaml') }
& docker @compose config --quiet
if ($LASTEXITCODE) { throw 'Invalid Compose configuration' }

if (!$FullStack) {
    & docker @compose up -d --wait mysql redis qdrant minio rmqnamesrv rmqbroker rmq-topics
} else {
    & docker @compose up -d --build --wait
}
if ($LASTEXITCODE) { throw 'VideoFlow container startup failed' }

& docker @compose ps
if ($FullStack) {
    Write-Output 'VideoFlow: http://127.0.0.1:5173 ; MinIO console uses MINIO_CONSOLE_PORT from .env (default 9001).'
} else {
    Write-Output 'Middleware ready. Debug VideoFlow Backend (IDEA) in IDEA, then run npm run dev in client/.'
}
