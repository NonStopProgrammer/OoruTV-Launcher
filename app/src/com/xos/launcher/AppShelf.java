package com.xos.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The top shelf of the focused app (tvOS's Top Shelf): what the app itself puts on Android TV's home screen. That is
 * the shows it is in the middle of (Android's "Watch Next" row: Continue Watching, with how far you got) and the rows
 * it publishes (its "preview programs": new episodes, picks for you). Each becomes a top shelf card: the background,
 * the title and details, and OK plays it in the app.
 *
 * Other apps' rows are only readable with the TV listings permissions; the TV's own ADB grants them (as for the home
 * screen switch: only when the TV already trusts X-OS, it never makes the TV ask). Apps that publish nothing (or a TV
 * without the permission) leave the top shelf on the picks.
 */
final class AppShelf {
    interface Listener { void onAppShelf(String pkg); }

    static final String KIND = "app";
    /** Not an app: Continue Watching from every app (Settings › Top Shelf › All Continue Watching). */
    static final String ALL = "*";
    private static final int MAX = 10, IMAGES = 6;
    private static final long FRESH_MS = 5 * 60_000L, RETRY_MS = 60_000L;
    static final String[] PERMISSIONS = {"android.permission.READ_TV_LISTINGS",
            "com.android.providers.tv.permission.READ_EPG_DATA"};

    private static AppShelf instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, List<Recs.Pick>> lists = new HashMap<>();
    private final Map<String, Long> listAt = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final LinkedHashMap<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> imagePending = new HashSet<>(), imageFailed = new HashSet<>();
    private final List<Listener> listeners = new ArrayList<>();
    private long grantTriedAt;

    static synchronized AppShelf get(Context c) {
        if (instance == null) instance = new AppShelf(c.getApplicationContext());
        return instance;
    }

    private AppShelf(Context c) {
        context = c;
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** The app's cards if read lately (else null, and they are read in the background; listeners hear when ready). */
    List<Recs.Pick> cards(String pkg) {
        if (pkg == null) return null;
        long now = System.currentTimeMillis();
        List<Recs.Pick> list;
        Long at;
        synchronized (lists) {
            list = lists.get(pkg);
            at = listAt.get(pkg);
            if ((list == null || at == null || now - at > FRESH_MS || now < at) && loading.add(pkg)) {
                io.execute(() -> load(pkg));
            }
        }
        return list;
    }

    /** Forget what was read (the person came back from an app: its Continue Watching may have moved on). */
    void stale() {
        synchronized (lists) {
            listAt.clear();
        }
    }

    private void load(String pkg) {
        List<Recs.Pick> out = new ArrayList<>();
        try {
            if (allowed() || grant()) out = read(pkg);
        } catch (RuntimeException e) {
            AppLog.w("top shelf of " + pkg + ": " + e.getMessage());
        }
        final List<Recs.Pick> list = out;
        synchronized (lists) {
            lists.put(pkg, list);
            listAt.put(pkg, System.currentTimeMillis());
            loading.remove(pkg);
        }
        if (!list.isEmpty()) AppLog.w("top shelf of " + pkg + ": " + list.size() + " cards");
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onAppShelf(pkg);
        });
    }

    boolean allowed() {
        for (String p : PERMISSIONS) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Grants the TV listings permissions through the TV's ADB (once a minute at most; never makes the TV ask). */
    private boolean grant() {
        long now = System.currentTimeMillis();
        if (now - grantTriedAt < RETRY_MS && now >= grantTriedAt) return false;
        grantTriedAt = now;
        try (Adb adb = Adb.connect(context, 0, null)) {
            for (String p : PERMISSIONS) adb.shell("pm grant " + context.getPackageName() + " " + p);
        } catch (IOException | RuntimeException e) {
            AppLog.w("top shelf: TV listings permission: " + e.getMessage());
            return false;
        }
        return allowed();
    }

    /** Continue Watching (its Watch Next row) first, then its own rows, as cards. */
    private List<Recs.Pick> read(String pkg) {
        List<Recs.Pick> out = new ArrayList<>();
        boolean all = ALL.equals(pkg);
        String app = all ? "" : appName(pkg);
        ContentResolver cr = context.getContentResolver();
        Set<String> seen = new HashSet<>();
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            List<Recs.Pick> next = new ArrayList<>();
            List<Long> when = new ArrayList<>();
            while (c != null && c.moveToNext()) {
                String owner = text(c, "package_name");
                if (owner == null || (!all && !pkg.equals(owner))) continue;
                Recs.Pick p = card(c, owner, all ? appName(owner) : app, Ui.tr("Continue Watching"));
                if (p == null || !seen.add(p.title + p.meta)) continue;
                long t = number(c, "last_engagement_time_utc_millis");
                int i = 0;
                while (i < when.size() && when.get(i) >= t) i++;
                next.add(i, p);
                when.add(i, t);
            }
            out.addAll(next);
        } catch (RuntimeException e) {
            AppLog.w("top shelf: watch next: " + e.getMessage());
        }
        if (all) return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
        Map<Long, String> channels = channelNames(cr, pkg);
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext() && out.size() < MAX) {
                if (!pkg.equals(text(c, "package_name"))) continue;
                if (c.getColumnIndex("browsable") >= 0 && number(c, "browsable") == 0) continue;
                String row = channels.get(number(c, "channel_id"));
                Recs.Pick p = card(c, pkg, app, row == null ? app : row);
                if (p != null && seen.add(p.title + p.meta)) out.add(p);
            }
        } catch (RuntimeException e) {
            AppLog.w("top shelf: rows: " + e.getMessage());
        }
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private Map<Long, String> channelNames(ContentResolver cr, String pkg) {
        Map<Long, String> names = new HashMap<>();
        try (Cursor c = cr.query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!pkg.equals(text(c, "package_name"))) continue;
                String name = text(c, "display_name");
                if (name != null && !name.isEmpty()) names.put(number(c, "_id"), name);
            }
        } catch (RuntimeException e) {
            AppLog.w("top shelf: channels: " + e.getMessage());
        }
        return names;
    }

    private Recs.Pick card(Cursor c, String pkg, String app, String row) {
        String title = text(c, "title");
        String image = text(c, "thumbnail_uri");
        String poster = text(c, "poster_art_uri");
        // A 16:9 picture makes the background: the thumbnail, unless only the poster is wide (aspect ratio 0 = 16:9).
        if (image == null || (number(c, "thumbnail_aspect_ratio") != 0 && poster != null && number(c, "poster_art_aspect_ratio") == 0)) {
            image = poster != null ? poster : image;
        }
        // Some apps publish placeholders ("your_poster_url"): only real addresses make a card.
        if (title == null || title.trim().isEmpty() || !picture(image)) return null;
        Recs.Pick p = new Recs.Pick();
        p.kind = KIND;
        p.pkg = pkg;
        p.appName = app;
        p.title = title.trim();
        p.backdrop = image;
        p.link = text(c, "intent_uri");
        p.label = row;
        String episode = text(c, "episode_title");
        String season = text(c, "season_display_number"), number = text(c, "episode_display_number");
        StringBuilder meta = new StringBuilder();
        if (season != null && number != null) meta.append(Ui.trf("S{s} E{e}", "s", season, "e", number));
        else if (number != null) meta.append(Ui.trf("Episode {e}", "e", number));
        if (episode != null && !episode.trim().isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(episode.trim());
        long position = number(c, "last_playback_position_millis"), duration = number(c, "duration_millis");
        if (position > 0 && duration > position) {
            p.progress = position / (float) duration;
            long left = Math.max(1, (duration - position) / 60_000);
            meta.append(meta.length() > 0 ? " · " : "").append(left >= 60
                    ? Ui.trf("{h} h {m} min left", "h", left / 60, "m", left % 60) : Ui.trf("{m} min left", "m", left));
        }
        p.meta = meta.toString();
        String about = text(c, "short_description");
        p.overview = about == null ? "" : about.trim();
        String released = text(c, "release_date");
        p.year = released != null && released.length() >= 4 ? released.substring(0, 4) : "";
        p.detailed = true;
        return p;
    }

    private static boolean picture(String address) {
        if (address == null) return false;
        String a = address.toLowerCase(Locale.ROOT);
        return a.startsWith("http://") || a.startsWith("https://") || a.startsWith("content://")
                || a.startsWith("android.resource://") || a.startsWith("file://");
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? -1 : c.getLong(i);
    }

    // MARK: - Pictures

    /** The card's background, ready (or null: it is made in the background and listeners hear when it is ready). */
    Backdrop backdrop(Recs.Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (imageFailed.contains(key) || !imagePending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                Bitmap bitmap = decode(key);
                if (bitmap == null) return;
                Backdrop b = Backdrop.make(bitmap, false);
                synchronized (images) {
                    images.put(key, b);
                    while (images.size() > IMAGES) images.remove(images.keySet().iterator().next());
                }
                main.post(() -> {
                    for (Listener l : new ArrayList<>(listeners)) l.onAppShelf(p.pkg);
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("top shelf picture: " + e.getMessage());
                synchronized (images) {
                    imageFailed.add(key);   // not again until X-OS restarts
                }
            } finally {
                synchronized (images) {
                    imagePending.remove(key);
                }
            }
        });
        return null;
    }

    /** Pictures come as web addresses or as the app's own content (content:, android.resource:, file:). */
    private Bitmap decode(String address) throws IOException {
        byte[] data;
        String scheme = Uri.parse(address).getScheme();
        if (scheme != null && (scheme.toLowerCase(Locale.ROOT).startsWith("http"))) {
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; TV) XOS");
            c.setInstanceFollowRedirects(true);
            try (InputStream in = c.getInputStream()) {
                data = readAll(in);
            } finally {
                c.disconnect();
            }
        } else {
            try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(address))) {
                if (in == null) return null;
                data = readAll(in);
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > 12_000_000) throw new IOException("picture too large");
        }
        return out.toByteArray();
    }
}
