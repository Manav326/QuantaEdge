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

$script:RunId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$script:RunStartedAt = Get-Date
$script:ReportDirectory = Join-Path $RepoRoot 'reports\offline-translation-imports'
$script:RunReportPath = Join-Path $script:ReportDirectory "bundle-import-run-$($script:RunId).json"
$script:RunLogPath = Join-Path $script:ReportDirectory "bundle-import-run-$($script:RunId).log"
$script:RunStatus = 'RUNNING'
$script:RunError = $null
$script:StageRecords = New-Object System.Collections.ArrayList
$script:CurrentStage = $null
$script:TranscriptStarted = $false
$script:AuthConfirmed = $false
$script:SourceApplyStarted = $false
$script:TranslationApplyStarted = $false

function Start-QeRunStage {
    param([Parameter(Mandatory = $true)][string]$Name)
    $stage = [pscustomobject]@{ name=$Name; status='RUNNING'; started_at=(Get-Date).ToString('o'); ended_at=$null; duration_seconds=$null; exit_code=$null; message='' }
    [void]$script:StageRecords.Add($stage)
    $script:CurrentStage = $stage
    Write-Host "=== START: $Name ==="
    return $stage
}

function Complete-QeRunStage {
    param([Parameter(Mandatory = $true)]$Stage, [Parameter(Mandatory = $true)][string]$Status, [string]$Message='', [int]$ExitCode=-999)
    $Stage.status = $Status
    $Stage.message = $Message
    $Stage.ended_at = (Get-Date).ToString('o')
    $Stage.duration_seconds = [math]::Round(((Get-Date) - [datetime]$Stage.started_at).TotalSeconds, 2)
    if ($ExitCode -ne -999) { $Stage.exit_code = $ExitCode }
    if ($script:CurrentStage -eq $Stage) { $script:CurrentStage = $null }
    Write-Host "=== $Status - $($Stage.name) ($($Stage.duration_seconds)s) ==="
}

function Get-QeChildReportSummaries {
    $files = @()
    $candidateDirs = @($script:ReportDirectory, (Join-Path $RepoRoot 'QuantaEdge_Bilingual_Draft_Bundle'))
    foreach ($dir in $candidateDirs) {
        if (Test-Path -LiteralPath $dir -PathType Container) {
            $files += Get-ChildItem -LiteralPath $dir -Filter '*.json' -File -ErrorAction SilentlyContinue |
                Where-Object { $_.LastWriteTime -ge $script:RunStartedAt.AddSeconds(-3) -and $_.FullName -ne $script:RunReportPath }
        }
    }
    $summaries = @()
    foreach ($file in ($files | Sort-Object LastWriteTime -Unique)) {
        try {
            $doc = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
            $summaries += [pscustomobject]@{
                path = $file.FullName
                status = if ($doc.status) { $doc.status } elseif ($doc.mode) { $doc.mode } else { 'REPORT_WRITTEN' }
                summary = $doc.summary
                created_drafts = $doc.created_drafts
                api_error_count = $doc.api_error_count
                failed_batches = $doc.failed_batches
                mapping_preflight = $doc.mapping_preflight
                added_as_draft = $doc.added_as_draft
                skipped_duplicate = $doc.skipped_duplicate
                fatal_error = $doc.fatal_error
            }
        } catch {
            $summaries += [pscustomobject]@{ path=$file.FullName; status='REPORT_PARSE_FAILED'; error=$_.Exception.Message }
        }
    }
    return ,$summaries
}

