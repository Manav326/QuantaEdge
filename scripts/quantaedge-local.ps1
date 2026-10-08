param(
  [ValidateSet("up","rebuild","down","reset","logs","ps","audit")]
  [string]$Action = "up"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$env:APP_DEMO_SEED = if ($env:APP_DEMO_SEED) { $env:APP_DEMO_SEED } else { "true" }

switch ($Action) {
  "audit" {
    $api = "http://localhost:$($env:API_PORT ?? 8080)"
    $response = Invoke-RestMethod "$api/api/v1/curriculum/audit/strict"
    $response | ConvertTo-Json -Depth 20
    if ($response.status -ne "GREEN") { throw "Curriculum strict audit is RED. Do not ship until every chapter passes." }
    Write-Host "Curriculum strict audit: GREEN"
    exit 0
  }
  "up" {
    docker compose up -d --build --remove-orphans
  }
  "rebuild" {
    docker compose build --no-cache
    docker compose up -d --remove-orphans
  }
  "down" {
    docker compose down
    exit 0
  }
  "reset" {
    docker compose down -v --remove-orphans
    docker compose up -d --build --remove-orphans
  }
  "logs" {
    docker compose logs -f --tail=200
    exit 0
  }
  "ps" {
    docker compose ps
    exit 0
  }
}

docker compose ps
Write-Host ""
Write-Host "Waiting for API health..."
for ($i = 0; $i -lt 30; $i++) {
  try {
    Invoke-WebRequest -UseBasicParsing "http://localhost:$($env:API_PORT ?? 8080)/actuator/health" | Out-Null
    Write-Host "API:   http://localhost:$($env:API_PORT ?? 8080)/actuator/health"
    Write-Host "Web:   http://localhost:$($env:WEB_PORT ?? 3000)"
    Write-Host "Admin: http://localhost:$($env:ADMIN_PORT ?? 3001)"
    Write-Host "Preview student: /student"
    exit 0
  } catch {
    Start-Sleep -Seconds 2
  }
}
Write-Host "===== API logs =====" -ForegroundColor Yellow
docker compose ps -a
docker compose logs --no-color --tail=200 api
throw "API did not become healthy within 60 seconds."
