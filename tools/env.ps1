# Dot-source to use the repo-local toolchain in the current PowerShell session:
#     . .\tools\env.ps1
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME        = Join-Path $repoRoot '.toolchain\jdk'
$env:ANDROID_HOME     = Join-Path $repoRoot '.toolchain\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
