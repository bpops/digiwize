$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$srcDir = Join-Path $projectRoot "src/main/java"
$buildDir = Join-Path $projectRoot "build"
$classesDir = Join-Path $buildDir "classes"
$distDir = Join-Path $projectRoot "dist"
$jarFile = Join-Path $distDir "digiwize.jar"
$mainClass = "com.digiwize.Digiwize"
$appVersion = "1.0"

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw "javac was not found. Install a JDK and try again."
}

if (-not (Get-Command jar -ErrorAction SilentlyContinue)) {
    throw "jar was not found. Install a JDK and try again."
}

Remove-Item -Recurse -Force $buildDir, $distDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classesDir, $distDir | Out-Null

$sources = Get-ChildItem -Path $srcDir -Filter *.java -Recurse | Sort-Object FullName
if ($sources.Count -eq 0) {
    throw "No Java source files found under $srcDir."
}

$sourcesFile = Join-Path $buildDir "sources.txt"
$sources | ForEach-Object { '"' + $_.FullName + '"' } | Set-Content -Encoding UTF8 $sourcesFile

Write-Host "Compiling digiwize..."
javac -encoding UTF-8 -d $classesDir "@$sourcesFile"

Write-Host "Running self-test..."
java -Djava.awt.headless=true -cp $classesDir $mainClass --self-test

$manifestFile = Join-Path $buildDir "manifest.mf"
@(
    "Implementation-Title: digiwize"
    "Implementation-Version: $appVersion"
) | Set-Content -Encoding ASCII $manifestFile

Write-Host "Packaging $jarFile..."
jar --create --file $jarFile --main-class $mainClass --manifest $manifestFile -C $classesDir .

@'
#!/usr/bin/env zsh
set -euo pipefail
script_dir=${0:A:h}
java -jar "${script_dir}/digiwize.jar"
'@ | Set-Content -Encoding UTF8 (Join-Path $distDir "run-digiwize.zsh")

@'
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
java -jar (Join-Path $scriptDir "digiwize.jar")
'@ | Set-Content -Encoding UTF8 (Join-Path $distDir "run-digiwize.ps1")

Write-Host "Built $jarFile"
Write-Host "Run with: java -jar $jarFile"
