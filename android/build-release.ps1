# Builds the signed release APK (com.eko.dcvisits) for DCs.
# Run it yourself in PowerShell:   cd Downloads\eko-dc-visits\android ; .\build-release.ps1
# It asks for the keystore passwords and never saves them anywhere.

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$env:JAVA_HOME = (Get-ChildItem "$HOME\tools" -Directory -Filter "jdk-17*")[0].FullName
$env:ANDROID_HOME = "$HOME\tools\android-sdk"
$apiUrl = "https://122.176.147.78/dc-visits/api/v1/"

function Plain([Security.SecureString]$s) {
    [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
}

$env:EKO_KEYSTORE_FILE = "eko-dcvisits.jks"
$alias = Read-Host "Key alias (ask your teammate; press Enter to list aliases first)"
if ([string]::IsNullOrWhiteSpace($alias)) {
    & "$env:JAVA_HOME\bin\keytool.exe" -list -keystore eko-dcvisits.jks
    $alias = Read-Host "Key alias"
}
$env:EKO_KEY_ALIAS = $alias
$env:EKO_KEYSTORE_PASSWORD = Plain (Read-Host "Keystore password" -AsSecureString)
$kp = Plain (Read-Host "Key password (press Enter if same as keystore password)" -AsSecureString)
$env:EKO_KEY_PASSWORD = if ([string]::IsNullOrEmpty($kp)) { $env:EKO_KEYSTORE_PASSWORD } else { $kp }

try {
    & .\gradlew.bat --no-daemon ":app:assembleRelease" "-PapiBaseUrl=$apiUrl"
    if ($LASTEXITCODE -ne 0) { throw "Build failed (see output above)" }

    $apk = Get-ChildItem "app\build\outputs\apk\release\*.apk" | Where-Object { $_.Name -notlike "*unsigned*" } | Select-Object -First 1
    if (-not $apk) { throw "No signed APK produced - check the alias/passwords" }

    & "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw "Signature check failed" }

    $out = "$HOME\Downloads\DC_App\eko-dc-visits-0.14.0.apk"
    Copy-Item $apk.FullName $out -Force
    Write-Host "`nDONE: $out  <- give THIS file to the DCs" -ForegroundColor Green
}
finally {
    Remove-Item Env:EKO_KEYSTORE_PASSWORD, Env:EKO_KEY_PASSWORD -ErrorAction SilentlyContinue
}
