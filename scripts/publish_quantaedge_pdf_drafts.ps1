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

function Invoke-QEApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [object]$Body = $null
    )
    $uri = "$ApiUrl$Path"
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress -Depth 12
        return Invoke-RestMethod -Uri $uri -Method $Method -Headers $script:Headers -ContentType 'application/json' -Body $json -TimeoutSec 60
    }
    return Invoke-RestMethod -Uri $uri -Method $Method -Headers $script:Headers -TimeoutSec 60
}

if ([string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_SESSION)) {
    throw 'QUANTAEDGE_ADMIN_SESSION is empty. Sign in to QuantaEdge Admin and set the current QE_SESSION cookie first.'
}
if (-not (Test-Path -LiteralPath $ImportReport -PathType Leaf)) {
    throw "Import report not found: $ImportReport. Run from the PDF bundle directory or pass -ImportReport explicitly."
}
$parsedApiUrl = [Uri]$ApiUrl
$isLocal = $parsedApiUrl.Host -in @('localhost','127.0.0.1','::1')
if ($parsedApiUrl.Scheme -ne 'https' -and -not ($parsedApiUrl.Scheme -eq 'http' -and $isLocal)) {
    throw 'Use HTTPS for a non-local API URL. HTTP is allowed only for localhost development.'
}

$script:Headers = @{
    Accept = 'application/json'
    Cookie = "QE_SESSION=$($env:QUANTAEDGE_ADMIN_SESSION)"
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
    publish_actions_taken = 0
    content_depth_warning = '54 Class 7-8 chapter PDFs failed the current content-depth gate (three pages and one distinct worked example detected). Publishing does not correct this quality issue.'
}

try {
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
    $report.targets = @($rows | ForEach-Object {
        [ordered]@{
            document_id = [long]$_.document_id
            class_code = [string]$_.class_code
            subject_code = $(if ($_.subject_code) { [string]$_.subject_code } else { [string]$_.subject })
            scope = [string]$_.scope
            chapter_title = [string]$_.chapter_title
            title = [string]$_.title
            import_action = [string]$_.action
        }
    })

    $me = Invoke-QEApi -Path '/api/v1/auth/me'
    if (-not $me.authenticated) { throw 'The API did not confirm an authenticated session.' }
    $permissions = @($me.permissions | ForEach-Object { [string]$_ })
    if ([string]$me.role -ne 'ADMIN' -and $permissions -notcontains 'CONTENT_PUBLISH') {
        throw "The authenticated staff account lacks CONTENT_PUBLISH (role=$($me.role)). No documents were changed."
    }
    Write-Host "Authenticated: role=$($me.role); staffId=$($me.staffId); publish permission verified."

    # Admin list API caps results at 500 per request; these per-class batches are below that.
    $liveById = @{}
    foreach ($classCode in @('6','7','8')) {
        $classDocs = @(Invoke-QEApi -Path "/api/v1/admin/learning-documents?classCode=$classCode&status=ALL")
        foreach ($doc in $classDocs) {
            if ($null -ne $doc.document_id) { $liveById[[string]$doc.document_id] = $doc }
        }
    }

    foreach ($row in $rows) {
        $id = [string]$row.document_id
        if (-not $liveById.ContainsKey($id)) {
            $failures.Add("Document #$id was not returned by the Admin API for Classes 6-8.")
            continue
        }
        $doc = $liveById[$id]
        $rowSubject = if ($row.subject_code) { [string]$row.subject_code } else { [string]$row.subject }
        if ([string]$doc.class_code -ne [string]$row.class_code -or [string]$doc.subject_code -ne $rowSubject -or [string]$doc.scope -ne [string]$row.scope) {
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
    if ($report.result -eq 'PREFLIGHT_RUNNING') { $report.result = 'PREFLIGHT_FAILED' }
    if ($null -eq $report.finished_at) { $report.finished_at = (Get-Date).ToString('o') }
    if ($report.result -in @('PARTIAL_FAILURE','PREFLIGHT_FAILED')) {
        $report.failed_count = [Math]::Max([int]$report.failed_count, 1)
    } else {
        $report.result = 'FAILED'
        $report.failed_count = [Math]::Max([int]$report.failed_count, 1)
    }
    if ($failures.Count -eq 0) { $failures.Add($_.Exception.Message) }
    if (-not $report.failures -or $report.failures.Count -eq 0) { $report.failures = $failures.ToArray() }
    $report.actions = $actions.ToArray()
    $report.replacements = @($replacements.ToArray() | Select-Object -Unique)
    try { Save-PublishReport $report } catch { }
    Write-Host "PUBLISH SCRIPT STOPPED SAFELY: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Inspect $PublishReport. If result is PARTIAL_FAILURE, rerun only after reviewing recorded statuses." -ForegroundColor Yellow
    throw
}
