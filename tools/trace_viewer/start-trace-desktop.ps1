# Desktop entry point for Windows PowerShell 5.1 and PowerShell 7.
[CmdletBinding()]
param(
    [string]$Python,
    [string]$Adb,
    [string]$Serial,
    [switch]$NoBrowser,
    [switch]$NonInteractive,
    [switch]$Foreground,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'trace-devices.ps1')

function Resolve-DesktopAdb {
    $candidates = @()
    if ($Adb) { $candidates = @($Adb) }
    else {
        $candidates += Join-Path $PSScriptRoot '..\..\adb.exe'
        $candidates += Join-Path $PSScriptRoot '..\..\bin\windows\adb.exe'
        foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
            if ($root) { $candidates += Join-Path $root 'platform-tools\adb.exe' }
        }
        if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
        if ($env:USERPROFILE) { $candidates += Join-Path $env:USERPROFILE 'platform-tools\adb.exe' }
        $candidates += 'adb.exe'
    }
    foreach ($candidate in $candidates) {
        $command = Get-Command $candidate -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { return $command.Source }
    }
    throw 'Windows adb.exe was not found. Install SDK Platform-Tools, add it to PATH, or pass -Adb C:\platform-tools\adb.exe.'
}

function Test-NativeAdbOwner([int]$Number) {
    # Older Platform-Tools do not implement server-status. Verify the local
    # listening process instead, without stopping any WSL relay or other server.
    try {
        $owners = @(Get-NetTCPConnection -State Listen -LocalPort $Number -ErrorAction Stop)
        if (-not $owners.Count) { return $false }
        foreach ($owner in $owners) {
            $process = Get-Process -Id $owner.OwningProcess -ErrorAction Stop
            if (-not $process.Path -or [IO.Path]::GetFileName($process.Path) -ine 'adb.exe') { return $false }
        }
        return $true
    } catch { return $false }
}

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
        return $status.kind -eq 'status' -and $status.source -eq $Serial -and $status.status -eq 'live'
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
    $Adb = Resolve-DesktopAdb
    $env:ADB_SERVER_SOCKET = $null

    # A WSL relay can occupy 5037. Query occupied servers without restarting them;
    # only reuse a server that identifies itself as native Windows ADB.
    $adbPort = $null
    foreach ($candidate in @(5037, 5038)) {
        if (Test-ListeningPort $candidate) {
            $info = Read-AdbServerStatus $candidate
            $nativeStatus = $info -match '(?m)^os: "Windows ' -and
                $info -match '(?m)^executable_absolute_path: ".*\\adb\.exe"'
            if (-not $nativeStatus -and -not (Test-NativeAdbOwner $candidate)) {
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

    $listing = @(& $Adb -H 127.0.0.1 -P $adbPort devices -l)
    if ($LASTEXITCODE -ne 0) { throw 'Could not list USB devices. Check the ADB output and reconnect USB.' }
    $devices = @(ConvertFrom-AdbDevices $listing)
    $selected = Select-TraceDevice -Devices $devices -RequestedSerial $Serial -NonInteractive:$NonInteractive
    $Serial = $selected.Serial
    Write-Host "Selected: $($selected.Label) [$Serial]"
    Write-Host 'This window stays paired to that serial. After swapping phones, run the desktop shortcut again.'
    if ($CheckOnly) { exit 0 }

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

    if ($Foreground) {
        Write-Host 'Keep this window open while viewing the trace. Press Ctrl-C to stop.'
        & (Join-Path $PSScriptRoot 'start-trace.ps1') -Serial $Serial -Adb $Adb -Python $Python -Port $viewerPort -NoBrowser:$NoBrowser
        exit $LASTEXITCODE
    }

    # The viewer survives closing the launcher window. Wait for the actual
    # selected device's live SSE status before opening a browser tab.
    $logRoot = Join-Path $env:LOCALAPPDATA 'MobileClaw\trace'
    [void](New-Item -ItemType Directory -Force -Path $logRoot)
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $outLog = Join-Path $logRoot "$stamp-$viewerPort.out.log"
    $errLog = Join-Path $logRoot "$stamp-$viewerPort.err.log"
    $entry = Join-Path $PSScriptRoot 'start-trace.ps1'
    function Quote-Literal([string]$Value) { return "'" + $Value.Replace("'", "''") + "'" }
    $command = '& ' + (Quote-Literal $entry) + ' -Serial ' + (Quote-Literal $Serial) +
        ' -Adb ' + (Quote-Literal $Adb) + ' -Port ' + $viewerPort + ' -NoBrowser'
    if ($Python) { $command += ' -Python ' + (Quote-Literal $Python) }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $child = Start-Process powershell.exe -ArgumentList @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    $deadline = (Get-Date).AddSeconds(60)
    do {
        if (Test-ExistingViewer $viewerPort) {
            $url = "http://127.0.0.1:$viewerPort/"
            Write-Host "MobileClaw is running in the background: $url"
            Write-Host "Logs: $logRoot"
            if (-not $NoBrowser) { Start-Process $url }
            exit 0
        }
        if ($child.HasExited) {
            Get-Content $outLog, $errLog -ErrorAction SilentlyContinue | Write-Host
            throw "Viewer exited during startup. Logs: $logRoot"
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Viewer has not become live yet. Retry the launcher; inspect logs in $logRoot."
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
