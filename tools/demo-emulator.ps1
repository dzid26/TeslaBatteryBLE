# SPDX-License-Identifier: AGPL-3.0-only
<#
.SYNOPSIS
  Windows wrapper around tools/demo-emulator.sh.

.DESCRIPTION
  All of the logic lives in the bash script so Linux, macOS, CI, and Git Bash
  share it. This wrapper just finds Git Bash and forwards the arguments.

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

$bash = Join-Path $env:ProgramFiles "Git\bin\bash.exe"
if (-not (Test-Path $bash)) {
    $gitBash = Get-Command bash -ErrorAction SilentlyContinue
    if ($gitBash -and $gitBash.Source -notlike "*\system32\*") {
        $bash = $gitBash.Source
    } else {
        throw "bash not found; install Git for Windows or run tools/demo-emulator.sh in WSL/macOS/Linux"
    }
}

$bashArgs = @((Join-Path $PSScriptRoot "demo-emulator.sh"), "--avd", $Avd)
if ($NoWindow) { $bashArgs += "--no-window" }
if ($Capture) { $bashArgs += "--capture" }
if ($SkipBuild) { $bashArgs += "--skip-build" }
$bashArgs += @("--out-dir", $OutDir.Replace('\', '/'))

& $bash @bashArgs
exit $LASTEXITCODE
