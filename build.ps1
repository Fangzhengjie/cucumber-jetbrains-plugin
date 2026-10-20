<#
.SYNOPSIS
  Convenience wrapper around the Gradle build (./gradlew.bat buildPlugin selfTest).

.DESCRIPTION
  1. Runs SelfTest (unit cases, snippet round-trips and the src/test/resources/testdata/multi-language
     fixture project). Extra projects can be checked with -Project.
  2. Builds build/distributions/cucumber-jetbrains-plugin-<version>.zip with the IntelliJ Platform
     Gradle Plugin (install with Settings | Plugins | gear | Install Plugin from Disk...).
  -Install copies the plugin into the IDE's plugin directory (the IDE must not be running).

.EXAMPLE
  ./build.ps1
  ./build.ps1 -Project C:\src\my-service -Install -IdeHome "$env:LOCALAPPDATA\Programs\IntelliJ IDEA"
#>
param(
  [string]$IdeHome,
  [string[]]$Project = @(),
  [switch]$Install,
  [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$distributions = Join-Path $root 'build\distributions'

function Find-Ide {
  $candidates = @('IntelliJ IDEA', 'RustRover', 'GoLand', 'PyCharm', 'WebStorm', 'Rider', 'CLion', 'RubyMine')
  foreach ($base in @("$env:LOCALAPPDATA\Programs", "$env:ProgramFiles\JetBrains")) {
    foreach ($name in $candidates) {
      $dir = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "$name*" } | Select-Object -First 1
      if ($dir -and (Test-Path (Join-Path $dir.FullName 'product-info.json'))) { return $dir.FullName }
    }
  }
  throw 'No JetBrains IDE found; pass -IdeHome.'
}

$gradleArgs = @('buildPlugin', '--console=plain', '-q')
if (-not $SkipTests) { $gradleArgs += 'selfTest' }
if ($IdeHome) { $gradleArgs += "-PplatformLocalPath=$IdeHome" }
if ($Project.Count -gt 0) { $gradleArgs += "-PcucumberProjects=$($Project -join ',')" }
& (Join-Path $root 'gradlew.bat') -p $root @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)" }

$version = (Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^\s*pluginVersion\s*=' }) -replace '^\s*pluginVersion\s*=\s*', ''
$zip = Join-Path $distributions "cucumber-jetbrains-plugin-$($version.Trim()).zip"
$pluginDir = Join-Path $distributions 'cucumber-jetbrains-plugin'
Remove-Item -Recurse -Force $pluginDir -ErrorAction SilentlyContinue
Expand-Archive -Path $zip -DestinationPath $distributions -Force
Write-Host "Plugin: $zip"

if ($Install) {
  if (-not $IdeHome) { $IdeHome = Find-Ide }
  $product = Get-Content (Join-Path $IdeHome 'product-info.json') -Raw | ConvertFrom-Json
  $running = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($IdeHome, 'OrdinalIgnoreCase') }
  if ($running) { throw "$($product.name) is running; close it before -Install (or install the zip from disk)." }
  $target = Join-Path $env:APPDATA "JetBrains\$($product.dataDirectoryName)\plugins\cucumber-jetbrains-plugin"
  Remove-Item -Recurse -Force $target -ErrorAction SilentlyContinue
  Copy-Item -Recurse $pluginDir $target
  Write-Host "Installed into $target"
}
