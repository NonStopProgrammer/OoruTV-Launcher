package com.xos.launcher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scenery for the screensaver, like the Windows lock screen: Bing's photo of the day (the same daily photos Windows
 * and Bing show), the last 15 days of it, kept on the TV (the newest 30), so the screensaver has them offline too.
 * The list is fetched at most every 12 hours and only while the screensaver runs, then the photos one at a time in the
 * background. Only the chosen source is contacted (no key, no account, nothing sent but the request): www.bing.com, or
 * (More Settings › StandBy & Screensaver › Photos) NASA's picture of the day (api.nasa.gov) or Wikipedia's
 * (api.wikimedia.org, upload.wikimedia.org). The photos go to the
 * screensaver as picks of kind KIND: the place as the title, the photographer's credit as the line below, and Bing's
 * headline above. Home Settings > Screensaver photos picks scenery, the picks' photos, or both.
 */
final class Scenery {
    static final String KIND = "scenery";
    private static final String HOST = "https://www.bing.com";
    private static final long EVERY = 12 * 3600_000L, RETRY = 30 * 60_000L;
    private static final int KEEP = 30;
    private static Scenery instance;

    private final File dir, index;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile List<Recs.Pick> photos = Collections.emptyList();
    private volatile JSONArray saved = new JSONArray();     // the same photos as saved in index.json
    private volatile boolean fetching;
    private volatile long fetchedAt, failedAt;
    private volatile String fetchedSource = "bing";   // the source of the photos on disk (Prefs.photoSource)

    static synchronized Scenery get(Context c) {
        if (instance == null) instance = new Scenery(c.getApplicationContext());
        return instance;
    }

    private Scenery(Context c) {
        dir = new File(c.getFilesDir(), "scenery");
        index = new File(dir, "index.json");
        worker.execute(() -> {
            try {
                JSONObject saved = new JSONObject(read(index));
                fetchedAt = saved.optLong("fetchedAt");
                fetchedSource = saved.optString("source", "bing");
                publish(saved.optJSONArray("photos"));
            } catch (Exception ignored) {   // nothing saved yet
            }
        });
    }

    /** The photos on disk, newest first. */
    List<Recs.Pick> photos() {
        return photos;
    }

    File file(Recs.Pick p) {
        File f = new File(dir, p.backdrop);
        return f.isFile() ? f : null;
    }

    void addListener(Runnable l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Runnable l) {
        listeners.remove(l);
    }

    /** Fetches the list (and the photos not on disk yet) if it's older than 12 hours. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        boolean other = !Prefs.photoSource().equals(fetchedSource);   // another source picked: read it now
        if (fetching || (!other && ((now - fetchedAt >= 0 && now - fetchedAt < EVERY) || (now - failedAt >= 0 && now - failedAt < RETRY)))) return;
        fetching = true;
        worker.execute(() -> {
            try {
                fetch();
                fetchedAt = System.currentTimeMillis();
                save();
                AppLog.w("scenery: " + photos.size() + " photos on the TV");
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("scenery: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    // MARK: - Sources

    private void fetch() throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder for the photos");
        String source = Prefs.photoSource();
        // Only this source's photos are kept (the others' files go with the clean-up below).
        JSONArray all0 = !source.equals(fetchedSource) ? new JSONArray() : savedPhotos(), known = new JSONArray();
        for (int i = 0; i < all0.length(); i++) {
            JSONObject o = all0.getJSONObject(i);
            if (o.optString("src", "bing").equals(source)) known.put(o);
        }
        List<JSONObject> found = source.equals("nasa") ? nasa() : source.equals("wikimedia") ? wikimedia() : bing();
        fetchedSource = source;
        // Newest first, the ones already on disk kept with them; then download what is missing, publishing each one.
        List<JSONObject> all = new ArrayList<>(found);
        Set<String> names = new HashSet<>();
        for (JSONObject o : found) names.add(o.getString("file"));
        for (int i = 0; i < known.length(); i++) {
            JSONObject o = known.getJSONObject(i);
            if (names.add(o.optString("file"))) all.add(o);
        }
        Collections.sort(all, (a, b) -> b.optString("date").compareTo(a.optString("date")));
        while (all.size() > KEEP) all.remove(all.size() - 1);
        JSONArray kept = new JSONArray();
        for (JSONObject o : all) {
            String name = o.getString("file");
            File f = new File(dir, name);
            if (!f.isFile()) {
                try {
                    String url = o.getString("url");
                    download(url.startsWith("http") ? url : HOST + url, f);
                } catch (Exception e) {
                    AppLog.w("scenery: photo " + name + ": " + e.getMessage());
                    continue;
                }
            }
            kept.put(o);
            publish(kept);
        }
        // Photos that dropped out of the newest 30: off the disk.
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < kept.length(); i++) keep.add(kept.getJSONObject(i).getString("file"));
        keep.add(index.getName());
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (!keep.contains(f.getName())) f.delete();
        publish(kept);
    }

    /** Bing's photo of the day: 8 days at a time, back to 15 days ago (idx 8 gives the 7 before). */
    private static List<JSONObject> bing() throws Exception {
        List<JSONObject> found = new ArrayList<>();
        for (int idx : new int[] {0, 8}) {
            JSONArray images = new JSONObject(text(HOST + "/HPImageArchive.aspx?format=js&n=8&idx=" + idx + "&mkt=" + market()))
                    .optJSONArray("images");
            for (int i = 0; images != null && i < images.length(); i++) {
                JSONObject photo = entry(images.getJSONObject(i));
                if (photo != null) found.add(photo);
            }
        }
        return found;
    }

