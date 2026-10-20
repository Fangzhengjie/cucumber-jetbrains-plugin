<#
.SYNOPSIS
  Formats a copy of a messy .feature file with the IDE's command-line formatter (bin\format.bat)
  and the built plugin, to check that Code | Reformat Code goes through the plugin's formatter.

.EXAMPLE
  ./build.ps1 -SkipTests; ./scripts/format-in-ide.ps1
#>
param(
  [string]$IdeHome = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA",
  [switch]$KeepSandbox
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$product = Get-Content (Join-Path $IdeHome 'product-info.json') -Raw | ConvertFrom-Json
$sandbox = Join-Path $root "build\ide-format-sandbox-$([guid]::NewGuid().ToString('N').Substring(0, 8))"

$messy = @(
  '@smoke   ', '  Feature: Shopping', ' # comment', '     Scenario Outline: buy <n>', ' Given a store  ',
  '  When I buy <n> items', '   Examples:', ' |n|total|', '   |  10 | 1\|2 |', ''
) -join "`n"
$expected = @(
  '@smoke', 'Feature: Shopping', '  # comment', '  Scenario Outline: buy <n>', '    Given a store',
  '    When I buy <n> items', '    Examples:', '      | n  | total |', '      | 10 | 1\|2  |', ''
) -join "`n"

try {
  foreach ($dir in 'config', 'system', 'plugins', 'log', 'project') { New-Item -ItemType Directory -Force (Join-Path $sandbox $dir) | Out-Null }
  Set-Content -Encoding ascii (Join-Path $sandbox 'config\disabled_plugins.txt') 'gherkin'
  $built = Join-Path $root 'build\distributions\cucumber-jetbrains-plugin'
  if (-not (Test-Path $built)) { throw 'Build the plugin first: ./build.ps1' }
  Copy-Item -Recurse $built (Join-Path $sandbox 'plugins\cucumber-jetbrains-plugin')
  $feature = Join-Path $sandbox 'project\messy.feature'
  [IO.File]::WriteAllText($feature, $messy, (New-Object Text.UTF8Encoding $false))

  $paths = @{ config = 'config'; system = 'system'; plugins = 'plugins'; log = 'log' }
  $properties = ($paths.GetEnumerator() | ForEach-Object { "idea.$($_.Key).path=" + ((Join-Path $sandbox $_.Value) -replace '\\', '/') }) -join "`n"
  Set-Content -Encoding ascii (Join-Path $sandbox 'idea.properties') $properties
  [Environment]::SetEnvironmentVariable("$($product.envVarBaseName)_PROPERTIES", (Join-Path $sandbox 'idea.properties'))

  # Under Windows PowerShell 5.1 with ErrorActionPreference=Stop, redirected native stderr would be terminating.
  $previousPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & (Join-Path $IdeHome 'bin\format.bat') -allowDefaults $feature *> (Join-Path $sandbox 'format.log')
  } finally {
    $ErrorActionPreference = $previousPreference
  }
  if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $sandbox 'format.log') -Tail 20; throw "format.bat failed (exit $LASTEXITCODE)" }
  $actual = [IO.File]::ReadAllText($feature)
  if ($actual -ne $expected) {
    Get-Content (Join-Path $sandbox 'format.log') -Tail 20
    Write-Host "--- actual:`n$actual"
    throw 'format.bat did not produce the expected Gherkin formatting.'
  }
  Write-Host "$($product.name) $($product.buildNumber): format.bat formatted the feature file as expected."
} finally {
  [Environment]::SetEnvironmentVariable("$($product.envVarBaseName)_PROPERTIES", $null)
  if ($KeepSandbox) { Write-Host "Sandbox kept: $sandbox" } else { cmd /c "rd /s /q `"\\?\$sandbox`" 2>nul" }
}
