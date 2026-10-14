[CmdletBinding()]
param(
    [ValidateSet("DryRun", "ImportDraft", "ImportAndApprove")]
    [string]$Mode = "DryRun",
    [string]$ApiBaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
if ($env:QUANTAEDGE_API_BASE_URL) { $ApiBaseUrl = $env:QUANTAEDGE_API_BASE_URL }

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ExtractionRoot = Join-Path $RepoRoot "data\textbook-extractions\class-6"
$Importer = Join-Path $RepoRoot "scripts\import_ghcr_extracted_pages.py"
$ExpectedBookIds = @(
    "ncert-c6-hindi-fhgp1",
    "ncert-c6-hindi-fhcu1",
    "ncert-c6-english-fegp1",
    "ncert-c6-english-fecu1"
)

if (-not (Test-Path -LiteralPath $ExtractionRoot -PathType Container)) {
    throw "Class 6 extraction artifacts are missing. Pull main and check: $ExtractionRoot"
}
if (-not (Test-Path -LiteralPath $Importer -PathType Leaf)) {
    throw "The existing QuantaEdge GHCR page importer is missing: $Importer"
}

# Resolve Python without depending on PATH alone. This importer uses only the standard library.
$candidates = @()
foreach ($name in @("python.exe", "python3.exe")) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) {
        $candidates += [pscustomobject]@{ Exe = $command.Source; Prefix = @() }
    }
}
foreach ($path in @(
    "D:\Tools\Python312\python.exe",
    (Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\python.exe"),
    (Join-Path $env:LOCALAPPDATA "Programs\Python\Python311\python.exe"),
    (Join-Path $env:LOCALAPPDATA "Programs\Python\Python310\python.exe")
)) {
    if ($path -and (Test-Path -LiteralPath $path -PathType Leaf)) {
        $candidates += [pscustomobject]@{ Exe = $path; Prefix = @() }
    }
}
$launcher = Get-Command py.exe -ErrorAction SilentlyContinue
if ($launcher) {
    $candidates += [pscustomobject]@{ Exe = $launcher.Source; Prefix = @("-3") }
}

$PythonExe = $null
$PythonPrefix = @()
foreach ($candidate in $candidates) {
    try {
        $versionArgs = @()
        $versionArgs += $candidate.Prefix
        $versionArgs += @("-c", "import sys; raise SystemExit(0 if sys.version_info >= (3,10) else 1)")
        & $candidate.Exe @versionArgs *> $null
        if ($LASTEXITCODE -eq 0) {
            $PythonExe = $candidate.Exe
            $PythonPrefix = @($candidate.Prefix)
            break
        }
    } catch {
        # Try the next interpreter candidate, including a Windows Store execution alias.
    }
}
if (-not $PythonExe) {
    throw "Python 3.10+ was not found. Install Python 3.12 or set up D:\Tools\Python312\python.exe; then rerun this script."
}

$manifestFiles = @(Get-ChildItem -LiteralPath $ExtractionRoot -Recurse -File -Filter "*.manifest.json")
if ($manifestFiles.Count -ne $ExpectedBookIds.Count) {
    throw "Expected exactly four approved-pilot book manifests; found $($manifestFiles.Count). No import was attempted."
}

$records = @()
foreach ($file in $manifestFiles) {
    $manifest = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.scope -ne "SUBJECT_BOOK" -or
        $manifest.content_state -ne "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW") {
        throw "Refusing a non-complete-book or non-review-gated manifest: $($file.FullName)"
    }
    if ([string]$manifest.class -ne "6") {
        throw "Expected a Class 6 book, found '$($manifest.class)' in $($file.FullName)"
    }
    if ([string]$manifest.language -notin @("hindi", "english", "hi", "en")) {
        throw "Unsupported language metadata in $($file.FullName)"
    }
    $sourceHash = ([string]$manifest.original_book_sha256).ToLowerInvariant()
    $assetHash = ([string]$manifest.asset_pdf_sha256).ToLowerInvariant()
    if ($sourceHash -notmatch "^[0-9a-f]{64}$" -or $assetHash -ne $sourceHash) {
        throw "Original-book and extracted-PDF checksums do not match: $($file.FullName)"
    }
    $uri = $null
    if (-not [Uri]::TryCreate([string]$manifest.source_url, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne "https" -or $uri.UserInfo) {
        throw "Invalid HTTPS source URL in $($file.FullName)"
    }
    $officialHosts = @("ncert.nic.in", "ncert.ncert.org.in", "scert.bihar.gov.in", "bstbpc.gov.in")
    $hostAllowed = $false
    foreach ($officialHost in $officialHosts) {
        if ($uri.Host -eq $officialHost -or $uri.Host.EndsWith("." + $officialHost, [StringComparison]::OrdinalIgnoreCase)) {
            $hostAllowed = $true
            break
        }
    }
    if (-not $hostAllowed) {
        throw "Non-approved source host '$($uri.Host)' in $($file.FullName)"
    }
    $pageCount = [int]$manifest.page_count
    if ($pageCount -lt 1 -or $pageCount -gt 2000 -or [int]$manifest.completed_pages -ne $pageCount) {
        throw "Inconsistent page counts in $($file.FullName)"
    }
    $pagesName = [string]$manifest.text_jsonl
    if ([IO.Path]::GetFileName($pagesName) -ne $pagesName -or
        [IO.Path]::GetExtension($pagesName) -ne ".jsonl" -or
        -not (Test-Path -LiteralPath (Join-Path $file.DirectoryName $pagesName) -PathType Leaf)) {
        throw "Missing or unsafe sibling page JSONL in $($file.FullName)"
    }
    if (-not $manifest.book_id -or -not $manifest.source_title -or -not $manifest.edition) {
        throw "Missing book title, edition, or ID in $($file.FullName)"
    }
    $lowTextPages = 0
    if ($manifest.status_counts -and $manifest.status_counts.LOW_TEXT_REVIEW) {
        $lowTextPages = [int]$manifest.status_counts.LOW_TEXT_REVIEW
    }
    $records += [pscustomobject]@{
        BookId = [string]$manifest.book_id
        Title = [string]$manifest.source_title
        Subject = [string]$manifest.subject
        Language = [string]$manifest.language
        Edition = [string]$manifest.edition
        Pages = $pageCount
        LowTextPages = $lowTextPages
        SourceUrl = [string]$manifest.source_url
        ShaPrefix = $sourceHash.Substring(0, 12)
        ManifestPath = $file.FullName
    }
}

$actualBookIds = @($records | ForEach-Object { $_.BookId })
$missingIds = @($ExpectedBookIds | Where-Object { $_ -notin $actualBookIds })
$unexpectedIds = @($actualBookIds | Where-Object { $_ -notin $ExpectedBookIds })
if ($missingIds.Count -gt 0 -or $unexpectedIds.Count -gt 0 -or
    (@($actualBookIds | Select-Object -Unique).Count -ne $ExpectedBookIds.Count)) {
    throw "Class 6 pilot scope mismatch. Missing: $($missingIds -join ', '); unexpected: $($unexpectedIds -join ', '). No import was attempted."
}

Write-Host ""
Write-Host "QuantaEdge Class 6 GHCR extraction pilot"
Write-Host "Repository artifacts: $ExtractionRoot"
Write-Host "API: $ApiBaseUrl"
Write-Host ""
Write-Host "Verified source manifest summary:"
$records |
    Sort-Object Language, Subject |
    Select-Object BookId, Title, Subject, Language, Edition, Pages, LowTextPages, ShaPrefix |
    Format-Table -AutoSize
Write-Host "Total pages: $(($records | Measure-Object -Property Pages -Sum).Sum)"
Write-Host "Pages flagged for low text: $(($records | Measure-Object -Property LowTextPages -Sum).Sum)"
Write-Host "The source PDFs remain in GHCR; this command imports only extracted page text and metadata."

if ($Mode -ne "DryRun") {
    if (-not $env:QUANTAEDGE_ADMIN_SESSION) {
        throw "Set QUANTAEDGE_ADMIN_SESSION to the value of your authenticated QE_SESSION cookie before importing. Do not paste it into this chat."
    }
    try {
        $health = Invoke-RestMethod -Uri ($ApiBaseUrl.TrimEnd("/") + "/actuator/health") -TimeoutSec 8
        if ([string]$health.status -ne "UP") {
            throw "API health is not UP."
        }
    } catch {
        throw "QuantaEdge API is not healthy at $ApiBaseUrl. Start the local stack first, then retry. Details: $($_.Exception.Message)"
    }
}

if ($Mode -eq "ImportAndApprove") {
    Write-Host ""
    Write-Host "Source approval is a separate, audited action. Review each URL, edition, page count, and SHA prefix above."
    Write-Host "LOW_TEXT pages remain excluded from AI Tutor retrieval by the API quality filter."
    $confirmation = Read-Host "Type APPROVE CLASS 6 SOURCES to import and approve these four source books"
    if ($confirmation -cne "APPROVE CLASS 6 SOURCES") {
        Write-Host "Cancelled. No API changes were made."
        exit 0
    }
}

$arguments = @()
$arguments += $PythonPrefix
$arguments += @($Importer, "--extracted-root", $ExtractionRoot, "--api-base-url", $ApiBaseUrl)
if ($Mode -ne "DryRun") { $arguments += "--apply" }
if ($Mode -eq "ImportAndApprove") { $arguments += "--approve" }

Write-Host ""
Write-Host "Running existing QuantaEdge import pipeline in mode: $Mode"
& $PythonExe @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Textbook import pipeline failed with exit code $LASTEXITCODE. Fix the reported error before retrying."
}
