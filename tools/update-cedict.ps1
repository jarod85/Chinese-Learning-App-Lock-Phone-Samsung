<#
.SYNOPSIS
    Refreshes the bundled CC-CEDICT dictionary (content\dictionary) from mdbg.net.

.DESCRIPTION
    CC-CEDICT is licensed CC BY-SA 4.0 (https://cc-cedict.org/wiki/). The app re-imports the
    dictionary automatically when the file's "#! date=" header changes.
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repo = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $repo 'content\dictionary\cedict_1_0_ts_utf-8_mdbg.txt.gz'
$part = "$dest.part"
Invoke-WebRequest -UseBasicParsing -Uri 'https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz' -OutFile $part
Move-Item -Force $part $dest
Write-Host "Updated $dest ($([math]::Round((Get-Item $dest).Length / 1MB, 1)) MB). Rebuild and reinstall the app."
