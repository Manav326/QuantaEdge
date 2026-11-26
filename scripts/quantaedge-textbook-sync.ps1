param(
    [ValidateSet("both", "hindi", "english")]
    [string]$Language = "both",
    [ValidateScript({ $_ -eq 0 -or ($_ -ge 6 -and $_ -le 12) })]
    [int]$ClassNo = 0
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot
$Owner = if ($env:QUANTAEDGE_GHCR_OWNER) { $env:QUANTAEDGE_GHCR_OWNER } else { "Manav326" }
$Owner = $Owner.ToLowerInvariant()
$CacheRoot = if ($env:QUANTAEDGE_TEXTBOOK_CACHE_DIR) { $env:QUANTAEDGE_TEXTBOOK_CACHE_DIR } else { Join-Path $RepoRoot "source-pdfs\registry-cache" }
$DraftRoot = if ($env:QUANTAEDGE_TEXTBOOK_DRAFT_DIR) { $env:QUANTAEDGE_TEXTBOOK_DRAFT_DIR } else { Join-Path $RepoRoot "private-review\textbook-library" }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker CLI is required to pull textbook images from GHCR." }
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { throw "Python 3.10+ is required." }
# Native Python writes an import traceback to stderr when PyMuPDF is absent.
# Under PowerShell's Stop preference that stderr can terminate the script before
# $LASTEXITCODE is checked. Temporarily make this probe non-terminating and merge
# stderr into the success stream so the expected failure stays quiet.
$previousErrorActionPreference = $ErrorActionPreference
$nativeCommandPreference = Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue
try {
    $ErrorActionPreference = "Continue"
    if ($nativeCommandPreference) {
        $PSNativeCommandUseErrorActionPreference = $false
    }

    python -c "import fitz" 2>&1 | Out-Null
    $fitzCheckExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
    if ($nativeCommandPreference) {
        Set-Variable -Name PSNativeCommandUseErrorActionPreference -Value $nativeCommandPreference.Value
    }
}

if ($fitzCheckExitCode -ne 0) {
    Write-Host "PyMuPDF is missing; installing it in the active Python environment..."
    python -m pip install --disable-pip-version-check "PyMuPDF>=1.24,<2"
    if ($LASTEXITCODE -ne 0) { throw "Could not install PyMuPDF." }
}

$Grades = if ($ClassNo -eq 0) { @(6..12) } else { @($ClassNo) }
$Media = if ($Language -eq "both") { @("hindi", "english") } else { @($Language) }
foreach ($Grade in $Grades) {
    foreach ($Medium in $Media) {
        $Image = "ghcr.io/$Owner/quantaedge-textbooks-class-$($Grade)-$($Medium):latest"
        $RegistryPath = Join-Path $CacheRoot "class-$($Grade)\$Medium"
        $OutputPath = Join-Path $DraftRoot "class-$($Grade)\$Medium"
        Write-Host "==> Pulling Class $Grade ($Medium) from $Image"
        python .\scripts\textbook_registry.py pull --image $Image --output-dir $RegistryPath
        if ($LASTEXITCODE -ne 0) { throw "Could not pull/checksum-verify Class $Grade ($Medium)." }
        Write-Host "==> Preparing draft assets for Class $Grade ($Medium)"
        python .\scripts\textbook_registry.py prepare-library --registry-dir $RegistryPath --output-dir $OutputPath
        if ($LASTEXITCODE -ne 0) { throw "Could not prepare Class $Grade ($Medium) draft assets." }
    }
}

Write-Host ""
Write-Host "Draft preparation finished. Inspect each class/medium prepare-report.json."
Write-Host "Whole books stay in GHCR. Importing, approving and publishing to students remain explicit review actions."
