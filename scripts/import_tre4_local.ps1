#requires -Version 5.1
<#
.SYNOPSIS
  Safely dry-runs or imports BPSC TRE Computer Science — Previous-Year Practice Series data into a local QuantaEdge PostgreSQL database.

.DESCRIPTION
  Runs the offline importer tests and source-archive validation first. By default it performs
  a dry run only. With -Apply it validates that the database URL targets localhost, asks for
  explicit confirmation, then calls scripts/import_tre4_course.py. The Python importer owns
  the database transaction and checks required migrations, including V199.
  Import always persists assessments, packs and the first course release in DRAFT. Optional -RepairDraftHindi
  reconstructs source Hindi prompts from the embedded SHREE-DEV PDF render via Tesseract Hindi OCR, but only
  for an existing all-DRAFT course graph whose question/answer review states are still pending.
  Optional -SubmitForReview is a separate lifecycle action and cannot be combined with -RepairDraftHindi.
  The script never marks tests/packs published or approves a release.

.EXAMPLE
  .\scripts\import_tre4_local.ps1
  Tests and validates the archive; no database writes.

.EXAMPLE
  $env:TRE4_DATABASE_URL = "postgresql://quantaedge:YOUR_LOCAL_DB_PASSWORD@localhost:5432/quantaedge"
  .\scripts\import_tre4_local.ps1 -Apply
  Remove-Item Env:TRE4_DATABASE_URL

.EXAMPLE
  .\scripts\import_tre4_local.ps1 -Apply -RepairDraftHindi
  Repairs only OCR-reconstructed Hindi prompts in the existing editable local draft. Requires Tesseract with Hindi trained data.
  
.EXAMPLE
  .\scripts\import_tre4_local.ps1 -Apply -SubmitForReview
  After all assessments and packs were human-reviewed and published through Admin Web, advances an existing DRAFT release to IN_QUALITY_REVIEW. A first import always persists DRAFT first.

#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$SubmitForReview,
    [switch]$RepairDraftHindi,
    [switch]$Yes,
    [string]$ArchivePath = "data/TRE-4_PYQ.zip",
    [string]$DatabaseUrl,
    [string]$PythonExe
)

$ErrorActionPreference = "Stop"
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$importer = Join-Path $repoRoot "scripts/import_tre4_course.py"
$testPath = Join-Path $repoRoot "tests"
$migration = Join-Path $repoRoot "services/api/src/main/resources/db/migration/V199__assessment_only_readiness_uses_test_packs.sql"
$sourceRegister = Join-Path $repoRoot "data/TRE-Papres/source-register.json"

function Stop-WithMessage {
    param([Parameter(Mandatory = $true)][string]$Message)
    throw $Message
}

function Invoke-CheckedPython {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $script:resolvedPython @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        Stop-WithMessage "Python command failed with exit code $exitCode. No import should be considered successful."
    }
}

