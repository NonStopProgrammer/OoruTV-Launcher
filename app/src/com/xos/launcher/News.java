package com.xos.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Top Shelf › News: the latest headlines from Google News' RSS feed for the chosen edition, shown on the wallpaper
 * (no pictures: the feed has none, and small ones would look blurry on a TV). Only news.google.com is contacted, with
 * nothing but the edition in the request. Read again every 30 minutes while the home screen asks for them.
 */
final class News {
    static final String KIND = "news";
    /** Editions: the country's top stories, four sections, then Hindi and Tamil (India) . */
    static final String[] EDITIONS = {"top", "world", "business", "tech", "sports", "hi", "ta"};
    private static final int MAX = 12;
    private static final long EVERY = 30 * 60_000L, RETRY = 5 * 60_000L;

    interface Listener { void onNews(); }

    private static News instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Recs.Pick> items = Collections.emptyList();
    private volatile String edition = "";
    private volatile long fetchedAt, failedAt;
    private volatile boolean fetching;

    static synchronized News get(Context c) {
        if (instance == null) instance = new News();
        return instance;
    }

    private News() {}

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    static String editionName(String e) {
        switch (e) {
            case "world": return Ui.tr("World");
            case "business": return Ui.tr("Business");
            case "tech": return Ui.tr("Technology");
            case "sports": return Ui.tr("Sports");
            case "hi": return "हिन्दी";
            case "ta": return "தமிழ்";
            default: return Ui.tr("Top stories");
        }
    }

    /** The headlines for the edition in use (empty until the first read; asks for a new read when old). */
    List<Recs.Pick> now() {
        String want = Prefs.news();
        long t = System.currentTimeMillis();
        boolean other = !want.equals(edition);
        if (!fetching && (other || t - fetchedAt > EVERY || t < fetchedAt) && (other || t - failedAt > RETRY)) fetch(want);
        return other ? Collections.<Recs.Pick>emptyList() : items;
    }

    private void fetch(String want) {
        fetching = true;
        worker.execute(() -> {
            try {
                List<Recs.Pick> found = read(want);
                items = Collections.unmodifiableList(found);
                edition = want;
                fetchedAt = System.currentTimeMillis();
                AppLog.w("news: " + found.size() + " headlines (" + want + ")");
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("news: " + e.getMessage());
            } finally {
                fetching = false;
            }
            main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onNews(); });
        });
    }

    private static String url(String e) {
        String region = Recs.REGION.matches("[A-Z]{2}") ? Recs.REGION : "US";
        String lang = e.equals("hi") || e.equals("ta") ? e : "en-" + region;
        String ceid = region + ":" + (e.equals("hi") || e.equals("ta") ? e : "en");
        String topic = e.equals("world") ? "WORLD" : e.equals("business") ? "BUSINESS" : e.equals("tech") ? "TECHNOLOGY"
                : e.equals("sports") ? "SPORTS" : null;
        return "https://news.google.com/rss" + (topic == null ? "" : "/headlines/section/topic/" + topic)
                + "?hl=" + lang + "&gl=" + region + "&ceid=" + ceid;
    }

    private static List<Recs.Pick> read(String e) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url(e)).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; TV) XOS");
        List<Recs.Pick> out = new ArrayList<>();
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                XmlPullParser x = Xml.newPullParser();
                x.setInput(in, null);
                String title = null, source = null, date = null, link = null, tag = null;
                boolean item = false;
                SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                String label = (Ui.tr("NEWS") + " · " + editionName(e)).toUpperCase(Ui.locale());
                for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < MAX; ev = x.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        tag = x.getName();
                        if ("item".equals(tag)) {
                            item = true;
                            title = source = date = link = null;
                        }
                    } else if (ev == XmlPullParser.TEXT && item && tag != null) {
                        String t = x.getText().trim();
                        if (t.isEmpty()) continue;
                        switch (tag) {
                            case "title": title = title == null ? t : title + t; break;
                            case "source": source = t; break;
                            case "pubDate": date = t; break;
                            case "link": link = t; break;
                            default: break;
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        if ("item".equals(x.getName()) && title != null) {
                            item = false;
                            Recs.Pick p = new Recs.Pick();
                            p.kind = KIND;
                            if (source != null && title.endsWith(" - " + source)) title = title.substring(0, title.length() - source.length() - 3);
                            p.title = title;
                            p.appName = source == null ? "" : source;
                            p.label = label;
                            p.link = link;
                            p.id = title.hashCode();
                            p.detailed = true;
                            long at = 0;
                            try { at = date == null ? 0 : rfc.parse(date).getTime(); } catch (Exception ignored) {}
                            p.meta = at > 0 ? ago(at) : "";
                            out.add(p);
                        }
                        tag = null;
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 60) return Ui.trf("{n} min ago", "n", Math.max(1, minutes));
        if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
        return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
    }
}