    private static String day(long at, String pattern) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(at));
    }

    /** NASA's Astronomy Picture of the Day, the last 20 days (pictures only, not the videos). DEMO_KEY: no account. */
    private static List<JSONObject> nasa() throws Exception {
        List<JSONObject> found = new ArrayList<>();
        long now = System.currentTimeMillis();
        JSONArray days = new JSONArray(text("https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&start_date="
                + day(now - 20 * 86_400_000L, "yyyy-MM-dd")));
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.getJSONObject(i);
            if (!"image".equals(d.optString("media_type"))) continue;
            String url = d.optString("hdurl", d.optString("url"));
            String date = d.optString("date");
            if (!url.startsWith("https://") || !date.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
            String ext = url.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? ".png" : ".jpg";
            String credit = d.optString("copyright").replaceAll("\\s+", " ").trim();
            found.add(new JSONObject().put("file", "nasa-" + date + ext).put("url", url).put("date", date.replace("-", ""))
                    .put("place", d.optString("title").trim()).put("credit", credit.isEmpty() ? "NASA" : credit + " · NASA APOD")
                    .put("headline", "NASA").put("src", "nasa"));
        }
        return found;
    }

    /** Wikipedia's picture of the day (Wikimedia Commons), the last 15 days, wide pictures only. */
    private static List<JSONObject> wikimedia() throws Exception {
        List<JSONObject> found = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int back = 0; back < 15; back++) {
            long at = now - back * 86_400_000L;
            JSONObject img;
            try {
                img = new JSONObject(text("https://api.wikimedia.org/feed/v1/wikipedia/en/featured/" + day(at, "yyyy/MM/dd")))
                        .optJSONObject("image");
            } catch (Exception e) {
                AppLog.w("scenery: wikimedia " + day(at, "yyyy-MM-dd") + ": " + e.getMessage());
                continue;
            }
            if (img == null) continue;
            JSONObject full = img.optJSONObject("image"), thumb = img.optJSONObject("thumbnail");
            if (full == null) continue;
            int width = full.optInt("width"), height = full.optInt("height");
            if (width < 1280 || width < height * 1.2) continue;   // portraits would be cropped to a strip
            String url = full.optString("source");
            if (width > 2400 && thumb != null && thumb.optString("source").matches(".*/\\d+px-[^/]+$")) {
                url = thumb.optString("source").replaceAll("/\\d+px-([^/]+)$", "/1920px-$1");
            }
            if (!url.startsWith("https://")) continue;
            JSONObject desc = img.optJSONObject("description"), artist = img.optJSONObject("artist");
            String place = desc == null ? "" : desc.optString("text").replaceAll("\\s+", " ").trim();
            if (place.length() > 90) place = place.substring(0, 87).trim() + "…";
            String credit = artist == null ? "" : artist.optString("text").replaceAll("\\s+", " ").trim();
            String ext = url.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? ".png" : ".jpg";
            String date = day(at, "yyyyMMdd");
            found.add(new JSONObject().put("file", "wiki-" + date + ext).put("url", url).put("date", date)
                    .put("place", place).put("credit", credit.isEmpty() ? "Wikimedia Commons" : credit + " · Wikimedia Commons")
                    .put("headline", "").put("src", "wikimedia"));
        }
        return found;
    }

    /** One of Bing's images as we keep it, or null if it lacks what we need. */
    private static JSONObject entry(JSONObject img) throws Exception {
        if (!img.optBoolean("wp", true)) return null;     // Bing marks the ones it allows as wallpapers
        String base = img.optString("urlbase");
        int id = base.indexOf("id=");
        if (!base.startsWith("/") || id < 0) return null;
        String name = base.substring(id + 3).replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty() || name.length() > 120) return null;
        // "Meenakshi Temple, Madurai, Tamil Nadu (© Photographer/Getty Images)": the place, then the credit.
        String copyright = img.optString("copyright").trim(), place = copyright, credit = "";
        int at = copyright.lastIndexOf(" (");
        if (at > 0 && copyright.endsWith(")")) {
            place = copyright.substring(0, at).trim();
            credit = copyright.substring(at + 2, copyright.length() - 1).trim();
        }
        String headline = img.optString("title").trim();
        if (headline.equalsIgnoreCase("info")) headline = "";
        return new JSONObject().put("file", name + ".jpg").put("url", base + "_1920x1080.jpg")
                .put("date", img.optString("startdate")).put("place", place).put("credit", credit).put("headline", headline);
    }

    /** Bing's market: the TV's language in the country set for the picks (en-IN, pt-BR...), English if unknown. */
    private static String market() {
        String region = Recs.REGION.matches("[A-Z]{2}") ? Recs.REGION : "US";
        String lang = Ui.language.replaceAll("-.*", "");
        return (lang.matches("[a-z]{2}") ? lang : "en") + "-" + region;
    }

    // MARK: - Saved list

    private JSONArray savedPhotos() {
        try {
            JSONArray a = new JSONObject(read(index)).optJSONArray("photos");
            return a != null ? a : new JSONArray();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void save() throws Exception {
        JSONArray list = saved;
        File tmp = new File(dir, index.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONObject().put("fetchedAt", fetchedAt).put("source", fetchedSource).put("photos", list)
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(index)) throw new IOException("couldn't save the list");
    }

    /** The photos on disk become the screensaver's list (newest first). */
    private void publish(JSONArray list) {
        if (list == null) return;
        List<Recs.Pick> out = new ArrayList<>();
        JSONArray keep = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("file");
            if (name.isEmpty() || !new File(dir, name).isFile()) continue;
            keep.put(o);
            Recs.Pick p = new Recs.Pick();
            p.kind = KIND;
            p.id = name.hashCode();
            p.backdrop = name;
            p.title = o.optString("place");
            p.meta = o.optString("credit");
            String headline = o.optString("headline");
            p.label = headline.isEmpty() ? null : headline.toUpperCase(Ui.locale());
            p.detailed = true;
            out.add(p);
        }
        saved = keep;
        photos = Collections.unmodifiableList(out);
        main.post(() -> { for (Runnable l : new ArrayList<>(listeners)) l.run(); });
    }

    // MARK: - Network and files

    private static String text(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, 1 << 20);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    /** Downloads to a temporary file and keeps it only if it is a whole image. */
    private static void download(String url, File to) throws IOException {
        File tmp = new File(to.getPath() + ".part");
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            copy(in, out, 24 << 20);   // NASA's full-size pictures can be large (decoded at screen size)
        } finally {
            c.disconnect();
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), o);
        if (o.outWidth < 640 || o.outHeight < 360) {
            tmp.delete();
            throw new IOException("not an image");
        }
        if (!tmp.renameTo(to)) {
            tmp.delete();
            throw new IOException("couldn't save");
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "X-OS-Launcher/2.0 (https://github.com/NonStopProgrammer/X-OS-TV-Launcher)");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    private static void copy(InputStream in, OutputStream out, int max) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int n, total = 0;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > max) throw new IOException("too large");
            out.write(buffer, 0, n);
        }
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, 1 << 20);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