function Save-QeRunReport {
    try {
        New-Item -ItemType Directory -Path $script:ReportDirectory -Force | Out-Null
        $report = [ordered]@{
            schema = 'quantaedge.offline-translation-bundle-run.v1'
            run_id = $script:RunId
            status = $script:RunStatus
            started_at = $script:RunStartedAt.ToString('o')
            finished_at = (Get-Date).ToString('o')
            duration_seconds = [math]::Round(((Get-Date) - $script:RunStartedAt).TotalSeconds, 2)
            configuration = @{ pilot_limit=$PilotLimit; skip_hindi_source_import=[bool]$SkipHindiSourceImport; api_base_url=$ApiBaseUrl; source_catalog=[IO.Path]::GetFileName($CatalogPath); translation_queue=[IO.Path]::GetFileName($QueuePath); translation_map=[IO.Path]::GetFileName($TranslationPath) }
            stages = @($script:StageRecords | ForEach-Object { @{ name=$_.name; status=$_.status; started_at=$_.started_at; ended_at=$_.ended_at; duration_seconds=$_.duration_seconds; exit_code=$_.exit_code; message=$_.message } })
            child_report_summaries = @(Get-QeChildReportSummaries)
            safety = @{ staff_authentication_confirmed=[bool]$script:AuthConfirmed; source_apply_started=[bool]$script:SourceApplyStarted; translation_apply_started=[bool]$script:TranslationApplyStarted; publication_attempted=$false; draft_only_workflow=$true; cookie_value_recorded=$false }
            error = $script:RunError
            full_log = $script:RunLogPath
        }
        $tmp = "$($script:RunReportPath).tmp"
        [IO.File]::WriteAllText($tmp, (ConvertTo-Json -InputObject $report -Depth 12) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $tmp -Destination $script:RunReportPath -Force
    } catch {
        Write-Warning "Could not save the bundle run report: $($_.Exception.Message)"
    }
}


function Invoke-QeStaffAuthRequest {
    param(
        [Parameter(Mandatory = $true)][string]$CookieValue,
        [Parameter(Mandatory = $true)][string]$Uri
    )

    # Let .NET serialize the cookie from a CookieContainer. This avoids differences
    # between PowerShell versions when manually setting the restricted Cookie header.
    Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue
    $handler = $null
    $client = $null
    $request = $null
    $response = $null
    try {
        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.UseCookies = $true
        $handler.CookieContainer = [System.Net.CookieContainer]::new()
        $targetUri = [Uri]$Uri
        $staffCookie = [System.Net.Cookie]::new("QE_STAFF_SESSION", $CookieValue.Trim(), "/")
        $handler.CookieContainer.Add($targetUri, $staffCookie)
        $client = [System.Net.Http.HttpClient]::new($handler)
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $targetUri)
        [void]$request.Headers.TryAddWithoutValidation("Accept", "application/json")
        [void]$request.Headers.TryAddWithoutValidation("X-QE-Workspace", "STAFF")

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = $body
        }
    }
    finally {
        if ($null -ne $response) { $response.Dispose() }
        if ($null -ne $request) { $request.Dispose() }
        if ($null -ne $client) { $client.Dispose() }
        if ($null -ne $handler) { $handler.Dispose() }
    }
}

