# Builds a debug APK and installs it. Run from any shell:
#   powershell -File tools/build-install.ps1 [-Flavor personal|store] [-Serial <adb serial>] [-Launch]
# personal = the Android Auto parked-app build for Gaurav's car; store = the video-category build.
param([string]$Flavor = "personal", [string]$Serial = "", [switch]$Launch)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$task = ":app:assemble" + (Get-Culture).TextInfo.ToTitleCase($Flavor) + "Debug"
.\gradlew.bat $task --console=plain -q
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED"; exit 1 }
$apk = "app\build\outputs\apk\$Flavor\debug\app-$Flavor-debug.apk"
Write-Host ("built " + (Get-Item $apk).LastWriteTime)
$target = @()
if ($Serial) { $target = @("-s", $Serial) }
& adb @target install -r $apk
if ($Launch) {
  & adb @target shell am force-stop com.thealgothrim.dwellplay.debug
  & adb @target shell am start -n com.thealgothrim.dwellplay.debug/com.thealgothrim.dwellplay.MainActivity
}
