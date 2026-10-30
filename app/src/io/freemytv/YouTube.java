package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The home screen's YouTube tab: the newest videos of a few Tamil channels (serials, film songs and trailers,
 * cooking), from each channel's public feed (youtube.com/feeds/videos.xml: no account, no key). Your own YouTube
 * suggestions can't be shown here: Android lets only the TV's built-in launcher read the rows the YouTube app
 * publishes. The channels: youtube_channels in config.properties ("UC…" ids or "@handles"; "off" removes the tab).
 * Only youtube.com and i.ytimg.com (the thumbnails) are contacted, every 3 hours.
 */
final class YouTube {
    static final boolean ON = !Config.YOUTUBE_CHANNELS.trim().equalsIgnoreCase("off");
    static final String PKG = LiveNews.YOUTUBE;
    /** Sun TV, Vijay Television, Zee Tamil, Think Music, Sony Music South, Saregama Tamil, Sun Pictures, Village Cooking. */
    private static final String[] DEFAULT = {
        "UCBnxEdpoZwstJqC1yZpOjRA", "@VijayTelevision", "@zeetamil", "@ThinkMusicIndia", "@SonyMusicSouth",
        "UCzee67JnEcuvjErRyWP3GpQ", "UCo2r1S9iXJshkeV9v_ZDicw", "UCk3JZr7eS3pg5AGEvBdEvFg",
    };
    private static final long EVERY = 3 * 3600_000L, RETRY = 20 * 60_000L, FRESH = 10 * 86_400_000L;
    private static final int PER_CHANNEL = 4, WINDOW = 9;
    private static final Pattern CHANNEL = Pattern.compile("https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})");
    private static YouTube instance;

    static final class Video {
        String id = "", title = "", channel = "";
        long published;
        boolean shorts;
    }

    private final Context context;
    private final File cacheFile;
    private final SharedPreferences handles;     // "@name" → its channel id (looked up once)
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Recs.Listener> listeners = new ArrayList<>();
    private final Map<String, Recs.Pick> picks = new HashMap<>();   // by video id: the same pick every time
    private volatile List<Video> videos = Collections.emptyList();
    private volatile long fetchedAt, failedAt;
    private volatile boolean fetching, failed;

    static synchronized YouTube get(Context c) {
        if (instance == null) instance = new YouTube(c.getApplicationContext());
        return instance;
    }

    private YouTube(Context c) {
        context = c;
        cacheFile = new File(c.getCacheDir(), "youtube.json");
        handles = c.getSharedPreferences("youtube", Context.MODE_PRIVATE);
        if (ON) io.execute(this::load);
    }

