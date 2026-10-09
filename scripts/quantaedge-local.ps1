param(
  [ValidateSet("up","rebuild","down","reset","logs","ps")]
  [string]$Action = "up"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$env:APP_DEMO_SEED = if ($env:APP_DEMO_SEED) { $env:APP_DEMO_SEED } else { "true" }

# Keep this script compatible with Windows PowerShell 5.1, which does not
# support the PowerShell 7 null-coalescing operator (??).
$apiPort = if ([string]::IsNullOrWhiteSpace($env:API_PORT)) { "8080" } else { $env:API_PORT }
$webPort = if ([string]::IsNullOrWhiteSpace($env:WEB_PORT)) { "3000" } else { $env:WEB_PORT }
$adminPort = if ([string]::IsNullOrWhiteSpace($env:ADMIN_PORT)) { "3001" } else { $env:ADMIN_PORT }

switch ($Action) {
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
    Invoke-WebRequest -UseBasicParsing "http://localhost:$apiPort/actuator/health" | Out-Null
    Write-Host "API:   http://localhost:$apiPort/actuator/health"
    Write-Host "Web:   http://localhost:$webPort"
    Write-Host "Admin: http://localhost:$adminPort"
    Write-Host "Preview student: /student"
    exit 0
  } catch {
    Start-Sleep -Seconds 2
  }
}
throw "API did not become healthy within 60 seconds."
