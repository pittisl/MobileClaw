$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'trace-devices.ps1')
function Assert($Condition, $Message) { if (-not $Condition) { throw $Message } }
function Must-Fail([scriptblock]$Action, [string]$Pattern) {
    try { & $Action | Out-Null } catch {
        Assert ($_.Exception.Message -match $Pattern) "Unexpected error: $_"
        return
    }
    throw "Expected failure matching $Pattern"
}
$devices = @(ConvertFrom-AdbDevices @(
    'List of devices attached',
    'demo-phone-1 device product:CPH2611 model:CPH2611 transport_id:1',
    'demo-phone-2 device product:CPH2611 model:CPH2611 transport_id:2',
    'demo-pixel-3 device product:caiman model:Pixel_9_Pro transport_id:3',
    '* daemon started successfully',
    'blocked unauthorized transport_id:4',
    'gone offline transport_id:5'
))
Assert ($devices.Count -eq 5) 'ADB parser lost devices or parsed the banner'
Assert ($devices[0].Label -eq $devices[1].Label) 'Duplicate models should retain the reported model name'
Assert ($devices[0].Serial -ne $devices[1].Serial) 'Duplicate model names must preserve distinct serials'
foreach ($id in @('demo-phone-1', 'demo-phone-2', 'demo-pixel-3')) {
    $one = @($devices | Where-Object { $_.Serial -eq $id })
    Assert ((Select-TraceDevice -Devices $one).Serial -eq $id) "Single-phone auto select failed: $id"
    Assert ((Select-TraceDevice -Devices $devices -RequestedSerial $id).Serial -eq $id) "Explicit select failed: $id"
}
Must-Fail { Select-TraceDevice -Devices $devices -NonInteractive } 'Multiple phones'
Must-Fail { Select-TraceDevice -Devices @() } 'No authorized phone'
Must-Fail { Select-TraceDevice -Devices $devices -RequestedSerial 'missing' } 'not connected'
Must-Fail { Select-TraceDevice -Devices $devices -RequestedSerial 'blocked' } 'unauthorized'
Must-Fail { Select-TraceDevice -Devices $devices -RequestedSerial 'gone' } 'offline'
function Read-Host { '2' }
Assert ((Select-TraceDevice -Devices $devices).Serial -eq 'demo-phone-2') 'Interactive choice selected wrong phone'
Write-Host 'PASS: three test phones, duplicate model names, explicit/automatic/interactive selection, unavailable and missing devices.'
