# Creates the private GitHub repository your phone and Mac sync through (Settings > Sync between your
# devices), then opens GitHub's page for the token each device needs. Run once, from the repo root:
#     .\tools\setup-sync.ps1
# Needs the GitHub CLI, signed in: winget install --id GitHub.cli, then gh auth login.
param([string]$Name = 'lingo-lock-data')
$ErrorActionPreference = 'Stop'

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = 'C:\Program Files\GitHub CLI\gh.exe' }
if (-not (Test-Path $gh)) { throw 'GitHub CLI not found. Install it (winget install --id GitHub.cli), run gh auth login, then try again.' }

$owner = (& $gh api user --jq .login).Trim()
$repo = "$owner/$Name"
# "Not found" arrives on stderr, which Windows PowerShell would treat as a fatal error here.
$ErrorActionPreference = 'Continue'
& $gh repo view $repo --json name *> $null
$exists = $LASTEXITCODE -eq 0
$ErrorActionPreference = 'Stop'
if ($exists) {
    Write-Host "$repo already exists."
} else {
    & $gh repo create $repo --private --add-readme --description 'Lingo Lock: words, progress and stories synced between my devices'
    if ($LASTEXITCODE -ne 0) { throw "Couldn't create $repo." }
    Write-Host "Created the private repository $repo."
}

Write-Host @"

Now make a token that can only use that repository (GitHub opens the page):
  1. Token name: Lingo Lock sync. Expiration: as long as you like (you'll paste a new one when it runs out).
  2. Repository access: Only select repositories > $Name
  3. Permissions > Repository permissions > Contents: Read and write
  4. Generate token, and copy it.

Then on the phone and on the Mac: Settings > Sync between your devices
  Repository:    $repo
  GitHub token:  (the token)
  and tap Save & sync now.
"@
Start-Process 'https://github.com/settings/personal-access-tokens/new'
