# SPDX-License-Identifier: AGPL-3.0-only
<#
.SYNOPSIS
  Exercises the vehicle-key backup and restore path on a connected device.

.DESCRIPTION
  Proves a paired key survives a fresh install when key backup is on:

    1. checks a pairing key exists (pair the car first if not);
    2. turns on portable key storage if needed (same effect as Settings ->
       "Include vehicle keys in Android backup", applied to the prefs and the
       app is restarted so it migrates the key);
    3. forces an Android backup and keeps a local tar safety copy;
    4. uninstalls and reinstalls the APK (same signing key, so this is exactly
       the "fresh install" case);
    5. restores: Android backup first, the local tar as fallback;
    6. verifies the portable key came back and the app does not ask to pair
       again.

  The car keeps its whitelist entry, so even a failed restore only costs one
  NFC card tap.

.EXAMPLE
  .\tools\test-key-restore.ps1
  .\tools\test-key-restore.ps1 -Mode google -Serial 192.168.50.194:37265
  .\tools\test-key-restore.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk",
    [ValidateSet("google", "manual", "both")]
    [string]$Mode = "both",
    [string]$OutDir = "key-restore-test"
)

$ErrorActionPreference = "Stop"
$pkg = "com.dzid26.teslable"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found under '$sdk' (set ANDROID_HOME?)" }

if (-not $Serial) {
    $Serial = (& $adb devices) |
        Select-String -Pattern "^\S+\s+device$" |
        ForEach-Object { ($_.Line -split "\s+")[0] } |
        Select-Object -First 1
}
if (-not $Serial) { throw "no device in 'adb devices'; connect one or pass -Serial" }

$Apk = (Resolve-Path $Apk).Path
$OutDir = Join-Path $repo $OutDir
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$uiFile = Join-Path $OutDir "ui.xml"

function Adb { & $adb -s $Serial @args }
function AppShell { Adb shell run-as $pkg @args }
function UiXml {
    Adb shell uiautomator dump /sdcard/window.xml | Out-Null
    Adb pull /sdcard/window.xml $uiFile 2>$null | Out-Null
    Get-Content $uiFile -Raw
}
function Tap-Text([string]$Text) {
    $xml = UiXml
    $pattern = 'text="' + [regex]::Escape($Text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $pattern)
    if (-not $m.Success) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    Adb shell input tap $x $y | Out-Null
    return $true
}
function KeyPrefs { (AppShell cat shared_prefs/pairing_key.xml) -join "`n" }
function HasKey { return [bool]((AppShell ls shared_prefs) -match "pairing_key\.xml") }
function IsPortable { return -not ((KeyPrefs) -match 'name="iv"') }
function BackupEnabled { return (KeyPrefs) -match 'name="key_backup_enabled" value="true"' }
function EnableBackupMode {
    # Same end state as the Settings toggle: flag on, key re-saved portable.
    # The app is stopped, the flag written, then the app migrates on next load.
    $xml = KeyPrefs
    if ($xml -match 'name="key_backup_enabled"') {
        $xml = $xml -replace 'name="key_backup_enabled" value="false"', 'name="key_backup_enabled" value="true"'
    } else {
        $xml = $xml -replace '</map>', '    <boolean name="key_backup_enabled" value="true" />' + "`n</map>"
    }
    $edited = Join-Path $OutDir "pairing_key.xml"
    [IO.File]::WriteAllText($edited, $xml)
    Adb push $edited /data/local/tmp/pairing_key.xml | Out-Null
    AppShell cp /data/local/tmp/pairing_key.xml shared_prefs/pairing_key.xml | Out-Null
    Adb shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 4
    Adb shell am force-stop $pkg | Out-Null
}

Write-Host "device: $Serial"
Write-Host "apk:    $Apk"

# 0. Prerequisites
if (-not ((Adb shell pm path $pkg) -match "package:")) {
    throw "$pkg is not installed; install a build with the key-backup setting first"
}
if (-not (HasKey)) {
    throw "no pairing key on the device - pair the car (NFC card) first, then re-run"
}
Write-Host "key storage: $(if (IsPortable) { 'portable' } else { 'device-only' })"

