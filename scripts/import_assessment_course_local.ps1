#requires -Version 5.1
<#
.SYNOPSIS
  Guided local import of one or more portable ASSESSMENT_ONLY course packages.

.DESCRIPTION
  Runs the offline importer regression tests first. Default mode is a dry run.
  -Apply writes to localhost PostgreSQL only and requires the importer to confirm the operation.
  The importer asks for missing course/package metadata interactively with examples; it never
  invents missing question content and never approves or publishes assessment content.

.EXAMPLE
  .\scripts\import_assessment_course_local.ps1

.EXAMPLE
  .\scripts\import_assessment_course_local.ps1 -ManifestPath "data\assessment-courses\course.json"

.EXAMPLE
  $env:ASSESSMENT_DATABASE_URL = "postgresql://quantaedge:YOUR_LOCAL_DB_PASSWORD@localhost:5432/quantaedge"
  .\scripts\import_assessment_course_local.ps1 -ManifestPath "data\assessment-courses\course.json" -Apply
  Remove-Item Env:ASSESSMENT_DATABASE_URL

.EXAMPLE
  .\scripts\import_assessment_course_local.ps1 -PackagePath "data\assessment-courses\paper1.json","data\assessment-courses\paper2.json" -Apply
#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$Yes,
    [string]$ManifestPath,
    [string[]]$PackagePath = @(),
    [string]$DatabaseUrl,
    [string]$PythonExe
)

$ErrorActionPreference = "Stop"
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$importer = Join-Path $repoRoot "scripts/import_assessment_course.py"
$testDirectory = Join-Path $repoRoot "tests"
$manifestSchema = Join-Path $repoRoot "schemas/assessment-course-manifest.schema.json"

function Stop-WithMessage {
    param([Parameter(Mandatory = $true)][string]$Message)
    throw $Message
}
function Invoke-CheckedPython {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $script:resolvedPython @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        Stop-WithMessage "Python command failed with exit code $exitCode."
    }
}
function Test-PythonModule {
    param([Parameter(Mandatory = $true)][string]$ModuleName)
    & $script:resolvedPython -c "import importlib.util,sys; sys.exit(0 if importlib.util.find_spec('$ModuleName') else 1)" | Out-Null
    return ($LASTEXITCODE -eq 0)
}
function Ensure-PythonModule {
    param([Parameter(Mandatory = $true)][string]$ModuleName,[Parameter(Mandatory = $true)][string]$InstallSpec)
    if (-not (Test-PythonModule -ModuleName $ModuleName)) {
        Write-Host "Installing missing Python dependency: $InstallSpec" -ForegroundColor Yellow
        Invoke-CheckedPython -Arguments @("-m","pip","install","--disable-pip-version-check",$InstallSpec)
    }
}

try {
    foreach ($required in @($importer,(Join-Path $testDirectory "test_import_assessment_course.py"),$manifestSchema)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            Stop-WithMessage "Required file is missing: $required. Pull the latest PR branch."
        }
    }
    if ([string]::IsNullOrWhiteSpace($PythonExe)) {
        $preferred = "D:\Tools\Python312\python.exe"
        if (Test-Path -LiteralPath $preferred -PathType Leaf) {
            $PythonExe = $preferred
        } else {
            $command = Get-Command python -ErrorAction SilentlyContinue
            if (-not $command) { Stop-WithMessage "Python 3.10+ was not found. Pass -PythonExe with the full path." }
            $PythonExe = $command.Source
        }
    } elseif (-not (Test-Path -LiteralPath $PythonExe -PathType Leaf)) {
        $command = Get-Command $PythonExe -ErrorAction SilentlyContinue
        if (-not $command) { Stop-WithMessage "Python executable was not found: $PythonExe" }
        $PythonExe = $command.Source
    }
    $script:resolvedPython = $PythonExe
    $pythonVersion = & $script:resolvedPython -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
    if ($LASTEXITCODE -ne 0) { Stop-WithMessage "Unable to run Python: $script:resolvedPython" }
    $parts = $pythonVersion.Trim().Split(".")
    if ([int]$parts[0] -lt 3 -or ([int]$parts[0] -eq 3 -and [int]$parts[1] -lt 10)) {
        Stop-WithMessage "Python 3.10 or newer is required; detected $pythonVersion."
    }

    Write-Host ""
    Write-Host "QuantaEdge generic assessment-course import" -ForegroundColor Cyan
    Write-Host "Repository : $repoRoot"
    Write-Host "Python     : $script:resolvedPython ($pythonVersion)"
    Write-Host "Mode       : $(if ($Apply) { 'APPLY (localhost only; explicit confirmation required)' } else { 'DRY RUN (no database writes)' })"
    Write-Host "Safety     : classless ASSESSMENT_ONLY · one transaction · all content starts DRAFT"
    Write-Host ""
    Write-Host "Running importer regression tests..." -ForegroundColor Cyan
    Invoke-CheckedPython -Arguments @("-m","unittest","discover","-s",$testDirectory,"-p","test_import_assessment_course.py")

    $arguments = @($importer)
    if (-not [string]::IsNullOrWhiteSpace($ManifestPath)) {
        $manifestFull = if ([System.IO.Path]::IsPathRooted($ManifestPath)) { $ManifestPath } else { Join-Path $repoRoot $ManifestPath }
        $arguments += @("--manifest",$manifestFull)
    }
    foreach ($package in $PackagePath) {
        $packageFull = if ([System.IO.Path]::IsPathRooted($package)) { $package } else { Join-Path $repoRoot $package }
        $arguments += @("--package",$packageFull)
    }
    if ($Apply) {
        Ensure-PythonModule -ModuleName "psycopg" -InstallSpec "psycopg[binary]>=3.2,<4"
        $candidate = $DatabaseUrl
        if ([string]::IsNullOrWhiteSpace($candidate)) { $candidate = $env:ASSESSMENT_DATABASE_URL }
        if ([string]::IsNullOrWhiteSpace($candidate)) { $candidate = $env:SPRING_DATASOURCE_URL }
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            Stop-WithMessage 'Set ASSESSMENT_DATABASE_URL to localhost PostgreSQL or pass -DatabaseUrl. The password is not guessed.'
        }
        # The Python importer independently validates the URL and refuses non-loopback hosts.
        $arguments += @("--database-url",$candidate,"--apply")
        if ($Yes) { $arguments += "--yes" }
    } elseif ($Yes) {
        Stop-WithMessage "-Yes is meaningful only together with -Apply."
    }
    Write-Host ""
    Write-Host "Validating packages and preparing the import plan..." -ForegroundColor Cyan
    Invoke-CheckedPython -Arguments $arguments
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
