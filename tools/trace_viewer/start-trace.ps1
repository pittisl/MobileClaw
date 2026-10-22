# Windows PowerShell 5.1 and PowerShell 7. No modules or Python packages are required.
[CmdletBinding(DefaultParameterSetName = 'Live')]
param(
    [Parameter(ParameterSetName = 'Live')]
    [string]$Serial,
    [Parameter(ParameterSetName = 'Live')]
    [string]$Adb,
    [Parameter(ParameterSetName = 'Live')]
    [switch]$SkipEnableTrace,
    [Parameter(Mandatory = $true, ParameterSetName = 'Replay')]
    [string]$ReplayFile,
    [string]$Python,
    [ValidateRange(1, 65535)]
    [int]$Port = 8765,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'

function Resolve-Python {
    # Resolve the launcher's selected runtime once, then invoke that executable directly.
    # The timeout also handles a broken App Execution Alias without hanging the booth.
    $candidates = @()
    if ($Python) {
        $candidates += @{ Name = $Python; Prefix = '' }
    } else {
        $candidates += @{ Name = 'py.exe'; Prefix = '-3 ' }
        $candidates += @{ Name = 'python.exe'; Prefix = '' }
        $candidates += @{ Name = 'python3.exe'; Prefix = '' }
    }
    foreach ($candidate in $candidates) {
        $command = Get-Command $candidate.Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $command) { continue }
        $probe = New-Object System.Diagnostics.Process
        $probe.StartInfo.FileName = $command.Source
        $probe.StartInfo.Arguments = $candidate.Prefix + '-c "import sys; assert sys.version_info >= (3, 9); print(sys.executable)"'
        $probe.StartInfo.UseShellExecute = $false
        $probe.StartInfo.CreateNoWindow = $true
        $probe.StartInfo.RedirectStandardOutput = $true
        $probe.StartInfo.RedirectStandardError = $true
        $probe.StartInfo.EnvironmentVariables['PYTHONUTF8'] = '1'
        $probe.StartInfo.EnvironmentVariables['PYTHON_MANAGER_AUTOMATIC_INSTALL'] = 'false'
        $probe.StartInfo.EnvironmentVariables.Remove('PYLAUNCHER_ALLOW_INSTALL')
        $probe.StartInfo.EnvironmentVariables.Remove('PYLAUNCHER_ALWAYS_INSTALL')
        $probe.StartInfo.StandardOutputEncoding = New-Object System.Text.UTF8Encoding
        try {
            [void]$probe.Start()
            if (-not $probe.WaitForExit(10000)) {
                $probe.Kill()
                $probe.WaitForExit()
                continue
            }
            $runtime = $probe.StandardOutput.ReadToEnd().Trim()
            if ($probe.ExitCode -eq 0 -and (Test-Path -LiteralPath $runtime -PathType Leaf)) {
                return $runtime
            }
        } catch {
            continue
        } finally {
            $probe.Dispose()
        }
    }
    throw 'Python 3.9+ was not found. Install Windows Python from https://www.python.org/downloads/windows/, reopen this terminal, or pass -Python C:\path\python.exe.'
}

try {
    $runtime = Resolve-Python
    Write-Host "Python: $runtime"
    $viewerArgs = @('-u', (Join-Path $PSScriptRoot 'trace_viewer.py'), '--port', "$Port")
    if ($PSCmdlet.ParameterSetName -eq 'Replay') {
        $viewerArgs += @('--file', $ReplayFile)
    } else {
        if ($Serial) { $viewerArgs += @('--serial', $Serial) }
        else { $viewerArgs += '--auto-device' }
        if ($Adb) { $viewerArgs += @('--adb', $Adb) }
        if (-not $SkipEnableTrace) { $viewerArgs += '--enable-trace' }
    }
    if (-not $NoBrowser) { $viewerArgs += '--open' }
    & $runtime @viewerArgs
    exit $LASTEXITCODE
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
