# QuantaEdge: fetch the APK built from the exact current main commit and install over ADB.
# Wireless debugging works automatically: connect the phone in Android Studio/adb first.
[CmdletBinding()]
param(
    [string]$Repository = "Manav326/QuantaEdge",
    [string]$Branch = "main",
    [string]$ArtifactName = "",
    [switch]$ForceDownload
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$script:LogPath = Join-Path $repoRoot "artifacts\android-install-history.log"
$targetDir = Join-Path $repoRoot "artifacts"
$targetApk = Join-Path $targetDir "quantaedge-latest.apk"
$tempDir = Join-Path $env:TEMP ("quantaedge-apk-" + [guid]::NewGuid().ToString("N"))

function Write-Status([string]$Message, [string]$Color = "Gray") {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $Message -ForegroundColor $Color
    try {
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
        throw "No authorized ADB device is connected. For wireless debugging, open Developer options > Wireless debugging, pair/connect the phone, then verify with 'adb devices -l'. The downloaded APK is kept at $targetApk."
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
        if ($raw -match '^(q|quit)$') { throw "Installation cancelled by user. APK remains at $targetApk." }
        $number = 0
        $valid = [int]::TryParse($raw, [ref]$number) -and $number -ge 1 -and $number -le $Devices.Count
        if (-not $valid) { Write-Host ("Enter a number from 1 to {0}, or Q to cancel." -f $Devices.Count) -ForegroundColor Yellow }
    } while (-not $valid)
    return $Devices[$number - 1].Serial
}

try {
    $null = New-Item -ItemType Directory -Path $targetDir -Force
    $null = New-Item -ItemType Directory -Path $tempDir -Force
    Write-Status "=== QuantaEdge APK download and wireless install started ===" "Cyan"
    Write-Status ("Repository: {0}; branch: {1}" -f $Repository, $Branch)

    Require-Command "gh" "Install GitHub CLI from https://cli.github.com/ and run 'gh auth login'."
    $adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adbPath)) {
        $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
        if ($adbCommand) { $adbPath = $adbCommand.Source }
        else { throw "ADB was not found. Install Android Platform Tools or add adb.exe to PATH." }
    }

    $auth = & gh auth status 2>&1
    if ($LASTEXITCODE -ne 0) { throw "GitHub CLI is not authenticated. Run 'gh auth login'. Details: $($auth -join ' ')" }

    Write-Status "Reading the current remote branch commit..." "Cyan"
    $headSha = (& gh api "repos/$Repository/commits/$Branch" --jq ".sha" 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $headSha -notmatch '^[0-9a-f]{40}$') {
        throw "Could not resolve current $Branch commit from GitHub. Check repository access and branch name. Response: $headSha"
    }
    Write-Status ("Current GitHub {0} commit: {1}" -f $Branch, $headSha) "Green"

    Write-Status "Finding a successful Actions run for this exact commit..." "Cyan"
    $runsRaw = & gh run list --repo $Repository --branch $Branch --limit 100 --json databaseId,workflowName,createdAt,headSha,status,conclusion 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not query GitHub Actions runs: $($runsRaw -join ' ')" }
    $runs = @($runsRaw | ConvertFrom-Json)
    $candidates = @($runs | Where-Object {
        $_.headSha -eq $headSha -and $_.status -eq "completed" -and $_.conclusion -eq "success"
    } | Sort-Object createdAt -Descending)

    if ($candidates.Count -eq 0) {
        throw "There is no successful GitHub Actions run for the current $Branch commit $headSha. To avoid installing a stale APK, this script will not use an older build. Check Actions and rerun/fix the Android build, then run this script again."
    }

    $downloadedApk = $null
    $sourceRun = $null
    foreach ($run in $candidates) {
        $runDir = Join-Path $tempDir ([string]$run.databaseId)
        $null = New-Item -ItemType Directory -Path $runDir -Force
        Write-Status ("Checking run {0}: {1} ({2})" -f $run.databaseId, $run.workflowName, $run.createdAt) "Cyan"
        $args = @("run", "download", [string]$run.databaseId, "--repo", $Repository, "--dir", $runDir)
        if (-not [string]::IsNullOrWhiteSpace($ArtifactName)) { $args += @("--name", $ArtifactName) }
        $downloadOutput = @(& gh @args 2>&1)
        $downloadExit = $LASTEXITCODE
        if ($downloadExit -eq 0) {
            $matches = @(Get-ChildItem -LiteralPath $runDir -Filter "*.apk" -File -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.Length -gt 1MB -and $_.Name -notmatch '(?i)(unaligned|androidTest|test-)' } |
                Sort-Object Length -Descending)
            if ($matches.Count -gt 0) {
                $downloadedApk = $matches[0]
                $sourceRun = $run
                break
            }
            Write-Status "This successful run had downloadable artifacts, but no plausible APK was found." "Yellow"
        } else {
            Write-Status ("No matching APK artifact in run {0}: {1}" -f $run.databaseId, ($downloadOutput -join ' ')) "Yellow"
        }
    }

    if (-not $downloadedApk) {
        if ($ArtifactName) {
            throw "No APK was found in the successful runs for commit $headSha using artifact name '$ArtifactName'. Confirm the workflow's upload-artifact name."
        }
        throw "No APK artifact was found in successful Actions runs for current commit $headSha. Ensure the Android workflow uploads its APK with actions/upload-artifact."
    }

    # Validate the ZIP-extracted file looks like a real APK before replacing the last known file.
    if ($downloadedApk.Length -lt 1MB) { throw "Downloaded APK is unexpectedly small; refusing to replace the saved APK." }
    $stagedApk = Join-Path $targetDir "quantaedge-latest.staged.apk"
    Copy-Item -LiteralPath $downloadedApk.FullName -Destination $stagedApk -Force
    if (-not (Test-Path $stagedApk) -or (Get-Item $stagedApk).Length -ne $downloadedApk.Length) {
        Remove-Item $stagedApk -Force -ErrorAction SilentlyContinue
        throw "APK staging verification failed. The previously saved APK was not changed."
    }
    Move-Item -LiteralPath $stagedApk -Destination $targetApk -Force

    $sourceRunId = [string]$sourceRun.databaseId
    Write-Status ("APK downloaded and verified: {0}" -f $targetApk) "Green"
    Write-Status ("Build commit: {0}; Actions run: {1}; workflow: {2}; size: {3:N2} MB" -f $sourceRun.headSha, $sourceRunId, $sourceRun.workflowName, ((Get-Item $targetApk).Length / 1MB)) "Green"

    Write-Status "Checking connected ADB devices (USB or wireless debugging)..." "Cyan"
    $devices = @(Get-UsableDevices $adbPath)
    $selectedSerial = Select-Device $devices

    $confirm = Read-Host ("Install this current-main APK on device {0}? (Y/N)" -f $selectedSerial)
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
            throw "Android rejected the update because the installed QuantaEdge app is signed with a different key. Nothing was uninstalled automatically, so your app data is safe. To continue, uninstall the existing app manually (this removes its local data), then rerun this script."
        }
        if ($installText -match 'device offline|no devices/emulators found|device not found') {
            throw "The wireless ADB connection dropped during installation. Reconnect the phone in Wireless debugging, run 'adb devices -l', and rerun this script. APK remains saved at $targetApk."
        }
        throw "ADB installation failed on $selectedSerial. Review the ADB output above. APK remains saved at $targetApk."
    }

    Write-Status ("SUCCESS: QuantaEdge APK installed on {0} from Actions run {1} (commit {2})." -f $selectedSerial, $sourceRunId, $sourceRun.headSha) "Green"
    Write-Status ("History log: {0}" -f $script:LogPath)
} catch {
    Write-Status ("FAILED: {0}" -f $_.Exception.Message) "Red"
    throw
} finally {
    if (Test-Path $tempDir) {
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
