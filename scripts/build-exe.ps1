param(
    [string]$JPackage = "jpackage",
    [string]$Maven = "mvn",
    [string]$AppName = "KubernetesLensUIDesktop",
    [string]$AppVersion = "0.0.1",
    [int]$Port = 0,
    [switch]$Installer,
    [switch]$Web,
    [switch]$Console
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$target = Join-Path $root "target"
$dist = Join-Path $root "dist"
$icon = Join-Path $root "src\main\resources\static\favicon.ico"

$jpackagePath = $null
if (Test-Path -LiteralPath $JPackage -PathType Leaf) {
    $jpackagePath = (Resolve-Path $JPackage).Path
} else {
    $jpackageCommand = Get-Command $JPackage -ErrorAction SilentlyContinue
    if ($jpackageCommand) {
        $jpackagePath = $jpackageCommand.Source
    }
}

if (-not $jpackagePath) {
    $ideaJpackages = Get-ChildItem -Path (Join-Path $HOME ".jdks") -Recurse -Filter "jpackage.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName
    $jpackagePath = $ideaJpackages |
        Where-Object { $_.FullName -match "17" } |
        Select-Object -First 1 -ExpandProperty FullName

    if (-not $jpackagePath) {
        $jpackagePath = $ideaJpackages | Select-Object -First 1 -ExpandProperty FullName
    }
}

if (-not $jpackagePath) {
    throw "jpackage was not found. Install JDK 17 or newer, or pass -JPackage C:\path\to\jpackage.exe."
}

$jdkHome = Split-Path (Split-Path $jpackagePath -Parent) -Parent
$env:JAVA_HOME = $jdkHome
$env:Path = (Join-Path $jdkHome "bin") + [System.IO.Path]::PathSeparator + $env:Path
Write-Host "Using JDK: $jdkHome"

Write-Host "Building Spring Boot jar..."
Push-Location $root
try {
    $mavenArgs = @("-q", "-DskipTests", "clean", "package")
    if (-not $Web) {
        $mavenArgs = @("-q", "-Pdesktop", "-DskipTests", "clean", "package")
    }

    & $Maven @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$jar = Get-ChildItem -Path $target -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "No packaged jar found in $target"
}

$packageInput = Join-Path $target "jpackage-input"
if (Test-Path -LiteralPath $packageInput) {
    Remove-Item -LiteralPath $packageInput -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $packageInput | Out-Null
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $packageInput $jar.Name)

New-Item -ItemType Directory -Force -Path $dist | Out-Null

$packageType = if ($Installer) { "exe" } else { "app-image" }
$outputApp = Join-Path $dist $AppName
if (-not $Installer -and (Test-Path -LiteralPath $outputApp)) {
    Remove-Item -LiteralPath $outputApp -Recurse -Force
}

$args = @(
    "--type", $packageType,
    "--name", $AppName,
    "--app-version", $AppVersion,
    "--vendor", "Codex",
    "--input", $packageInput,
    "--main-jar", $jar.Name,
    "--dest", $dist
)

if ($Port -gt 0) {
    $args += @("--java-options", "-Dserver.port=$Port")
}

if ($Console) {
    $args += "--win-console"
}

if (Test-Path -LiteralPath $icon) {
    $args += @("--icon", $icon)
}

Write-Host "Packaging $AppName with jpackage..."
& $jpackagePath @args
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

if ($Installer) {
    Write-Host "Installer output:"
    Get-ChildItem -Path $dist -Filter "*.exe" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
} else {
    Write-Host "Executable output:"
    Write-Host (Join-Path $outputApp "$AppName.exe")
}
