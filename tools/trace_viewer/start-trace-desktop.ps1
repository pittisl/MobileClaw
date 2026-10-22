# Desktop entry point for Windows PowerShell 5.1 and PowerShell 7.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Python,
    [string]$Adb,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'

function Test-ListeningPort([int]$Number) {
    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    return @($listeners | Where-Object { $_.Port -eq $Number }).Count -gt 0
}

function Test-ExistingViewer([int]$Number) {
    # Read just the initial SSE status; never wait for a new phone event.
    $response = $null
    $reader = $null
    try {
        $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Number/events")
        $request.Proxy = $null
        $request.Timeout = 1500
        $request.ReadWriteTimeout = 1500
        $response = $request.GetResponse()
        if ($response.ContentType -notlike 'text/event-stream*') { return $false }
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $line = $reader.ReadLine()
        if ($line -notlike 'data: *') { return $false }
        $status = $line.Substring(6) | ConvertFrom-Json
        return $status.kind -eq 'status' -and $status.source -eq $Serial
    } catch {
        return $false
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
    }
}

function Read-AdbServerStatus([int]$Number) {
    $probe = New-Object System.Diagnostics.Process
    $probe.StartInfo.FileName = $Adb
    $probe.StartInfo.Arguments = "-H 127.0.0.1 -P $Number server-status"
    $probe.StartInfo.UseShellExecute = $false
    $probe.StartInfo.CreateNoWindow = $true
    $probe.StartInfo.RedirectStandardOutput = $true
    $probe.StartInfo.RedirectStandardError = $true
    try {
        [void]$probe.Start()
        if (-not $probe.WaitForExit(3000)) {
            $probe.Kill()
            $probe.WaitForExit()
            return ''
        }
        if ($probe.ExitCode -eq 0) { return $probe.StandardOutput.ReadToEnd() }
        return ''
    } finally {
        $probe.Dispose()
    }
}

try {
    if (-not $Adb) { $Adb = Join-Path $PSScriptRoot '..\..\adb.exe' }
    foreach ($file in @($Python, $Adb)) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw "Required executable not found: $file"
        }
    }
    $Adb = (Resolve-Path -LiteralPath $Adb).Path
    $env:ADB_SERVER_SOCKET = $null

    # A WSL relay can occupy 5037. Query occupied servers without restarting them;
    # only reuse a server that identifies itself as native Windows ADB.
    $adbPort = $null
    foreach ($candidate in @(5037, 5038)) {
        if (Test-ListeningPort $candidate) {
            $info = Read-AdbServerStatus $candidate
            if ($info -notmatch '(?m)^os: "Windows ' -or
                $info -notmatch '(?m)^executable_absolute_path: ".*\\adb\.exe"') {
                Write-Host "Port $candidate belongs to another server; trying the next port."
                continue
            }
        } else {
            & $Adb -H 127.0.0.1 -P $candidate start-server
            if ($LASTEXITCODE -ne 0) { continue }
        }
        $adbPort = $candidate
        break
    }
    if ($null -eq $adbPort) {
        throw 'Neither port 5037 nor 5038 is available for native Windows ADB. Close the conflicting server and try again.'
    }
    $env:ADB_SERVER_SOCKET = "tcp:127.0.0.1:$adbPort"
    Write-Host "Windows ADB: $Adb (port $adbPort)"

    $viewerPort = $null
    foreach ($candidate in 8765..8775) {
        if (-not (Test-ListeningPort $candidate)) {
            if ($null -eq $viewerPort) { $viewerPort = $candidate }
            continue
        }
        if (Test-ExistingViewer $candidate) {
            $url = "http://127.0.0.1:$candidate/"
            Write-Host "Opening the existing MobileClaw viewer: $url"
            if (-not $NoBrowser) { Start-Process $url }
            exit 0
        }
    }
    if ($null -eq $viewerPort) { throw 'Viewer ports 8765 through 8775 are occupied.' }

    Write-Host 'Keep this window open while viewing the trace. Press Ctrl-C to stop.'
    & (Join-Path $PSScriptRoot 'start-trace.ps1') -Serial $Serial -Adb $Adb -Python $Python -Port $viewerPort -NoBrowser:$NoBrowser
    exit $LASTEXITCODE
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
