package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

/**
 * Thirukkural of the day: one of the 1330 couplets (assets/kural.json: the Tamil text and G. U. Pope's 1886 English,
 * both public domain), the same one all day, a different one each day. Only the first two books (Aram and Porul,
 * 1 to 1080) take turns: the third, on love, is left for the reader to open on their own.
 */
final class Kural {
    final int number;
    final String line1, line2, english, chapter;

    private Kural(int number, String line1, String line2, String english, String chapter) {
        this.number = number;
        this.line1 = line1;
        this.line2 = line2;
        this.english = english;
        this.chapter = chapter;
    }

    private static final int TURNS = 1080;
    private static JSONObject data;
    private static Kural cached;
    private static long cachedDay = -1;
    private static String cachedLanguage = "";

    /** Today's couplet, or null if the data can't be read. */
    static synchronized Kural today(Context c) {
        Calendar cal = Calendar.getInstance();
        long day = cal.get(Calendar.YEAR) * 400L + cal.get(Calendar.DAY_OF_YEAR);
        if (cached != null && cachedDay == day && cachedLanguage.equals(Ui.language)) return cached;
        try {
            if (data == null) data = new JSONObject(read(c, "kural.json"));
            JSONArray all = data.getJSONArray("k");
            // Days since 1 January 2026, so the order is the same on every TV (and starts at Kural 1 on that day).
            Calendar start = Calendar.getInstance();
            start.clear();
            start.set(2026, Calendar.JANUARY, 1);
            Calendar now = Calendar.getInstance();
            now.set(Calendar.HOUR_OF_DAY, 12);
            long days = Math.max(0, (now.getTimeInMillis() - start.getTimeInMillis()) / 86_400_000L);
            int i = (int) (days % TURNS);
            JSONArray k = all.getJSONArray(i);
            boolean tamil = Ui.language.startsWith("ta");
            String chapter = data.getJSONArray(tamil ? "chapters" : "chaptersEn").getString(i / 10);
            cached = new Kural(i + 1, k.getString(0), k.getString(1), k.getString(2), chapter);
            cachedDay = day;
            cachedLanguage = Ui.language;
            return cached;
        } catch (Exception e) {
            AppLog.w("kural: " + e.getMessage());
            return null;
        }
    }

    private static String read(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