if (-not (BackupEnabled) -or -not (IsPortable)) {
    Write-Host "turning on portable key storage (Settings toggle equivalent)..."
    EnableBackupMode
}
if (-not (BackupEnabled)) { throw "backup setting did not turn on" }
if (-not (IsPortable)) { throw "key did not migrate to portable storage" }
Write-Host "backup enabled: yes; portable key: yes"

# 1. Force an Android backup (best effort)
$googleOk = $false
if ($Mode -in @("google", "both")) {
    $enabled = (Adb shell bmgr enabled) -join " "
    if ($enabled -match "true") {
        Write-Host "forcing an Android backup (bmgr backupnow --monitor)..."
        $out = Adb shell bmgr backupnow --monitor $pkg
        $out | Out-File (Join-Path $OutDir "backupnow.txt") -Encoding utf8
        $googleOk = ($out -join "`n") -notmatch "fail|error|not allowed"
        Write-Host "backupnow: $(if ($googleOk) { 'ok' } else { 'see backupnow.txt' })"
    } else {
        Write-Host "system backup is disabled on this device; skipping the Google path"
    }
}

# 2. Local safety copy
$tar = Join-Path $OutDir "app-data-before-uninstall.tar"
Write-Host "saving app data to $tar"
cmd /c "`"$adb`" -s $Serial exec-out run-as $pkg tar -cf - files shared_prefs > `"$tar`""
if (-not (Test-Path $tar)) { throw "tar safety copy failed" }

# 3. Fresh install
Write-Host "uninstalling..."
Adb uninstall $pkg | Out-Null
Write-Host "installing $Apk..."
Adb install -r $Apk | Out-Null
foreach ($p in @("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "ACCESS_FINE_LOCATION", "POST_NOTIFICATIONS")) {
    Adb shell pm grant $pkg "android.permission.$p" 2>$null | Out-Null
}
if (HasKey) { throw "key still present right after uninstall?!" }

# 4. Restore
$restoredBy = $null
if ($Mode -in @("google", "both") -and $googleOk) {
    Write-Host "restoring from Android backup..."
    $transports = Adb shell bmgr list transports
    $google = $transports | Select-String "com.google.android.gms/.backup.BackupTransportService" | Select-Object -First 1
    if ($google) {
        Adb shell bmgr transport $google.Line.Trim().TrimStart("*").Trim() | Out-Null
        $sets = Adb shell bmgr list sets
        $token = $sets | Select-String -Pattern "^\s*([0-9a-fA-F]+)\s*:" | Select-Object -First 1
        if ($token) {
            $out = Adb shell bmgr restore $token.Matches[0].Groups[1].Value $pkg
            $out | Out-File (Join-Path $OutDir "bmgr-restore.txt") -Encoding utf8
        }
        for ($i = 0; $i -lt 12 -and -not $restoredBy; $i++) {
            Start-Sleep -Seconds 5
            if (HasKey) { $restoredBy = "google" }
        }
    }
}
if (-not $restoredBy -and $Mode -in @("manual", "both")) {
    Write-Host "restoring from the local tar copy..."
    Adb push $tar /data/local/tmp/key-restore-test.tar | Out-Null
    AppShell tar -xf /data/local/tmp/key-restore-test.tar | Out-Null
    if (HasKey) { $restoredBy = "manual" }
}
if (-not $restoredBy) { throw "key was not restored by either path; re-pair with the NFC card" }

# 5. Verify
$portable = IsPortable
Adb shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 6
$pairingText = "unknown"
$xml = UiXml
if ($xml -match 'text="[^"]*Teslak[^"]*"') { Tap-Text "Teslak" | Out-Null; Start-Sleep -Seconds 3; $xml = UiXml }
if ($xml -match 'text="Not paired"') { $pairingText = "Not paired" } elseif ($xml -match 'Phone key') { $pairingText = "paired" }
$session = if ($xml -match "session established") { "session established" } else { "no session yet" }

Write-Host ""
Write-Host "RESULT"
Write-Host "  restored by:  $restoredBy"
Write-Host "  portable key: $portable"
Write-Host "  app shows:    $pairingText"
Write-Host "  log:          $session"
Write-Host "  artifacts:    $OutDir"
if ($pairingText -eq "Not paired") { exit 1 }
