param(
    [string]$JavaHome = "",
    [string]$AndroidSdkRoot = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$MobileRoot = Join-Path $RepoRoot "mobile\android"

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $BundledJdk = Join-Path $env:USERPROFILE ".jdks\ms-17.0.18"
    $AndroidStudioJbr = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
    if (Test-Path (Join-Path $BundledJdk "bin\java.exe")) {
        $JavaHome = $BundledJdk
    } elseif (Test-Path (Join-Path $AndroidStudioJbr "bin\java.exe")) {
        $JavaHome = $AndroidStudioJbr
    } elseif (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $JavaHome = $env:JAVA_HOME
    }
}

if (-not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    throw "JDK 17 was not found. Pass -JavaHome C:\path\to\jdk17."
}

if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $AndroidSdkRoot = $env:ANDROID_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $AndroidSdkRoot = $env:ANDROID_SDK_ROOT
    } else {
        $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }
}

if (-not (Test-Path (Join-Path $AndroidSdkRoot "platforms\android-34\android.jar"))) {
    throw "Android SDK platform 34 was not found. Pass -AndroidSdkRoot C:\path\to\Android\Sdk."
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdkRoot
$env:ANDROID_SDK_ROOT = $AndroidSdkRoot
$env:Path = "$JavaHome\bin;$AndroidSdkRoot\platform-tools;$env:Path"

Push-Location $MobileRoot
try {
    & ".\gradlew.bat" assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }

    $ApkPath = Join-Path $MobileRoot "app\build\outputs\apk\debug\app-debug.apk"
    $Apk = Get-Item $ApkPath
    Write-Host "APK built: $($Apk.FullName)"
    Write-Host "Mode: standalone Android app with embedded UI and local Kubernetes API adapter"
    Write-Host ("Size: {0:N2} MB" -f ($Apk.Length / 1MB))
} finally {
    Pop-Location
}
