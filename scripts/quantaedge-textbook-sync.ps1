param(
    [ValidateSet("both", "hindi", "english")]
    [string]$Language = "both"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Owner = if ($env:QUANTAEDGE_GHCR_OWNER) { $env:QUANTAEDGE_GHCR_OWNER } else { "Manav326" }
$Owner = $Owner.ToLowerInvariant()
$CacheRoot = if ($env:QUANTAEDGE_TEXTBOOK_CACHE_DIR) { $env:QUANTAEDGE_TEXTBOOK_CACHE_DIR } else { Join-Path $RepoRoot "source-pdfs\registry-cache" }
$DraftRoot = if ($env:QUANTAEDGE_TEXTBOOK_DRAFT_DIR) { $env:QUANTAEDGE_TEXTBOOK_DRAFT_DIR } else { Join-Path $RepoRoot "private-review\textbook-library" }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker CLI is required to pull textbook images from GHCR."
}
if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "Python 3.10+ is required. Install Python and retry."
}

python -c "import fitz" *> $null
if ($LASTEXITCODE -ne 0) {
    python -m pip install --disable-pip-version-check "PyMuPDF>=1.24,<2"
    if ($LASTEXITCODE -ne 0) { throw "Could not install PyMuPDF." }
}

foreach ($Medium in @("hindi", "english")) {
    if ($Language -ne "both" -and $Language -ne $Medium) { continue }
    $Image = "ghcr.io/$Owner/quantaedge-textbooks-$($Medium):latest"
    $RegistryPath = Join-Path $CacheRoot $Medium
    $OutputPath = Join-Path $DraftRoot $Medium

    Write-Host "==> Pulling $Medium registry from $Image"
    python .\scripts\textbook_registry.py pull --image $Image --output-dir $RegistryPath
    if ($LASTEXITCODE -ne 0) { throw "Could not pull or checksum-verify the $Medium registry." }

    Write-Host "==> Preparing draft library assets for $Medium"
    python .\scripts\textbook_registry.py prepare-library --registry-dir $RegistryPath --output-dir $OutputPath
    if ($LASTEXITCODE -ne 0) { throw "Could not prepare draft library assets for $Medium." }
}

Write-Host ""
Write-Host "Draft preparation finished. Inspect each prepare-report.json before importing."
Write-Host "Import is a separate, permission-protected review action; no content was published to students."
