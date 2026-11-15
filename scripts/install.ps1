# Installs an X-OS build on the TV over adb. Finds the TV by itself: the address saved last time, a TV adb already
# knows, or a look over this computer's Wi-Fi network for a device with adb's port (5555) open that has X-OS on it.
# Then it opens the home screen and saves a screenshot and a log in .\shots (with -Keys, it also opens the top shelf,
# the weather panel and Control Center for three more screenshots).
# adb: platform-tools\adb.exe next to this script, or the adb on your PATH.
param([string]$Apk = "XOS-Launcher.apk", [switch]$Keys)
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
$adb = Join-Path $PSScriptRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = Join-Path $PSScriptRoot "tools\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) { $adb = "adb" }
New-Item -ItemType Directory -Force -Path shots | Out-Null
$log = Join-Path $PSScriptRoot "shots\install-log.txt"
"X-OS install $(Get-Date -Format s)" | Set-Content $log
function Say($m) { Write-Host $m; Add-Content $log $m }
function Adb { & $adb @args 2>&1 | ForEach-Object { "$_" } }

function State($target) {
    foreach ($line in (Adb devices)) { if ($line -like "$target`t*") { return ($line -split "`t")[1] } }
    return ""
}

function HasXOS($target) {
    return ((Adb -s $target shell pm path com.xos.launcher) -join " ") -match "package:"
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
            if (HasXOS "$($Matches[1]):5555") { $target = "$($Matches[1]):5555"; break }
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
            if ($t -and (HasXOS $t)) { $target = $t; break }
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

# Recent apps switcher: usage access (which apps were used lately). Top shelf of the focused app: the TV listings
# (apps' Continue Watching and rows). Custom icons: reading the TV's Pictures and Download folders.
Adb -s $target shell appops set com.xos.launcher GET_USAGE_STATS allow | Out-Null
foreach ($p in "android.permission.READ_TV_LISTINGS", "com.android.providers.tv.permission.READ_EPG_DATA",
        "android.permission.READ_EXTERNAL_STORAGE") {
    Adb -s $target shell pm grant com.xos.launcher $p | Out-Null
}
# Pictures for custom icons (hold OK on an app > Choose custom icon): an "icons" folder next to this script.
$iconDir = Join-Path $PSScriptRoot "icons"
if (Test-Path $iconDir) {
    $pics = @(Get-ChildItem -Path $iconDir -Recurse -File | Where-Object { $_.Extension -match '^\.(png|jpe?g|webp)$' })
    if ($pics.Count -gt 0) {
        Say "Copying $($pics.Count) icon pictures to the TV's Pictures folder ..."
        Adb -s $target shell mkdir -p "/sdcard/Pictures/X-OS Icons" | Out-Null
        foreach ($f in $pics) { Adb -s $target push $f.FullName "/sdcard/Pictures/X-OS Icons/$($f.Name)" | Out-Null }
    }
}
# Apple's SF fonts (SF Pro, SF Rounded): the files you put in a "fonts" folder next to this script go to the app's own
# folder on the TV (Settings > Home Settings > Font). They are never built into the app.
$fontDir = Join-Path $PSScriptRoot "fonts"
if (Test-Path $fontDir) {
    $fonts = @(Get-ChildItem -Path $fontDir -Recurse -File | Where-Object { $_.Extension -match '^\.(otf|ttf)$' })
    if ($fonts.Count -gt 0) {
        Say "Copying $($fonts.Count) font files to the TV ..."
        $tvFonts = "/sdcard/Android/data/com.xos.launcher/files/fonts"
        Adb -s $target shell mkdir -p $tvFonts | Out-Null
        foreach ($f in $fonts) { Adb -s $target push $f.FullName "$tvFonts/$($f.Name)" | Out-Null }
    }
}

Say "Opening the home screen and taking screenshots ..."
Adb -s $target shell am start -W -n com.xos.launcher/.HomeActivity | Out-Null
Start-Sleep -Seconds 9
Adb -s $target shell screencap -p /sdcard/fmt-home.png | Out-Null
if (-not $Keys) {
    Adb -s $target pull /sdcard/fmt-home.png shots\home.png | Out-Null
    # Each screen opened directly (no remote keys), photographed, then back home.
    $homeAct = "com.xos.launcher/.HomeActivity"
    $screens = [ordered]@{
        "apps" = @("am", "start", "-n", $homeAct, "--es", "show", "apps");
        "control" = @("am", "start", "-n", $homeAct, "--es", "show", "control");
        "weather" = @("am", "start", "-n", $homeAct, "--es", "show", "weather");
        "recents" = @("am", "start", "-n", $homeAct, "--es", "show", "recents");
        "folder" = @("am", "start", "-n", $homeAct, "--es", "show", "folder");
        "menu" = @("am", "start", "-n", $homeAct, "--es", "show", "menu");
        "today" = @("am", "start", "-n", $homeAct, "--es", "show", "today");
        "settings" = @("am", "start", "-n", "com.xos.launcher/.SettingsActivity");
        "standby" = @("am", "start", "-n", "com.xos.launcher/.SettingsActivity", "--ez", "screensaver", "true", "--es", "saver", "standby")
    }
    foreach ($name in $screens.Keys) {
        Adb -s $target shell @($screens[$name]) | Out-Null
        Start-Sleep -Seconds $(if ($name -eq "today" -or $name -eq "settings" -or $name -eq "recents") { 7 } else { 3 })
        Adb -s $target shell screencap -p "/sdcard/fmt-$name.png" | Out-Null
        Adb -s $target pull "/sdcard/fmt-$name.png" "shots\$name.png" | Out-Null
        if ($name -eq "standby") { Adb -s $target shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Seconds 1 }
        Adb -s $target shell am start -n $homeAct | Out-Null
        Start-Sleep -Seconds 2
    }
    Adb -s $target shell rm /sdcard/fmt-*.png | Out-Null
    Adb -s $target logcat -d -t 600 -s XOS AndroidRuntime | Set-Content shots\log.txt
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
Adb -s $target logcat -d -t 600 -s XOS AndroidRuntime | Set-Content shots\log.txt
Say "Done."
