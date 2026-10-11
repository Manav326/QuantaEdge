[CmdletBinding()]
param(
    [int]$PilotLimit = 0,
    [switch]$SkipHindiSourceImport,
    [string]$ApiBaseUrl = "http://localhost:8080",
    [string]$PythonExe = "D:\Tools\Python312\python.exe"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$CatalogPath = Join-Path $RepoRoot "QuantaEdge_Classes6-8_Hindi_Practice_Banks_ALL.json"
$QueuePath = Join-Path $RepoRoot "translation-queue.jsonl"
$TranslationPath = Join-Path $RepoRoot "english-translation-map-complete.jsonl"
$SourceImporter = Join-Path $RepoRoot "scripts\import_quantaedge_practice_banks.py"
$TranslationImporter = Join-Path $RepoRoot "scripts\import_quantaedge_offline_translations.py"

$OldAdminSession = $env:QUANTAEDGE_ADMIN_SESSION
$OldQeSession = $env:QE_SESSION

function Invoke-CheckedPython {
    param([string[]]$Arguments)
    & $PythonExe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Python command failed with exit code ${LASTEXITCODE}: $PythonExe $($Arguments -join ' ')"
    }
}

try {
    if (-not (Test-Path -LiteralPath $PythonExe -PathType Leaf)) {
        throw "Python was not found at '$PythonExe'. Pass -PythonExe if your Python 3.12 path differs."
    }
    $requiredFiles = @($QueuePath, $TranslationPath, $TranslationImporter)
    if (-not $SkipHindiSourceImport) {
        $requiredFiles += @($CatalogPath, $SourceImporter)
    }
    foreach ($path in $requiredFiles) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Required file not found: $path. Pull main and put the translation JSONL files and source catalog in the repository root."
        }
    }
    if ($PilotLimit -lt 0) {
        throw "-PilotLimit must be 0 (full import) or a positive number."
    }
    if (-not $ApiBaseUrl.StartsWith("http://localhost") -and
        -not $ApiBaseUrl.StartsWith("http://127.0.0.1")) {
        throw "This script reads IDs from local Docker Postgres; ApiBaseUrl must point to localhost."
    }

    $session = $env:QUANTAEDGE_ADMIN_SESSION
    if ([string]::IsNullOrWhiteSpace($session)) { $session = $env:QE_SESSION }
    if ([string]::IsNullOrWhiteSpace($session)) {
        $secure = Read-Host "Paste the fresh QE_STAFF_SESSION cookie value (value only; input is hidden)" -AsSecureString
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            $session = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
        }
        finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        }
    }
    if ([string]::IsNullOrWhiteSpace($session)) {
        throw "A fresh QE_STAFF_SESSION cookie is required to call the protected Admin API."
    }
    $env:QUANTAEDGE_ADMIN_SESSION = $session
    $env:QE_SESSION = $session

    Push-Location $RepoRoot
    try {
        Write-Host ""
        Write-Host "[1/4] Starting the database, API, and Admin services..."
        & docker compose up -d --build postgres api admin
        if ($LASTEXITCODE -ne 0) { throw "docker compose failed with exit code ${LASTEXITCODE}." }

        Write-Host ""
        Write-Host "[2/4] Checking API health..."
        $health = & curl.exe -fsS "$ApiBaseUrl/actuator/health"
        if ($LASTEXITCODE -ne 0) { throw "API health check failed at $ApiBaseUrl/actuator/health." }
        Write-Host $health

        Write-Host ""
        Write-Host "[Auth] Validating the staff session against the running API..."
        $authUrl = $ApiBaseUrl.TrimEnd([char]47) + "/api/v1/auth/me"
        $authHeaders = @{
            Accept = "application/json"
            Cookie = "QE_STAFF_SESSION=$session"
            "X-QE-Workspace" = "STAFF"
        }
        try {
            $authCheck = Invoke-RestMethod -Uri $authUrl -Method Get -Headers $authHeaders -TimeoutSec 15
        }
        catch {
            # Access cookies expire after 15 minutes; a saved token may expire during a Docker rebuild.
            Write-Warning "The saved staff session was rejected or expired. Sign in at http://localhost:3001 and copy the current QE_STAFF_SESSION value."
            $secureRetry = Read-Host "Fresh QE_STAFF_SESSION value (hidden)" -AsSecureString
            $retryPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureRetry)
            try { $session = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($retryPtr) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($retryPtr) }
            if ([string]::IsNullOrWhiteSpace($session)) { throw "No staff session value was supplied; stopped before writing any content." }
            $env:QUANTAEDGE_ADMIN_SESSION = $session
            $env:QE_SESSION = $session
            $authHeaders.Cookie = "QE_STAFF_SESSION=$session"
            try {
                $authCheck = Invoke-RestMethod -Uri $authUrl -Method Get -Headers $authHeaders -TimeoutSec 15
            }
            catch {
                $httpStatus = "connection error"
                $detail = $_.Exception.Message
                if ($_.Exception.Response) {
                    try { $httpStatus = [string][int]$_.Exception.Response.StatusCode } catch {}
                    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $detail = $_.ErrorDetails.Message }
                }
                throw "Staff-session validation failed (HTTP $httpStatus): $detail. No content was written."
            }
        }
        if ($authCheck.authenticated -ne $true -or [int]$authCheck.staffId -le 0) { throw "The API did not confirm an authenticated staff account. No content was written." }
        Write-Host "Staff authentication confirmed for role $($authCheck.role)." -ForegroundColor Green

        if (-not $SkipHindiSourceImport) {
            Write-Host ""
            Write-Host "[3/4] Validating Hindi source catalog, then importing missing Hindi questions as DRAFTs..."
            Invoke-CheckedPython @($SourceImporter, "--package", $CatalogPath)
            Invoke-CheckedPython @($SourceImporter, "--package", $CatalogPath, "--apply")
        }
        else {
            Write-Host ""
            Write-Host "[3/4] Skipping Hindi source import as requested."
        }

        Write-Host ""
        Write-Host "[4/4] Creating English translation DRAFTs (no OpenAI key required)..."
        $translateArgs = @($TranslationImporter, "--queue", $QueuePath, "--translations", $TranslationPath,
                           "--api-base-url", $ApiBaseUrl, "--apply", "--delay", "0.03")
        if ($PilotLimit -gt 0) { $translateArgs += @("--limit", "$PilotLimit") }
        Invoke-CheckedPython $translateArgs

        Write-Host ""
        Write-Host "Completed. All imported records are DRAFT; nothing was published."
        Write-Host "Review English content at http://localhost:3001/translations."
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:QUANTAEDGE_ADMIN_SESSION = $OldAdminSession
    $env:QE_SESSION = $OldQeSession
    $session = $null
    $secure = $null
}
