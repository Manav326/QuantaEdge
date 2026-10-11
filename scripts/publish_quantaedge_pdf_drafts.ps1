[CmdletBinding()]
param(
    [string]$ApiUrl = 'http://localhost:8080',
    [string]$ImportReport,
    [string]$PublishReport,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$ApiUrl = $ApiUrl.TrimEnd('/')

# Keep this script in the repository while resolving generated reports from the bundle folder.
if ([string]::IsNullOrWhiteSpace($ImportReport)) {
    $ImportReport = Join-Path (Get-Location).Path 'rebuilt\pdf-import-report.json'
}
if ([string]::IsNullOrWhiteSpace($PublishReport)) {
    $PublishReport = Join-Path (Split-Path -Parent $ImportReport) 'pdf-publish-report.json'
}

function Save-PublishReport {
    param([hashtable]$Data)
    $parent = Split-Path -Parent $PublishReport
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $Data | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $PublishReport -Encoding UTF8
}

function Sync-QEAuthEnvironment {
    $cookies = $script:WebSession.Cookies.GetCookies($script:BaseUri)
    $sessionCookie = $cookies['QE_SESSION']
    $refreshCookie = $cookies['QE_REFRESH']
    if ($null -ne $sessionCookie -and -not [string]::IsNullOrWhiteSpace($sessionCookie.Value)) {
        $env:QUANTAEDGE_ADMIN_SESSION = $sessionCookie.Value
    }
    if ($null -ne $refreshCookie -and -not [string]::IsNullOrWhiteSpace($refreshCookie.Value)) {
        $env:QUANTAEDGE_ADMIN_REFRESH = $refreshCookie.Value
    }
}

function Read-QERefreshCookieSecurely {
    param([string]$PromptText = 'Paste the CURRENT QE_REFRESH cookie value (input is hidden)')

    $secureValue = Read-Host -Prompt $PromptText -AsSecureString
    if ($null -eq $secureValue -or $secureValue.Length -eq 0) {
        throw 'No refresh cookie was entered. No publish requests were sent.'
    }
    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
        $plainValue = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }
    if ([string]::IsNullOrWhiteSpace($plainValue)) {
        throw 'The refresh cookie was blank. No publish requests were sent.'
    }

    $env:QUANTAEDGE_ADMIN_REFRESH = $plainValue
    # Replace the cookie in this run's session so refresh uses exactly the newly entered value.
    $cookie = [System.Net.Cookie]::new('QE_REFRESH', $plainValue, '/')
    $script:WebSession.Cookies.Set($cookie)
    $plainValue = $null
}

function Invoke-QERaw {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [object]$Body = $null
    )
    $uri = "$ApiUrl$Path"
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress -Depth 12
        return Invoke-RestMethod -Uri $uri -Method $Method -WebSession $script:WebSession -Headers @{ Accept = 'application/json' } -ContentType 'application/json' -Body $json -TimeoutSec 60
    }
    return Invoke-RestMethod -Uri $uri -Method $Method -WebSession $script:WebSession -Headers @{ Accept = 'application/json' } -TimeoutSec 60
}

function Get-QEField {
    param([object]$Object, [string[]]$Names)
    if ($null -eq $Object) { return $null }
    foreach ($name in $Names) {
        $property = $Object.PSObject.Properties | Where-Object { $_.Name -ieq $name } | Select-Object -First 1
        if ($null -ne $property) { return $property.Value }
        if ($Object -is [System.Collections.IDictionary] -and $Object.Contains($name)) { return $Object[$name] }
    }
    return $null
}

