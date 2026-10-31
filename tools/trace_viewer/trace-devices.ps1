# Device selection shared by the desktop launcher and its device-free checks.
function ConvertFrom-AdbDevices([string[]]$Lines) {
    foreach ($line in $Lines) {
        if ($line -match '^\s*(\S+)\s+(device|offline|unauthorized|no permissions)\b(.*)$') {
            $id = $Matches[1]
            $state = $Matches[2]
            $details = $Matches[3]
            $model = 'Android'
            if ($details -match '\bmodel:(\S+)') { $model = $Matches[1].Replace('_', ' ') }
            $label = $model
            [PSCustomObject]@{ Serial = $id; State = $state; Label = $label }
        }
    }
}

function Select-TraceDevice {
    param([object[]]$Devices, [string]$RequestedSerial, [switch]$NonInteractive)
    if ($RequestedSerial) {
        $match = @($Devices | Where-Object { $_.Serial -eq $RequestedSerial })
        if ($match.Count -ne 1) { throw "Phone $RequestedSerial is not connected. Check USB and run again." }
        if ($match[0].State -ne 'device') {
            throw "Phone $RequestedSerial is $($match[0].State). Unlock it and approve USB debugging, or reconnect USB."
        }
        return $match[0]
    }
    $ready = @($Devices | Where-Object { $_.State -eq 'device' })
    foreach ($device in @($Devices | Where-Object { $_.State -ne 'device' })) {
        Write-Host "Unavailable: $($device.Label) [$($device.Serial)] - $($device.State)"
    }
    if ($ready.Count -eq 0) {
        throw 'No authorized phone is connected. Connect USB, unlock the phone, approve USB debugging, then run again.'
    }
    if ($ready.Count -eq 1) { return $ready[0] }
    Write-Host 'Several phones are connected. Select the phone for THIS trace window:'
    for ($i = 0; $i -lt $ready.Count; $i++) {
        Write-Host ("  {0}. {1} [{2}]" -f ($i + 1), $ready[$i].Label, $ready[$i].Serial)
    }
    if ($NonInteractive) { throw 'Multiple phones: specify -Serial SERIAL, or run interactively to choose.' }
    while ($true) {
        $choice = Read-Host 'Phone number (Q to cancel)'
        if ($choice -match '^[Qq]$' -or $null -eq $choice) { throw 'Selection cancelled.' }
        $number = 0
        if ([int]::TryParse($choice, [ref]$number) -and $number -ge 1 -and $number -le $ready.Count) {
            return $ready[$number - 1]
        }
        Write-Host "Enter a number from 1 through $($ready.Count), or Q."
    }
}
