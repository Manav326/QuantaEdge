[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$CourseId,

    [ValidateSet("Preflight", "ApplyDrafts")]
    [string]$Mode = "Preflight",

    [string]$ApiUrl = $(if ($env:QUANTAEDGE_API_BASE_URL) { $env:QUANTAEDGE_API_BASE_URL } else { "http://localhost:8080" }),

    [string]$WorkDirectory = "",

    [string]$ReviewedPreflightReport = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$SyncScript = Join-Path $RepoRoot "scripts\quantaedge-textbook-sync.ps1"
$PdfImporter = Join-Path $RepoRoot "scripts\import_official_ncert_books_and_chapters.py"
$PageImporter = Join-Path $RepoRoot "scripts\import_ghcr_extracted_pages.py"
$MapBuilder = Join-Path $RepoRoot "scripts\build_chapter_map_review_candidates.py"
$ExtractionRoot = Join-Path $RepoRoot "data\textbook-extractions\class-6"
$CacheRoot = if ($env:QUANTAEDGE_TEXTBOOK_CACHE_DIR) { $env:QUANTAEDGE_TEXTBOOK_CACHE_DIR } else { Join-Path $RepoRoot "source-pdfs\registry-cache" }
$CourseRoot = Join-Path $RepoRoot "private-review\course-content\course-$CourseId"
$Stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$ReportDirectory = Join-Path $RepoRoot "reports\ncert-pdf-imports\course-$CourseId"
$ReportRelative = "reports\ncert-pdf-imports\course-$CourseId\course-textbook-split-$Stamp.json"
$CandidateDirectory = Join-Path $CourseRoot "chapter-map-candidates\$Stamp"
$ManifestFiles = @(
    @{ Language = "english"; BookId = "ncert-c6-english-fegp1"; Filename = "NCERT_Class_6_Maths_English_BOOK.pdf" },
    @{ Language = "english"; BookId = "ncert-c6-english-fecu1"; Filename = "NCERT_Class_6_Science_English_BOOK.pdf" },
    @{ Language = "hindi";   BookId = "ncert-c6-hindi-fhgp1";   Filename = "NCERT_Class_6_Maths_Hindi_BOOK.pdf" },
    @{ Language = "hindi";   BookId = "ncert-c6-hindi-fhcu1";   Filename = "NCERT_Class_6_Science_Hindi_BOOK.pdf" }
)

$PythonExe = $null
$candidates = @()
$pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
if ($pythonCommand) { $candidates += $pythonCommand.Source }
foreach ($candidate in @(
    "D:\Tools\Python312\python.exe",
    (Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\python.exe"),
    (Join-Path $env:LOCALAPPDATA "Programs\Python\Python311\python.exe")
)) {
    if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { $candidates += $candidate }
}
foreach ($candidate in $candidates) {
    try {
        & $candidate -c "import sys; raise SystemExit(0 if sys.version_info >= (3,10) else 1)" *> $null
        if ($LASTEXITCODE -eq 0) { $PythonExe = $candidate; break }
    } catch { }
}
if (-not $PythonExe) { throw "Python 3.10+ was not found. Expected python.exe on PATH or D:\Tools\Python312\python.exe." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker CLI is required to reuse the existing GHCR textbook cache." }
foreach ($required in @($SyncScript, $PdfImporter, $PageImporter, $MapBuilder)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required existing pipeline file is missing: $required" }
}
$ReviewedReportPath = ""
if ($Mode -eq "ApplyDrafts") {
    if ([string]::IsNullOrWhiteSpace($ReviewedPreflightReport)) {
        throw "ApplyDrafts requires -ReviewedPreflightReport pointing to the successful preflight JSON you reviewed."
    }
    $ReviewedReportPath = if ([IO.Path]::IsPathRooted($ReviewedPreflightReport)) {
        $ReviewedPreflightReport
    } else {
        Join-Path $RepoRoot $ReviewedPreflightReport
    }
    if (-not (Test-Path -LiteralPath $ReviewedReportPath -PathType Leaf)) {
        throw "Reviewed preflight report not found: $ReviewedReportPath"
    }
} elseif (-not [string]::IsNullOrWhiteSpace($ReviewedPreflightReport)) {
    throw "-ReviewedPreflightReport is only valid with -Mode ApplyDrafts."
}

$PythonDirectory = Split-Path -Parent $PythonExe
$OriginalPath = $env:PATH
$env:PATH = $PythonDirectory + ";" + $env:PATH
$PreviousLocation = Get-Location
$AskedForSession = $false

try {
    Set-Location $RepoRoot

    if (-not $env:QUANTAEDGE_ADMIN_SESSION) {
        $secure = Read-Host "Paste the current QE_STAFF_SESSION access-cookie VALUE for Education Admin (input hidden)" -AsSecureString
        $pointer = [IntPtr]::Zero
        try {
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
            if ($value.Length -ne 43) { throw "Cookie length is invalid. Paste only the access-cookie value, not 'QE_STAFF_SESSION='." }
            $env:QUANTAEDGE_ADMIN_SESSION = $value
            $AskedForSession = $true
        } finally {
            if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
            Remove-Variable secure, value, pointer -ErrorAction SilentlyContinue
        }
    }

    if ([string]::IsNullOrWhiteSpace($WorkDirectory)) {
        $BooksDirectory = Join-Path $RepoRoot "private-review\course-source-pdfs\course-$CourseId\books"
    } else {
        $BooksDirectory = if ([IO.Path]::IsPathRooted($WorkDirectory)) { Join-Path $WorkDirectory "books" } else { Join-Path (Join-Path $RepoRoot $WorkDirectory) "books" }
    }
    New-Item -ItemType Directory -Force -Path $BooksDirectory | Out-Null

    Write-Host ""
    Write-Host "QuantaEdge additive course textbook pipeline"
    Write-Host "Course ID: $CourseId (numeric identity; course name/code are not used as keys)"
    Write-Host "Mode: $Mode"
    Write-Host "Source curriculum grade: 6 (independent of learner eligibility)"
    Write-Host ""

    Write-Host "Step 1/5: pulling and checksum-verifying the existing four whole-book GHCR assets."
    & $SyncScript -ClassNo 6 -Language both
    if ($LASTEXITCODE -ne 0) { throw "Existing textbook sync returned exit code $LASTEXITCODE." }

    Write-Host "Step 2/5: matching the cached whole PDFs to the committed extraction manifests."
    $ManifestPaths = @{}
    foreach ($entry in $ManifestFiles) {
        $registryDirectory = Join-Path $CacheRoot ("class-6\" + $entry.Language)
        $indexPath = Join-Path $registryDirectory "index.json"
        if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) { throw "GHCR cache index is missing: $indexPath" }
        $index = Get-Content -LiteralPath $indexPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $indexed = @($index.books | Where-Object { $_.book_id -eq $entry.BookId })
        if ($indexed.Count -ne 1) { throw "Expected exactly one GHCR index entry for $($entry.BookId); found $($indexed.Count)." }
        $expectedHash = ([string]$indexed[0].sha256).ToLowerInvariant()
        if ($expectedHash -notmatch "^[0-9a-f]{64}$") { throw "Invalid GHCR SHA-256 for $($entry.BookId)." }
        $relative = [string]$indexed[0].file
        if ($relative -ne ("books/" + $entry.BookId + ".pdf")) { throw "Unexpected PDF path in GHCR index for $($entry.BookId)." }
        $cachedPdf = Join-Path $registryDirectory ($relative -replace "/", "\")
        if (-not (Test-Path -LiteralPath $cachedPdf -PathType Leaf)) { throw "Checksum-verified whole-book PDF is missing: $cachedPdf" }
        $cachedHash = (Get-FileHash -LiteralPath $cachedPdf -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($cachedHash -ne $expectedHash) { throw "Local cached PDF checksum mismatch for $($entry.BookId)." }

        $manifestDirectory = Join-Path $RepoRoot ("data\textbook-extractions\class-6\" + $entry.Language + "\pages")
        $matchedManifests = @()
        foreach ($manifestFile in Get-ChildItem -LiteralPath $manifestDirectory -Filter "*.manifest.json" -File) {
            $manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
            if ([string]$manifest.book_id -eq $entry.BookId) {
                $matchedManifests += [pscustomobject]@{ Path = $manifestFile.FullName; Data = $manifest }
            }
        }
        if ($matchedManifests.Count -ne 1) { throw "Expected one committed source manifest for $($entry.BookId); found $($matchedManifests.Count)." }
        $ManifestPaths[$entry.BookId] = $matchedManifests[0].Path
        $sourceHash = ([string]$matchedManifests[0].Data.original_book_sha256).ToLowerInvariant()
        $assetHash = ([string]$matchedManifests[0].Data.asset_pdf_sha256).ToLowerInvariant()
        if ($sourceHash -ne $expectedHash -or $assetHash -ne $expectedHash) {
            throw "GHCR PDF checksum does not match the committed source manifest for $($entry.BookId)."
        }
        $manifestJsonl = [string]$matchedManifests[0].Data.text_jsonl
        if ([IO.Path]::GetFileName($manifestJsonl) -ne $manifestJsonl -or
            -not (Test-Path -LiteralPath (Join-Path $manifestDirectory $manifestJsonl) -PathType Leaf)) {
            throw "Committed page JSONL is missing or unsafe for $($entry.BookId)."
        }

        $destination = Join-Path $BooksDirectory $entry.Filename
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            $existingHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($existingHash -ne $expectedHash) {
                throw "Refusing to overwrite a different source PDF at $destination. Use a new -WorkDirectory to stage a new edition."
            }
        } else {
            Copy-Item -LiteralPath $cachedPdf -Destination $destination
        }
        Write-Host ("  VERIFIED {0} | {1} pages | SHA-256 {2}" -f $entry.BookId, $matchedManifests[0].Data.page_count, $expectedHash.Substring(0,12))
    }

    Write-Host "Step 3/5: validating all four extraction JSONL files with the existing importer (dry run)."
    $pageDryArgs = @(
        $PageImporter, "--extracted-root", $ExtractionRoot,
        "--api-base-url", $ApiUrl, "--course-id", [string]$CourseId
    )
    & $PythonExe @pageDryArgs
    if ($LASTEXITCODE -ne 0) { throw "Existing GHCR extracted-page preflight failed with exit code $LASTEXITCODE." }

    $splitArgs = @(
        $PdfImporter, "--root", $RepoRoot, "--books-dir", $BooksDirectory,
        "--languages", "english", "hindi", "--api-url", $ApiUrl,
        "--course-id", [string]$CourseId, "--grades", "6", "--report", $ReportRelative
    )
    if ($Mode -eq "ApplyDrafts") {
        Write-Host ""
        Write-Host "APPLY MODE: the existing importer will compare its freshly rebuilt plan with your reviewed preflight report before the first API write."
        Write-Host "The importer will create DRAFT PDF library assets and DRAFT document assignments only."
        Write-Host "The GHCR page importer will create DRAFT source-book/page records only."
        Write-Host "No source book is approved and no lesson, question, test or course content is published."
        Write-Host "Reviewed preflight report: $ReviewedReportPath"
        $confirmation = Read-Host "Type CREATE DRAFT ASSIGNMENTS to continue"
        if ($confirmation -cne "CREATE DRAFT ASSIGNMENTS") {
            Write-Host "Cancelled. No API writes were requested."
            return
        }
        $splitArgs += @("--apply", "--require-matching-preflight", $ReviewedReportPath)
    }

    Write-Host "Step 4/5: running the existing title-verified PDF splitter and import pipeline."
    & $PythonExe @splitArgs
    if ($LASTEXITCODE -ne 0) { throw "Existing whole-book/chapter splitter failed with exit code $LASTEXITCODE. Inspect its report and rerun only after resolving the stated issue." }
    $reportPath = Join-Path $RepoRoot $ReportRelative
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { throw "Splitter report was not produced: $reportPath" }

    Write-Host "Step 5/5: creating four non-publishable chapter-map review candidates from the splitter report."
    New-Item -ItemType Directory -Force -Path $CandidateDirectory | Out-Null
    foreach ($entry in $ManifestFiles) {
        $manifestPath = $ManifestPaths[$entry.BookId]
        if (-not $manifestPath -or -not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Expected source manifest missing for $($entry.BookId)." }
        $candidatePath = Join-Path $CandidateDirectory ($entry.BookId + ".review-candidate.json")
        & $PythonExe $MapBuilder --manifest $manifestPath --split-report $reportPath --course-id $CourseId --output $candidatePath
        if ($LASTEXITCODE -ne 0) { throw "Review candidate generation failed for $($entry.BookId)." }
    }

    if ($Mode -eq "ApplyDrafts") {
        Write-Host "Importing the page-text JSONL through the existing draft-only GHCR importer."
        $pageApplyArgs = @(
            $PageImporter, "--extracted-root", $ExtractionRoot, "--api-base-url", $ApiUrl,
            "--course-id", [string]$CourseId, "--auth-mode", "cookie", "--apply"
        )
        & $PythonExe @pageApplyArgs
        if ($LASTEXITCODE -ne 0) { throw "PDF assignments were staged, but the page-text draft importer failed. Inspect its report and rerun the import idempotently after resolving the error." }
    }

    Write-Host ""
    Write-Host "PIPELINE COMPLETE: source assets and split ranges are verified; generated chapter maps remain DRAFT_REQUIRES_EDITOR_REVIEW."
    Write-Host "Splitter report: $reportPath"
    Write-Host "Chapter-map candidates: $CandidateDirectory"
    if ($Mode -eq "Preflight") {
        Write-Host "Mode was preflight: no application-data writes were made. Re-run with -Mode ApplyDrafts only after reviewing this report."
    } else {
        Write-Host "DRAFT assets, assignments and source-page records were imported. Approval/publication and lesson/question review are still separate actions."
    }
} finally {
    Set-Location $PreviousLocation
    $env:PATH = $OriginalPath
    if ($AskedForSession) { Remove-Item Env:\QUANTAEDGE_ADMIN_SESSION -ErrorAction SilentlyContinue }
}
