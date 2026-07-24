# Stop everything: Spring Boot + docker containers (keeps volumes)

$ErrorActionPreference = 'SilentlyContinue'

Write-Host "==== 1. Stop Spring Boot ====" -ForegroundColor Cyan
$jps = Get-Process -Name java -ErrorAction SilentlyContinue
if ($jps) {
    $jps | ForEach-Object {
        Write-Host "  stopping PID $($_.Id)"
        Stop-Process -Id $_.Id -Force
    }
} else {
    Write-Host "  no java process running"
}

Write-Host "==== 2. Stop containers (keep volumes) ====" -ForegroundColor Cyan
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)
docker compose down

Write-Host "Done. To remove data too: docker compose down -v" -ForegroundColor Yellow
