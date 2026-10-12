[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ImportReport,

    [string]$ReviewManifest,
    [string]$PublishReport,
    [string]$ApiUrl = 'http://localhost:8080',

    # First run: create a 84-row review CSV and exit without calling the API.
    [switch]$PrepareReviewManifest,

    # Required to make status changes. Without this switch, the script only preflights.
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$ApiUrl = $ApiUrl.TrimEnd('/')

function Get-QEField {
    param([object]$Object, [string[]]$Names)
    if ($null -eq $Object) { return $null }
    foreach ($name in $Names) {
        $property = $Object.PSObject.Properties |
            Where-Object { $_.Name -ieq $name } |
            Select-Object -First 1
        if ($null -ne $property) { return $property.Value }
        if ($Object -is [System.Collections.IDictionary] -and $Object.Contains($name)) {
            return $Object[$name]
        }
    }
    return $null
}

function Save-PublishReport {
    param([hashtable]$Data)
    $parent = Split-Path -Parent $PublishReport
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $Data | ConvertTo-Json -Depth 30 |
        Set-Content -LiteralPath $PublishReport -Encoding UTF8
}

function Read-QEStaffCookieSecurely {
    $secureValue = Read-Host 'Paste the CURRENT QE_STAFF_SESSION cookie (input is hidden)' -AsSecureString
    if ($null -eq $secureValue -or $secureValue.Length -eq 0) {
        throw 'No staff cookie was entered; no publish request was sent.'
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
        throw 'The staff cookie was blank; no publish request was sent.'
    }
    $env:QUANTAEDGE_ADMIN_SESSION = $plainValue
    $plainValue = $null
}

function Get-QEStatusCode {
    param([System.Management.Automation.ErrorRecord]$ErrorRecord)
    try { return [int]$ErrorRecord.Exception.Response.StatusCode } catch { }
    return 0
}

function Invoke-QERaw {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [object]$Body = $null
    )
    if ([string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_SESSION)) {
        throw 'No QE_STAFF_SESSION is loaded.'
    }
    $headers = @{
        Accept = 'application/json'
        Cookie = "QE_STAFF_SESSION=$($env:QUANTAEDGE_ADMIN_SESSION)"
        'X-QE-Workspace' = 'STAFF'
    }
    $parameters = @{
        Uri = "$ApiUrl$Path"
        Method = $Method
        Headers = $headers
        TimeoutSec = 180
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 20
    }
    return Invoke-RestMethod @parameters
}

function Invoke-QEApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [object]$Body = $null
    )
    if ([string]::IsNullOrWhiteSpace($env:QUANTAEDGE_ADMIN_SESSION)) {
        Read-QEStaffCookieSecurely
    }
    try {
        return Invoke-QERaw -Path $Path -Method $Method -Body $Body
    } catch {
        $status = Get-QEStatusCode -ErrorRecord $_
        if ($status -ne 401) { throw }
        Write-Host 'The staff session expired or was rejected. Enter a fresh QE_STAFF_SESSION; the failed request will be retried once.' -ForegroundColor Yellow
        Read-QEStaffCookieSecurely
        $me = Invoke-QERaw -Path '/api/v1/auth/me'
        if ($null -eq $me -or $me.authenticated -ne $true) {
            throw 'The new staff cookie failed /api/v1/auth/me verification. No retry was sent.'
        }
        return Invoke-QERaw -Path $Path -Method $Method -Body $Body
    }
}

function Convert-ToLanguageCode {
    param([object]$Value)
    $text = ([string]$Value).Trim().ToLowerInvariant()
    switch ($text) {
        { $_ -in @('english','en') } { return 'en' }
        { $_ -in @('hindi','hi') } { return 'hi' }
        default { return $text }
    }
}

