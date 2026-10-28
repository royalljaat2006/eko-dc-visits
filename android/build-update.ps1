# Builds the next DC app update, signed with this PC's debug key (the key DCs have since 0.14.0),
# so DCs can install it over the old app without uninstalling.
#   .\build-update.ps1 -Version 0.14.1        bump version, build, copy to DC-Visit\06_Send_to_DCs
#   .\build-update.ps1 -NoBump                rebuild the current version (no version change)
# Lose ~\.android\debug.keystore and DCs must uninstall again: a backup is in
# DC-Visit\03_Release_0.14.0\KEEP_SAFE_app_update_key\debug.keystore.

param([string]$Version, [switch]$NoBump)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$env:JAVA_HOME = (Get-ChildItem "$HOME\tools" -Directory -Filter "jdk-17*")[0].FullName
$env:ANDROID_HOME = "$HOME\tools\android-sdk"
$apiUrl = "https://122.176.147.78/dc-visits/api/v1/"
$expectedKey = "b5c98a2a076dfc7cf112625441790cedf4cc489dafa046468e72b73bc8cd2186"
$outDir = Resolve-Path "$here\..\..\..\06_Send_to_DCs"

$gradleFile = "app\build.gradle.kts"
$text = Get-Content $gradleFile -Raw
$code = [int]([regex]::Match($text, 'versionCode = (\d+)').Groups[1].Value)
$name = [regex]::Match($text, 'versionName = "([^"]+)"').Groups[1].Value

if (-not $NoBump) {
    if (-not $Version) { throw "Give the new version, e.g. .\build-update.ps1 -Version 0.14.1" }
    if ($Version -eq $name) { throw "Version $Version is already the current version - pick a higher one" }
    $code++
    $name = $Version
    $text = $text -replace 'versionCode = \d+', "versionCode = $code" -replace 'versionName = "[^"]+"', "versionName = `"$name`""
    [IO.File]::WriteAllText((Resolve-Path $gradleFile), $text)
    Write-Host "Version -> $name (code $code)"
}

& .\gradlew.bat --no-daemon ":app:assembleDebug" "-PapiBaseUrl=$apiUrl"
if ($LASTEXITCODE -ne 0) { throw "Build failed (see output above)" }

$apk = "app\build\outputs\apk\debug\app-debug.apk"
$certs = & "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "Signature check failed" }
if (-not ($certs -match $expectedKey)) { throw "APK is NOT signed with the DCs' key - DCs could not update. Restore debug.keystore from KEEP_SAFE_app_update_key." }

$out = Join-Path $outDir "Eko_DC_App_$name.apk"
Copy-Item $apk $out -Force
Write-Host "`nDONE: $out  <- send THIS file to the DCs (they install it over the old app)" -ForegroundColor Green
