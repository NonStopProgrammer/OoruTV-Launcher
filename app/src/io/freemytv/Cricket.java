package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Cricket scores for the Today page: India's matches and the IPL, from ESPN's public scoreboard (the one espncricinfo
 * is built on; no key, but not a documented API, so it may change: then the card says so and the rest of the page
 * carries on). The series that are on come from the scoreboard header; for each one about India (or the IPL), the
 * match that is live, else the last one played in the past two days, else the next one.
 */
final class Cricket {
    private Cricket() {}

    static final class Match {
        String series = "", title = "", status = "";
        final List<String> lines = new ArrayList<>();   // "IND 245/3 (40 ov)"
        boolean live;
    }

    private static final String HEADER = "https://site.web.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket";
    private static final String BOARD = "https://site.api.espn.com/apis/site/v2/sports/cricket/";

    /** Up to two matches; empty when India isn't playing (and no IPL). Network: call off the main thread. */
    static List<Match> fetch() throws Exception {
        JSONObject header = new JSONObject(get(HEADER));
        JSONArray sports = header.optJSONArray("sports");
        JSONArray leagues = sports != null && sports.length() > 0 ? sports.getJSONObject(0).optJSONArray("leagues") : null;
        List<String[]> series = new ArrayList<>();      // {id, name}
        for (int i = 0; leagues != null && i < leagues.length(); i++) {
            JSONObject lg = leagues.getJSONObject(i);
            String name = lg.optString("name"), id = lg.optString("id");
            boolean india = name.toLowerCase(Locale.ROOT).contains("india");
            JSONArray events = lg.optJSONArray("events");
            for (int e = 0; !india && events != null && e < events.length(); e++) {
                india = events.getJSONObject(e).optString("name").toLowerCase(Locale.ROOT).contains("india");
            }
            if (india && !id.isEmpty()) series.add(new String[] {id, name});
        }
        List<Match> out = new ArrayList<>();
        for (String[] s : series) {
            if (out.size() >= 2) break;
            try {
                Match m = pick(new JSONObject(get(BOARD + s[0] + "/scoreboard")), s[1]);
                if (m != null) out.add(m);
            } catch (Exception e) {
                AppLog.w("cricket " + s[1] + ": " + e.getMessage());
            }
        }
        Collections.sort(out, (a, b) -> Boolean.compare(b.live, a.live));   // the live one first
        return out;
    }

    private static Match pick(JSONObject board, String seriesName) throws Exception {
        JSONArray events = board.optJSONArray("events");
        if (events == null) return null;
        long now = System.currentTimeMillis();
        JSONObject live = null, last = null, next = null;
        long lastAt = Long.MIN_VALUE, nextAt = Long.MAX_VALUE;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            String state = status(e).optString("state");
            long at = time(e.optString("date"));
            if (state.equals("in")) live = e;
            else if (state.equals("post") && at > lastAt && now - at < 2 * 86_400_000L) { last = e; lastAt = at; }
            else if (state.equals("pre") && at < nextAt) { next = e; nextAt = at; }
        }
        JSONObject e = live != null ? live : last != null ? last : next;
        if (e == null) return null;
        Match m = new Match();
        m.series = seriesName;
        m.live = e == live;
        JSONObject comp = e.optJSONArray("competitions") != null ? e.getJSONArray("competitions").optJSONObject(0) : null;
        String description = comp == null ? "" : comp.optString("description");
        m.title = e.optString("shortName", e.optString("name")) + (description.isEmpty() ? "" : " · " + description);
        JSONObject type = status(e);
        m.status = type.optString("shortDetail", type.optString("detail"));
        JSONArray teams = comp == null ? null : comp.optJSONArray("competitors");
        for (int i = 0; teams != null && i < teams.length(); i++) {
            JSONObject t = teams.getJSONObject(i);
            JSONObject team = t.optJSONObject("team");
            String abbr = team == null ? "" : team.optString("abbreviation", team.optString("displayName"));
            String score = t.optString("score");
            m.lines.add(score.isEmpty() ? abbr : abbr + "  " + score);
        }
        return m;
    }

    private static JSONObject status(JSONObject event) {
        JSONArray comps = event.optJSONArray("competitions");
        JSONObject comp = comps == null ? null : comps.optJSONObject(0);
        JSONObject st = comp != null && comp.optJSONObject("status") != null ? comp.optJSONObject("status") : event.optJSONObject("status");
        JSONObject type = st == null ? null : st.optJSONObject("type");
        return type == null ? new JSONObject() : type;
    }

    /** ESPN's "2026-05-31T14:00Z". */
    private static long time(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(iso.length() > 17 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; TV) FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n, total = 0;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > 4 << 20) throw new IOException("too large");
                    out.write(buffer, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