function New-ReviewManifestRow {
    param([object]$Row, [string]$RootPath, [string]$BooksPath)
    $localPdf = ''
    if ([string]$Row.scope -eq 'SUBJECT_BOOK') {
        $localPdf = Join-Path $BooksPath ([string]$Row.filename)
    } elseif ([string]$Row.scope -eq 'CHAPTER_PDF') {
        $localPdf = Join-Path $RootPath (
            'private-review\ncert-book-splits\class-{0}\{1}\{2}\{3}' -f
            [string]$Row.class_code, [string]$Row.subject_code,
            [string]$Row.language, [string]$Row.filename
        )
    }
    return [PSCustomObject][ordered]@{
        document_id = [string]$Row.document_id
        asset_id = [string]$Row.asset_id
        class_code = [string]$Row.class_code
        subject_code = [string]$Row.subject_code
        language = [string]$Row.language
        scope = [string]$Row.scope
        chapter_id = [string]$Row.chapter_id
        chapter_code = [string]$Row.chapter_code
        chapter_name = [string]$Row.chapter_name
        book_part = [string]$Row.book_part
        title = [string]$Row.title
        filename = [string]$Row.filename
        sha256 = [string]$Row.sha256
        source_title = [string]$Row.source_title
        source_url = [string]$Row.source_url
        edition = [string]$Row.edition
        source_page_start = [string]$Row.source_page_start
        source_page_end = [string]$Row.source_page_end
        local_pdf_path = $localPdf
        decision = 'PENDING'
        review_notes = ''
    }
}