function Get-QEHttpErrorDetails {
    param(
        [Parameter(Mandatory = $true)][System.Management.Automation.ErrorRecord]$ErrorRecord,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $statusCode = 0
    $statusText = ''
    $bodyText = ''
    try {
        $response = $ErrorRecord.Exception.Response
        if ($null -ne $response) {
            try { $statusCode = [int]$response.StatusCode } catch { }
            try { $statusText = [string]$response.StatusDescription } catch { }
            try {
                $stream = $response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    try { $bodyText = $reader.ReadToEnd() } finally { $reader.Dispose() }
                }
            } catch { }
        }
    } catch { }
    if ($bodyText.Length -gt 3000) { $bodyText = $bodyText.Substring(0, 3000) + ' [truncated]' }
    $label = if ($statusCode -gt 0) { "HTTP $statusCode $statusText" } else { 'HTTP request failed' }
    $detail = "$label calling $Path. $($ErrorRecord.Exception.Message)"
    if (-not [string]::IsNullOrWhiteSpace($bodyText)) {
        $detail += " Response body: $bodyText"
    }
    return $detail
}

function Invoke-QEApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [object]$Body = $null
    )
    try {
        return Invoke-QERaw -Path $Path -Method $Method -Body $Body
    } catch {
        $statusCode = 0
        try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { }
        $hasRefresh = -not [string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_REFRESH)
        if ($statusCode -in @(401, 403) -and $Path -ne '/api/v1/auth/refresh' -and $hasRefresh) {
            try {
                Invoke-QERaw -Path '/api/v1/auth/refresh' -Method 'POST' | Out-Null
                Sync-QEAuthEnvironment
                return Invoke-QERaw -Path $Path -Method $Method -Body $Body
            } catch {
                throw "QuantaEdge rejected the access session and automatic refresh failed. Sign into Admin again and update both QUANTAEDGE_ADMIN_SESSION and QUANTAEDGE_ADMIN_REFRESH from the QE_SESSION and QE_REFRESH cookies. Details: $($_.Exception.Message)"
            }
        }
        if ($statusCode -in @(401, 403) -and $Path -eq '/api/v1/auth/me') {
            throw 'QuantaEdge rejected QE_SESSION. Access tokens expire after 15 minutes. Sign in to Admin and set a fresh QE_SESSION cookie; for automatic refresh, also set QE_REFRESH in QUANTAEDGE_ADMIN_REFRESH.'
        }
        throw (Get-QEHttpErrorDetails -ErrorRecord $_ -Path $Path)
    }
}

$startedAt = (Get-Date).ToString('o')
$actions = New-Object 'System.Collections.Generic.List[object]'
$failures = New-Object 'System.Collections.Generic.List[string]'
$replacements = New-Object 'System.Collections.Generic.List[string]'
$report = @{
    started_at = $startedAt
    finished_at = $null
    mode = $(if ($Apply) { 'apply' } else { 'dry_run' })
    result = 'PREFLIGHT_RUNNING'
    target_count = 0
    published_count = 0
    already_published_count = 0
    failed_count = 0
    targets = @()
    replacements = @()
    actions = @()
    failures = @()
    remaining_document_ids = @()
    summary = $null
    publish_actions_taken = 0
    content_depth_warning = '54 Class 7-8 chapter PDFs failed the current content-depth gate (three pages and one distinct worked example detected). Publishing does not correct this quality issue.'
}

