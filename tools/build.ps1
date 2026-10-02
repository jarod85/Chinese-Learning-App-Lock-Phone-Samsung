<#
.SYNOPSIS
    Builds the Lingo Lock APK (release, signed with your personal key) and runs the unit tests.

.EXAMPLE
    .\tools\build.ps1              # tests + release APK
    .\tools\build.ps1 -SkipTests   # just the APK
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$DebugBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path (Join-Path $repo '.toolchain\jdk\bin\java.exe')) -or
    -not (Test-Path (Join-Path $repo 'local.properties'))) {
    & (Join-Path $PSScriptRoot 'setup-toolchain.ps1')
}
. (Join-Path $PSScriptRoot 'env.ps1')

$variant = if ($DebugBuild) { 'debug' } else { 'release' }
$tasks = @()
if (-not $SkipTests) { $tasks += ':app:testDebugUnitTest' }
$tasks += if ($DebugBuild) { ':app:assembleDebug' } else { ':app:assembleRelease' }

Push-Location $repo
try {
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

$apk = Join-Path $repo "app\build\outputs\apk\$variant\app-$variant.apk"
Write-Host ''
Write-Host "APK: $apk"
