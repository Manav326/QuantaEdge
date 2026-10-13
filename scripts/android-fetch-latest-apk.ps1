# Downloads the newest successful QuantaEdge Android APK from GitHub Actions,
# falls back to a local Gradle debug build if no APK artifact is available,
# then installs the APK on a connected Android device with ADB.
[CmdletBinding()]
param(
    [string]$Repository = "Manav326/QuantaEdge",
    [string]$Branch = "main",
    [string]$ProjectRoot = (Get-Location).Path,
    [string]$ArtifactName = "",
    [switch]$BuildLocal,
    [switch]$NoInstall,
    [switch]$ForceDownload
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$Name, [string]$InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $InstallHint"
    }
}

function Find-Apk([string]$Root) {
    if (-not (Test-Path $Root)) { return $null }
    $files = Get-ChildItem -Path $Root -Filter "*.apk" -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "unaligned|androidTest|test-" } |
        Sort-Object LastWriteTime -Descending
    return $files | Select-Object -First 1
}

Require-Command "gh" "Install GitHub CLI, then run 'gh auth login'."
$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$Destination = Join-Path $ProjectRoot "artifacts\quantaedge-latest.apk"
$null = New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force

$apk = $null
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("quantaedge-apk-" + [guid]::NewGuid().ToString("N"))
$null = New-Item -ItemType Directory -Path $tempRoot -Force

try {
    if (-not $BuildLocal) {
        Write-Host "Checking GitHub Actions for the latest successful $Branch run..." -ForegroundColor Cyan
        $runsJson = gh run list --repo $Repository --branch $Branch --status success --limit 30 --json databaseId,workflowName,createdAt,headSha
        if ($LASTEXITCODE -ne 0) { throw "Could not list GitHub Actions runs. Check 'gh auth status' and repository access." }
        $runs = @($runsJson | ConvertFrom-Json)

        foreach ($run in $runs) {
            $runDir = Join-Path $tempRoot ([string]$run.databaseId)
            $null = New-Item -ItemType Directory -Path $runDir -Force
            Write-Host ("Checking run {0} ({1}, {2})..." -f $run.databaseId, $run.workflowName, $run.createdAt)

            $downloadArgs = @("run", "download", [string]$run.databaseId, "--repo", $Repository, "--dir", $runDir)
            if ($ArtifactName) { $downloadArgs += @("--name", $ArtifactName) }
            & gh @downloadArgs 2>$null
            if ($LASTEXITCODE -eq 0) {
                $apk = Find-Apk $runDir
                if ($apk) {
                    Write-Host ("Found APK from commit {0}" -f $run.headSha) -ForegroundColor Green
                    break
                }
            }
        }
    }

    if ($BuildLocal -or -not $apk) {
        Write-Host "No downloadable APK artifact found. Trying a local Android debug build..." -ForegroundColor Yellow
        $gradleWrapper = @(
            (Join-Path $ProjectRoot "android\gradlew.bat"),
            (Join-Path $ProjectRoot "gradlew.bat")
        ) | Where-Object { Test-Path $_ } | Select-Object -First 1

        if (-not $gradleWrapper) {
            throw "No APK artifact was found in successful GitHub Actions runs, and no gradlew.bat was found at the project root or android\gradlew.bat. Ensure the Android APK workflow uploads an APK artifact, or run this script from the correct project root."
        }

        $gradleDir = Split-Path $gradleWrapper -Parent
        Push-Location $gradleDir
        try {
            & $gradleWrapper ":app:assembleDebug"
            if ($LASTEXITCODE -ne 0) { throw "Gradle debug build failed with exit code $LASTEXITCODE." }
        } finally {
            Pop-Location
        }

        $apk = Find-Apk (Join-Path $gradleDir "app\build\outputs\apk")
        if (-not $apk) {
            $apk = Find-Apk (Join-Path $ProjectRoot "android\app\build\outputs\apk")
        }
        if (-not $apk) { throw "Gradle completed but no APK was found under the expected app build output folders." }
    }

    Copy-Item -LiteralPath $apk.FullName -Destination $Destination -Force
    Write-Host ""
    Write-Host "APK ready: $Destination" -ForegroundColor Green
    Write-Host ("Size: {0:N1} MB" -f ($apk.Length / 1MB))

    if (-not $NoInstall) {
        Require-Command "adb" "Install Android Platform Tools and add adb.exe to PATH."
        $devices = & adb devices
        if ($LASTEXITCODE -ne 0) { throw "ADB could not start. Check your Android Platform Tools installation." }
        $connected = @($devices | Select-String -Pattern "^\S+\s+device$")
        if ($connected.Count -eq 0) {
            throw "No authorized Android device is connected. Enable Developer options and USB debugging, connect the phone, accept its RSA prompt, then run this script again. The APK is already saved at $Destination."
        }

        Write-Host "Installing APK on the connected Android device..." -ForegroundColor Cyan
        & adb install -r $Destination
        if ($LASTEXITCODE -ne 0) {
            throw "ADB install failed. The existing app was not automatically uninstalled; this can happen if the APK signing key differs from the installed app."
        }
        Write-Host "Installation successful. Open QuantaEdge on your phone to test." -ForegroundColor Green
    }
} finally {
    if (Test-Path $tempRoot) { Remove-Item $tempRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