function Get-QeStaffSessionDiagnostics {
    param([Parameter(Mandatory = $true)][string]$CookieValue)

    # The raw token is hashed in-process. Only the one-way hash is used in the
    # SQL query/output; never print or persist the cookie itself.
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $raw = [System.Text.Encoding]::UTF8.GetBytes($CookieValue.Trim())
        $digest = $sha.ComputeHash($raw)
        $tokenHash = [Convert]::ToBase64String($digest).TrimEnd([char]'=').Replace('+','-').Replace('/','_')
    }
    finally {
        if ($null -ne $sha) { $sha.Dispose() }
        $raw = $null
        $digest = $null
    }

    $sql = "SELECT count(*)::text, coalesce(bool_or(s.revoked_at IS NULL),'false')::text, coalesce(bool_or(s.expires_at > now()),'false')::text, coalesce(bool_or(s.refresh_expires_at > now()),'false')::text, coalesce(bool_or(s.last_seen_at > now()-interval '4 hours'),'false')::text, coalesce(bool_or(s.staff_id IS NOT NULL AND s.user_id IS NULL AND s.student_id IS NULL),'false')::text, coalesce(bool_or(st.active = true),'false')::text FROM auth_session s LEFT JOIN staff_account st ON st.id=s.staff_id WHERE s.token_hash='$tokenHash';"
    $shellScript = 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -c "$1"'
    $output = & docker compose exec -T postgres sh -lc $shellScript qe-session-diagnostic $sql 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        Write-Warning "Could not inspect the session row in local Postgres: $($output -join ' ')"
        return
    }
    $line = ($output | Select-Object -Last 1).ToString().Trim()
    $parts = $line.Split('|')
    if ($parts.Count -lt 7) {
        Write-Warning "Postgres returned an unexpected session diagnostic result."
        return
    }
    Write-Host ""
    Write-Host "Local session diagnosis (cookie value is not shown):"
    Write-Host "  matching session rows: $($parts[0])"
    if ($parts[0] -eq "0") {
        Write-Host "  result: this token is not stored in this Docker database." -ForegroundColor Yellow
        return
    }
    Write-Host "  not revoked: $($parts[1])"
    Write-Host "  access token unexpired: $($parts[2])"
    Write-Host "  refresh session unexpired: $($parts[3])"
    Write-Host "  recent session activity: $($parts[4])"
    Write-Host "  staff-only identity: $($parts[5])"
    Write-Host "  staff account active: $($parts[6])"
}

