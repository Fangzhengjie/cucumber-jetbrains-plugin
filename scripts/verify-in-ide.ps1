<#
.SYNOPSIS
  Runs a JetBrains IDE headless with the built plugin and checks Cucumber step resolution.

.DESCRIPTION
  Uses a throwaway sandbox (config, system, plugins, a copy of the project), so neither the
  IDE's settings nor the project are touched. Gherkin is disabled in the sandbox to verify the
  plugin's standalone feature-file support.
  -Control runs without this plugin, to show the baseline.

.EXAMPLE
  ./scripts/verify-in-ide.ps1
  ./scripts/verify-in-ide.ps1 -IdeHome "$env:LOCALAPPDATA\Programs\WebStorm"
  ./scripts/verify-in-ide.ps1 -Project C:\src\my-service -Control
#>
param(
  [string]$IdeHome = "$env:LOCALAPPDATA\Programs\RustRover",
  [string]$Project = (Join-Path $PSScriptRoot '..\src\test\resources\testdata\multi-language'),
  [switch]$Control,
  [switch]$KeepSandbox,
  # Keep the project's .idea (e.g. to check a project whose module setup does not cover its files).
  [switch]$KeepProjectConfig,
  [int]$ExpectUndefined = 0,
  [int]$ExpectAmbiguous = 0
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$product = Get-Content (Join-Path $IdeHome 'product-info.json') -Raw | ConvertFrom-Json
$sandbox = Join-Path ([IO.Path]::GetTempPath()) "cucumber-jetbrains-plugin-ide-$([guid]::NewGuid().ToString('N').Substring(0, 8))"

try {
  foreach ($dir in 'config', 'system', 'plugins', 'log', 'out') { New-Item -ItemType Directory -Force (Join-Path $sandbox $dir) | Out-Null }
  Set-Content -Encoding ascii (Join-Path $sandbox 'config\disabled_plugins.txt') 'gherkin'
  if (-not $Control) {
    $built = Join-Path $root 'build\distributions\cucumber-jetbrains-plugin'
    if (-not (Test-Path $built)) { throw 'Build the plugin first: ./build.ps1' }
    Copy-Item -Recurse $built (Join-Path $sandbox 'plugins\cucumber-jetbrains-plugin')
  }

  $projectCopy = Join-Path $sandbox 'project'
  Copy-Item -Recurse (Resolve-Path $Project) $projectCopy
  Get-ChildItem $projectCopy -Directory -Recurse -Force |
    Where-Object { $_.Name -in 'target', 'node_modules', '.git' -or ($_.Name -eq '.idea' -and -not $KeepProjectConfig) } |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

  $paths = @{ config = 'config'; system = 'system'; plugins = 'plugins'; log = 'log' }
  $properties = ($paths.GetEnumerator() | ForEach-Object { "idea.$($_.Key).path=" + ((Join-Path $sandbox $_.Value) -replace '\\', '/') }) -join "`n"
  Set-Content -Encoding ascii (Join-Path $sandbox 'idea.properties') $properties
  @'
<component name="InspectionProjectProfileManager">
  <profile version="1.0">
    <option name="myName" value="cucumber-steps" />
    <inspection_tool class="CucumberStepResolution" enabled="true" level="WARNING" enabled_by_default="true" />
  </profile>
</component>
'@ | Set-Content -Encoding utf8 (Join-Path $sandbox 'profile.xml')

  $envName = "$($product.envVarBaseName)_PROPERTIES"
  [Environment]::SetEnvironmentVariable($envName, (Join-Path $sandbox 'idea.properties'))
  $out = Join-Path $sandbox 'out'
  # Under Windows PowerShell 5.1 with ErrorActionPreference=Stop, redirected native stderr would be terminating.
  $previousPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & (Join-Path $IdeHome 'bin\inspect.bat') $projectCopy (Join-Path $sandbox 'profile.xml') $out -v1 -format xml *> (Join-Path $sandbox 'inspect.log')
  } finally {
    $ErrorActionPreference = $previousPreference
  }
  if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $sandbox 'inspect.log') -Tail 30; throw "inspect failed (exit $LASTEXITCODE)" }

  $report = Join-Path $out 'CucumberStepResolution.xml'
  if (-not $Control -and ($ExpectUndefined -gt 0 -or $ExpectAmbiguous -gt 0) -and -not (Test-Path $report)) {
    throw 'The standalone Cucumber step inspection did not run; check that the feature file type was loaded.'
  }
  $problems = if (Test-Path $report) { @(([xml](Get-Content $report -Raw)).problems.problem) } else { @() }
  $undefined = @($problems | Where-Object { $_.description -like 'Undefined*' })
  $ambiguous = @($problems | Where-Object { $_.description -like 'Ambiguous*' })
  $features = @(Get-ChildItem $projectCopy -Recurse -Filter *.feature).Count
  $mode = if ($Control) { 'without the plugin' } else { 'with standalone Cucumber' }
  Write-Host "$($product.name) $($product.buildNumber), $mode, $features feature files: $($undefined.Count) undefined, $($ambiguous.Count) ambiguous steps"
  $problems | Group-Object description | Sort-Object Count -Descending | Select-Object -First 8 |
    ForEach-Object { Write-Host ("  {0,4} x {1}" -f $_.Count, $_.Name) }

  # Any error whose stack trace runs through this plugin (the frames are on later lines).
  $pluginErrors = @()
  $logFile = Join-Path $sandbox 'log\idea.log'
  if (Test-Path $logFile) {
    $lines = Get-Content $logFile
    for ($i = 0; $i -lt $lines.Count; $i++) {
      if ($lines[$i] -match ' (SEVERE|ERROR) - ') {
        $trace = @($lines[$i])
        for ($j = $i + 1; $j -lt $lines.Count -and $lines[$j] -notmatch '^\d{4}-\d\d-\d\d '; $j++) { $trace += $lines[$j] }
        if (($trace -join "`n") -match 'cucumber\.jetbrains\.plugin') { $pluginErrors += ($trace | Select-Object -First 12) -join "`n" }
      }
    }
  }
  if ($pluginErrors) {
    $pluginErrors | Select-Object -First 3 | ForEach-Object { Write-Host "  log:`n$_" }
    throw 'The plugin logged errors.'
  }
  if (-not $Control -and ($undefined.Count -ne $ExpectUndefined -or $ambiguous.Count -ne $ExpectAmbiguous)) {
    Write-Host "Expected $ExpectUndefined undefined and $ExpectAmbiguous ambiguous."
    exit 1
  }
} finally {
  [Environment]::SetEnvironmentVariable("$($product.envVarBaseName)_PROPERTIES", $null)
  if ($KeepSandbox) {
    Write-Host "Sandbox kept: $sandbox"
  } else {
    Remove-Item -Recurse -Force $sandbox -ErrorAction SilentlyContinue
  }
}
