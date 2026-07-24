# Start the Spring Boot app (assumes PG/Redis/RabbitMQ containers are already up).
# Adapts to local mismatches: setup.sql password vs application default,
# and Docker host ports 6380/5673 to dodge Memurai on 6379 / 5672 bind issue.

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

# JetBrains Runtime 21 (project targets JDK 21)
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
$env:Path      = "$env:JAVA_HOME\bin;$env:Path"

# Override application.properties defaults
$env:DB_PASSWORD   = 'demo_pwd@2026'   # password set by sql/setup.sql
$env:REDIS_PORT    = '6380'            # docker host port for Redis
$env:RABBITMQ_PORT = '5673'            # docker host port for RabbitMQ AMQP

$jar = Join-Path $ProjectRoot 'target\demo-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jar)) {
    Write-Host "Jar not found at $jar; building..." -ForegroundColor Yellow
    mvn -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'mvn build failed' }
}

Write-Host "==== Starting Spring Boot ====" -ForegroundColor Cyan
Write-Host "JAVA_HOME     = $env:JAVA_HOME"
Write-Host "DB_PASSWORD   = $env:DB_PASSWORD"
Write-Host "REDIS_PORT    = $env:REDIS_PORT"
Write-Host "RABBITMQ_PORT = $env:RABBITMQ_PORT"
Write-Host ""
& java -jar $jar