    void addListener(Recs.Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Recs.Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        main.post(() -> { for (Recs.Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    /** Fetches the feeds again when they're 3 hours old (20 minutes after a failure). */
    void refreshIfStale() {
        if (!ON || fetching) return;
        long now = System.currentTimeMillis();
        if (now - fetchedAt < EVERY || now - failedAt < RETRY) return;
        fetching = true;
        io.execute(() -> {
            try {
                fetch();
            } finally {
                fetching = false;
            }
        });
    }

    /** Why the tab has no videos yet (null when it has some). */
    String problem() {
        if (!videos.isEmpty()) return null;
        return failed ? Ui.tr("YouTube didn't answer. Trying again soon.") : Ui.tr("Getting the newest YouTube videos…");
    }

    /**
     * This hour's three: from the newest videos (each channel's newest few, the past 10 days), a window of 9 that
     * moves on every hour, three different channels when there are.
     */
    List<Recs.Pick> now() {
        List<Recs.Pick> out = new ArrayList<>();
        List<Video> list = videos;
        if (!ON || list.isEmpty()) return out;
        long now = System.currentTimeMillis();
        List<Video> fresh = new ArrayList<>();
        for (Video v : list) if (now - v.published < FRESH) fresh.add(v);
        if (fresh.isEmpty()) fresh = list.subList(0, Math.min(list.size(), WINDOW));   // all old: still the newest
        int n = Math.min(fresh.size(), WINDOW);
        int start = (int) ((now / 3600_000L * 3) % n);
        Set<String> seen = new HashSet<>();
        List<Video> chosen = new ArrayList<>();
        for (int i = 0; i < n && chosen.size() < 3; i++) {
            Video v = fresh.get((start + i) % n);
            if (seen.add(v.channel)) chosen.add(v);
        }
        for (int i = 0; i < n && chosen.size() < 3; i++) {
            Video v = fresh.get((start + i) % n);
            if (!chosen.contains(v)) chosen.add(v);
        }
        // Newest first: the wide card gets the latest of the three.
        Collections.sort(chosen, (a, b) -> Long.compare(b.published, a.published));
        boolean app = installed();
        synchronized (picks) {
            for (Video v : chosen) {
                Recs.Pick p = picks.get(v.id);
                if (p == null) {
                    p = new Recs.Pick();
                    p.id = v.id.hashCode();
                    p.kind = "youtube";
                    p.title = shortTitle(v.title);
                    p.backdrop = "https://i.ytimg.com/vi/" + v.id + "/maxresdefault.jpg";
                    p.app = "youtube";
                    p.link = "https://www.youtube.com/watch?v=" + v.id;
                    p.label = "YouTube";
                    picks.put(v.id, p);
                }
                p.pkg = app ? PKG : null;
                p.meta = v.channel + " · " + age(v.published, now);
                out.add(p);
            }
            if (picks.size() > 60) picks.clear();
        }
        return out;
    }

    private long appCheckedAt;
    private boolean appInstalled;

    /** The YouTube app (looked at once a minute: now() runs every 15 s). */
    private boolean installed() {
        long now = System.currentTimeMillis();
        if (now - appCheckedAt > 60_000L) {
            appCheckedAt = now;
            try {
                context.getPackageManager().getPackageInfo(PKG, 0);
                appInstalled = true;
            } catch (Exception e) {
                appInstalled = false;
            }
        }
        return appInstalled;
    }

    /** "Ethirneechal - Promo | 24 Oct 2026 | Sun TV Serial" → "Ethirneechal - Promo": the part before the first bar. */
    static String shortTitle(String t) {
        t = t.trim();
        int bar = t.indexOf(" | ");
        if (bar < 0) bar = t.indexOf(" || ");
        if (bar < 0) bar = t.indexOf("| ");
        return bar >= 8 ? t.substring(0, bar).trim() : t;
    }

    private static String age(long at, long now) {
        long hours = Math.max(0, (now - at) / 3600_000L);
        if (hours < 1) return Ui.tr("Just now");
        if (hours < 24) return Ui.plural("plural|one:{n} hour ago|other:{n} hours ago", (int) hours);
        long days = hours / 24;
        if (days == 1) return Ui.tr("Yesterday");
        return Ui.plural("plural|one:{n} day ago|other:{n} days ago", (int) days);
    }

    // MARK: - Feeds

    private String[] channels() {
        String s = Config.YOUTUBE_CHANNELS.trim();
        if (s.isEmpty()) return DEFAULT;
        List<String> out = new ArrayList<>();
        for (String part : s.split("[,\\s]+")) if (!part.isEmpty()) out.add(part);
        return out.toArray(new String[0]);
    }

    private void fetch() {
        long now = System.currentTimeMillis();
        if (now - fetchedAt < EVERY) return;     // the saved list (read just before) is new enough
        String[] channels = channels();
        List<Video> all = new ArrayList<>();
        int ok = 0;
        for (String ch : channels) {
            try {
                List<Video> v = feed(channelId(ch));
                Collections.sort(v, (a, b) -> Long.compare(b.published, a.published));
                all.addAll(v.subList(0, Math.min(v.size(), PER_CHANNEL)));
                ok++;
            } catch (Exception e) {
                AppLog.w("youtube " + ch + ": " + e.getMessage());
            }
        }
        if (ok == 0) {
            failedAt = now;
            failed = true;
            AppLog.w("youtube: no feed answered");
            notifyListeners();
            return;
        }
        Collections.sort(all, (a, b) -> Long.compare(b.published, a.published));
        videos = all;
        fetchedAt = now;
        failed = false;
        save(all, now);
        AppLog.w("youtube: " + all.size() + " videos from " + ok + " of " + channels.length + " channels");
        notifyListeners();
    }

    /** "UC…" as it is; "@name" looked up once on its channel page (the page's canonical link has the id). */
    private String channelId(String entry) throws IOException {
        if (entry.startsWith("UC") && entry.length() == 24) return entry;
        String handle = entry.startsWith("@") ? entry : "@" + entry;
        String saved = handles.getString(handle, null);
        if (saved != null) return saved;
        String link = LiveNews.canonical("https://www.youtube.com/" + handle);
        Matcher m = link == null ? null : CHANNEL.matcher(link);
        if (m == null || !m.matches()) throw new IOException("channel not found");
        handles.edit().putString(handle, m.group(1)).apply();
        return m.group(1);
    }

    private static List<Video> feed(String channelId) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; TV) FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return parse(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** The feed's videos (not the Shorts). */
    static List<Video> parse(InputStream in) throws Exception {
        List<Video> out = new ArrayList<>();
        XmlPullParser x = Xml.newPullParser();
        // Android's parser resolves namespaces: off, so the names stay as written ("yt:videoId", "media:title").
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        x.setInput(in, "UTF-8");
        String channel = "";
        Video v = null;
        for (int ev = x.next(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String name = x.getName();
                if (name.equals("entry")) {
                    v = new Video();
                } else if (name.equals("title")) {
                    String t = x.nextText();
                    if (v == null) channel = t.trim();
                    else v.title = t;
                } else if (v == null) {
                    continue;
                } else if (name.equals("yt:videoId")) {
                    v.id = x.nextText().trim();
                } else if (name.equals("link")) {
                    String href = x.getAttributeValue(null, "href");
                    if (href != null && href.contains("/shorts/")) v.shorts = true;
                } else if (name.equals("name") && channel.isEmpty()) {
                    channel = x.nextText().trim();     // the author, when the feed's own title is missing
                } else if (name.equals("published")) {
                    v.published = time(x.nextText().trim());
                }
            } else if (ev == XmlPullParser.END_TAG && x.getName().equals("entry") && v != null) {
                boolean shorts = v.shorts || v.title.toLowerCase(Locale.ROOT).contains("#short");
                if (v.id.length() == 11 && !v.title.trim().isEmpty() && v.published > 0 && !shorts) {
                    v.channel = channel;
                    out.add(v);
                }
                v = null;
            }
        }
        return out;
    }

    /** The feed's "2026-10-20T12:34:56+00:00". */
    private static long time(String iso) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // MARK: - Saved list (shown at once after a restart)

    private void save(List<Video> list, long at) {
        try {
            JSONArray a = new JSONArray();
            for (Video v : list) {
                a.put(new JSONObject().put("id", v.id).put("title", v.title).put("channel", v.channel).put("published", v.published));
            }
            String json = new JSONObject().put("at", at).put("channels", Config.YOUTUBE_CHANNELS.trim()).put("videos", a).toString();
            File part = new File(cacheFile.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(cacheFile)) throw new IOException("couldn't save");
        } catch (Exception e) {
            AppLog.w("youtube cache: " + e.getMessage());
        }
    }

    private void load() {
        if (!cacheFile.exists()) return;
        try (FileInputStream in = new FileInputStream(cacheFile)) {
            byte[] data = new byte[(int) Math.min(cacheFile.length(), 1 << 20)];
            int size = 0, n;
            while (size < data.length && (n = in.read(data, size, data.length - size)) > 0) size += n;
            JSONObject o = new JSONObject(new String(data, 0, size, StandardCharsets.UTF_8));
            if (!o.optString("channels").equals(Config.YOUTUBE_CHANNELS.trim())) return;   // other channels now
            JSONArray a = o.getJSONArray("videos");
            List<Video> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Video v = new Video();
                v.id = j.getString("id");
                v.title = j.getString("title");
                v.channel = j.optString("channel");
                v.published = j.getLong("published");
                list.add(v);
            }
            if (videos.isEmpty()) {
                videos = list;
                fetchedAt = o.getLong("at");
                notifyListeners();
            }
        } catch (Exception e) {
            AppLog.w("youtube cache: " + e.getMessage());
        }
    }
}