$rows = @()
try {
    if (-not (Test-Path -LiteralPath $ImportReport -PathType Leaf)) {
        throw "Import report not found: $ImportReport. Run from the PDF bundle directory or pass -ImportReport explicitly."
    }
    $script:BaseUri = [Uri]$ApiUrl
    $isLocal = $script:BaseUri.Host -in @('localhost','127.0.0.1','::1')
    if ($script:BaseUri.Scheme -ne 'https' -and -not ($script:BaseUri.Scheme -eq 'http' -and $isLocal)) {
        throw 'Use HTTPS for a non-local API URL. HTTP is allowed only for localhost development.'
    }

    # The web session captures cookie rotation from refresh; never print cookie values.
    $script:WebSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    if (-not [string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_SESSION)) {
        $script:WebSession.Cookies.Add($script:BaseUri, [System.Net.Cookie]::new('QE_SESSION', $env:QUANTAEDGE_ADMIN_SESSION, '/'))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_REFRESH)) {
        $script:WebSession.Cookies.Add($script:BaseUri, [System.Net.Cookie]::new('QE_REFRESH', $env:QUANTAEDGE_ADMIN_REFRESH, '/'))
    }

    $import = Get-Content -LiteralPath $ImportReport -Raw | ConvertFrom-Json
    if ([string]$import.result -ne 'COMPLETED') {
        throw "Import report result must be COMPLETED; found '$($import.result)'."
    }
    $rows = @($import.rows | Where-Object { $_.action -eq 'CREATED_DRAFT' -and $null -ne $_.document_id })
    if ($rows.Count -ne 82 -or [int]$import.created_draft_assignments -ne 82) {
        throw "Expected the completed import report for 82 created drafts; found $($rows.Count) CREATED_DRAFT rows and count $($import.created_draft_assignments). Refusing to publish an ambiguous batch."
    }
    $ids = @($rows | ForEach-Object { [string]$_.document_id })
    if (@($ids | Select-Object -Unique).Count -ne 82 -or @($ids | Where-Object { $_ -notmatch '^\d+$' }).Count -gt 0) {
        throw 'The import report contains duplicate or invalid document IDs. Refusing to publish.'
    }
    $report.target_count = $rows.Count
    $report.targets = $rows

    # Always refresh first. If no refresh cookie is available, request it securely in this terminal.
    if ([string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_REFRESH)) {
        Write-Host 'A current QE_REFRESH cookie is required. Paste it at the secure prompt; the value will not be echoed.' -ForegroundColor Yellow
        Read-QERefreshCookieSecurely
    }

    Write-Host 'Refreshing the Admin session before preflight...'
    try {
        Invoke-QERaw -Path '/api/v1/auth/refresh' -Method 'POST' | Out-Null
        Sync-QEAuthEnvironment
    } catch {
        $firstRefreshError = Get-QEHttpErrorDetails -ErrorRecord $_ -Path '/api/v1/auth/refresh'
        Write-Host 'The refresh cookie was rejected. Sign in to QuantaEdge Admin, copy the CURRENT QE_REFRESH cookie, and enter it at the next secure prompt.' -ForegroundColor Yellow
        Read-QERefreshCookieSecurely -PromptText 'Enter the CURRENT QE_REFRESH cookie (input is hidden)'
        try {
            Invoke-QERaw -Path '/api/v1/auth/refresh' -Method 'POST' | Out-Null
            Sync-QEAuthEnvironment
        } catch {
            $secondRefreshError = Get-QEHttpErrorDetails -ErrorRecord $_ -Path '/api/v1/auth/refresh'
            throw "The newly supplied QE_REFRESH cookie was also rejected. No publish requests were sent. Sign in to Admin again, then rerun and enter the latest QE_REFRESH cookie. First attempt: $firstRefreshError Second attempt: $secondRefreshError"
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_SESSION)) {
        throw 'Refresh returned without a QE_SESSION cookie. No publish requests were sent. Sign in to QuantaEdge Admin, copy the CURRENT QE_REFRESH cookie (and QE_SESSION if shown), update the environment variables, then rerun.'
    }
    if ([string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_REFRESH)) {
        throw 'Refresh did not provide a current QE_REFRESH cookie. No publish requests were sent. Sign in to QuantaEdge Admin and set the CURRENT QE_REFRESH cookie in QUANTAEDGE_ADMIN_REFRESH, then rerun.'
    }

    # Verify the newly refreshed session directly. Do not auto-refresh here: failure must stop and request a current cookie.
    try {
        $me = Invoke-QERaw -Path '/api/v1/auth/me'
    } catch {
        $detail = Get-QEHttpErrorDetails -ErrorRecord $_ -Path '/api/v1/auth/me'
        throw "The refreshed Admin session could not be verified. No publish requests were sent. Sign in to QuantaEdge Admin, copy the CURRENT QE_REFRESH cookie, set it in QUANTAEDGE_ADMIN_REFRESH, and rerun. Details: $detail"
    }
    if (-not $me.authenticated) {
        throw 'The refreshed Admin session failed /auth/me verification. No publish requests were sent. Sign in to QuantaEdge Admin, copy the CURRENT QE_REFRESH cookie, set it in QUANTAEDGE_ADMIN_REFRESH, and rerun.'
    }
    $permissions = @($me.permissions | ForEach-Object { [string]$_ })
    if ([string]$me.role -ne 'ADMIN' -and $permissions -notcontains 'CONTENT_PUBLISH') {
        throw "The authenticated staff account lacks CONTENT_PUBLISH (role=$($me.role)). No documents were changed."
    }
    Write-Host "Authenticated: role=$($me.role); staffId=$($me.staffId); publish permission verified."

    # The Admin list API returns up to 500 records. Request one unfiltered status=ALL
    # page so class-code filter quirks cannot hide a batch whose assignments already exist.
    $liveById = @{}
    $rawDocuments = Invoke-QEApi -Path '/api/v1/admin/learning-documents?status=ALL'
    $documentList = @($rawDocuments)
    if ($documentList.Count -eq 1 -and $null -ne $documentList[0].items) {
        $documentList = @($documentList[0].items)
    } elseif ($documentList.Count -eq 1 -and $null -ne $documentList[0].documents) {
        $documentList = @($documentList[0].documents)
    }
    foreach ($doc in $documentList) {
        $docId = Get-QEField -Object $doc -Names @('document_id','documentId','id')
        if ($null -ne $docId) { $liveById[[string]$docId] = $doc }
    }
    Write-Host "Admin API returned $($documentList.Count) learning-document record(s); indexed $($liveById.Count) record ID(s)."

    foreach ($row in $rows) {
        $id = [string]$row.document_id
        if (-not $liveById.ContainsKey($id)) {
            $failures.Add("Document #$id was not returned by the Admin API for Classes 6-8.")
            continue
        }
        $doc = $liveById[$id]
        $rowSubject = if ($row.subject_code) { [string]$row.subject_code } else { [string]$row.subject }
        $docClass = Get-QEField -Object $doc -Names @('class_code','classCode')
        $docSubject = Get-QEField -Object $doc -Names @('subject_code','subjectCode')
        $docScope = Get-QEField -Object $doc -Names @('scope')
        if ([string]$docClass -ne [string]$row.class_code -or [string]$docSubject -ne $rowSubject -or [string]$docScope -ne [string]$row.scope) {
            $failures.Add("Document #$id does not match the import report class/subject/scope mapping.")
            continue
        }
        $status = ([string]$doc.status).ToUpperInvariant()
        if ($status -notin @('DRAFT','PUBLISHED')) {
            $failures.Add("Document #$id has unexpected status '$status'.")
            continue
        }
        if ($status -eq 'DRAFT') {
            if ([string]$doc.asset_review_status -ne 'APPROVED') {
                $failures.Add("Document #$id PDF asset review status is '$($doc.asset_review_status)', not APPROVED.")
            }
            if ([string]::IsNullOrWhiteSpace([string]$doc.source_title) -or [string]::IsNullOrWhiteSpace([string]$doc.edition) -or [string]::IsNullOrWhiteSpace([string]$doc.source_url) -or -not ([string]$doc.source_url).StartsWith('https://', [StringComparison]::OrdinalIgnoreCase)) {
                $failures.Add("Document #$id is missing publication source metadata or an HTTPS source URL.")
            }
            $pageStart = 0; $pageEnd = 0; $pageCount = 0
            [void][int]::TryParse([string]$doc.page_start, [ref]$pageStart)
            [void][int]::TryParse([string]$doc.page_end, [ref]$pageEnd)
            [void][int]::TryParse([string]$doc.page_count, [ref]$pageCount)
            if ($pageStart -lt 1 -or $pageEnd -lt $pageStart -or $pageEnd -gt $pageCount) {
                $failures.Add("Document #$id has an invalid page range ($pageStart-$pageEnd of $pageCount).")
            }
            if ([string]$doc.scope -eq 'CHAPTER_PDF' -and $doc.chapter_active -ne $true) {
                $failures.Add("Document #$id maps to an inactive or missing chapter.")
            }
        }

        # V47 makes publication slots language-aware; only warn when replacing the same language slot.
        if ($status -eq 'DRAFT') {
            $sameSlot = @($liveById.Values | Where-Object {
                [string]$_.status -eq 'PUBLISHED' -and
                [string]$_.class_code -eq [string]$doc.class_code -and
                [string]$_.subject_code -eq [string]$doc.subject_code -and
                [string]$_.scope -eq [string]$doc.scope -and
                [string]$_.language -eq [string]$doc.language -and
                (([string]$doc.scope -eq 'SUBJECT_BOOK' -and $null -eq $_.chapter_id) -or ([string]$doc.scope -eq 'CHAPTER_PDF' -and [string]$_.chapter_id -eq [string]$doc.chapter_id))
            })
            foreach ($old in $sameSlot) {
                $replacements.Add("Publishing #$id will archive current published #$($old.document_id) in Class $($doc.class_code) $($doc.subject_code) $($doc.scope) $($doc.language).")
            }
        }
        $actions.Add([ordered]@{
            document_id = [long]$row.document_id
            class_code = [string]$doc.class_code
            subject_code = [string]$doc.subject_code
            scope = [string]$doc.scope
            chapter_title = [string]$doc.chapter_name
            title = [string]$doc.title
            previous_status = $status
            result = $(if ($status -eq 'PUBLISHED') { 'ALREADY_PUBLISHED' } else { 'READY_TO_PUBLISH' })
            error = $null
        })
    }

    if ($failures.Count -gt 0) {
        $report.result = 'PREFLIGHT_FAILED'
        $report.failed_count = $failures.Count
        $report.failures = $failures.ToArray()
        $report.actions = $actions.ToArray()
        $report.replacements = @($replacements.ToArray() | Select-Object -Unique)
        $report.finished_at = (Get-Date).ToString('o')
        Save-PublishReport $report
        Write-Host "Preflight found $($failures.Count) issue(s). Nothing was published. Report: $PublishReport" -ForegroundColor Red
        $failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
        throw 'Publishing stopped at preflight. Resolve the reported issues and rerun.'
    }

    $todo = @($actions | Where-Object { $_.result -eq 'READY_TO_PUBLISH' })
    Write-Host "Preflight passed: $($rows.Count) imported resources; $($todo.Count) to publish; $($actions.Count - $todo.Count) already published."
    if ($replacements.Count -gt 0) {
        Write-Host 'Existing published versions in the same slots will be archived by the Admin API:' -ForegroundColor Yellow
        $replacements.ToArray() | Select-Object -Unique | ForEach-Object { Write-Host " - $_" -ForegroundColor Yellow }
    }
    Write-Host 'CONTENT WARNING: 54 Class 7-8 chapter PDFs are below the content-depth thresholds. Publishing does not resolve this.' -ForegroundColor Yellow

    if (-not $Apply) {
        $report.result = 'PREFLIGHT_OK_NO_WRITES'
        $report.already_published_count = $actions.Count - $todo.Count
        $report.actions = $actions.ToArray()
        $report.replacements = @($replacements.ToArray() | Select-Object -Unique)
        $report.finished_at = (Get-Date).ToString('o')
        Save-PublishReport $report
        Write-Host "Dry run only. No status was changed. Report: $PublishReport"
        Write-Host 'To publish the batch, rerun with -Apply.'
        return
    }

    $published = 0
    $already = 0
    foreach ($item in $actions) {
        if ($item.result -eq 'ALREADY_PUBLISHED') { $already++; continue }
        $id = [long]$item.document_id
        try {
            $response = Invoke-QEApi -Path "/api/v1/admin/learning-documents/$id/status" -Method 'PATCH' -Body @{ status = 'PUBLISHED' }
            if ([string]$response.status -ne 'PUBLISHED') {
                throw "API response status was '$($response.status)' instead of PUBLISHED."
            }
            $item.result = 'PUBLISHED'
            $item.published_at_local = (Get-Date).ToString('o')
            $published++
            $report.published_count = $published
            $report.already_published_count = $already
            $report.publish_actions_taken = $published
            $report.actions = $actions.ToArray()
            $report.replacements = @($replacements.ToArray() | Select-Object -Unique)
            $report.result = 'IN_PROGRESS'
            Save-PublishReport $report
            Write-Host "[$($published + $already)/82] PUBLISHED #$id - Class $($item.class_code) $($item.subject_code) | $($item.scope) | $($item.chapter_title)"
        } catch {
            $item.result = 'ERROR'
            $item.error = $_.Exception.Message
            $failures.Add("Document #${id}: $($_.Exception.Message)")
            $report.result = 'PARTIAL_FAILURE'
            $report.failed_count = $failures.Count
            $report.failures = $failures.ToArray()
            $report.published_count = $published
            $report.already_published_count = $already
            $report.publish_actions_taken = $published
            $report.actions = $actions.ToArray()
            $report.replacements = @($replacements.ToArray() | Select-Object -Unique)
            $report.finished_at = (Get-Date).ToString('o')
            Save-PublishReport $report
            Write-Host "Stopped at document #$id. $published item(s) were published before this error." -ForegroundColor Red
            Write-Host "Audit report: $PublishReport" -ForegroundColor Yellow
            throw
        }
    }

    $report.result = 'COMPLETED'
    $report.published_count = $published
    $report.already_published_count = $already
    $report.failed_count = 0
    $report.publish_actions_taken = $published
    $report.actions = $actions.ToArray()
    $report.replacements = @($replacements.ToArray() | Select-Object -Unique)
    $report.finished_at = (Get-Date).ToString('o')
    Save-PublishReport $report
    Write-Host ''
    Write-Host "PUBLISH COMPLETE: published=$published; already_published=$already; failed=0; total=82." -ForegroundColor Green
    Write-Host "Audit report: $PublishReport"
    Write-Host 'Verify with a student account enrolled in the relevant class and language.'
} catch {
    $caughtMessage = $_.Exception.Message
    if ($report.result -eq 'PREFLIGHT_RUNNING') { $report.result = 'PREFLIGHT_FAILED' }
    if ($null -eq $report.finished_at) { $report.finished_at = (Get-Date).ToString('o') }
    if ($report.result -in @('PARTIAL_FAILURE','PREFLIGHT_FAILED')) {
        $report.failed_count = [Math]::Max([int]$report.failed_count, 1)
    } else {
        $report.result = 'FAILED'
        $report.failed_count = [Math]::Max([int]$report.failed_count, 1)
    }
    if ($failures.Count -eq 0) { $failures.Add($caughtMessage) }
    if (-not $report.failures -or $report.failures.Count -eq 0) { $report.failures = $failures.ToArray() }
    Write-Host "PUBLISH SCRIPT STOPPED SAFELY: $caughtMessage" -ForegroundColor Red
}
finally {
    # Recover the intended batch from the import report even when setup/auth failed early.
    if ((@($rows)).Count -eq 0 -and (Test-Path -LiteralPath $ImportReport -PathType Leaf)) {
        try {
            $savedImport = Get-Content -LiteralPath $ImportReport -Raw | ConvertFrom-Json
            $rows = @($savedImport.rows | Where-Object { $_.action -eq 'CREATED_DRAFT' -and $null -ne $_.document_id })
            if ($report.target_count -eq 0 -and $rows.Count -gt 0) {
                $report.target_count = $rows.Count
                $report.targets = $rows
            }
        } catch { }
    }
    # Always reconcile and print the end-state, for successful, dry-run, and failed executions.
    $actionById = @{}
    foreach ($item in @($actions.ToArray())) {
        $actionById[[string]$item.document_id] = $item
    }
    $publishedInRun = @($actions | Where-Object { $_.result -eq 'PUBLISHED' }).Count
    $alreadyPublishedNow = @($actions | Where-Object { $_.result -eq 'ALREADY_PUBLISHED' }).Count
    $failedIds = @($actions | Where-Object { $_.result -eq 'ERROR' } | ForEach-Object { [string]$_.document_id })
    $remainingIds = New-Object 'System.Collections.Generic.List[string]'
    $notAttemptedIds = New-Object 'System.Collections.Generic.List[string]'
    foreach ($targetRow in @($rows)) {
        $targetId = [string]$targetRow.document_id
        if (-not $actionById.ContainsKey($targetId)) {
            $remainingIds.Add($targetId)
            $notAttemptedIds.Add($targetId)
            continue
        }
        $targetResult = [string]$actionById[$targetId].result
        if ($targetResult -notin @('PUBLISHED','ALREADY_PUBLISHED')) {
            $remainingIds.Add($targetId)
            if ($targetResult -ne 'ERROR') { $notAttemptedIds.Add($targetId) }
        }
    }

    $report.published_count = $publishedInRun
    $report.already_published_count = $alreadyPublishedNow
    if ($report.result -eq 'PREFLIGHT_FAILED') {
        $report.failed_count = @($failures.ToArray()).Count
    } else {
        $report.failed_count = $failedIds.Count
    }
    $report.publish_actions_taken = $publishedInRun
    $report.remaining_document_ids = $remainingIds.ToArray()
    $report.summary = [ordered]@{
        intended_count = [int]$report.target_count
        already_published_count = $alreadyPublishedNow
        published_this_run_count = $publishedInRun
        failed_item_count = $failedIds.Count
        validation_or_api_issue_count = @($failures.ToArray()).Count
        not_attempted_count = $notAttemptedIds.Count
        remaining_to_publish_count = $remainingIds.Count
        failed_document_ids = $failedIds
        not_attempted_document_ids = $notAttemptedIds.ToArray()
        remaining_document_ids = $remainingIds.ToArray()
        result = [string]$report.result
    }
    $report.actions = $actions.ToArray()
    $report.failures = $failures.ToArray()
    $report.replacements = @($replacements.ToArray() | Select-Object -Unique)
    if ($null -eq $report.finished_at) { $report.finished_at = (Get-Date).ToString('o') }
    try {
        Save-PublishReport $report
    } catch {
        Write-Host "WARNING: Could not save the final audit report: $($_.Exception.Message)" -ForegroundColor Red
    }

    Write-Host ''
    Write-Host '========== PUBLISH RUN SUMMARY ==========' -ForegroundColor Cyan
    Write-Host "Intended batch:                 $($report.target_count) resource(s)"
    Write-Host "Already published (verified):   $alreadyPublishedNow"
    Write-Host "Published during this run:      $publishedInRun"
    Write-Host "Failed item(s):                 $($failedIds.Count)"
    Write-Host "Validation/API issue record(s): $(@($failures.ToArray()).Count)"
    Write-Host "Not attempted:                  $($notAttemptedIds.Count)"
    Write-Host "Still remaining to publish:     $($remainingIds.Count)"
    if ($failedIds.Count -gt 0) {
        Write-Host "Failed document ID(s):          $($failedIds -join ', ')" -ForegroundColor Red
    }
    if ($remainingIds.Count -gt 0) {
        Write-Host "Remaining document ID(s):       $($remainingIds -join ', ')" -ForegroundColor Yellow
    }
    Write-Host "Final result:                   $($report.result)" -ForegroundColor $(if ($report.result -eq 'COMPLETED') { 'Green' } else { 'Red' })
    Write-Host "Audit report:                   $PublishReport"

    if ($report.result -eq 'PREFLIGHT_OK_NO_WRITES') {
        Write-Host 'DRY RUN: this was a preflight only; no publish requests were sent.' -ForegroundColor Yellow
        $global:LASTEXITCODE = 0
    } elseif ($report.result -eq 'COMPLETED') {
        Write-Host 'DONE: all intended resources are published or were already published.' -ForegroundColor Green
        $global:LASTEXITCODE = 0
    } else {
        Write-Host 'SAFE STOP: no further publish requests were sent after the first failure. Review the API error and report before resuming.' -ForegroundColor Yellow
        $global:LASTEXITCODE = 1
    }
}
