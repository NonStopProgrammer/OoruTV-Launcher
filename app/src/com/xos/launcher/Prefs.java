package com.xos.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Settings changed on the TV itself (X-OS > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    static final int[] KEYS = RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    private static void put(String key, float value) {
        sp().edit().putFloat(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    /** "dark" (black, the default), "light" or "auto" (light by day, dark at night). */
    static String theme() {
        String v = sp().getString("theme", "dark");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /**
     * Home screen style: "tvos" (like the tvOS home: the pick's photo full screen with its title, a status pill with
     * the clock and weather, Control Center) or "classic" (the Apple TV app's For You page with cards and tabs).
     */
    static String style() {
        return "tvos";
    }

    /**
     * The top shelf: "picks" (the default: sharp movie and show pictures), "app" (the focused app's own content: its
     * Continue Watching and rows, else the picks), "all" (Continue Watching from every app), "news" (headlines on the
     * wallpaper) or "wallpaper" (a still wallpaper with the big clock).
     */
    static String topShelf() {
        String v = sp().getString("top_shelf", "picks");
        return v.equals("app") || v.equals("all") || v.equals("news") || v.equals("wallpaper") ? v : "picks";
    }

    static void setTopShelf(String v) { put("top_shelf", v); }

    static boolean appShelf() { return topShelf().equals("app"); }

    /** The font (Fonts.KEYS): "sf" (SF Pro, once its files are on the TV; Inter until then), "inter", "outfit"… */
    static String font() {
        String v = app == null ? "sf" : sp().getString("font", "sf");
        for (String k : Fonts.KEYS) if (k.equals(v)) return v;
        return "sf";
    }

    static void setFont(String v) { put("font", v); }

    /** "liquid" (Liquid Glass: the picture behind bends at the edges) or "frosted" (soft frosted glass, no bending). */
    static String glass() {
        return sp().getString("glass", "liquid").equals("frosted") ? "frosted" : "liquid";
    }

    static void setGlass(String v) { put("glass", v); }

    // MARK: - Profile: the name and avatar in the status pill and Control Center

    static final String[] AVATARS = {"\uD83E\uDD8A", "\uD83D\uDC3C", "\uD83D\uDC2F", "\uD83E\uDD81", "\uD83D\uDC38",
            "\uD83D\uDC28", "\uD83D\uDC19", "\uD83E\uDD84", "\uD83D\uDC36", "\uD83D\uDC31"};

    static String profileName() { return sp().getString("profile_name", "").trim(); }

    static void setProfileName(String v) { put("profile_name", v == null ? "" : v.trim()); }

    static String avatar() {
        String v = sp().getString("avatar", AVATARS[1]);
        return v.isEmpty() ? AVATARS[1] : v;
    }

    static void setAvatar(String v) { put("avatar", v); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return !soundMode().equals("off"); }

    /** Interface sounds: "xos" (X-OS's soft clicks), "tv" (the TV's own sounds) or "off". */
    static String soundMode() {
        String v = sp().getString("sound_mode", null);
        if (v == null) return sp().getBoolean("sounds", true) ? "xos" : "off";
        return v.equals("tv") || v.equals("off") ? v : "xos";
    }

    static void setSoundMode(String v) { put("sound_mode", v); }

    // MARK: - Look and feel (Settings › More Settings)

    /** The clock: "auto" (the language's own), "12" or "24" hours. */
    static String clockHours() {
        String v = sp().getString("clock_hours", "auto");
        return v.equals("12") || v.equals("24") ? v : "auto";
    }

    static void setClockHours(String v) { put("clock_hours", v); }

    /** The date next to the clock in the status pill. */
    static boolean clockDate() { return sp().getBoolean("clock_date", false); }

    static void setClockDate(boolean on) { put("clock_date", on); }

    /** Text size: 0.9, 1 (default), 1.1 or 1.2. */
    static float textSize() {
        float v = app == null ? 1 : sp().getFloat("text_size", 1);
        return v < 0.85f || v > 1.25f ? 1 : v;
    }

    static void setTextSize(float v) { put("text_size", v); }

    /** Animations: "normal", "fast", "slow" or "reduced" (they finish at once). */
    static String animations() {
        String v = app == null ? "normal" : sp().getString("animations", "normal");
        return v.equals("fast") || v.equals("slow") || v.equals("reduced") ? v : "normal";
    }

    static void setAnimations(String v) { put("animations", v); }

    /** Folders open in a glass "window" or "full" screen. */
    static String folderStyle() { return sp().getString("folder_style", "window").equals("full") ? "full" : "window"; }

    static void setFolderStyle(String v) { put("folder_style", v); }

    /** StandBy's clock: "analog" (with the month), "world", "digital", "float" or "date" (with the weather). */
    static String standbyClock() {
        String v = sp().getString("standby_clock", "analog");
        return v.equals("world") || v.equals("digital") || v.equals("float") || v.equals("date") ? v : "analog";
    }

    static void setStandbyClock(String v) { put("standby_clock", v); }

    static final String[] WALLPAPERS = {"aurora", "sunset", "ocean", "graphite", "emoji"};

    /** Top shelf wallpaper (Top Shelf › Wallpaper): one of WALLPAPERS. */
    static String wallpaper() {
        String v = sp().getString("wallpaper", "aurora");
        for (String w : WALLPAPERS) if (w.equals(v)) return v;
        return "aurora";
    }

    static void setWallpaper(String v) { put("wallpaper", v); }

    /** The emoji of the emoji wallpaper. */
    static String wallpaperEmoji() { return sp().getString("wallpaper_emoji", "🐼"); }

    static void setWallpaperEmoji(String v) { put("wallpaper_emoji", v); }

    /** The Dynamic Island: a black pill at the top with your first four shelf apps (Appearance › Dynamic Island). */
    static boolean island() { return sp().getBoolean("island", false); }

    static void setIsland(boolean on) { put("island", on); }

    /** The screensaver's photos: "bing" (photo of the day), "nasa" (NASA's picture of the day) or "wikimedia". */
    static String photoSource() {
        String v = app == null ? "bing" : sp().getString("photo_source", "bing");
        return v.equals("nasa") || v.equals("wikimedia") ? v : "bing";
    }

    static void setPhotoSource(String v) { put("photo_source", v); }

    /** The news edition on the top shelf (News.EDITIONS). */
    static String news() { return sp().getString("news", "top"); }

    static void setNews(String v) { put("news", v); }

    // MARK: - Backup and restore (Settings › More Settings › Backup & Restore)

    /** Every setting made on the TV and the remote buttons, as JSON: [type, value] per key. */
    static JSONObject export() throws JSONException {
        JSONObject values = new JSONObject();
        for (java.util.Map.Entry<String, ?> e : sp().getAll().entrySet()) {
            Object v = e.getValue();
            String type = v instanceof Boolean ? "b" : v instanceof Float ? "f" : v instanceof Integer ? "i"
                    : v instanceof Long ? "l" : v instanceof String ? "s" : null;
            if (type != null) values.put(e.getKey(), new JSONArray().put(type).put(String.valueOf(v)));
        }
        JSONObject keys = new JSONObject();
        for (int key : KEYS) keys.put(Integer.toString(key), keyApp(key));
        return new JSONObject().put("app", "X-OS").put("format", 1).put("at", System.currentTimeMillis())
                .put("settings", values).put("remoteKeys", keys);
    }

    /** Puts back what export() saved (keys it doesn't know are kept as they are). Returns how many were restored. */
    static int restore(JSONObject backup) throws JSONException {
        if (!"X-OS".equals(backup.optString("app"))) throw new JSONException("not an X-OS backup");
        JSONObject values = backup.getJSONObject("settings");
        SharedPreferences.Editor e = sp().edit();
        int n = 0;
        for (java.util.Iterator<String> it = values.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONArray tv = values.optJSONArray(key);
            if (tv == null || tv.length() != 2 || key.equals("setup_done")) continue;
            String type = tv.optString(0), v = tv.optString(1);
            try {
                switch (type) {
                    case "b": e.putBoolean(key, Boolean.parseBoolean(v)); break;
                    case "f": e.putFloat(key, Float.parseFloat(v)); break;
                    case "i": e.putInt(key, Integer.parseInt(v)); break;
                    case "l": e.putLong(key, Long.parseLong(v)); break;
                    case "s": e.putString(key, v); break;
                    default: continue;
                }
                n++;
            } catch (NumberFormatException ignored) {
            }
        }
        e.apply();
        JSONObject keys = backup.optJSONObject("remoteKeys");
        if (keys != null) {
            StringBuilder sb = new StringBuilder();
            for (int key : KEYS) sb.append(key).append('=').append(keys.optString(Integer.toString(key), keyApp(key))).append('\n');
            writeKeys(sb.toString());
        }
        version++;
        return n;
    }

    /** First run: the setup pages were seen (an install that already has settings counts as set up). */
    static boolean setupDone() {
        if (sp().contains("setup_done")) return sp().getBoolean("setup_done", false);
        return sp().getAll().size() > 3;
    }

    static void setSetupDone() { put("setup_done", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_MARKETS); }
    static void setQuotes(boolean on) { put("quotes", on); }

    /** NIFTY 50, SENSEX and BANK NIFTY: each on by default. */
    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }

    /**
     * The screensaver: photos ("scenery", Bing's photo of the day; "picks", the recommendations'; or "both") or
     * "standby" (the iPhone's StandBy: an analog clock and the month's calendar on black).
     */
    static String screensaver() {
        String v = sp().getString("screensaver", Config.SCREENSAVER.trim().toLowerCase(java.util.Locale.ROOT));
        return v.equals("picks") || v.equals("both") || v.equals("standby") ? v : "scenery";
    }

    static void setScreensaver(String v) { put("screensaver", v); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Folders in All Apps

    /** A folder: its name and its apps (packages), in order. */
    static final class Folder {
        String name;
        final List<String> apps = new ArrayList<>();

        Folder(String name) { this.name = name; }
    }

    /** The folders, in the order they were made: one per line, the name, a tab, then the packages with commas. */
    static List<Folder> folders() {
        List<Folder> out = new ArrayList<>();
        String v = sp().getString("folders", "");
        for (String line : v.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            Folder f = new Folder(line.substring(0, tab));
            for (String p : line.substring(tab + 1).split(",")) if (!p.isEmpty() && !f.apps.contains(p)) f.apps.add(p);
            out.add(f);
        }
        return out;
    }

    /** Saves the folders (empty ones are dropped: a folder holds at least one app). */
    static void setFolders(List<Folder> list) {
        StringBuilder b = new StringBuilder();
        for (Folder f : list) {
            if (f.apps.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(f.name.replace('\t', ' ').replace('\n', ' ').trim()).append('\t').append(android.text.TextUtils.join(",", f.apps));
        }
        put("folders", b.toString());
    }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Top buttons

    /**
     * The round buttons at the top right, in order: "live", "inputs" and/or "settings". null until the user edits
     * them (then HomeApps.TOP_DEFAULT applies); empty when they took all three off.
     */
    static List<String> topButtons() {
        String v = sp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopButtons(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("topButtons", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
