<#
.SYNOPSIS
    Installs a self-contained Android build toolchain into <repo>\.toolchain (gitignored).

.DESCRIPTION
    Downloads only what is missing, so it is safe to re-run:
      * Eclipse Temurin JDK 17                  -> .toolchain\jdk
      * Android SDK command-line tools           -> .toolchain\android-sdk\cmdline-tools\latest
      * SDK packages: platform-tools, android-35, build-tools 35.0.0 (licenses are accepted)
    Then writes local.properties (sdk.dir) and creates a personal signing key in keystore\
    if there is none yet.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is ~10x slower with the progress bar

$Repo      = Split-Path -Parent $PSScriptRoot
$Toolchain = Join-Path $Repo '.toolchain'
$JdkDir    = Join-Path $Toolchain 'jdk'
$SdkDir    = Join-Path $Toolchain 'android-sdk'
$Downloads = Join-Path $Toolchain 'downloads'

$JdkUrl          = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'
$CmdlineToolsZip = 'commandlinetools-win-11076708_latest.zip'
$SdkPackages     = @('platform-tools', 'platforms;android-35', 'build-tools;35.0.0')

New-Item -ItemType Directory -Force -Path $Toolchain, $Downloads | Out-Null

function Save-Url([string]$Url, [string]$Path) {
    if (Test-Path $Path) { return }
    Write-Host "    downloading $Url"
    $part = "$Path.part"
    Invoke-WebRequest -Uri $Url -OutFile $part -UseBasicParsing
    Move-Item -Force $part $Path
}

# Runs a native command without letting its stderr chatter abort the script
# (Windows PowerShell 5.1 turns redirected stderr into terminating errors under 'Stop').
function Invoke-Native([scriptblock]$Block) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Block 2>&1 | ForEach-Object { "$_" } |
            Where-Object { $_ -notmatch '^\s*\[=*\s*\]|^\s*$' } |
            ForEach-Object { Write-Host "    $_" }
    } finally {
        $ErrorActionPreference = $old
    }
}

function Expand-Zip([string]$Zip, [string]$Dest) {
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    # bsdtar ships with Windows 10+ and is far faster than Expand-Archive.
    Invoke-Native { & tar.exe -xf $Zip -C $Dest }
    if ($LASTEXITCODE -ne 0) { throw "Failed to extract $Zip" }
}

function Test-SdkPackage([string]$Package) {
    Test-Path (Join-Path $SdkDir ($Package -replace ';', '\'))
}

# 1. JDK -------------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $JdkDir 'bin\java.exe'))) {
    Write-Host '[1/4] Temurin JDK 17'
    $zip = Join-Path $Downloads 'temurin-jdk17.zip'
    Save-Url $JdkUrl $zip
    $tmp = Join-Path $Toolchain 'jdk-tmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Zip $zip $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (Test-Path $JdkDir) { Remove-Item -Recurse -Force $JdkDir }
    Move-Item $inner.FullName $JdkDir
    Remove-Item -Recurse -Force $tmp
} else {
    Write-Host '[1/4] JDK already installed'
}
$env:JAVA_HOME = $JdkDir
$env:Path = "$JdkDir\bin;$env:Path"

# 2. Android command-line tools ---------------------------------------------------------
$sdkmanager = Join-Path $SdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    Write-Host '[2/4] Android SDK command-line tools'
    $zip = Join-Path $Downloads $CmdlineToolsZip
    Save-Url "https://dl.google.com/android/repository/$CmdlineToolsZip" $zip
    $tmp = Join-Path $Toolchain 'cmdline-tmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Zip $zip $tmp
    $latest = Join-Path $SdkDir 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $latest) | Out-Null
    if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
    Move-Item (Join-Path $tmp 'cmdline-tools') $latest
    Remove-Item -Recurse -Force $tmp
} else {
    Write-Host '[2/4] cmdline-tools already installed'
}

# 3. SDK packages ------------------------------------------------------------------------
$missing = @($SdkPackages | Where-Object { -not (Test-SdkPackage $_) })
if ($missing.Count -gt 0) {
    Write-Host "[3/4] SDK packages: $($missing -join ', ')"
    # sdkmanager wants "y" typed at every license prompt. Feeding stdin from a file inside a
    # small .cmd script is the only way that works reliably from Windows PowerShell.
    $yesFile = Join-Path $Downloads 'yes.txt'
    Set-Content -Encoding ASCII -Path $yesFile -Value (@('y') * 60)
    $pkgArgs = ($missing | ForEach-Object { "`"$_`"" }) -join ' '
    $script = Join-Path $Downloads 'sdk-install.cmd'
    @(
        '@echo off'
        "set `"JAVA_HOME=$JdkDir`""
        "call `"$sdkmanager`" --licenses < `"$yesFile`" > nul"
        "call `"$sdkmanager`" $pkgArgs < `"$yesFile`""
    ) | Set-Content -Encoding ASCII -Path $script
    Invoke-Native { & cmd.exe /d /c $script }
    $still = @($SdkPackages | Where-Object { -not (Test-SdkPackage $_) })
    if ($still.Count -gt 0) { throw "sdkmanager failed to install: $($still -join ', ')" }
} else {
    Write-Host '[3/4] SDK packages already installed'
}

# 4. local.properties + personal signing key ---------------------------------------------
Write-Host '[4/4] local.properties and signing key'
$escaped = $SdkDir.Replace('\', '\\').Replace(':', '\:')
Set-Content -Encoding ASCII -Path (Join-Path $Repo 'local.properties') -Value "sdk.dir=$escaped"

$ksDir   = Join-Path $Repo 'keystore'
$ksProps = Join-Path $ksDir 'keystore.properties'
$ksFile  = Join-Path $ksDir 'hanzilock.jks'
if (-not (Test-Path $ksProps)) {
    New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
    if (Test-Path $ksFile) { Remove-Item -Force $ksFile }   # left over from an interrupted run
    $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    $keytool = Join-Path $JdkDir 'bin\keytool.exe'
    Invoke-Native {
        & $keytool -genkeypair -keystore $ksFile -storetype PKCS12 -alias hanzilock `
            -keyalg RSA -keysize 2048 -validity 36500 -storepass $pw -keypass $pw `
            -dname 'CN=HanziLock, O=Personal'
    }
    if (-not (Test-Path $ksFile)) { throw 'keytool failed to create the signing key' }
    @(
        'storeFile=keystore/hanzilock.jks'
        "storePassword=$pw"
        'keyAlias=hanzilock'
        "keyPassword=$pw"
    ) | Set-Content -Encoding ASCII -Path $ksProps
    Write-Host '    created keystore\hanzilock.jks - back up the keystore\ folder; updates must use the same key'
}

Write-Host 'Toolchain ready.'
