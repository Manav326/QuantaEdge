# Download the latest available QuantaEdge Android APK built from the selected branch.
# Prints the exact source branch and commit for the downloaded APK, then offers ADB installation.
[CmdletBinding()]
param(
    [string]$Repository = "Manav326/QuantaEdge",
    [string]$Branch = "main",
    [string]$ArtifactName = "quantaedge-android-apk"
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$targetDir = Join-Path $repoRoot "artifacts"
$targetApk = Join-Path $targetDir "quantaedge-latest.apk"
$metadataPath = Join-Path $targetDir "quantaedge-latest.apk.json"
$script:LogPath = Join-Path $targetDir "android-install-history.log"
$tempDir = Join-Path $env:TEMP ("quantaedge-apk-" + [guid]::NewGuid().ToString("N"))

function Write-Status([string]$Message, [string]$Color = "Gray") {
    Write-Host $Message -ForegroundColor $Color
    try {
        $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
        Add-Content -LiteralPath $script:LogPath -Value $line -Encoding UTF8
    } catch {
        Write-Host "Warning: could not append to history log: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

function Require-Command([string]$Name, [string]$Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $Hint"
    }
}

function Get-UsableDevices([string]$Adb) {
    $output = @(& $Adb devices -l 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB device listing failed: $($output -join ' ')" }
    $found = @()
    foreach ($line in $output) {
        if ([string]$line -match '^([^\s]+)\s+device(?:\s+(.*))?$') {
            $found += [PSCustomObject]@{
                Serial = $matches[1]
                Details = if ($matches[2]) { $matches[2].Trim() } else { "" }
            }
        } elseif ([string]$line -match '^([^\s]+)\s+(unauthorized|offline)$') {
            Write-Status ("ADB device {0} is {1}; it will not be used." -f $matches[1], $matches[2]) "Yellow"
        }
    }
    return $found
}

function Select-Device($Devices) {
    if ($Devices.Count -eq 0) {
        throw "No authorized ADB device is connected. Pair/connect the phone in Developer options > Wireless debugging, then verify with 'adb devices -l'. The downloaded APK is saved at $targetApk."
    }
    if ($Devices.Count -eq 1) {
        Write-Status ("One authorized device found: {0} {1}" -f $Devices[0].Serial, $Devices[0].Details) "Green"
        return $Devices[0].Serial
    }

    Write-Host ""
    Write-Host "Multiple authorized Android devices are connected:" -ForegroundColor Cyan
    for ($i = 0; $i -lt $Devices.Count; $i++) {
        Write-Host ("  [{0}] {1} {2}" -f ($i + 1), $Devices[$i].Serial, $Devices[$i].Details)
    }
    do {
        $raw = Read-Host "Enter the number of the phone to install QuantaEdge on (Q to cancel)"
        if ($raw -match '^(q|quit)$') { throw "Installation cancelled. APK remains at $targetApk." }
        $number = 0
        $valid = [int]::TryParse($raw, [ref]$number) -and $number -ge 1 -and $number -le $Devices.Count
        if (-not $valid) { Write-Host ("Enter a number from 1 to {0}, or Q to cancel." -f $Devices.Count) -ForegroundColor Yellow }
    } while (-not $valid)
    return $Devices[$number - 1].Serial
}

try {
    $null = New-Item -ItemType Directory -Path $targetDir -Force
    $null = New-Item -ItemType Directory -Path $tempDir -Force
    Write-Status "=== QuantaEdge latest Android APK download started ===" "Cyan"
    Write-Status ("Repository: {0}; source branch requested: {1}" -f $Repository, $Branch)

    Require-Command "gh" "Install GitHub CLI from https://cli.github.com/ and run 'gh auth login'."
    $adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adbPath)) {
        $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
        if ($adbCommand) { $adbPath = $adbCommand.Source }
        else { throw "ADB was not found. Install Android Platform Tools or add adb.exe to PATH." }
    }

    $auth = & gh auth status 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "GitHub CLI is not authenticated. Run 'gh auth login'. Details: $($auth -join ' ')"
    }

    Write-Status ("Finding the newest available APK artifact from source branch '{0}'..." -f $Branch) "Cyan"
    $runsRaw = & gh run list --repo $Repository --branch $Branch --workflow "ci.yml" --limit 100 --json databaseId,workflowName,createdAt,headSha,headBranch,status,conclusion 2>&1
    $runsExitCode = $LASTEXITCODE
    if ($runsExitCode -ne 0) {
        throw "Could not query GitHub Actions runs: $($runsRaw -join ' ')"
    }

    $runsJson = (@($runsRaw) | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
    try {
        $parsedRuns = ConvertFrom-Json -InputObject $runsJson
        $runs = @($parsedRuns)
    } catch {
        throw "GitHub CLI returned invalid workflow-run JSON. Response: $runsJson"
    }

    # A run must originate from this branch, be complete, and be from the Android CI workflow.
    # Do not require its commit to equal today's branch head and do not inspect unrelated changed files.
    $candidates = @($runs | Where-Object {
        $_.workflowName -eq "QuantaEdge Build and Publish" -and
        $_.headBranch -eq $Branch -and
        ($_.status -eq "completed" -or $_.status -eq "in_progress") -and
        $_.headSha -match '^[0-9a-f]{40}$'
    } | Sort-Object createdAt -Descending)

    if ($candidates.Count -eq 0) {
        throw "No recent Android CI runs were found for source branch '$Branch'. Open GitHub Actions, ensure the main-branch Android build has run, then try again."
    }

    $downloadedApk = $null
    $sourceRun = $null
    foreach ($run in $candidates) {
        $runDir = Join-Path $tempDir ([string]$run.databaseId)
        $null = New-Item -ItemType Directory -Path $runDir -Force
        Write-Status ("Checking latest candidate: run {0}, branch {1}, commit {2}, result {3}..." -f $run.databaseId, $run.headBranch, $run.headSha, $run.conclusion) "Cyan"

        # Download only the installable APK artifact, not Docker build cache artifacts.
        $ghArgs = @("run", "download", [string]$run.databaseId, "--repo", $Repository, "--name", $ArtifactName, "--dir", $runDir)
        # Missing artifacts are expected for cancelled/unfinished runs. In Windows PowerShell,
        # native stderr can become a terminating error when ErrorActionPreference is Stop,
        # so temporarily allow it through and use gh's exit code to continue to the next run.
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $downloadOutput = @(& gh @ghArgs 2>&1)
            $downloadExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }

        if ($downloadExit -eq 0) {
            $apkFiles = @(Get-ChildItem -LiteralPath $runDir -Filter "*.apk" -File -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.Length -gt 1MB -and $_.Name -notmatch '(?i)(unaligned|androidTest|test-)' } |
                Sort-Object Length -Descending)
            if ($apkFiles.Count -gt 0) {
                $downloadedApk = $apkFiles[0]
                $sourceRun = $run
                break
            }
            Write-Status ("Run {0} has no plausible installable APK file." -f $run.databaseId) "Yellow"
        } else {
            Write-Status ("No '{0}' artifact in run {1}; checking the next newest run." -f $ArtifactName, $run.databaseId) "Yellow"
        }
    }

    if (-not $downloadedApk) {
        throw "No installable APK artifact named '$ArtifactName' was found in recent completed CI runs originating from '$Branch'. Ensure the Android build uploads this artifact."
    }
    if ($downloadedApk.Length -lt 1MB) {
        throw "Downloaded APK is unexpectedly small; the existing saved APK was not changed."
    }

    # Stage and verify the file before replacing the previous download.
    $stagedApk = Join-Path $targetDir "quantaedge-latest.staged.apk"
    Copy-Item -LiteralPath $downloadedApk.FullName -Destination $stagedApk -Force
    if (-not (Test-Path -LiteralPath $stagedApk -PathType Leaf) -or
        (Get-Item -LiteralPath $stagedApk).Length -ne $downloadedApk.Length) {
        Remove-Item -LiteralPath $stagedApk -Force -ErrorAction SilentlyContinue
        throw "APK download verification failed. The previously saved APK was not changed."
    }

    $apkInfo = Get-Item -LiteralPath $stagedApk
    $apkHash = (Get-FileHash -LiteralPath $stagedApk -Algorithm SHA256).Hash
    $metadata = [ordered]@{
        repository = $Repository
        branch = [string]$sourceRun.headBranch
        headSha = [string]$sourceRun.headSha
        artifactName = $ArtifactName
        runId = [string]$sourceRun.databaseId
        workflowName = [string]$sourceRun.workflowName
        runConclusion = [string]$sourceRun.conclusion
        runCreatedAt = [string]$sourceRun.createdAt
        downloadedAtUtc = [DateTime]::UtcNow.ToString("o")
        sizeBytes = [long]$apkInfo.Length
        sha256 = $apkHash
    }

    $metadataTempPath = "$metadataPath.tmp"
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataTempPath -Encoding UTF8
    Move-Item -LiteralPath $stagedApk -Destination $targetApk -Force
    Move-Item -LiteralPath $metadataTempPath -Destination $metadataPath -Force

    Write-Status "=== Latest available QuantaEdge APK downloaded ===" "Green"
    Write-Status ("APK file: {0}" -f $targetApk) "Green"
    Write-Status ("APK source branch: {0}" -f $sourceRun.headBranch) "Green"
    Write-Status ("APK source commit: {0}" -f $sourceRun.headSha) "Green"
    Write-Status ("Actions run: {0}; created: {1}; run result: {2}" -f $sourceRun.databaseId, $sourceRun.createdAt, $sourceRun.conclusion) "Green"
    Write-Status ("File size: {0:N2} MB; SHA-256: {1}" -f ($apkInfo.Length / 1MB), $apkHash) "Green"

    Write-Status "Checking connected ADB devices (USB or wireless debugging)..." "Cyan"
    $devices = @(Get-UsableDevices $adbPath)
    $selectedSerial = Select-Device $devices

    $confirm = Read-Host ("Install this APK from branch '{0}', commit {1} on device {2}? (Y/N)" -f $sourceRun.headBranch, $sourceRun.headSha, $selectedSerial)
    if ($confirm -notmatch '^(Y|YES)$') {
        Write-Status "Installation cancelled. The downloaded APK is saved and ready." "Yellow"
        return
    }

    Write-Status ("Installing APK on device {0}..." -f $selectedSerial) "Cyan"
    $installOutput = @(& $adbPath -s $selectedSerial install -r $targetApk 2>&1)
    $installCode = $LASTEXITCODE
    foreach ($line in $installOutput) { Write-Host $line }

    if ($installCode -ne 0) {
        $installText = $installOutput -join [Environment]::NewLine
        if ($installText -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
            throw "Android rejected the update because the installed app is signed with a different key. Nothing was uninstalled automatically; the downloaded APK remains at $targetApk."
        }
        if ($installText -match 'device offline|no devices/emulators found|device not found') {
            throw "The ADB connection dropped during installation. Reconnect the phone and rerun the script. APK remains saved at $targetApk."
        }
        throw "ADB installation failed on $selectedSerial. Review the output above. APK remains saved at $targetApk."
    }

    Write-Status ("SUCCESS: Installed APK on {0}; source branch {1}; source commit {2}." -f $selectedSerial, $sourceRun.headBranch, $sourceRun.headSha) "Green"
    Write-Status ("History log: {0}" -f $script:LogPath)
} catch {
    Write-Status ("FAILED: {0}" -f $_.Exception.Message) "Red"
    throw
} finally {
    if (Test-Path $tempDir) {
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
