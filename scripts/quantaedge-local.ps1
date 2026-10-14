param(
  [ValidateSet("up", "sync", "ai", "rebuild", "clean-build", "down", "reset", "logs", "ps")]
  [string]$Action = "up",

  # Use "all" for the first start. Select one application for routine iteration.
  [ValidateSet("all", "api", "web", "admin", "people")]
  [string]$Service = "all",

  # Default uses published GHCR images. Set this switch to build from the checked-out source.
  [switch]$BuildLocal,

  # Explicitly run an isolated Docker Ollama instead of reusing a host model.
  [switch]$DockerOllama,

  # Used only when pulling published GHCR images.
  [string]$ImageTag = "latest"
)

$ErrorActionPreference = "Stop"
$script:AiMode = ""
$script:AiModel = ""
Set-Location (Join-Path $PSScriptRoot "..")

if ($Action -in @("sync", "down", "reset", "logs", "ps") -and $Service -ne "all") {
  throw "-Service is supported for up/rebuild/clean-build only. '$Action' operates on the full Compose project."
}
if ($Action -eq "clean-build" -and -not $BuildLocal) {
  throw "'clean-build' is local-source only. Re-run with -BuildLocal."
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

# Windows PowerShell 5.1 compatible host port defaults.
# QuantaEdge owns its configured API port. Fail clearly if it is occupied; never silently move the API.
function Test-HostPortAvailable {
  param([Parameter(Mandatory = $true)][int]$Port)

  $listener = $null
  try {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
    $listener.Start()
    return $true
  } catch {
    return $false
  } finally {
    if ($null -ne $listener) {
      $listener.Stop()
    }
  }
}

$script:apiPort = "8080"
$env:API_PORT = $script:apiPort
$webPort = if ([string]::IsNullOrWhiteSpace($env:WEB_PORT)) { "3000" } else { $env:WEB_PORT }
$adminPort = if ([string]::IsNullOrWhiteSpace($env:ADMIN_PORT)) { "3001" } else { $env:ADMIN_PORT }
$peoplePort = if ([string]::IsNullOrWhiteSpace($env:PEOPLE_PORT)) { "3002" } else { $env:PEOPLE_PORT }

function Ensure-ApiPortAvailable {
  $preferredApiPort = 0
  if (-not [int]::TryParse([string]$script:apiPort, [ref]$preferredApiPort) -or $preferredApiPort -lt 1 -or $preferredApiPort -gt 65535) {
    throw "API_PORT must be an integer from 1 to 65535; received '$script:apiPort'."
  }

  # Pin Compose interpolation to the port checked here, overriding .env if needed.
  $env:API_PORT = [string]$preferredApiPort

  # Repeated startup is allowed when QuantaEdge API already owns this exact port.
  $composePsArgs = @($script:ComposeFiles) + @("ps", "-q", "api")
  $existingApiIds = & docker compose @composePsArgs 2>$null
  if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($existingApiIds -join ""))) {
    $existingApiId = ($existingApiIds | Select-Object -First 1).Trim()
    $existingHostPort = & docker inspect --format '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' $existingApiId 2>$null
    $existingHostPortNumber = 0
    if ([int]::TryParse([string]$existingHostPort, [ref]$existingHostPortNumber) -and $existingHostPortNumber -eq $preferredApiPort) {
      return
    }
  }

  if (-not (Test-HostPortAvailable -Port $preferredApiPort)) {
    $ownerDetails = @()
    try {
      $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $preferredApiPort -ErrorAction Stop)
      foreach ($listener in $listeners) {
        $processInfo = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        if ($null -ne $processInfo) {
          $ownerDetails += "$($processInfo.ProcessName) (PID $($listener.OwningProcess))"
        } else {
          $ownerDetails += "PID $($listener.OwningProcess)"
        }
      }
    } catch {
      # Windows can reserve/exclude a port without exposing a listener.
    }

    $ownerText = if ($ownerDetails.Count -gt 0) { ($ownerDetails | Select-Object -Unique) -join ", " } else { "another process or a Windows-reserved port" }
    throw "QuantaEdge requires API host port $preferredApiPort and will not switch to another port. It is unavailable because of $ownerText. Stop/disable the conflicting service, then retry."
  }
}

# Default is GHCR cache-first mode; -BuildLocal uses only the normal local Compose file.
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
    throw "AI tutor Compose command failed (exit code $exitCode). Inspect the actual failures with: docker compose -f docker-compose.yml -f docker-compose.ghcr.yml -f docker-compose.ai.yml logs --tail=200 ollama ollama-model-init redis api"
  }
}

function Invoke-HostAiCompose {
  param([Parameter(Mandatory = $true)][string[]]$ComposeArgs)

  $dockerArgs = @("-f", "docker-compose.yml")
  if (-not $BuildLocal) {
    $dockerArgs += @("-f", "docker-compose.ghcr.yml")
  }
  $dockerArgs += @("-f", "docker-compose.ai-host.yml") + $ComposeArgs
  & docker compose @dockerArgs
  $exitCode = $LASTEXITCODE
  if ($exitCode -ne 0) {
    throw "Host Ollama Compose command failed (exit code $exitCode). Inspect with: docker compose -f docker-compose.yml -f docker-compose.ghcr.yml -f docker-compose.ai-host.yml logs --tail=200 api redis"
  }
}

