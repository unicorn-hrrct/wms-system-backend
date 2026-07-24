# Start everything: docker compose up -> wait healthy -> mvn package -> Spring Boot

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

Write-Host "==== 1. Start containers ====" -ForegroundColor Cyan
docker compose up -d
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }

# Wait for healthy
Write-Host "Waiting for containers to become healthy..."
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    $rows = docker compose ps --format '{{.Service}}|{{.Status}}' 2>&1
    $unhealthy = $rows | Where-Object { $_ -and ($_ -notmatch 'healthy') }
    if (-not $unhealthy) {
        Write-Host "  all containers healthy" -ForegroundColor Green
        break
    }
}

Write-Host "==== 2. Build jar (skipped if already built) ====" -ForegroundColor Cyan
mvn -B -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'mvn build failed' }

Write-Host "==== 3. Start Spring Boot ====" -ForegroundColor Cyan
& .\start-app.ps1