function Invoke-CheckedPython {
    param([string[]]$Arguments)
    $scriptName = [IO.Path]::GetFileName($Arguments[0])
    if ($scriptName -eq 'import_quantaedge_practice_banks.py') {
        if ($Arguments -contains '--apply') { $stageName = 'Import Hindi question drafts after full mapping preflight'; $script:SourceApplyStarted = $true }
        else { $stageName = 'Validate Hindi source catalog (dry run)' }
    } elseif ($scriptName -eq 'import_quantaedge_offline_translations.py') {
        $stageName = 'Import English translation drafts'
        $script:TranslationApplyStarted = $true
    } else { $stageName = "Run Python: $scriptName" }

    $stage = Start-QeRunStage -Name $stageName
    try {
        & $PythonExe @Arguments
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            Complete-QeRunStage -Stage $stage -Status 'FAILED' -Message 'Python returned a non-zero exit code; inspect full log and child report.' -ExitCode $exitCode
            throw "Python command failed with exit code ${exitCode}: $PythonExe $($Arguments -join ' ')"
        }
        Complete-QeRunStage -Stage $stage -Status 'SUCCEEDED' -Message 'Python exited successfully.' -ExitCode 0
    } catch {
        if ($stage.status -eq 'RUNNING') { Complete-QeRunStage -Stage $stage -Status 'FAILED' -Message $_.Exception.Message }
        throw
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

    New-Item -ItemType Directory -Path $script:ReportDirectory -Force | Out-Null
    try {
        Start-Transcript -Path $script:RunLogPath -Force | Out-Null
        $script:TranscriptStarted = $true
    } catch { Write-Warning "Could not start full transcript: $($_.Exception.Message)" }

    Push-Location $RepoRoot
    try {
        Write-Host ""
        $dockerStage = Start-QeRunStage -Name 'Build and start Postgres, API, Admin'
        Write-Host "[1/4] Starting the database, API, and Admin services..."
        & docker compose up -d --build postgres api admin
        $dockerExit = $LASTEXITCODE
        if ($dockerExit -ne 0) {
            Complete-QeRunStage -Stage $dockerStage -Status 'FAILED' -Message 'docker compose returned a non-zero exit code.' -ExitCode $dockerExit
            throw "docker compose failed with exit code ${dockerExit}."
        }
        Complete-QeRunStage -Stage $dockerStage -Status 'SUCCEEDED' -Message 'Required services are running.' -ExitCode 0

        Write-Host ""
        $apiStage = Start-QeRunStage -Name 'Check API health and validate staff session'
        Write-Host "[2/4] Checking API health..."
        $health = & curl.exe -fsS "$ApiBaseUrl/actuator/health"
        if ($LASTEXITCODE -ne 0) { throw "API health check failed at $ApiBaseUrl/actuator/health." }
        Write-Host $health

        Write-Host ""
        Write-Host "[Auth] Validating the staff session against the running API..."
        $authUrl = $ApiBaseUrl.TrimEnd([char]47) + "/api/v1/auth/me"
        $authResponse = Invoke-QeStaffAuthRequest -CookieValue $session -Uri $authUrl
        if ($authResponse.StatusCode -ne 200) {
            if ($authResponse.StatusCode -ne 401 -and $authResponse.StatusCode -ne 403) {
                throw "Staff-session validation failed with HTTP $($authResponse.StatusCode): $($authResponse.Body). No content was written."
            }

            # Ask for a fresh token only after the API is up. The HTTP client above
            # explicitly sends Cookie; do not use Invoke-RestMethod -Headers for this.
            Write-Warning "The API rejected the staff session (HTTP $($authResponse.StatusCode)). Sign in at http://localhost:3001 and copy the current QE_STAFF_SESSION value."
            $secureRetry = Read-Host "Fresh QE_STAFF_SESSION value (hidden)" -AsSecureString
            $retryPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureRetry)
            try { $session = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($retryPtr) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($retryPtr) }
            if ([string]::IsNullOrWhiteSpace($session)) { throw "No staff session value was supplied; stopped before writing any content." }
            $env:QUANTAEDGE_ADMIN_SESSION = $session
            $env:QE_SESSION = $session

            $authResponse = Invoke-QeStaffAuthRequest -CookieValue $session -Uri $authUrl
            if ($authResponse.StatusCode -ne 200) {
                Get-QeStaffSessionDiagnostics -CookieValue $session
                throw "Staff-session validation failed (HTTP $($authResponse.StatusCode)): $($authResponse.Body). The local database diagnosis above distinguishes a missing/expired session from a request-authentication problem. No content was written."
            }
        }
        try { $authCheck = $authResponse.Body | ConvertFrom-Json -ErrorAction Stop }
        catch { throw "The API returned an invalid JSON response during staff-session validation. No content was written." }
        if ($authCheck.authenticated -ne $true -or [int]$authCheck.staffId -le 0) {
            throw "The API did not confirm an authenticated staff account. No content was written."
        }
        Write-Host "Staff authentication confirmed for role $($authCheck.role)." -ForegroundColor Green
        $script:AuthConfirmed = $true
        Complete-QeRunStage -Stage $apiStage -Status 'SUCCEEDED' -Message 'API ready and staff authentication confirmed.' -ExitCode 0

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
    $script:RunStatus = 'SUCCEEDED'
}
catch {
    $script:RunStatus = 'FAILED'
    $script:RunError = $_.Exception.Message
    if ($null -ne $script:CurrentStage -and $script:CurrentStage.status -eq 'RUNNING') {
        Complete-QeRunStage -Stage $script:CurrentStage -Status 'FAILED' -Message $script:RunError
    }
    Write-Host "Bundle import failed safely: $script:RunError" -ForegroundColor Red
    throw
}
finally {
    $env:QUANTAEDGE_ADMIN_SESSION = $OldAdminSession
    $env:QE_SESSION = $OldQeSession
    $session = $null
    $secure = $null
    if ($script:TranscriptStarted) { try { Stop-Transcript | Out-Null } catch {} }
    Save-QeRunReport
    Write-Host ''
    Write-Host '========== QUANTAEDGE IMPORT FINAL REPORT =========='
    Write-Host "Status: $script:RunStatus"
    Write-Host "Duration: $([math]::Round(((Get-Date) - $script:RunStartedAt).TotalSeconds, 2)) seconds"
    Write-Host "JSON report: $script:RunReportPath"
    Write-Host "Full log:    $script:RunLogPath"
    Write-Host 'Published automatically: NO'
    Write-Host 'Cookie recorded: NO'
    Write-Host '===================================================='
}
