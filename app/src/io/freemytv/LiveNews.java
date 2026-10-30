package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tamil news channels' live streams on YouTube, one press away. A channel's /live page points (its canonical link) at
 * the stream that is on now; that video opens in the TV's YouTube app. Only youtube.com is contacted, and only when a
 * tile is pressed (the answer is kept for 10 minutes).
 */
final class LiveNews {
    private LiveNews() {}

    /** {Tamil name, English name, channel page on youtube.com}. */
    static final String[][] CHANNELS = {
        {"தந்தி டிவி", "Thanthi TV", "/channel/UC-JFyL0zDFOsPMpuWu39rPA"},
        {"புதிய தலைமுறை", "Puthiya Thalaimurai", "/@puthiyathalaimuraitv"},
        {"சன் நியூஸ்", "Sun News", "/channel/UCYlh4lH762HvHt6mmiecyWQ"},
        {"பாலிமர் நியூஸ்", "Polimer News", "/@polimernews"},
        {"நியூஸ்18 தமிழ்நாடு", "News18 Tamil Nadu", "/@news18tamilnadu"},
        {"நியூஸ்7 தமிழ்", "News7 Tamil", "/channel/UC2f4w_ppqHplvjiNaoTAK9w"},
    };
    static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final Pattern CANONICAL = Pattern.compile("<link rel=\"canonical\" href=\"([^\"]+)\"");
    private static final Pattern WATCH = Pattern.compile("https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})");
    private static final Map<String, String[]> FOUND = new HashMap<>();     // path -> {video id, time}
    private static final byte[] NEEDLE = "rel=\"canonical\"".getBytes(StandardCharsets.US_ASCII);

    private static int indexOf(byte[] data, int from, int to, byte[] needle) {
        outer:
        for (int i = from; i + needle.length <= to; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static String name(int i) {
        return Ui.language.startsWith("ta") ? CHANNELS[i][0] : CHANNELS[i][1];
    }

    /** The live video's id, or null when the channel isn't live. Network: call off the main thread. */
    static String liveVideo(int i) throws IOException {
        String path = CHANNELS[i][2];
        synchronized (FOUND) {
            String[] f = FOUND.get(path);
            if (f != null && System.currentTimeMillis() - Long.parseLong(f[1]) < 10 * 60_000L) return f[0].isEmpty() ? null : f[0];
        }
        String link = canonical("https://www.youtube.com" + path + "/live");
        Matcher m = link == null ? null : WATCH.matcher(link);
        String id = m != null && m.matches() ? m.group(1) : null;
        synchronized (FOUND) {
            FOUND.put(path, new String[] {id == null ? "" : id, String.valueOf(System.currentTimeMillis())});
        }
        return id;
    }

    /**
     * The page's canonical link (in its head: read until it shows up, at most 1.5 MB, scanning only the new bytes), or
     * null. Network: call off the main thread.
     */
    static String canonical(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; TV) FreeMyTV");
        c.setRequestProperty("Accept-Language", "en");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                byte[] data = new byte[256 * 1024];
                int size = 0, scanned = 0, at = -1, n;
                while (size < 1_500_000 && (at < 0 || size < at + 200)) {
                    if (size == data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
                    if ((n = in.read(data, size, data.length - size)) <= 0) break;
                    size += n;
                    if (at < 0) {
                        at = indexOf(data, Math.max(0, scanned - NEEDLE.length), size, NEEDLE);
                        scanned = size;
                    }
                }
                if (at < 0) return null;
                int from = Math.max(0, at - 16);
                Matcher m = CANONICAL.matcher(new String(data, from, Math.min(size, at + 200) - from, StandardCharsets.UTF_8));
                return m.find() ? m.group(1) : null;
            }
        } finally {
            c.disconnect();
        }
    }

    /** Opens the video in YouTube (the TV app; any app that takes YouTube links if it isn't there). */
    static boolean open(Context c, String videoId) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + videoId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (c.getPackageManager().getLaunchIntentForPackage(YOUTUBE) != null
                || c.getPackageManager().getLeanbackLaunchIntentForPackage(YOUTUBE) != null) i.setPackage(YOUTUBE);
        try {
            c.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            AppLog.w("live news: " + e.getMessage());
            return false;
        }
    }
}
