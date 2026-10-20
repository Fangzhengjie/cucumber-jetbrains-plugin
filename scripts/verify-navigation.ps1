<#
.SYNOPSIS
  Proves step-definition -> feature navigation (gutter markers, Ctrl+click, StepUsageIndex) in a real headless IDE.

.DESCRIPTION
  Compiles the probe in scripts\verify-navigation (an ApplicationStarter in a separate, throwaway
  plugin that is never part of the shipped plugin) into build\verify-harness, installs it next to the
  built plugin in a sandbox (like verify-in-ide.ps1: own config/system/plugins, gherkin disabled, a
  copy of the project) and runs it. Build the plugin first: ./build.ps1 -SkipTests

.EXAMPLE
  ./scripts/verify-navigation.ps1
  ./scripts/verify-navigation.ps1 -Crlf -Module
  ./scripts/verify-navigation.ps1 -Results   # needs the Maven project in build\e2e\project
  ./scripts/verify-navigation.ps1 -Completion   # step completion + live template in java.feature / zh_cn.feature
  ./scripts/verify-navigation.ps1 -ToolWindow   # tool window toolbar + Run Selected / Run All on build\e2e\project
  ./scripts/verify-navigation.ps1 -ToolWindow -Project src\test\resources\testdata\multi-language
#>
param(
  [string]$IdeHome = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA",
  [string]$Project = (Join-Path $PSScriptRoot '..\src\test\resources\testdata\multi-language'),
  [string[]]$Files = @('steps/Steps.java', 'steps/Steps.kt', 'steps/steps.go', 'steps/steps.rs', 'steps/js_steps.js'),
  # Convert the project copy's step definition files to CRLF line endings.
  [switch]$Crlf,
  # Give the project copy a module with steps/ as a test source root (as in a real Maven/Gradle project).
  [switch]$Module,
  # Run the Cucumber run configuration of build\e2e\project's feature and report the result gutter icons.
  [switch]$Results,
  # feature: unused. Add unused step definitions to the project copy, then run the UnusedProbe
  # (find them, delete them, print -Files, check every step still resolves, undo).
  [switch]$Unused,
  # feature: completion. Run the CompletionProbe on java.feature and zh_cn.feature.
  [switch]$Completion,
  # Create the Cucumber tool window, report its toolbar actions and click Run Selected / Run All
  # (build\e2e\project by default; with -Project, only report the actions).
  [switch]$ToolWindow,
  [switch]$KeepSandbox
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
if ($Completion -and -not $PSBoundParameters.ContainsKey('Files')) {
  $Files = @('completion:features/java.feature', 'completion:features/zh_cn.feature')
}
if ($Results) {
  $Project = Join-Path $root 'build\e2e\project'
  $Files = @('results:src/test/resources/e2e/eating.feature')
}
if ($ToolWindow -and -not $PSBoundParameters.ContainsKey('Project')) {
  $Project = Join-Path $root 'build\e2e\project'
  if (-not $PSBoundParameters.ContainsKey('Files')) {
    $Files = @('toolwindow:src/test/resources/e2e/eating.feature@Eat some', 'toolwindow-all:')
  }
} elseif ($ToolWindow -and -not $PSBoundParameters.ContainsKey('Files')) {
  $Files = @('toolwindow-norun:')
}
$product = Get-Content (Join-Path $IdeHome 'product-info.json') -Raw | ConvertFrom-Json
$built = Join-Path $root 'build\distributions\cucumber-jetbrains-plugin'
if (-not (Test-Path $built)) { throw 'Build the plugin first: ./build.ps1 -SkipTests' }
$harness = Join-Path $root 'build\verify-harness'
$sandbox = Join-Path $harness "sandbox-$([guid]::NewGuid().ToString('N').Substring(0, 8))"

$previousPreference = $ErrorActionPreference
try {
  # Compile the probe against the IDE and the built plugin.
  $classes = Join-Path $harness 'classes'
  Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force $classes | Out-Null
  $pluginJar = (Get-ChildItem (Join-Path $built 'lib') -Filter *.jar | Select-Object -First 1).FullName
  $classpath = "$IdeHome\lib\*;$IdeHome\plugins\platform-testRunner-plugin\lib\*;$IdeHome\plugins\platform-testRunner-plugin\lib\modules\*;$pluginJar"
  $sources = Get-ChildItem (Join-Path $PSScriptRoot 'verify-navigation\src') -Recurse -Filter *.java | ForEach-Object FullName
  $javacArgs = @('-nowarn', '-proc:none', '--release', '21', '-cp', $classpath, '-d', "$classes") + @($sources)
  $ErrorActionPreference = 'Continue'
  & javac @javacArgs 2>&1 | Where-Object { "$_" -notmatch '^Note:' } | ForEach-Object { Write-Host "$_" }
  $ErrorActionPreference = $previousPreference
  if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
  Copy-Item -Recurse (Join-Path $PSScriptRoot 'verify-navigation\META-INF') $classes

  foreach ($dir in 'config', 'system', 'plugins', 'log') { New-Item -ItemType Directory -Force (Join-Path $sandbox $dir) | Out-Null }
  Set-Content -Encoding ascii (Join-Path $sandbox 'config\disabled_plugins.txt') 'gherkin'
  Copy-Item -Recurse $built (Join-Path $sandbox 'plugins\cucumber-jetbrains-plugin')
  $probeLib = Join-Path $sandbox 'plugins\cucumber-navigation-probe\lib'
  New-Item -ItemType Directory -Force $probeLib | Out-Null
  & jar --create --file (Join-Path $probeLib 'probe.jar') -C $classes .
  if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

  $projectCopy = Join-Path $sandbox 'project'
  # robocopy handles long paths (real projects exceed MAX_PATH under the sandbox) and skips build output.
  robocopy (Resolve-Path $Project) $projectCopy /E /XD target node_modules .git .idea build out .gradle /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "copying the project failed (robocopy exit $LASTEXITCODE)" }
  $global:LASTEXITCODE = 0
  if ($Results -or ($ToolWindow -and -not $PSBoundParameters.ContainsKey('Project'))) {
    # Open as an IDEA project (no Maven import); the run configuration calls Maven itself.
    New-Item -ItemType Directory -Force (Join-Path $projectCopy '.idea') | Out-Null
    Set-Content -Encoding ascii (Join-Path $projectCopy '.idea\misc.xml') "<?xml version=`"1.0`" encoding=`"UTF-8`"?>`n<project version=`"4`" />"
  }
  if ($Crlf) {
    Get-ChildItem (Join-Path $projectCopy 'steps') -File | ForEach-Object {
      $text = [IO.File]::ReadAllText($_.FullName) -replace "`r`n", "`n" -replace "`n", "`r`n"
      [IO.File]::WriteAllText($_.FullName, $text, (New-Object Text.UTF8Encoding $false))
    }
  }
  if ($Module) {
    New-Item -ItemType Directory -Force (Join-Path $projectCopy '.idea') | Out-Null
    @'
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
      <module fileurl="file://$PROJECT_DIR$/project.iml" filepath="$PROJECT_DIR$/project.iml" />
    </modules>
  </component>
</project>
'@ | Set-Content -Encoding ascii (Join-Path $projectCopy '.idea\modules.xml')
    @'
<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/steps" isTestSource="true" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
'@ | Set-Content -Encoding ascii (Join-Path $projectCopy 'project.iml')
  }

  # feature: unused
  $starter = 'cucumberNavigationProbe'
  if ($Unused) {
    $starter = 'cucumberUnusedProbe'
    if (-not $PSBoundParameters.ContainsKey('Files')) { $Files = @('steps/Steps.java', 'steps/steps.go', 'steps/test_bdd.py', 'steps/steps.rs') }
    $utf8 = New-Object Text.UTF8Encoding $false
    $additions = @{
      'Steps.java' = "    @Then(`"the java probe unused step`")`n    public void probeUnused() {`n        String s = `"}`";`n    }`n"
      'steps.go' = "`tctx.Step(``^the go probe unused step`$``, ready)`n"
    }
    foreach ($name in $additions.Keys) {
      $file = Join-Path $projectCopy "steps\$name"
      $text = [IO.File]::ReadAllText($file)
      $close = $text.LastIndexOf('}')
      [IO.File]::WriteAllText($file, $text.Substring(0, $close) + $additions[$name] + $text.Substring($close), $utf8)
    }
    $appends = @{
      'test_bdd.py' = "`n`n@then(`"the pytest-bdd probe unused step`")`ndef probe_unused():`n    pass`n"
      'steps.rs' = "`n#[then(`"the rust probe unused step`")]`nasync fn probe_unused(_world: &mut DemoWorld) {}`n"
    }
    foreach ($name in $appends.Keys) {
      $file = Join-Path $projectCopy "steps\$name"
      [IO.File]::WriteAllText($file, [IO.File]::ReadAllText($file) + $appends[$name], $utf8)
    }
  }

  $paths = @{ config = 'config'; system = 'system'; plugins = 'plugins'; log = 'log' }
  $properties = ($paths.GetEnumerator() | ForEach-Object { "idea.$($_.Key).path=" + ((Join-Path $sandbox $_.Value) -replace '\\', '/') }) -join "`n"
  Set-Content -Encoding ascii (Join-Path $sandbox 'idea.properties') $properties
  Set-Content -Encoding ascii (Join-Path $sandbox 'idea.vmoptions') "-Djava.awt.headless=true`n-Didea.trust.all.projects=true`n-Didea.is.internal=false"
  [Environment]::SetEnvironmentVariable("$($product.envVarBaseName)_PROPERTIES", (Join-Path $sandbox 'idea.properties'))
  [Environment]::SetEnvironmentVariable("$($product.envVarBaseName)_VM_OPTIONS", (Join-Path $sandbox 'idea.vmoptions'))

  $report = Join-Path $sandbox 'report.txt'
  $ErrorActionPreference = 'Continue'
  $launcher = Join-Path $IdeHome 'bin\idea.bat'
  if ($product.launch) {
    $name = [IO.Path]::GetFileNameWithoutExtension("$($product.launch[0].launcherPath)") -replace '64$', ''
    if ($name -and (Test-Path (Join-Path $IdeHome "bin\$name.bat"))) { $launcher = Join-Path $IdeHome "bin\$name.bat" }
  }
  & $launcher $starter $projectCopy $report @Files *> (Join-Path $sandbox 'ide.log')
  $ErrorActionPreference = $previousPreference
  if (-not (Test-Path $report)) { Get-Content (Join-Path $sandbox 'ide.log') -Tail 40; throw 'The probe did not run.' }
  Get-Content $report
  $logFile = Join-Path $sandbox 'log\idea.log'
  if (Test-Path $logFile) {
    Select-String -Path $logFile -Pattern ' (SEVERE|ERROR) - ' -Context 0, 6 | Select-Object -First 5 | ForEach-Object { Write-Host "log: $_" }
  }
} finally {
  $ErrorActionPreference = $previousPreference
  [Environment]::SetEnvironmentVariable("$($product.envVarBaseName)_PROPERTIES", $null)
  [Environment]::SetEnvironmentVariable("$($product.envVarBaseName)_VM_OPTIONS", $null)
  if ($KeepSandbox) {
    Write-Host "Sandbox kept: $sandbox"
  } else {
    # The IDE's index paths can exceed MAX_PATH.
    cmd /c rd /s /q "\\?\$sandbox" 2>$null
  }
}
