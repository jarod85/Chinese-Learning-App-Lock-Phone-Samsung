<#
.SYNOPSIS
    Builds HanziLock and installs/updates it on the phone connected over USB (or wireless adb).

.DESCRIPTION
    Uses the adb already on your PATH (e.g. the one that ships with scrcpy) so a running scrcpy
    session is not disturbed; falls back to the repo-local SDK's adb.
    Your progress is kept: the app is updated in place (adb install -r).

.EXAMPLE
    .\tools\install.ps1            # build + install
    .\tools\install.ps1 -NoBuild   # install the APK that was built last
#>
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [switch]$DebugBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -SkipTests -DebugBuild:$DebugBuild }
if (-not $adb) {
    $sdkAdb = Join-Path $repo '.toolchain\android-sdk\platform-tools\adb.exe'
    if (Test-Path $sdkAdb) { $adb = $sdkAdb }
}
if (-not $adb) { throw 'adb not found. Run tools\setup-toolchain.ps1 or put adb on your PATH.' }

$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
if ($devices.Count -eq 0) {
    throw ('No phone found. On the phone: Settings > Developer options > USB debugging ON, ' +
        'and Settings > Security and privacy > Auto Blocker OFF (it blocks USB installs). ' +
        'Plug in, accept the "Allow USB debugging?" prompt, then retry.')
}

$variant = if ($DebugBuild) { 'debug' } else { 'release' }
$apk = Join-Path $repo "app\build\outputs\apk\$variant\app-$variant.apk"
if (-not (Test-Path $apk)) { throw "APK not found: $apk (run without -NoBuild)" }

Write-Host "Installing $apk ..."
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
Write-Host 'Installed. Open HanziLock on the phone and finish the setup checklist.'
