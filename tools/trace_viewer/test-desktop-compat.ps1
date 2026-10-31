$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'start-trace-desktop.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw $errors[0] }
foreach ($name in @('Resolve-DesktopAdb', 'Test-NativeAdbOwner', 'Quote-Literal')) {
    $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    $definitionText = $definition.Extent.Text.Replace('$PSScriptRoot', ("'" + $PSScriptRoot.Replace("'", "''") + "'"))
    Invoke-Expression $definitionText
}

# Isolate tool discovery from this machine's installed tools.
$script:found = ''
function Get-Command { param($Name, $CommandType, $ErrorAction)
    if ($Name -eq $script:found) { [pscustomobject]@{ Source = $Name } }
}
$Adb = 'D:\SDK with spaces\adb.exe'
$script:found = $Adb
if ((Resolve-DesktopAdb) -ne $Adb) { throw 'Explicit path failed' }
$Adb = 'D:\missing\adb.exe'
try { $null = Resolve-DesktopAdb; throw 'Expected missing explicit path error' }
catch { if ($_.Exception.Message -notlike 'Windows adb.exe was not found*') { throw } }
$Adb = ''
$script:found = 'adb.exe'
if ((Resolve-DesktopAdb) -ne 'adb.exe') { throw 'PATH fallback failed' }
$script:found = Join-Path $PSScriptRoot '..\..\bin\windows\adb.exe'
if ((Resolve-DesktopAdb) -ne $script:found) { throw 'Station bundle fallback failed' }

function Get-NetTCPConnection { [pscustomobject]@{ OwningProcess = 123 } }
$script:processPath = 'C:\platform-tools\adb.exe'
function Get-Process { [pscustomobject]@{ Path = $script:processPath } }
if (-not (Test-NativeAdbOwner 5037)) { throw 'Legacy native ADB rejected' }
$script:processPath = 'C:\Windows\System32\wslrelay.exe'
if (Test-NativeAdbOwner 5037) { throw 'WSL relay accepted' }
$script:processPath = $null
if (Test-NativeAdbOwner 5037) { throw 'Unknown owner accepted' }
$path = "C:\User's folder\trace.ps1"
if ((Invoke-Expression (Quote-Literal $path)) -ne $path) { throw 'Quoted path did not round-trip' }
Write-Host 'PASS: explicit/PATH/bundle discovery, missing path, legacy ADB owner, WSL rejection, quoted paths.'
