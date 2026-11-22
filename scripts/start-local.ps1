param([switch]$InfrastructureOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (!(Test-Path .env)) { throw 'Missing .env. Configure it before starting.' }
foreach ($line in Get-Content .env) {
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $value = $Matches[2].Trim().Trim("'").Trim('"')
        [Environment]::SetEnvironmentVariable($Matches[1], $value, 'Process')
    }
}
$compose = @('compose','--env-file','.env','-f','docker-compose.yml','-f','docker-compose.reuse.yml')
& docker @compose config --quiet
if ($LASTEXITCODE) { throw 'Invalid Compose configuration' }
& docker @compose up -d --wait --wait-timeout 120
if ($LASTEXITCODE) { throw 'Infrastructure startup failed' }
foreach ($topic in @('video-analysis-topic','video-analysis-dead-topic')) {
    & docker @compose exec -T rmqbroker sh -c ('${ROCKETMQ_HOME}/bin/mqadmin updateTopic -n rmqnamesrv:9876 -b 127.0.0.1:10911 -t ' + $topic)
    if ($LASTEXITCODE) { throw "Topic creation failed: $topic" }
}
if ($InfrastructureOnly) { return }
New-Item -ItemType Directory -Force .runtime | Out-Null
foreach ($port in @(9090,5173)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port already occupied; no duplicate processes started." }
}
$jar = Join-Path $root 'server/target/server-0.0.1-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw 'Build backend first: cd server; ./mvnw.cmd package' }
if (!(Test-Path client/dist/index.html)) { throw 'Build frontend first: cd client; npm ci; npm run build' }
$backend = Start-Process java -ArgumentList @('-jar',$jar) -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput "$root/.runtime/backend.log" -RedirectStandardError "$root/.runtime/backend.err.log" -PassThru
$frontend = Start-Process node -ArgumentList @('node_modules/vite/bin/vite.js','preview','--host','127.0.0.1','--port','5173','--strictPort') -WorkingDirectory "$root/client" -WindowStyle Hidden -RedirectStandardOutput "$root/.runtime/frontend.log" -RedirectStandardError "$root/.runtime/frontend.err.log" -PassThru
@{backend=$backend.Id;frontend=$frontend.Id} | ConvertTo-Json | Set-Content .runtime/processes.json
Write-Output 'Started: http://localhost:5173 ; API: http://localhost:9090/health'