function Assert-SameText {
    param([string]$Actual, [string]$Expected, [string]$Label)
    if (-not [string]::Equals([string]$Actual, [string]$Expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label mismatch: expected '$Expected', API returned '$Actual'."
    }
}

# ---------- Validate the saved import checkpoint ----------
if (-not (Test-Path -LiteralPath $ImportReport -PathType Leaf)) {
    throw "Import checkpoint not found: $ImportReport"
}
$ImportReport = (Resolve-Path -LiteralPath $ImportReport).Path
$checkpoint = Get-Content -LiteralPath $ImportReport -Raw | ConvertFrom-Json

if ($checkpoint.schema -ne 'quantaedge.official-ncert-book-chapter-import-report.v1') {
    throw 'This is not the official NCERT book/chapter import checkpoint.'
}
if ([string]$checkpoint.mode -ne 'apply' -or [string]$checkpoint.result -ne 'COMPLETED') {
    throw "Checkpoint must be a completed apply report; mode='$($checkpoint.mode)', result='$($checkpoint.result)'."
}
if ([int]$checkpoint.planned_draft_assignments -ne 84) {
    throw "Expected the 84-resource English NCERT checkpoint; found $($checkpoint.planned_draft_assignments) planned rows."
}
if ([string]$checkpoint.languages_requested -notmatch 'english') {
    throw 'This publisher is intended for the English-language NCERT import.'
}
$targets = @($checkpoint.files | Where-Object {
    $_.action -in @('CREATED_DRAFT','RECOVERED_DRAFT') -and
    $null -ne $_.document_id -and $null -ne $_.asset_id
})
if ($targets.Count -ne 84 -or [int]$checkpoint.created_draft_assignments -ne 84) {
    throw "Expected exactly 84 completed draft assignments; checkpoint has $($targets.Count) target rows and created_draft_assignments=$($checkpoint.created_draft_assignments). No publish calls were sent."
}
$ids = @($targets | ForEach-Object { [string]$_.document_id })
if (@($ids | Select-Object -Unique).Count -ne 84 -or @($ids | Where-Object { $_ -notmatch '^\d+$' }).Count -gt 0) {
    throw 'Checkpoint has duplicate or invalid document IDs; refusing to publish.'
}
$rootPath = [string]$checkpoint.root
$booksPath = [string]$checkpoint.input_books_directory
if ([string]::IsNullOrWhiteSpace($rootPath) -or [string]::IsNullOrWhiteSpace($booksPath)) {
    throw 'Checkpoint is missing its work-root or Books directory.'
}
if ([string]::IsNullOrWhiteSpace($ReviewManifest)) {
    $ReviewManifest = Join-Path (Split-Path -Parent $ImportReport) 'ncert-review-approval-manifest.csv'
}
if ([string]::IsNullOrWhiteSpace($PublishReport)) {
    $PublishReport = Join-Path (Split-Path -Parent $ImportReport) 'ncert-publish-report.json'
}

if ($PrepareReviewManifest) {
    $manifestRows = @($targets | ForEach-Object {
        New-ReviewManifestRow -Row $_ -RootPath $rootPath -BooksPath $booksPath
    })
    $missingPdfs = @($manifestRows | Where-Object {
        -not (Test-Path -LiteralPath $_.local_pdf_path -PathType Leaf)
    })
    if ($missingPdfs.Count -gt 0) {
        $missingPdfs | ForEach-Object { Write-Host "Local PDF not found: $($_.local_pdf_path)" -ForegroundColor Yellow }
        throw "$($missingPdfs.Count) local PDF(s) could not be located. Manifest was not written."
    }
    $manifestRows | Export-Csv -LiteralPath $ReviewManifest -NoTypeInformation -Encoding UTF8
    Write-Host "Review manifest created: $ReviewManifest" -ForegroundColor Green
    Write-Host 'Review every PDF, then set decision=APPROVED or REJECTED for each reviewed row. Leave unreviewed rows as PENDING.'
    Write-Host 'This step makes no API calls and publishes nothing.'
    return
}

if (-not (Test-Path -LiteralPath $ReviewManifest -PathType Leaf)) {
    throw "Review manifest not found: $ReviewManifest. First run with -PrepareReviewManifest."
}
$manifest = @(Import-Csv -LiteralPath $ReviewManifest)
if ($manifest.Count -ne 84) {
    throw "Review manifest must contain all 84 rows; found $($manifest.Count)."
}
$manifestById = @{}
foreach ($item in $manifest) {
    $id = [string]$item.document_id
    if ($id -notmatch '^\d+$' -or $manifestById.ContainsKey($id)) {
        throw "Review manifest has an invalid or duplicate document_id '$id'."
    }
    $manifestById[$id] = $item
}
foreach ($target in $targets) {
    $id = [string]$target.document_id
    if (-not $manifestById.ContainsKey($id)) {
        throw "Review manifest is missing checkpoint document #$id."
    }
    $item = $manifestById[$id]
    foreach ($field in @('asset_id','class_code','subject_code','scope','title','sha256')) {
        Assert-SameText -Actual ([string]$item.$field) -Expected ([string]$target.$field) -Label "Manifest $field for document #$id"
    }
    if ((Convert-ToLanguageCode $item.language) -ne (Convert-ToLanguageCode $target.language)) {
        throw "Manifest language does not match checkpoint for document #$id."
    }
    $decision = ([string]$item.decision).Trim().ToUpperInvariant()
    if ($decision -notin @('PENDING','APPROVED','REJECTED')) {
        throw "Document #$id has invalid decision '$($item.decision)'. Choose PENDING, APPROVED, or REJECTED."
    }
}
$approved = @($targets | Where-Object {
    ([string]$manifestById[[string]$_.document_id].decision).Trim().ToUpperInvariant() -eq 'APPROVED'
})
$pending = @($manifest | Where-Object { ([string]$_.decision).Trim().ToUpperInvariant() -eq 'PENDING' })
$rejected = @($manifest | Where-Object { ([string]$_.decision).Trim().ToUpperInvariant() -eq 'REJECTED' })
if ($approved.Count -eq 0) {
    throw 'No rows are marked APPROVED in the review manifest. Nothing to publish.'
}

$baseUri = [Uri]$ApiUrl
$isLocal = $baseUri.Host -in @('localhost','127.0.0.1','::1')
if ($baseUri.Scheme -ne 'https' -and -not ($baseUri.Scheme -eq 'http' -and $isLocal)) {
    throw 'Use HTTPS for a non-local API URL. HTTP is allowed only for localhost development.'
}
if (-not $Apply) {
    Write-Host "Checkpoint verified: 84 NCERT resources."
    Write-Host "Explicitly marked APPROVED: $($approved.Count)"
    Write-Host "Still PENDING: $($pending.Count)"
    Write-Host "Marked REJECTED: $($rejected.Count)"
    Write-Host 'Dry run only. No API requests or publishing were performed.'
}

# ---------- Authenticate and verify the live API state ----------
$initialMe = Invoke-QEApi -Path '/api/v1/auth/me'
if ($null -eq $initialMe -or $initialMe.authenticated -ne $true) {
    throw 'The staff session is not authenticated. No publish requests were sent.'
}
$permissions = @($initialMe.permissions | ForEach-Object { [string]$_ })
if ([string]$initialMe.role -ne 'ADMIN' -and $permissions -notcontains 'CONTENT_PUBLISH') {
    throw "Authenticated staff lacks CONTENT_PUBLISH (role=$($initialMe.role)). No documents were changed."
}
$staffId = [string]$initialMe.staffId
Write-Host "Authenticated staff; CONTENT_PUBLISH verified. Staff ID: $staffId"

$rawDocuments = Invoke-QEApi -Path '/api/v1/admin/learning-documents?status=ALL'
$documentList = @($rawDocuments)
if ($documentList.Count -eq 1 -and $null -ne $documentList[0].items) {
    $documentList = @($documentList[0].items)
} elseif ($documentList.Count -eq 1 -and $null -ne $documentList[0].documents) {
    $documentList = @($documentList[0].documents)
}
$liveById = @{}
foreach ($doc in $documentList) {
    $docId = Get-QEField -Object $doc -Names @('document_id','documentId','id')
    if ($null -ne $docId) { $liveById[[string]$docId] = $doc }
}
Write-Host "Admin API returned $($documentList.Count) records; indexed $($liveById.Count) IDs."

$failures = New-Object 'System.Collections.Generic.List[string]'
$actions = New-Object 'System.Collections.Generic.List[object]'
$replacements = New-Object 'System.Collections.Generic.List[string]'
foreach ($target in $targets) {
    $id = [string]$target.document_id
    $manifestRow = $manifestById[$id]
    $decision = ([string]$manifestRow.decision).Trim().ToUpperInvariant()
    if ($decision -ne 'APPROVED') {
        $actions.Add([ordered]@{
            document_id = [long]$id
            title = [string]$target.title
            decision = $decision
            result = $(if ($decision -eq 'REJECTED') { 'NOT_PUBLISHED_REJECTED' } else { 'NOT_PUBLISHED_PENDING_REVIEW' })
            error = $null
        })
        continue
    }
    if (-not $liveById.ContainsKey($id)) {
        $failures.Add("Document #$id was not returned by the live Admin API.")
        continue
    }
    $doc = $liveById[$id]
    $checks = @(
        @{ actual=[string]$doc.pdf_asset_id; expected=[string]$target.asset_id; name='PDF asset ID' },
        @{ actual=[string]$doc.class_code; expected=[string]$target.class_code; name='class code' },
        @{ actual=[string]$doc.subject_code; expected=[string]$target.subject_code; name='subject code' },
        @{ actual=[string]$doc.scope; expected=[string]$target.scope; name='scope' },
        @{ actual=[string]$doc.title; expected=[string]$target.title; name='title' },
        @{ actual=[string]$doc.sha256; expected=[string]$target.sha256; name='PDF SHA-256' }
    )
    foreach ($check in $checks) {
        if (-not [string]::Equals($check.actual, $check.expected, [StringComparison]::OrdinalIgnoreCase)) {
            $failures.Add("Document #$id $($check.name) mismatch: checkpoint='$($check.expected)', API='$($check.actual)'.")
        }
    }
    if ((Convert-ToLanguageCode $doc.language) -ne (Convert-ToLanguageCode $target.language)) {
        $failures.Add("Document #$id language does not match the checkpoint.")
    }
    if ([string]$doc.chapter_id -ne [string]$target.chapter_id -and
        -not ([string]::IsNullOrWhiteSpace([string]$doc.chapter_id) -and [string]::IsNullOrWhiteSpace([string]$target.chapter_id))) {
        $failures.Add("Document #$id chapter ID does not match the checkpoint.")
    }
    if ([string]$doc.book_part -ne [string]$target.book_part -and
        -not ([string]::IsNullOrWhiteSpace([string]$doc.book_part) -and [string]::IsNullOrWhiteSpace([string]$target.book_part))) {
        $failures.Add("Document #$id book part does not match the checkpoint.")
    }
    $status = ([string]$doc.status).ToUpperInvariant()
    if ($status -notin @('DRAFT','PUBLISHED')) {
        $failures.Add("Document #$id has unexpected live status '$status'.")
        continue
    }
    if ($status -eq 'DRAFT') {
        if ([string]$doc.asset_review_status -ne 'APPROVED') {
            $failures.Add("Document #$id asset status is '$($doc.asset_review_status)', not APPROVED.")
        }
        if ([string]::IsNullOrWhiteSpace([string]$doc.source_title) -or
            [string]::IsNullOrWhiteSpace([string]$doc.edition) -or
            [string]::IsNullOrWhiteSpace([string]$doc.source_url) -or
            -not ([string]$doc.source_url).StartsWith('https://', [StringComparison]::OrdinalIgnoreCase)) {
            $failures.Add("Document #$id is missing source title, edition, or official HTTPS source URL.")
        }
        $start = 0; $end = 0; $pages = 0
        [void][int]::TryParse([string]$doc.page_start, [ref]$start)
        [void][int]::TryParse([string]$doc.page_end, [ref]$end)
        [void][int]::TryParse([string]$doc.page_count, [ref]$pages)
        if ($start -lt 1 -or $end -lt $start -or $end -gt $pages) {
            $failures.Add("Document #$id has invalid page range $start-$end of $pages.")
        }
        if ([string]$doc.scope -eq 'CHAPTER_PDF' -and $doc.chapter_active -ne $true) {
            $failures.Add("Document #$id chapter is inactive or missing.")
        }
        # List current versions that the API will archive when publishing this slot.
        $oldVersions = @($liveById.Values | Where-Object {
            [string]$_.status -eq 'PUBLISHED' -and
            [string]$_.class_code -eq [string]$doc.class_code -and
            [string]$_.subject_code -eq [string]$doc.subject_code -and
            [string]$_.scope -eq [string]$doc.scope -and
            (Convert-ToLanguageCode $_.language) -eq (Convert-ToLanguageCode $doc.language) -and
            [string]$_.chapter_id -eq [string]$doc.chapter_id -and
            (
                $null -eq $doc.book_part -or [string]$_.book_part -eq [string]$doc.book_part -or
                [string]::IsNullOrWhiteSpace([string]$_.book_part)
            )
        })
        foreach ($old in $oldVersions) {
            $replacements.Add("Publishing #$id will archive current document #$($old.document_id): $($old.title)")
        }
    }
    $actions.Add([ordered]@{
        document_id = [long]$id
        asset_id = [long]$target.asset_id
        class_code = [string]$target.class_code
        subject_code = [string]$target.subject_code
        language = [string]$target.language
        scope = [string]$target.scope
        chapter_code = [string]$target.chapter_code
        title = [string]$target.title
        decision = $decision
        previous_status = $status
        result = $(if ($status -eq 'PUBLISHED') { 'ALREADY_PUBLISHED' } else { 'READY_TO_PUBLISH' })
        review_notes = [string]$manifestRow.review_notes
        error = $null
    })
}

$publishReportData = @{
    started_at = (Get-Date).ToString('o')
    finished_at = $null
    import_checkpoint = $ImportReport
    review_manifest = (Resolve-Path -LiteralPath $ReviewManifest).Path
    mode = $(if ($Apply) { 'apply' } else { 'dry_run' })
    result = 'PREFLIGHT_RUNNING'
    authenticated_staff_id = $staffId
    checkpoint_resource_count = 84
    approved_count = $approved.Count
    pending_count = $pending.Count
    rejected_count = $rejected.Count
    published_count = 0
    already_published_count = 0
    publish_actions_taken = 0
    replacements = @()
    actions = @()
    failures = @()
}
if ($failures.Count -gt 0) {
    $publishReportData.result = 'PREFLIGHT_FAILED'
    $publishReportData.failures = $failures.ToArray()
    $publishReportData.actions = $actions.ToArray()
    $publishReportData.replacements = @($replacements.ToArray() | Select-Object -Unique)
    $publishReportData.finished_at = (Get-Date).ToString('o')
    Save-PublishReport $publishReportData
    $failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    throw "Preflight failed with $($failures.Count) issue(s); nothing was published. Report: $PublishReport"
}

$todo = @($actions | Where-Object { $_.result -eq 'READY_TO_PUBLISH' })
Write-Host "Preflight passed. $($approved.Count) approved; $($todo.Count) will be published; $($actions.Count - $todo.Count) are already published or withheld."
if ($pending.Count -gt 0) { Write-Host "$($pending.Count) resources remain PENDING and will not be published." -ForegroundColor Yellow }
if ($rejected.Count -gt 0) { Write-Host "$($rejected.Count) resources are REJECTED and will not be published." -ForegroundColor Yellow }
if ($replacements.Count -gt 0) {
    Write-Host 'WARNING: Existing student-visible PDFs in matching slots will be archived by the API:' -ForegroundColor Yellow
    @($replacements.ToArray() | Select-Object -Unique) | ForEach-Object { Write-Host " - $_" -ForegroundColor Yellow }
}
if (-not $Apply) {
    $publishReportData.result = 'PREFLIGHT_OK_NO_WRITES'
    $publishReportData.actions = $actions.ToArray()
    $publishReportData.replacements = @($replacements.ToArray() | Select-Object -Unique)
    $publishReportData.finished_at = (Get-Date).ToString('o')
    Save-PublishReport $publishReportData
    Write-Host "Dry run only. Nothing was published. Report: $PublishReport"
    Write-Host 'After reviewing the preflight report, rerun with -Apply to publish the manifest rows marked APPROVED.'
    return
}

if ($todo.Count -eq 0) {
    $publishReportData.result = 'COMPLETED'
    $publishReportData.actions = $actions.ToArray()
    $publishReportData.already_published_count = @($actions | Where-Object { $_.result -eq 'ALREADY_PUBLISHED' }).Count
    $publishReportData.finished_at = (Get-Date).ToString('o')
    Save-PublishReport $publishReportData
    Write-Host "Nothing new to publish. Report: $PublishReport"
    return
}
$confirmText = "PUBLISH $($todo.Count) REVIEWED NCERT PDFs"
$confirmation = Read-Host "Type exactly '$confirmText' to continue"
if ($confirmation -cne $confirmText) {
    throw 'Confirmation did not match. No publish requests were sent.'
}

Save-PublishReport $publishReportData
foreach ($item in $actions) {
    if ($item.result -eq 'ALREADY_PUBLISHED' -or $item.result -like 'NOT_PUBLISHED_*') {
        if ($item.result -eq 'ALREADY_PUBLISHED') { $publishReportData.already_published_count++ }
        continue
    }
    $id = [long]$item.document_id
    try {
        $response = Invoke-QEApi -Path "/api/v1/admin/learning-documents/$id/status" -Method 'PATCH' -Body @{ status = 'PUBLISHED' }
        if ([string]$response.status -ne 'PUBLISHED') {
            throw "API response status was '$($response.status)' instead of PUBLISHED."
        }
        $item.result = 'PUBLISHED'
        $item.published_at_local = (Get-Date).ToString('o')
        $publishReportData.published_count++
        $publishReportData.publish_actions_taken++
        $publishReportData.result = 'IN_PROGRESS'
        $publishReportData.actions = $actions.ToArray()
        $publishReportData.replacements = @($replacements.ToArray() | Select-Object -Unique)
        Save-PublishReport $publishReportData
        Write-Host "Published #$id — $($item.title)" -ForegroundColor Green
    } catch {
        $item.result = 'FAILED'
        $item.error = $_.Exception.Message
        $publishReportData.failures = @($publishReportData.failures) + @("Document #$id ($($item.title)): $($_.Exception.Message)")
        $publishReportData.actions = $actions.ToArray()
        $publishReportData.replacements = @($replacements.ToArray() | Select-Object -Unique)
        $publishReportData.result = 'INCOMPLETE'
        $publishReportData.finished_at = (Get-Date).ToString('o')
        Save-PublishReport $publishReportData
        throw "Stopped after a publish error on document #$id. Already completed results are saved; rerun after resolving the issue. Report: $PublishReport. Error: $($_.Exception.Message)"
    }
}
$publishReportData.result = 'COMPLETED'
$publishReportData.already_published_count = @($actions | Where-Object { $_.result -eq 'ALREADY_PUBLISHED' }).Count
$publishReportData.actions = $actions.ToArray()
$publishReportData.finished_at = (Get-Date).ToString('o')
Save-PublishReport $publishReportData
Write-Host "Publishing complete: $($publishReportData.published_count) newly published, $($publishReportData.already_published_count) already published." -ForegroundColor Green
Write-Host "Pending and rejected rows were left unpublished. Report: $PublishReport"