function Get-LocalOllamaModel {
  try {
    $tags = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSec 4 -ErrorAction Stop
  } catch {
    throw "Could not reach the existing Ollama API at http://127.0.0.1:11434/api/tags. Start Ollama and confirm 'ollama list' works. This avoids silently downloading a second model. To use the separate Docker-based Qwen model instead, add -DockerOllama to the ai action."
  }

  $availableModels = @($tags.models | ForEach-Object { [string]$_.name } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ($availableModels.Count -eq 0) {
    throw "Local Ollama is responding but has no installed models. Check 'ollama list', or rerun the ai action with -DockerOllama for the separate Qwen model."
  }

  if (-not [string]::IsNullOrWhiteSpace($env:QUANTAEDGE_OLLAMA_MODEL)) {
    if ($availableModels -notcontains $env:QUANTAEDGE_OLLAMA_MODEL) {
      throw "QUANTAEDGE_OLLAMA_MODEL='$($env:QUANTAEDGE_OLLAMA_MODEL)' is not in the local Ollama model list: $($availableModels -join ', ')."
    }
    return $env:QUANTAEDGE_OLLAMA_MODEL
  }

  $llamaModel = @($availableModels | Where-Object { $_ -match '(?i)^llama' } | Select-Object -First 1)
  if ($llamaModel.Count -gt 0) { return [string]$llamaModel[0] }
  return [string]$availableModels[0]
}

function Get-TargetArgs {
  if ($Service -eq "all") { return @() }
  return @($Service)
}

function Sync-PublishedImages {
  # Explicit sync refreshes application images only. Missing DB/cache images are fetched on first startup.
  Invoke-Compose @("pull", "api", "web", "admin", "people")
}

$targetArgs = @(Get-TargetArgs)

switch ($Action) {
  "sync" {
    if ($BuildLocal) { throw "'sync' updates GHCR images and cannot be combined with -BuildLocal." }
    Sync-PublishedImages
    Write-Host "Published QuantaEdge application images synced. Run the up action to start them."
    exit 0
  }
  "up" {
    if ($BuildLocal) {
      if ($Service -eq "all") {
        Invoke-Compose @("build", "--parallel")
        Ensure-ApiPortAvailable
        Invoke-Compose @("up", "-d", "--no-build", "--remove-orphans")
      } else {
        Invoke-Compose (@("build", "--parallel") + $targetArgs)
        Ensure-ApiPortAvailable
        Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
      }
    } else {
      Ensure-ApiPortAvailable
      Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
    }
  }
  "ai" {
    if ($DockerOllama) {
      # Explicit fallback: a separate Docker Ollama pulls qwen2.5:0.5b into its named volume.
      if ($BuildLocal) { Invoke-AiCompose @("build", "--parallel") }
      Ensure-ApiPortAvailable
      Invoke-AiCompose @("up", "-d", "--no-build", "--remove-orphans")
      $script:AiMode = "docker"
      $script:AiModel = "qwen2.5:0.5b"
    } else {
      # Default: reuse a model already installed in host Ollama; do not download a duplicate.
      $script:AiModel = Get-LocalOllamaModel
      $env:QUANTAEDGE_OLLAMA_MODEL = $script:AiModel
      if ($BuildLocal) { Invoke-HostAiCompose @("build", "--parallel") }
      Ensure-ApiPortAvailable
      Invoke-HostAiCompose @("up", "-d", "--no-build", "--remove-orphans")
      $script:AiMode = "host"
    }
  }
  "rebuild" {
    if ($BuildLocal) {
      # Cached local rebuild; --no-cache is reserved for clean-build.
      Invoke-Compose (@("build", "--parallel") + $targetArgs)
      Ensure-ApiPortAvailable
      Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
    } else {
      Sync-PublishedImages
      Ensure-ApiPortAvailable
      Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
    }
  }
  "clean-build" {
    Invoke-Compose (@("build", "--no-cache", "--parallel") + $targetArgs)
    Ensure-ApiPortAvailable
    Invoke-Compose (@("up", "-d", "--no-build", "--remove-orphans") + $targetArgs)
  }
  "down" {
    Invoke-Compose @("down")
    exit 0
  }
  "reset" {
    # WARNING: this intentionally deletes local database/cache volumes.
    Ensure-ApiPortAvailable
    Invoke-Compose @("down", "-v", "--remove-orphans")
    if ($BuildLocal) {
      Invoke-Compose @("build", "--parallel")
      Ensure-ApiPortAvailable
      Invoke-Compose @("up", "-d", "--no-build", "--remove-orphans")
    } else {
      Sync-PublishedImages
      Ensure-ApiPortAvailable
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
    Invoke-WebRequest -UseBasicParsing "http://localhost:$script:apiPort/actuator/health" | Out-Null
    Write-Host "API:    http://localhost:$script:apiPort/actuator/health"
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
      if ($script:AiMode -eq "host") {
        Write-Host "AI tutor mode: existing host Ollama model '$($script:AiModel)' via host.docker.internal:11434."
        Write-Host "If tutor requests fail with connection refused, see docs/ai/local-ai-tutor.md. Do not expose port 11434 to untrusted networks."
        Write-Host "AI tutor logs: docker compose -f docker-compose.yml -f docker-compose.ghcr.yml -f docker-compose.ai-host.yml logs --tail=200 api"
      } else {
        Write-Host "AI tutor mode: separate Docker Ollama / qwen2.5:0.5b (first model pull may take several minutes)."
        Write-Host "AI tutor logs: docker compose -f docker-compose.yml -f docker-compose.ghcr.yml -f docker-compose.ai.yml logs --tail=200 ollama ollama-model-init redis api"
      }
      Write-Host "Open a published lesson and select the tutor dock. Local model answers still require teacher review."
    }
    exit 0
  } catch {
    Start-Sleep -Seconds 2
  }
}
throw "API did not become healthy within $([int]($healthAttempts * 2)) seconds. Inspect services with: docker compose ps; for AI mode also run: docker compose -f docker-compose.yml -f docker-compose.ai.yml logs --tail=200 ollama ollama-model-init api"
