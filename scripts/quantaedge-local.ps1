param(
  [ValidateSet("up", "ai", "rebuild", "clean-build", "down", "reset", "logs", "ps")]
  [string]$Action = "up",

  # Use "all" for the first start. Select one application for routine iteration.
  [ValidateSet("all", "api", "web", "admin", "people")]
  [string]$Service = "all",

  # Default uses published GHCR images. Set this switch to build from the checked-out source.
  [switch]$BuildLocal,

  # Used only when pulling published GHCR images.
  [string]$ImageTag = "latest"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($Action -in @("down", "reset", "logs", "ps") -and $Service -ne "all") {
  throw "-Service is supported for up/rebuild/clean-build only. '$Action' operates on the full Compose project."
}
if ($Action -eq "clean-build" -and -not $BuildLocal) {
  throw "'clean-build' is local-source only. Re-run with -BuildLocal, or use 'up' to pull GHCR images."
}
if ([string]::IsNullOrWhiteSpace($ImageTag)) {
  throw "-ImageTag cannot be empty."
}

$env:APP_DEMO_SEED = if ($env:APP_DEMO_SEED) { $env:APP_DEMO_SEED } else { "true" }
$env:QUANTAEDGE_GHCR_OWNER = if ($env:QUANTAEDGE_GHCR_OWNER) { $env:QUANTAEDGE_GHCR_OWNER.ToLowerInvariant() } else { "manav326" }
$env:QUANTAEDGE_IMAGE_TAG = $ImageTag

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

# Default is GHCR-pull mode; -BuildLocal uses only the normal local Compose file.
$script:ComposeFiles = if ($BuildLocal) {
  @("-f", "docker-compose.yml")
} else {
  @("-f", "docker-compose.yml", "-f", "docker-compose.ghcr.yml")
}

function Invoke-Compose {
  param([Parameter(Mandatory = $true)][string[]]$ComposeArgs)

  $dockerArgs = @($script:ComposeFiles) + $ComposeArgs
  & docker compose @dockerArgs
  $exitCode = $LASTEXITCODE
  if ($exitCode -ne 0) {
    throw "docker compose $($dockerArgs -join ' ') failed with exit code $exitCode."
  }
}

function Invoke-AiCompose {
  param([Parameter(Mandatory = $true)][string[]]$ComposeArgs)

  $dockerArgs = @("-f", "docker-compose.yml")
  if (-not $BuildLocal) {
    $dockerArgs += @("-f", "docker-compose.ghcr.yml")
  }
  $dockerArgs += @("-f", "docker-compose.ai.yml") + $ComposeArgs
  & docker compose @dockerArgs
  $exitCode = $LASTEXITCODE
  if ($exitCode -ne 0) {
    throw "AI tutor Compose command failed (exit code $exitCode). Check Docker Desktop and available disk/RAM, then inspect with: docker compose -f docker-compose.yml -f docker-compose.ai.yml logs --tail=200 ollama ollama-model-init api"
  }
}

function Get-TargetArgs {
  if ($Service -eq "all") { return @() }
  return @($Service)
}

function Sync-PublishedImages {
  # Pull all images so a first-time local start also has healthy database/cache dependencies.
  Invoke-Compose @("pull")
}

$targetArgs = @(Get-TargetArgs)

switch ($Action) {
  "up" {
    if ($BuildLocal) {
      if ($Service -eq "all") {
        Invoke-Compose @("up", "-d", "--build", "--remove-orphans")
      } else {
        Invoke-Compose (@("build", "--parallel") + $targetArgs)
        Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
      }
    } else {
      Sync-PublishedImages
      Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
    }
  }
  "ai" {
    # Starts the normal stack plus a private-network Ollama model; existing volumes are preserved.
    if (-not $BuildLocal) { Sync-PublishedImages }
    $aiUpArgs = if ($BuildLocal) {
      @("up", "-d", "--build", "--remove-orphans")
    } else {
      @("up", "-d", "--no-build", "--remove-orphans")
    }
    Invoke-AiCompose $aiUpArgs
  }
  "rebuild" {
    if ($BuildLocal) {
      # Cached local rebuild; --no-cache is reserved for clean-build.
      Invoke-Compose (@("build", "--parallel") + $targetArgs)
      Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
    } else {
      Sync-PublishedImages
      Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
    }
  }
  "clean-build" {
    Invoke-Compose (@("build", "--no-cache", "--parallel") + $targetArgs)
    Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
  }
  "down" {
    Invoke-Compose @("down")
    exit 0
  }
  "reset" {
    # WARNING: this intentionally deletes local database/cache volumes.
    Invoke-Compose @("down", "-v", "--remove-orphans")
    if ($BuildLocal) {
      Invoke-Compose @("up", "-d", "--build", "--remove-orphans")
    } else {
      Sync-PublishedImages
      Invoke-Compose @("up", "-d", "--no-build", "--remove-orphans")
    }
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
$healthAttempts = if ($Action -eq "ai") { 180 } else { 30 }
for ($i = 0; $i -lt $healthAttempts; $i++) {
  try {
    Invoke-WebRequest -UseBasicParsing "http://localhost:$apiPort/actuator/health" | Out-Null
    Write-Host "API:    http://localhost:$apiPort/actuator/health"
    Write-Host "Web:    http://localhost:$webPort"
    Write-Host "Admin:  http://localhost:$adminPort"
    Write-Host "People: http://localhost:$peoplePort"
    Write-Host "Student preview: http://localhost:$webPort/student"
    if ($BuildLocal) {
      Write-Host "Image source: local source build."
    } else {
      Write-Host "Image source: GHCR ($($env:QUANTAEDGE_GHCR_OWNER)/quantaedge-*:$ImageTag)."
    }
    if ($Action -eq "ai") {
      Write-Host "AI tutor mode: Ollama / qwen2.5:0.5b (first launch may take several minutes)."
      Write-Host "Open a published lesson and select the tutor dock. The model is a local MVP and may make mistakes."
      Write-Host "AI tutor logs: docker compose -f docker-compose.yml -f docker-compose.ai.yml logs --tail=200 ollama ollama-model-init api"
    }
    exit 0
  } catch {
    Start-Sleep -Seconds 2
  }
}
throw "API did not become healthy within $([int]($healthAttempts * 2)) seconds. Inspect services with: docker compose ps; for AI mode also run: docker compose -f docker-compose.yml -f docker-compose.ai.yml logs --tail=200 ollama ollama-model-init api"
