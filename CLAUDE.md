# OoruTV Launcher: notes for AI assistants

A tvOS-style home screen for Android TV and Google TV, plus a settings app that switches between OoruTV and the TV's
original home screen. Plain Android app in Java (no Gradle, no libraries), built by `build.sh`.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for their city (and the TV's IP if you can't find it: on Windows `scripts/install.ps1` finds it on the Wi-Fi).
2. **Tools:** JDK 17+, Android SDK (build-tools 34+, platform android-34) and adb. Check what's there first
   (`java -version`, `$ANDROID_HOME`, `adb version`); install only what's missing (README → Install).
3. **Settings:** `./build.sh` once creates `config.properties`; fill in `latitude`, `longitude`, `city`, `tmdb_region`,
   `currency`, `temperature`, `language`.
4. **Keys are theirs and secret.** The TMDB key (and the optional Watchmode key) go only in `config.properties`. Ask them
   to paste it there themselves; never print it, never commit it. To update an app already on a TV, sign with the same
   key: `keystore=`, `key_alias=`, `key_password=` in `config.properties`.
5. **Build:** `./build.sh` must end with `Built OoruTV.apk`.
6. **Install:** `adb connect <IP>:5555` (they accept *Allow USB debugging?* with *Always allow*), then
   `adb install -r OoruTV.apk`. A hang means Play Protect is asking on the TV: *More details → Install anyway*.
   On TCL also run `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back.
8. **They switch on the TV:** OoruTV → Home Screen → OoruTV. The app uses the TV's own ADB on 127.0.0.1, so the TV asks
   once more to allow debugging. Don't switch modes from the computer.
9. **Check:** `adb logcat -s OoruTV`.

## Always

- Google TV's setup app (`setupwraith`) goes off before the stock home screen and comes back after it (`HomeMode`):
  with the home screen off and it on, its RecoveryActivity takes Home. If it can't go off, the home screen stays on.
- Never turn off an app that saved a Wi-Fi network (`wifiOwners` in `HomeMode`): Android deletes its networks.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: *Original* and the watchdog (`Watchdog`: back to the stock home after 3 crashes in 5 min).
- Never commit `config.properties`, `*.keystore` or any key.
- The package id stays `io.freemytv`: changing it makes a new app on every TV (settings lost, and an install that was
  in OoruTV mode would leave its disabled apps off).

## The code

- Java 8 language level, minSdk 26, targetSdk 33. Compile against android-34 but call newer APIs only behind checks:
  AGSL (`RuntimeShader`) is guarded by a try/catch in `Scene`, which then draws the Canvas glass.
- `HomeView` draws the home screen by hand on one Canvas, in layers; keep 60 fps: no allocations in `onDraw`, no work
  on the UI thread. `Ui.LEGACY` (Android 8–11) skips GPU layers and uses cached blurred shadows (`Shadows`).
- The home screen is the tvOS style (`tvos` is always true): top shelf (`drawTvSky`, `drawTvHero`), weather pill and
  status pill (`layoutTvStatus`, `drawTvStatus`), overlays on the control layer (Control Center, weather panel,
  Profile: `overlay`). The older card layout (`drawLightTop`) is no longer offered.
- Glass: `Scene.liquid` (Liquid Glass) and `Scene.glass` (frosted). Android 13+ uses `Shaders.LIQUID`/`GLASS`; 8–12 use
  `oldGlass` + `lens` (refraction by a magnified rim). `Scene.frosted` (Glass Style) turns refraction off.
- Text: `Ui.tr("English")` from `app/assets/i18n/<lang>.json`; patterns `Ui.trf("{n} min ago", "n", 12)`; plurals
  `Ui.plural(...)`. After changing text run `python3 tools/translate.py` and fill the missing keys.
- Settings from `config.properties` → generated `out/gen/io/freemytv/Config.java` (never edit that). What the person
  changes on the TV (`Prefs`) wins over those first values.
- After any change, `./build.sh` must end with `Built OoruTV.apk`.

## Adapting it to another TV

Read first, change after:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell settings get secure screensaver_components
adb shell pm list packages -s
```

Then `HomeMode.DEBLOAT` (the brand's promo apps), `HomeMode.PROTECTED` (picture, sound, inputs, tuner, remote,
setup, updates: when in doubt, protect it), and `HomeApps.SHELF` / `LIVE_TV_APPS` / `HIDDEN`.
