# SPDX-License-Identifier: AGPL-3.0-only
<#
.SYNOPSIS
  Runs the app against the simulated car on a local Android emulator.

.DESCRIPTION
  Builds the debug APK with -PdemoCar=true, boots an AVD when one is not already
  running, installs the APK, and launches the app. Use -Capture to drive the
  shared screenshot script (.github/scripts/capture-screenshots.sh) through the
  whole demo flow (scan, pair, wake, SOC) instead of leaving the app on screen.

.EXAMPLE
  .\tools\demo-emulator.ps1
  .\tools\demo-emulator.ps1 -Capture
  .\tools\demo-emulator.ps1 -Avd tbb30 -NoWindow -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$Avd = "tbb30",
    [switch]$NoWindow,
    [switch]$Capture,
    [switch]$SkipBuild,
    [string]$OutDir = (Join-Path $PSScriptRoot "..\demo-screenshots")
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
if (-not (Test-Path $adb)) { throw "adb not found under '$sdk' (set ANDROID_HOME?)" }
if (-not (Test-Path $emulator)) { throw "emulator not found under '$sdk' (install it with sdkmanager)" }

function Get-EmulatorSerial {
    (& $adb devices) 2>$null |
        Select-String -Pattern "^emulator-\d+\s+device" |
        ForEach-Object { ($_.Line -split "\s+")[0] } |
        Select-Object -First 1
}

$apk = Join-Path $repo "app\build\outputs\apk\debug\app-debug.apk"
if (-not $SkipBuild) {
    Write-Host "Building demo APK (-PdemoCar=true)..."
    Push-Location $repo
    try {
        & (Join-Path $repo "gradlew.bat") :app:assembleDebug -PdemoCar=true --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path $apk)) { throw "missing $apk" }

$serial = Get-EmulatorSerial
if (-not $serial) {
    $emulatorArgs = @(
        "-avd", $Avd, "-no-snapshot", "-no-audio", "-no-boot-anim",
        "-gpu", "swiftshader_indirect"
    )
    if ($NoWindow) { $emulatorArgs += "-no-window" }
    Write-Host "Starting emulator '$Avd'..."
    Start-Process -FilePath $emulator -ArgumentList $emulatorArgs
    for ($i = 0; $i -lt 120 -and -not $serial; $i++) {
        Start-Sleep -Seconds 3
        $serial = Get-EmulatorSerial
    }
    if (-not $serial) { throw "emulator did not show up in adb devices" }
}
Write-Host "Using $serial"

& $adb -s $serial wait-for-device
$booted = ""
for ($i = 0; $i -lt 120; $i++) {
    $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($booted -eq "1") { break }
    Start-Sleep -Seconds 3
}
if ($booted -ne "1") { throw "emulator did not finish booting" }

& $adb -s $serial install -r $apk | Out-Null
& $adb -s $serial shell pm grant com.dzid26.teslable android.permission.ACCESS_FINE_LOCATION 2>$null | Out-Null

if ($Capture) {
    $bash = Join-Path $env:ProgramFiles "Git\bin\bash.exe"
    if (-not (Test-Path $bash)) { $bash = "bash" }
    $env:ANDROID_SERIAL = $serial
    Write-Host "Running the shared demo script -> $OutDir"
    & $bash ".github/scripts/capture-screenshots.sh" `
        $apk.Replace('\', '/') $OutDir.Replace('\', '/')
} else {
    & $adb -s $serial shell am start -n com.dzid26.teslable/.MainActivity | Out-Null
    Write-Host "Launched on $serial"
    Write-Host "Stop it with: & '$adb' -s $serial emu kill"
}
