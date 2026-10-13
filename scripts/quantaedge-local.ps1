param(
  [ValidateSet("up", "rebuild", "clean-build", "down", "reset", "logs", "ps")]
  [string]$Action = "up",

  # Use "all" for the first start. Select one application for routine iteration.
  [ValidateSet("all", "api", "web", "admin", "people")]
  [string]$Service = "all"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($Action -in @("down", "reset", "logs", "ps") -and $Service -ne "all") {
  throw "-Service is supported for up/rebuild/clean-build only. '$Action' operates on the full Compose project."
}

$env:APP_DEMO_SEED = if ($env:APP_DEMO_SEED) { $env:APP_DEMO_SEED } else { "true" }

# Keep local browser origins allowed even if .env contains production origins.
$localCorsOrigins = @(
  "http://localhost:3000",
  "http://localhost:3001",
  "http://localhost:3002",
  "http://127.0.0.1:3000",
  "http://127.0.0.1:3001",
  "http://127.0.0.1:3002"
)
if (-not [string]::IsNullOrWhiteSpace($env:APP_CORS_ALLOWED_ORIGINS)) {
  $localCorsOrigins += @($env:APP_CORS_ALLOWED_ORIGINS -split "," | ForEach-Object { $_.Trim() })
}
$env:APP_CORS_ALLOWED_ORIGINS = ($localCorsOrigins | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique) -join ","

# Windows PowerShell 5.1 compatible port defaults.
$apiPort = if ([string]::IsNullOrWhiteSpace($env:API_PORT)) { "8080" } else { $env:API_PORT }
$webPort = if ([string]::IsNullOrWhiteSpace($env:WEB_PORT)) { "3000" } else { $env:WEB_PORT }
$adminPort = if ([string]::IsNullOrWhiteSpace($env:ADMIN_PORT)) { "3001" } else { $env:ADMIN_PORT }
$peoplePort = if ([string]::IsNullOrWhiteSpace($env:PEOPLE_PORT)) { "3002" } else { $env:PEOPLE_PORT }

function Invoke-Compose {
  param([Parameter(Mandatory = $true)][string[]]$ComposeArgs)

  & docker compose @ComposeArgs
  $exitCode = $LASTEXITCODE
  if ($exitCode -ne 0) {
    throw "docker compose $($ComposeArgs -join ' ') failed with exit code $exitCode."
  }
}

function Get-TargetArgs {
  if ($Service -eq "all") { return @() }
  return @($Service)
}

$targetArgs = @(Get-TargetArgs)

switch ($Action) {
  "up" {
    if ($Service -eq "all") {
      # Compose/BuildKit reuses layers; it does not force a clean rebuild.
      Invoke-Compose @("up", "-d", "--build", "--remove-orphans")
    } else {
      # Build only the changed component. Other services use their existing images.
      Invoke-Compose (@("build", "--parallel") + $targetArgs)
      Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
    }
  }
  "rebuild" {
    # Cached rebuild is the normal path; --no-cache is reserved for clean-build.
    Invoke-Compose (@("build", "--parallel") + $targetArgs)
    Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
  }
  "clean-build" {
    # Diagnostic escape hatch only: intentionally discard Docker layer reuse.
    Invoke-Compose (@("build", "--no-cache", "--parallel") + $targetArgs)
    Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
  }
  "down" {
    Invoke-Compose @("down")
    exit 0
  }
  "reset" {
    # This intentionally deletes local database/cache volumes; use only when wanted.
    Invoke-Compose @("down", "-v", "--remove-orphans")
    Invoke-Compose @("up", "-d", "--build", "--remove-orphans")
  }
  "logs" {
    Invoke-Compose @("logs", "-f", "--tail=200")
    exit 0
  }
  "ps" {
    Invoke-Compose @("ps")
    exit 0
  }
}

Invoke-Compose @("ps")
Write-Host ""
Write-Host "Waiting for API health..."
for ($i = 0; $i -lt 30; $i++) {
  try {
    Invoke-WebRequest -UseBasicParsing "http://localhost:$apiPort/actuator/health" | Out-Null
    Write-Host "API:    http://localhost:$apiPort/actuator/health"
    Write-Host "Web:    http://localhost:$webPort"
    Write-Host "Admin:  http://localhost:$adminPort"
    Write-Host "People: http://localhost:$peoplePort"
    Write-Host "Student preview: http://localhost:$webPort/student"
    exit 0
  } catch {
    Start-Sleep -Seconds 2
  }
}
throw "API did not become healthy within 60 seconds."
