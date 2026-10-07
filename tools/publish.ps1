<#
.SYNOPSIS
    Builds Lingo Lock on this PC and publishes it on GitHub; the phone then updates itself (no cable).

.DESCRIPTION
    The alternative to cloud builds (tools\setup-cloud-builds.ps1) that keeps your signing key on
    this PC only. Commit and push first: the build number is the commit count, and the release
    points at the pushed commit.

    Needs the GitHub CLI, signed in:
        winget install --id GitHub.cli
        gh auth login

.EXAMPLE
    .\tools\publish.ps1            # build + publish
    .\tools\publish.ps1 -NoBuild   # publish the APK that was built last
#>
[CmdletBinding()]
param(
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { throw 'GitHub CLI not found. Install it with: winget install --id GitHub.cli   then open a new terminal and run: gh auth login' }

Push-Location $repo
try {
    if (git status --porcelain) { Write-Warning 'You have uncommitted changes: they go into this build, but the build number only changes with a commit.' }
    if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -SkipTests }
    $build = (git rev-list --count HEAD).Trim()
    $tag = "build-$build"
    & $gh release view $tag *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "$tag is already published. Commit (and push) your changes to publish a new build."
        return
    }
    $apk = Join-Path $repo 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $apk)) { throw "APK not found: $apk (run without -NoBuild)" }
    $upload = Join-Path ([IO.Path]::GetTempPath()) 'lingo-lock.apk'
    Copy-Item $apk $upload -Force
    $notes = (git log -1 --pretty=%B) -join "`n"
    & $gh release create $tag $upload --title "Build $build" --notes $notes --target (git rev-parse HEAD)
    if ($LASTEXITCODE -ne 0) { throw 'Publishing failed. Is the commit pushed to GitHub?' }
    Write-Host "Published $tag. On the phone it shows up on the Home screen (or Settings > App updates > Check for updates)."
} finally {
    Pop-Location
}
