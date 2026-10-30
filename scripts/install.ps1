# Installs an OoruTV build on the TV over adb. Finds the TV by itself: the address saved last time, a TV adb already
# knows, or a look over this computer's Wi-Fi network for a device with adb's port (5555) open that has OoruTV on it.
# Then it opens the home screen and saves a screenshot and a log in .\shots (with -Keys, it also opens the top shelf,
# the weather panel and Control Center for three more screenshots).
# adb: platform-tools\adb.exe next to this script, or the adb on your PATH.
param([string]$Apk = "OoruTV.apk", [switch]$Keys)
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
$adb = Join-Path $PSScriptRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = Join-Path $PSScriptRoot "tools\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) { $adb = "adb" }
New-Item -ItemType Directory -Force -Path shots | Out-Null
$log = Join-Path $PSScriptRoot "shots\install-log.txt"
"OoruTV install $(Get-Date -Format s)" | Set-Content $log
function Say($m) { Write-Host $m; Add-Content $log $m }
function Adb { & $adb @args 2>&1 | ForEach-Object { "$_" } }

function State($target) {
    foreach ($line in (Adb devices)) { if ($line -like "$target`t*") { return ($line -split "`t")[1] } }
    return ""
}

function HasFreeMyTV($target) {
    return ((Adb -s $target shell pm path io.freemytv) -join " ") -match "package:"
}

function TryTarget($ip) {
    $t = "${ip}:5555"
    Adb connect $t | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
        $s = State $t
        if ($s -eq "device") { return $t }
        if ($s -eq "unauthorized" -and $i -eq 0) { Say "  $ip asks to allow USB debugging: choose Allow (tick Always allow) on the TV..." }
        if ($s -eq "" -and $i -gt 3) { break }
        Start-Sleep -Milliseconds 1500
    }
    Adb disconnect $t | Out-Null
    return $null
}

$target = $null
# 1. The address saved last time.
if (Test-Path tv-ip.txt) {
    $ip = (Get-Content tv-ip.txt -First 1).Trim()
    if ($ip) { Say "Trying the saved TV address $ip ..."; $target = TryTarget $ip }
}
# 2. A TV adb is already connected to.
if (-not $target) {
    foreach ($line in (Adb devices)) {
        if ($line -match "^(\d+\.\d+\.\d+\.\d+):5555`tdevice") {
            if (HasFreeMyTV "$($Matches[1]):5555") { $target = "$($Matches[1]):5555"; break }
        }
    }
}
# 3. Look over the Wi-Fi network for adb's port.
if (-not $target) {
    $nets = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address }
    foreach ($n in $nets) {
        $me = $n.IPv4Address[0].IPAddress
        $base = ($me -split "\.")[0..2] -join "."
        Say "Looking for the TV on $base.x ..."
        $probes = @()
        foreach ($h in 1..254) {
            $ip = "$base.$h"
            if ($ip -eq $me) { continue }
            $c = New-Object System.Net.Sockets.TcpClient
            $probes += ,@($ip, $c, $c.ConnectAsync($ip, 5555))
        }
        Start-Sleep -Milliseconds 2500
        $open = @()
        foreach ($p in $probes) {
            if ($p[2].Status -eq "RanToCompletion" -and $p[1].Connected) { $open += $p[0] }
            $p[1].Dispose()
        }
        Say ("  adb port open on: " + ($(if ($open) { $open -join ", " } else { "nothing" })))
        foreach ($ip in $open) {
            $t = TryTarget $ip
            if ($t -and (HasFreeMyTV $t)) { $target = $t; break }
            if ($t -and -not $target) { $fallback = $t }
        }
        if ($target) { break }
    }
    if (-not $target -and $fallback) { $target = $fallback }
}
if (-not $target) {
    Say "Couldn't find the TV. Check that it's on, on the same Wi-Fi, and that ADB debugging is on (Settings > Device Preferences > About > press Build 7 times, then Developer options > USB/ADB debugging)."
    exit 1
}
($target -split ":")[0] | Set-Content tv-ip.txt
Say "TV: $target ($((Adb -s $target shell getprop ro.product.model) -join '') , Android $((Adb -s $target shell getprop ro.build.version.release) -join ''))"

Say "Installing $Apk ..."
$out = (Adb -s $target install -r $Apk) -join " "
Say "  $out"
if ($out -notmatch "Success") { Say "Install failed."; exit 1 }

Say "Opening the home screen and taking screenshots ..."
Adb -s $target shell am start -W -n io.freemytv/.HomeActivity | Out-Null
Start-Sleep -Seconds 9
Adb -s $target shell screencap -p /sdcard/fmt-home.png | Out-Null
if (-not $Keys) {
    Adb -s $target pull /sdcard/fmt-home.png shots\home.png | Out-Null
    Adb -s $target shell rm /sdcard/fmt-home.png | Out-Null
    # The Today page, then back home.
    Adb -s $target shell am start -W -n io.freemytv/.TodayActivity | Out-Null
    Start-Sleep -Seconds 8
    Adb -s $target shell screencap -p /sdcard/fmt-today.png | Out-Null
    Adb -s $target shell input keyevent KEYCODE_BACK | Out-Null
    Adb -s $target pull /sdcard/fmt-today.png shots\today.png | Out-Null
    Adb -s $target shell rm /sdcard/fmt-today.png | Out-Null
    Adb -s $target logcat -d -t 600 -s OoruTV FreeMyTV AndroidRuntime | Set-Content shots\log.txt
    Say "Done."
    exit 0
}
function Key($k) { Adb -s $target shell input keyevent $k | Out-Null }
function Shot($n) { Start-Sleep -Seconds 2; Adb -s $target shell screencap -p "/sdcard/fmt-$n.png" | Out-Null }
Key KEYCODE_DPAD_UP; Shot "hero"
Key KEYCODE_DPAD_UP; Key KEYCODE_DPAD_CENTER; Shot "weather"
Key KEYCODE_BACK; Key KEYCODE_DPAD_DOWN; Key KEYCODE_DPAD_DOWN
Key KEYCODE_MENU; Shot "control"
Key KEYCODE_BACK
foreach ($s in "home", "hero", "weather", "control") {
    Adb -s $target pull "/sdcard/fmt-$s.png" "shots\$s.png" | Out-Null
    Adb -s $target shell rm "/sdcard/fmt-$s.png" | Out-Null
}
Adb -s $target logcat -d -t 600 -s OoruTV FreeMyTV AndroidRuntime | Set-Content shots\log.txt
Say "Done."
