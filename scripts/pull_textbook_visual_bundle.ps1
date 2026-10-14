[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BookSlug,
    [string]$IndexRoot = "data/textbook-visuals",
    [string]$DestinationRoot = "visual-bundles"
)

$ErrorActionPreference = "Stop"
if (-not $BookSlug -match '^[a-z0-9]+([.-][a-z0-9]+)*$') {
    throw "BookSlug must be a stable lowercase slug, e.g. class6-curiosity-en."
}
$repoRoot = Split-Path -Parent $PSScriptRoot
$indexPath = Join-Path (Join-Path $repoRoot $IndexRoot) (Join-Path $BookSlug "bundle-index.v1.json")
if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) {
    throw "Bundle index not found. Pull main first: $indexPath"
}
$index = Get-Content -LiteralPath $indexPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($index.schema_version -ne "quantaedge.textbook-visual-bundle-index.v1" -or
    $index.publication_allowed -ne $false -or $index.review_required -ne $true) {
    throw "Refusing an unknown or non-review-gated visual bundle index."
}
if (-not $index.ghcr_package -or -not $index.version_tag -or
    [string]$index.bundle_tar_sha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Bundle index is missing its package, immutable version tag, or checksum."
}
$oras = Get-Command oras -ErrorAction SilentlyContinue
if (-not $oras) {
    throw "ORAS CLI is required. Install it and authenticate to ghcr.io before running this script."
}

$ref = "$($index.ghcr_package):$($index.version_tag)"
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("quantaedge-visual-" + [guid]::NewGuid().ToString("N"))
$downloadRoot = Join-Path $tempRoot "download"
$extractRoot = Join-Path $tempRoot "extract"
New-Item -ItemType Directory -Path $downloadRoot, $extractRoot -Force | Out-Null
try {
    & $oras.Source pull $ref --output $downloadRoot
    if ($LASTEXITCODE -ne 0) { throw "ORAS pull failed for $ref." }
    $archives = @(Get-ChildItem -LiteralPath $downloadRoot -File -Filter "*.tar.gz")
    if ($archives.Count -ne 1) { throw "Expected exactly one tar.gz in the GHCR bundle; found $($archives.Count)." }
    $actualHash = (Get-FileHash -LiteralPath $archives[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne ([string]$index.bundle_tar_sha256).ToLowerInvariant()) {
        throw "Downloaded bundle SHA-256 mismatch. Expected $($index.bundle_tar_sha256), got $actualHash."
    }
    tar -xzf $archives[0].FullName -C $extractRoot
    if ($LASTEXITCODE -ne 0) { throw "Could not extract the downloaded visual bundle archive." }
    $bundleRoot = Join-Path $extractRoot "quantaedge-visual-bundle"
    if (-not (Test-Path -LiteralPath (Join-Path $bundleRoot "visual-inventory.v1.json") -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $bundleRoot "visual-analysis.v1.json") -PathType Leaf)) {
        throw "Downloaded bundle is missing required inventory or analysis sidecars."
    }
    $destination = Join-Path $repoRoot (Join-Path $DestinationRoot $BookSlug)
    if (Test-Path -LiteralPath $destination) {
        Remove-Item -LiteralPath $destination -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $bundleRoot -Destination $destination -Recurse -Force
    Write-Host "Restored verified visual bundle to: $destination"
    Write-Host "GHCR package: $ref"
    Write-Host "SHA-256: $actualHash"
    Write-Host "Reminder: these AI captions remain drafts requiring human review; they are not published lesson content."
}
finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