function Test-PythonModule {
    param([Parameter(Mandatory = $true)][string]$ModuleName)
    & $script:resolvedPython -c "import importlib.util,sys; sys.exit(0 if importlib.util.find_spec('$ModuleName') else 1)" | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Ensure-PythonModule {
    param(
        [Parameter(Mandatory = $true)][string]$ModuleName,
        [Parameter(Mandatory = $true)][string]$InstallSpec
    )
    if (-not (Test-PythonModule -ModuleName $ModuleName)) {
        Write-Host "Installing missing Python dependency: $InstallSpec" -ForegroundColor Yellow
        Invoke-CheckedPython -Arguments @("-m", "pip", "install", "--disable-pip-version-check", $InstallSpec)
        if (-not (Test-PythonModule -ModuleName $ModuleName)) {
            Stop-WithMessage "Dependency '$ModuleName' is still unavailable after installation. Check the Python environment."
        }
    }
}

function Resolve-LocalDatabase {
    param([Parameter(Mandatory = $true)][string]$Value)

    $valueToCheck = $Value.Trim()
    if ($valueToCheck.StartsWith("jdbc:postgresql://", [System.StringComparison]::OrdinalIgnoreCase)) {
        $valueToCheck = $valueToCheck.Substring(5)
    }
    if ($valueToCheck -notmatch '^postgres(?:ql)?://(?:[^/@]+@)?(?<host>\[[^\]]+\]|[^:/?#]+)(?::(?<port>\d+))?/(?<database>[^/?#]+)') {
        Stop-WithMessage "The database URL is not a valid PostgreSQL URL. Use postgresql://user:password@localhost:5432/quantaedge."
    }

    $hostName = ($Matches["host"] -replace '^\[|\]$', '').ToLowerInvariant()
    $portText = $Matches["port"]
    if ([string]::IsNullOrWhiteSpace($portText)) { $portText = "5432" }
    $databaseName = [System.Uri]::UnescapeDataString($Matches["database"])

    $allowedLocalHosts = @("localhost", "127.0.0.1", "::1")
    if ($allowedLocalHosts -notcontains $hostName) {
        Stop-WithMessage "Refusing to import into '$hostName'. This runner is restricted to localhost/loopback so production or another remote database cannot be changed."
    }

    return [PSCustomObject]@{
        Url = $valueToCheck
        Host = $hostName
        Port = $portText
        Database = $databaseName
    }
}

try {
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    if (-not $gitCommand) {
        Stop-WithMessage "Git was not found. Run this script from a QuantaEdge Git checkout."
    }
    $gitRoot = (& git -C $repoRoot rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($gitRoot -join ""))) {
        Stop-WithMessage "Could not verify the QuantaEdge Git repository at $repoRoot."
    }
    if ([System.IO.Path]::GetFullPath(($gitRoot -join "").Trim()) -ne $repoRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)) {
        Stop-WithMessage "The script path is not inside the expected Git repository root."
    }

    foreach ($requiredPath in @($importer, $migration, $sourceRegister)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            Stop-WithMessage "Required file is missing: $requiredPath. Pull the latest main branch before importing."
        }
    }

    if ([System.IO.Path]::IsPathRooted($ArchivePath)) {
        $archiveFullPath = [System.IO.Path]::GetFullPath($ArchivePath)
    } else {
        $archiveFullPath = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $ArchivePath))
    }
    if (-not (Test-Path -LiteralPath $archiveFullPath -PathType Leaf)) {
        Stop-WithMessage "Source archive not found: $archiveFullPath"
    }

    if ([string]::IsNullOrWhiteSpace($PythonExe)) {
        $preferredPython = "D:\Tools\Python312\python.exe"
        if (Test-Path -LiteralPath $preferredPython -PathType Leaf) {
            $PythonExe = $preferredPython
        } else {
            $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
            if (-not $pythonCommand) {
                Stop-WithMessage "Python was not found. Install Python 3.10+ or pass -PythonExe with the python.exe path."
            }
            $PythonExe = $pythonCommand.Source
        }
    } elseif (-not (Test-Path -LiteralPath $PythonExe -PathType Leaf)) {
        $pythonCommand = Get-Command $PythonExe -ErrorAction SilentlyContinue
        if (-not $pythonCommand) {
            Stop-WithMessage "Python executable not found: $PythonExe"
        }
        $PythonExe = $pythonCommand.Source
    }
    $script:resolvedPython = $PythonExe

    $pythonVersion = & $script:resolvedPython -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
    if ($LASTEXITCODE -ne 0) {
        Stop-WithMessage "Unable to run Python: $script:resolvedPython"
    }
    $versionParts = $pythonVersion.Trim().Split(".")
    if ([int]$versionParts[0] -lt 3 -or ([int]$versionParts[0] -eq 3 -and [int]$versionParts[1] -lt 10)) {
        Stop-WithMessage "Python 3.10 or newer is required; detected $pythonVersion."
    }

    Write-Host ""
    Write-Host "QuantaEdge BPSC TRE Computer Science — Previous-Year Practice Series import preflight" -ForegroundColor Cyan
    Write-Host "Repository : $repoRoot"
    Write-Host "Python     : $script:resolvedPython ($pythonVersion)"
    Write-Host "Archive    : $archiveFullPath"
    Write-Host "Course     : BPSC TRE Computer Science — Previous-Year Practice Series [ASSESSMENT / ASSESSMENT_ONLY]"
    Write-Host "Hierarchy  : Course -> versioned test packs -> assessments -> ordered question snapshots"
    Write-Host "Safety     : no answer-key approval and no learner publication"

    # Source extraction needs PyMuPDF (the extractor accepts either pymupdf or fitz).
    if (-not (Test-PythonModule -ModuleName "pymupdf") -and -not (Test-PythonModule -ModuleName "fitz")) {
        Write-Host "Installing missing PDF dependency: pymupdf" -ForegroundColor Yellow
        Invoke-CheckedPython -Arguments @("-m", "pip", "install", "--disable-pip-version-check", "pymupdf")
    }

    Write-Host ""
    Write-Host "Running offline importer regression tests..." -ForegroundColor Cyan
    Invoke-CheckedPython -Arguments @("-m", "unittest", "discover", "-s", $testPath, "-p", "test_import_tre4_course.py")
    Invoke-CheckedPython -Arguments @("-m", "unittest", "discover", "-s", $testPath, "-p", "test_extract_tre_source_questions.py")

    Write-Host ""
    Write-Host "Validating the real source archive (dry run; no database writes)..." -ForegroundColor Cyan
    Invoke-CheckedPython -Arguments @($importer, "--archive", $archiveFullPath, "--register", $sourceRegister)

    if ($SubmitForReview -and -not $Apply) {
        Stop-WithMessage "-SubmitForReview requires -Apply."
    }
    if ($RepairDraftHindi -and -not $Apply) {
        Stop-WithMessage "-RepairDraftHindi requires -Apply; otherwise the script performs no database writes."
    }
    if ($RepairDraftHindi -and $SubmitForReview) {
        Stop-WithMessage "-RepairDraftHindi and -SubmitForReview are separate lifecycle actions and cannot be combined."
    }

    if ($RepairDraftHindi) {
        $tesseract = Get-Command tesseract -ErrorAction SilentlyContinue
        if (-not $tesseract) {
            Stop-WithMessage "Hindi draft repair needs Tesseract on PATH with Hindi trained data. Install Tesseract OCR and the hin language data before using -RepairDraftHindi."
        }
        $languages = & $tesseract.Source --list-langs 2>$null
        if ($LASTEXITCODE -ne 0 -or -not ($languages -contains "hin")) {
            Stop-WithMessage "Tesseract is installed but its Hindi language data 'hin' is missing. Install Hindi trained data before using -RepairDraftHindi."
        }
    } elseif (-not (Get-Command tesseract -ErrorAction SilentlyContinue)) {
        Write-Warning "Tesseract Hindi OCR is not installed. SHREE-DEV stems will remain raw and flagged; do not use that dry-run inventory to import unreadable Hindi."
    }

    if (-not $Apply) {
        Write-Host ""
        Write-Host "DRY RUN COMPLETE. No database connection or database writes were performed." -ForegroundColor Green
        Write-Host "To import locally, set TRE4_DATABASE_URL to your localhost PostgreSQL URL and rerun with -Apply."
        Write-Host 'Example: $env:TRE4_DATABASE_URL = "postgresql://quantaedge:YOUR_LOCAL_DB_PASSWORD@localhost:5432/quantaedge"'
        Write-Host ".\scripts\import_tre4_local.ps1 -Apply"
        Write-Host ".\scripts\import_tre4_local.ps1 -Apply -RepairDraftHindi  # only for an existing all-DRAFT course"
        Write-Host "The importer defaults to DRAFT. Add -SubmitForReview only after question review and assessment/test-pack publication have completed; readiness is rechecked by the database." 
        return
    }

    $candidateUrl = $DatabaseUrl
    if ([string]::IsNullOrWhiteSpace($candidateUrl)) { $candidateUrl = $env:TRE4_DATABASE_URL }
    if ([string]::IsNullOrWhiteSpace($candidateUrl)) { $candidateUrl = $env:SPRING_DATASOURCE_URL }
    if ([string]::IsNullOrWhiteSpace($candidateUrl)) {
        Stop-WithMessage "No database URL supplied. Set TRE4_DATABASE_URL for your local PostgreSQL instance; the script does not guess credentials."
    }

    $database = Resolve-LocalDatabase -Value $candidateUrl
    Ensure-PythonModule -ModuleName "psycopg" -InstallSpec "psycopg[binary]>=3.2,<4"

    Write-Host ""
    Write-Host "Target database: $($database.Host):$($database.Port)/$($database.Database)" -ForegroundColor Yellow
    Write-Host "The importer uses one transaction and refuses to proceed if required migrations/readiness functions are missing."
    if ($SubmitForReview) {
        Write-Host "Lifecycle guard: DRAFT -> IN_QUALITY_REVIEW is allowed only for an existing DRAFT release when database readiness confirms every assessment and pack is PUBLISHED and question/answer review gates pass. No approval or Marketplace publication is triggered." -ForegroundColor Yellow
    } else {
        Write-Host "Lifecycle guard: new assessments, test packs and a new course release remain DRAFT. No release submission, approval or Marketplace publication is triggered." -ForegroundColor Yellow
    }

    if (-not $Yes) {
        $confirmationWord = if ($RepairDraftHindi) { "REPAIR" } else { "IMPORT" }
        $confirmation = Read-Host "To write to this local database, type $confirmationWord (anything else cancels)"
        if ($confirmation -cne $confirmationWord) {
            Write-Host "Cancelled. The dry run completed, but no database writes were made." -ForegroundColor Yellow
            return
        }
    }

    $previousTre4Url = $env:TRE4_DATABASE_URL
    $hadPreviousTre4Url = Test-Path Env:TRE4_DATABASE_URL
    try {
        $env:TRE4_DATABASE_URL = $database.Url
        $applyArguments = @($importer, "--archive", $archiveFullPath, "--register", $sourceRegister, "--apply")
        if ($RepairDraftHindi) { $applyArguments += "--repair-draft-hindi" }
        if ($SubmitForReview) { $applyArguments += "--submit-for-review" }
        Write-Host ""
        Write-Host "Applying BPSC TRE Computer Science — Previous-Year Practice Series data to the confirmed local database..." -ForegroundColor Cyan
        Invoke-CheckedPython -Arguments $applyArguments
        Write-Host ""
        Write-Host "IMPORT/REQUEST SUCCEEDED. Read the JSON report above for actual assessment, pack and release states. OCR-reconstructed Hindi still requires human review; -RepairDraftHindi does not approve questions or publish content." -ForegroundColor Green
        Write-Host "This script did not publish questions or packs, perform HRMS quality approval, approve the origin release, or publish a Marketplace offer."
    } finally {
        if ($hadPreviousTre4Url) {
            $env:TRE4_DATABASE_URL = $previousTre4Url
        } else {
            Remove-Item Env:TRE4_DATABASE_URL -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Error $_.Exception.Message
    Write-Host "If the failure mentions V199 or a readiness function, start the local API against this database and confirm Flyway migrations completed, then rerun." -ForegroundColor Yellow
    exit 1
}
