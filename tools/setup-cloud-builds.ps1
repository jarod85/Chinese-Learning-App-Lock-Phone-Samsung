<#
.SYNOPSIS
    One-time setup so GitHub builds Lingo Lock and the phone updates itself (no cable).

.DESCRIPTION
    Stores your signing key (keystore\) as encrypted secrets of this repository on GitHub, so the
    Release workflow (.github/workflows/release.yml) signs its builds with the same key as your PC.
    After that, every push to main is built and published as a release, and Lingo Lock on the phone
    offers it on the Home screen.

    Needs the GitHub CLI, signed in:
        winget install --id GitHub.cli
        gh auth login

.EXAMPLE
    .\tools\setup-cloud-builds.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { throw 'GitHub CLI not found. Install it with: winget install --id GitHub.cli   then open a new terminal and run: gh auth login' }
& $gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw 'Not signed in to GitHub. Run: gh auth login' }

$propsFile = Join-Path $repo 'keystore\keystore.properties'
if (-not (Test-Path $propsFile)) { throw "No signing key at $propsFile. Run tools\build.ps1 once first." }
$props = @{}
foreach ($line in Get-Content $propsFile) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2].Trim() }
}
$jks = Join-Path $repo $props['storeFile']
if (-not (Test-Path $jks)) { throw "Signing key file not found: $jks" }

Push-Location $repo
try {
    Write-Host 'Storing the signing key as encrypted GitHub secrets...'
    $secrets = [ordered]@{
        KEYSTORE_BASE64   = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
        KEYSTORE_PASSWORD = $props['storePassword']
        KEY_ALIAS         = $props['keyAlias']
        KEY_PASSWORD      = $props['keyPassword']
    }
    foreach ($name in $secrets.Keys) {
        & $gh secret set $name --body $secrets[$name]
        if ($LASTEXITCODE -ne 0) { throw "Couldn't store the secret $name" }
    }
    Write-Host 'Starting the first cloud build...'
    & $gh workflow run release.yml
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Push this repo to GitHub first (the workflow file must be on main); after that every push builds automatically.'
    } else {
        Write-Host 'Done. Each push to main now builds a release (about 5-10 minutes); the phone offers it on its Home screen.'
    }
} finally {
    Pop-Location
}
