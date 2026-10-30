package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Today page (the round "Today" button on the home screen): the Tamil date and year, today's Rahu Kalam,
 * Yamagandam and Gulikai, the coming festivals, the Thirukkural of the day, cricket scores (India, IPL) and the Tamil
 * news channels live on YouTube, over the screensaver's scenery photo with the home screen's glass. Only the news
 * tiles take focus: the arrows move between them, OK opens one, Back goes home.
 */
public class TodayActivity extends Activity {
    private TodayView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new TodayView(this);
        setContentView(view);
        // Dark from the first frame (not the light window color) while today's photo loads.
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF050506));
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        view.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return view.key(event) || super.dispatchKeyEvent(event);
    }

    static final class TodayView extends View implements Choreographer.FrameCallback {
        private static final float M = 80, RIGHT = 1840, CARDS_TOP = 300, CARDS_H = 350, LOW_TOP = 686, LOW_H = 330;
        private static final int AMBER = 0xFFFFC266, RED = 0xFFFF453A, INK = 0xFF1C1C1E, DIM = 0xB3FFFFFF, FAINT = 0x80FFFFFF;
        private static final int COLS = 3;

        private final Context context;
        private final Scene scene = new Scene();
        { scene.frosted = Prefs.glass().equals("frosted"); }
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Spring[] focus = new Spring[LiveNews.CHANNELS.length];
        private final RectF[] tiles = new RectF[LiveNews.CHANNELS.length];
        private final RectF tmp = new RectF(), card = new RectF();
        private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint label, big, sub, clockPaint, cardTitle, row, rowNum, small, smallDim, tileName, credit;
        private TextPaint kuralPaint, englishPaint;
        private StaticLayout kuralLayout, englishLayout;
        private Bitmap photo;
        private long photoAt;           // when the photo arrived: it fades in over the dark background
        private android.graphics.Shader dark;
        private int w, h, index;
        private float k = 1;
        private boolean running, animating;
        private long lastNanos, newsAt;
        private Panchang.TamilDate date;
        private List<Panchang.Period> periods = new ArrayList<>();
        private List<Panchang.Festival> festivals = new ArrayList<>();
        private Kural kural;
        private List<Cricket.Match> matches = new ArrayList<>();
        private String cricketNote = "", newsNote = "";
        private final Runnable onScenery = this::loadPhoto;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                recompute();
                invalidate();
                handler.postDelayed(this, 30_000);
            }
        };

        private final Runnable cricket = new Runnable() {
            @Override public void run() {
                if (!running) return;
                worker.execute(() -> {
                    List<Cricket.Match> found = null;
                    String note;
                    try {
                        found = Cricket.fetch();
                        note = found.isEmpty() ? Ui.tr("India isn't playing right now.") : "";
                    } catch (Exception e) {
                        AppLog.w("cricket: " + e.getMessage());
                        note = Ui.tr("Scores aren't available right now.");
                    }
                    final List<Cricket.Match> result = found;
                    final String text = note;
                    handler.post(() -> {
                        if (result != null) matches = result;
                        cricketNote = text;
                        invalidate();
                        boolean live = false;
                        for (Cricket.Match m : matches) live |= m.live;
                        if (running) handler.postDelayed(cricket, live ? 60_000 : 5 * 60_000);
                    });
                });
            }
        };

        TodayView(Context c) {
            super(c);
            context = c;
            for (int i = 0; i < focus.length; i++) {
                focus[i] = new Spring(0.28f, 0.85f, i == 0 ? 1 : 0);
                tiles[i] = new RectF();
            }
            HomeData.Weather wx = HomeData.get(c).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.4f, 0.3f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription(Ui.tr("light|Today"));
        }

        void resume() {
            if (running) return;
            running = true;
            cricketNote = matches.isEmpty() ? Ui.tr("Loading…") : cricketNote;
            recompute();
            Scenery.get(context).addListener(onScenery);
            loadPhoto();
            worker.execute(() -> {
                Kural kk = Kural.today(context);
                handler.post(() -> {
                    kural = kk;
                    kuralLayout = englishLayout = null;
                    invalidate();
                });
            });
            handler.post(cricket);
            handler.postDelayed(tick, 30_000);
            invalidate();
        }

        void pause() {
            running = false;
            handler.removeCallbacksAndMessages(null);
            Scenery.get(context).removeListener(onScenery);
            Choreographer.getInstance().removeFrameCallback(this);
            animating = false;
        }

        void destroy() {
            worker.shutdownNow();
        }

        // MARK: - Data

        private double coordinate(String v, double fallback) {
            try {
                return Double.parseDouble(v);
            } catch (Exception e) {
                return fallback;
            }
        }

        private void recompute() {
            long now = System.currentTimeMillis();
            double lat = coordinate(Prefs.latitude(), 13.0827), lon = coordinate(Prefs.longitude(), 80.2707);
            date = Panchang.tamilDate(now, lat, lon);
            // Today's sunrise and sunset: the forecast's when it is today's, otherwise worked out here.
            HomeData.Weather wx = HomeData.get(context).weather;
            long rise = wx.sunrise, set = wx.sunset;
            if (rise == 0 || set == 0 || Math.abs(now - (rise + set) / 2) > 16 * 3600_000L) {
                java.util.Calendar c = java.util.Calendar.getInstance();
                long[] s = Panchang.sun(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                        c.get(java.util.Calendar.DAY_OF_MONTH), lat, lon, java.util.TimeZone.getDefault());
                rise = s[0];
                set = s[1];
            }
            sunrise = rise;
            sunset = set;
            periods = Panchang.periods(now, rise, set);
            festivals = Panchang.upcoming(now, 4);
        }

        private long sunrise, sunset;

        /** Today's scenery photo (the newest), darkened a little for the text, and its blurred copy for the glass. */
        private void loadPhoto() {
            if (w == 0) return;
            List<Recs.Pick> photos = Scenery.get(context).photos();
            if (photos.isEmpty()) return;
            final File file = Scenery.get(context).file(photos.get(0));
            if (file == null) return;
            final int pw = w, ph = h;
            worker.execute(() -> {
                try {
                    Bitmap[] images = Backdrop.photo(file, pw, ph, null);
                    Bitmap image = images[0];
                    images[1].recycle();
                    Canvas c = new Canvas(image);
                    c.drawColor(0x66000000);
                    Bitmap small = Bitmap.createScaledBitmap(image, Backdrop.FW / 4, Backdrop.FH / 4, true);
                    Bitmap frost = Bitmap.createScaledBitmap(small, Backdrop.FW, Backdrop.FH, true);
                    small.recycle();
                    int[] px = new int[Backdrop.FW * Backdrop.FH];
                    frost.getPixels(px, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
                    frost.recycle();
                    // The Liquid Glass reads a lightly blurred copy (the picture bends at the cards' edges).
                    Bitmap half = Bitmap.createScaledBitmap(image, Backdrop.GW / 2, Backdrop.GH / 2, true);
                    Bitmap glassImage = Bitmap.createScaledBitmap(half, Backdrop.GW, Backdrop.GH, true);
                    half.recycle();
                    int[] gpx = new int[Backdrop.GW * Backdrop.GH];
                    glassImage.getPixels(gpx, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
                    glassImage.recycle();
                    handler.post(() -> {
                        Bitmap old = photo;
                        photo = image;
                        photoAt = old == null ? android.os.SystemClock.uptimeMillis() : 0;
                        scene.setFrost(px);
                        scene.setGlassImage(gpx);
                        invalidate();
                        if (old != null) handler.postDelayed(old::recycle, 1000);
                    });
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("today: photo: " + e.getMessage());
                }
            });
        }

        // MARK: - Layout

        @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            label = Ui.text(Ui.semibold, 26 * k, DIM);
            label.setLetterSpacing(0.08f);
            big = Ui.text(Ui.displaySemibold, 84 * k, 0xFFFFFFFF);
            sub = Ui.text(Ui.medium, 30 * k, DIM);
            clockPaint = Ui.numbers(Ui.thin, 108 * k, 0xF2FFFFFF);
            clockPaint.setTextAlign(Paint.Align.RIGHT);
            cardTitle = Ui.text(Ui.semibold, 25 * k, DIM);
            row = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowNum = Ui.numbers(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowNum.setTextAlign(Paint.Align.RIGHT);
            small = Ui.text(Ui.regular, 23 * k, 0xE6FFFFFF);
            smallDim = Ui.text(Ui.regular, 23 * k, DIM);
            tileName = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
            credit = Ui.text(Ui.regular, 18 * k, FAINT);
            kuralPaint = new TextPaint(Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF));
            englishPaint = new TextPaint(Ui.text(Ui.regular, 21 * k, DIM));
            kuralLayout = englishLayout = null;
            float left = 932 * k, right = RIGHT * k, top = (LOW_TOP + 84) * k, gap = 18 * k;
            float tw = (right - left - 68 * k - gap * (COLS - 1)) / COLS, th = 92 * k;
            for (int i = 0; i < tiles.length; i++) {
                float x = left + 34 * k + (i % COLS) * (tw + gap), y = top + (i / COLS) * (th + gap);
                tiles[i].set(x, y, x + tw, y + th);
            }
            loadPhoto();
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            if (w == 0 || date == null) return;
            // Gray to black (never the old blue sky) until today's photo has loaded, then the photo fades in.
            float in = photoAt == 0 ? 1 : Ui.clamp((android.os.SystemClock.uptimeMillis() - photoAt) / 500f);
            if (photo == null || in < 1) {
                if (dark == null) dark = Ui.gradient(h, 0xFF2A2A2E, 0xFF050506);
                fill.setShader(dark);
                c.drawRect(0, 0, w, h, fill);
                fill.setShader(null);
            }
            if (photo != null) {
                tmp.set(0, 0, w, h);
                photoPaint.setAlpha(Math.round(255 * in * in * (3 - 2 * in)));
                c.drawBitmap(photo, null, tmp, photoPaint);
                photoPaint.setAlpha(255);
                if (in < 1) postInvalidateOnAnimation();
            }
            long now = System.currentTimeMillis();
            float x = M * k;
            c.drawText(Ui.tr("light|Today").toUpperCase(Ui.locale()), x, 110 * k, label);
            c.drawText(Panchang.dateText(date), x - Ui.leftBearing(big, Panchang.dateText(date)), 204 * k, big);
            c.drawText(Ui.date(now) + "  ·  " + Ui.trf("{year} year", "year", date.year), x, 256 * k, sub);
            c.drawText(Ui.clock(now), RIGHT * k, 204 * k, clockPaint);
            HomeData.Weather wx = HomeData.get(context).weather;
            if (!Double.isNaN(wx.temp)) {
                String line = HomeData.degrees(wx.temp) + "  ·  " + HomeData.condition(wx.code, wx.isDay);
                c.drawText(line, RIGHT * k - sub.measureText(line), 256 * k, sub);
            }
            float cw = (RIGHT - M - 64) / 3f;
            drawTimes(c, x, CARDS_TOP * k, cw * k, now);
            drawFestivals(c, (M + cw + 32) * k, CARDS_TOP * k, cw * k, now);
            drawKural(c, (M + 2 * (cw + 32)) * k, CARDS_TOP * k, cw * k);
            drawCricket(c, x, LOW_TOP * k, (900 - M) * k);
            drawNews(c, 932 * k, LOW_TOP * k, (RIGHT - 932) * k);
            c.drawText(Ui.tr("Scores: ESPN · Festivals: Tamil Nadu holiday list, Drik Panchang · Kural in English: G. U. Pope (1886)"), x, 1052 * k, credit);
        }

        private void glass(Canvas c, float left, float top, float width, float height) {
            card.set(left, top, left + width, top + height);
            // Liquid Glass (or frosted, with Glass Style: Frosted), like the home screen's panels.
            scene.liquid(c, card, 30 * k, 0xFF1C1C1E, 0.42f, 0.02f, 30 * k, 22 * k, 0.9f, 1);
        }

        private void drawTimes(Canvas c, float left, float top, float width, long now) {
            glass(c, left, top, width, CARDS_H * k);
            float x = left + 34 * k, right = left + width - 34 * k;
            c.drawText(Ui.tr("Today's timings"), x, top + 54 * k, cardTitle);
            String sun = Ui.trf("Sunrise {time}", "time", Ui.clock(sunrise)) + "   " + Ui.trf("Sunset {time}", "time", Ui.clock(sunset));
            c.drawText(TextUtils.ellipsize(sun, new TextPaint(smallDim), right - x, TextUtils.TruncateAt.END).toString(), x, top + 96 * k, smallDim);
            for (int i = 0; i < periods.size(); i++) {
                Panchang.Period p = periods.get(i);
                float base = top + (168 + i * 64) * k;
                boolean on = now >= p.start && now < p.end, past = now >= p.end;
                int color = on ? AMBER : past ? 0x80FFFFFF : 0xF2FFFFFF;
                row.setColor(color);
                rowNum.setColor(color);
                c.drawText(p.name, x, base, row);
                String range = Ui.clock(p.start) + " – " + Ui.clock(p.end);
                c.drawText(range, right, base, rowNum);
                if (on) {
                    String nowText = Ui.tr("now");
                    float tw = small.measureText(nowText) + 24 * k, rx = right - rowNum.measureText(range) - 16 * k;
                    tmp.set(rx - tw, base - Ui.cap(row) - 6 * k, rx, base + 8 * k);
                    fill.setColor(0x33FFC266);
                    c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill);
                    small.setColor(AMBER);
                    c.drawText(nowText, tmp.left + 12 * k, base, small);
                    small.setColor(0xE6FFFFFF);
                }
            }
            row.setColor(0xF2FFFFFF);
            rowNum.setColor(0xF2FFFFFF);
        }

        private void drawFestivals(Canvas c, float left, float top, float width, long now) {
            glass(c, left, top, width, CARDS_H * k);
            float x = left + 34 * k, right = left + width - 34 * k;
            c.drawText(Ui.tr("Coming festivals"), x, top + 54 * k, cardTitle);
            TextPaint fit = new TextPaint(row);
            for (int i = 0; i < festivals.size(); i++) {
                Panchang.Festival f = festivals.get(i);
                int days = Panchang.daysUntil(f, now);
                float base = top + (120 + i * 60) * k;
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.clear();
                cal.set(f.year, f.month - 1, f.day);
                String when = Ui.datePattern("format|MMM d").format(cal.getTimeInMillis()) + " · "
                        + (days == 0 ? Ui.tr("day|Today") : days == 1 ? Ui.tr("Tomorrow") : Ui.trf("in {n} days", "n", days));
                smallDim.setColor(days <= 1 ? AMBER : DIM);
                float ww = smallDim.measureText(when);
                c.drawText(when, right - ww, base, smallDim);
                smallDim.setColor(DIM);
                c.drawText(TextUtils.ellipsize(f.name(), fit, right - x - ww - 20 * k, TextUtils.TruncateAt.END).toString(), x, base, row);
            }
        }

        private void drawKural(Canvas c, float left, float top, float width) {
            glass(c, left, top, width, CARDS_H * k);
            float x = left + 34 * k, right = left + width - 34 * k;
            if (kural == null) {
                c.drawText(Ui.tr("Thirukkural"), x, top + 54 * k, cardTitle);
                return;
            }
            String head = Ui.trf("Thirukkural · {n}", "n", kural.number);
            c.drawText(head, x, top + 54 * k, cardTitle);
            float hw = cardTitle.measureText(head) + 18 * k;
            String chapter = TextUtils.ellipsize(kural.chapter, new TextPaint(smallDim), right - x - hw, TextUtils.TruncateAt.END).toString();
            c.drawText(chapter, right - smallDim.measureText(chapter), top + 54 * k, smallDim);
            int lw = Math.round(right - x);
            if (kuralLayout == null) {
                String t = kural.line1 + "\n" + kural.line2;
                kuralLayout = StaticLayout.Builder.obtain(t, 0, t.length(), kuralPaint, lw).setMaxLines(4)
                        .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6 * k, 1).setIncludePad(false).build();
                englishLayout = StaticLayout.Builder.obtain(kural.english, 0, kural.english.length(), englishPaint, lw).setMaxLines(4)
                        .setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
            }
            c.save();
            c.translate(x, top + 82 * k);
            kuralLayout.draw(c);
            c.translate(0, kuralLayout.getHeight() + 20 * k);
            englishLayout.draw(c);
            c.restore();
        }

        private void drawCricket(Canvas c, float left, float top, float width) {
            glass(c, left, top, width, LOW_H * k);
            float x = left + 34 * k, right = left + width - 34 * k;
            c.drawText(Ui.tr("Cricket"), x, top + 54 * k, cardTitle);
            if (matches.isEmpty()) {
                c.drawText(cricketNote, x, top + 120 * k, smallDim);
                return;
            }
            TextPaint fit = new TextPaint(smallDim), fitRow = new TextPaint(rowNum);
            fitRow.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i < matches.size() && i < 2; i++) {
                Cricket.Match m = matches.get(i);
                float y = top + (98 + i * 118) * k;
                String head = m.series + "  ·  " + m.title;
                c.drawText(TextUtils.ellipsize(head, fit, right - x, TextUtils.TruncateAt.END).toString(), x, y, smallDim);
                String score = TextUtils.join("     ", m.lines);
                c.drawText(TextUtils.ellipsize(score, fitRow, right - x, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, fitRow);
                float sx = x;
                if (m.live) {
                    fill.setColor(RED);
                    c.drawCircle(sx + 7 * k, y + 76 * k - Ui.halfCap(small), 7 * k, fill);
                    small.setColor(RED);
                    String live = Ui.tr("LIVE");
                    c.drawText(live, sx + 22 * k, y + 76 * k, small);
                    sx += 22 * k + small.measureText(live) + 14 * k;
                    small.setColor(0xE6FFFFFF);
                }
                c.drawText(TextUtils.ellipsize(m.status, new TextPaint(small), right - sx, TextUtils.TruncateAt.END).toString(), sx, y + 76 * k, small);
            }
        }

        private void drawNews(Canvas c, float left, float top, float width) {
            glass(c, left, top, width, LOW_H * k);
            float x = left + 34 * k, right = left + width - 34 * k;
            c.drawText(Ui.tr("Live news"), x, top + 54 * k, cardTitle);
            if (!newsNote.isEmpty()) {
                String note = TextUtils.ellipsize(newsNote, new TextPaint(smallDim), (right - x) * 0.6f, TextUtils.TruncateAt.END).toString();
                c.drawText(note, right - smallDim.measureText(note), top + 54 * k, smallDim);
            }
            for (int i = 0; i < tiles.length; i++) {
                float f = Ui.clamp(focus[i].value), s = 1 + 0.05f * f;
                RectF b = tiles[i];
                tmp.set(b.centerX() - b.width() * s / 2, b.centerY() - b.height() * s / 2, b.centerX() + b.width() * s / 2, b.centerY() + b.height() * s / 2);
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.12f + 0.88f * f));
                c.drawRoundRect(tmp, 18 * k, 18 * k, fill);
                int ink = f > 0.5f ? INK : 0xFFFFFFFF;
                tileName.setColor(ink);
                String name = TextUtils.ellipsize(LiveNews.name(i), new TextPaint(tileName), tmp.width() - 36 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(name, tmp.left + 18 * k, tmp.top + 40 * k, tileName);
                fill.setColor(RED);
                c.drawCircle(tmp.left + 24 * k, tmp.bottom - 26 * k - Ui.halfCap(smallDim) + 2 * k, 6 * k, fill);
                smallDim.setColor(f > 0.5f ? 0x991C1C1E : DIM);
                c.drawText(Ui.tr("LIVE"), tmp.left + 38 * k, tmp.bottom - 24 * k, smallDim);
                smallDim.setColor(DIM);
            }
        }

        // MARK: - Keys and focus

        boolean key(KeyEvent e) {
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int n = tiles.length, next = index;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (down && index % COLS > 0) next = index - 1; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down && index % COLS < COLS - 1 && index + 1 < n) next = index + 1; break;
                case KeyEvent.KEYCODE_DPAD_UP: if (down && index >= COLS) next = index - COLS; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (down && index + COLS < n) next = index + COLS; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!down && e.getRepeatCount() == 0) openNews(index);
                    return true;
                default:
                    return false;
            }
            if (next != index) {
                index = next;
                for (int i = 0; i < focus.length; i++) focus[i].target = i == index ? 1 : 0;
                startMoving();
            }
            return true;
        }

        private void openNews(int i) {
            if (SystemClock.uptimeMillis() - newsAt < 1500) return;   // one at a time
            newsAt = SystemClock.uptimeMillis();
            newsNote = Ui.tr("Opening…");
            invalidate();
            worker.execute(() -> {
                String id = null, note;
                try {
                    id = LiveNews.liveVideo(i);
                    note = id == null ? Ui.trf("{name} isn't live right now.", "name", LiveNews.name(i)) : "";
                } catch (Exception e) {
                    AppLog.w("live news: " + e.getMessage());
                    note = Ui.tr("Couldn't reach YouTube.");
                }
                final String video = id, text = note;
                handler.post(() -> {
                    newsNote = text;
                    invalidate();
                    if (video != null && running && !LiveNews.open(context, video)) {
                        newsNote = Ui.tr("Couldn't reach YouTube.");
                        invalidate();
                    }
                });
            });
        }

        private void startMoving() {
            if (animating) return;
            animating = true;
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
            lastNanos = frameTimeNanos;
            boolean moving = false;
            for (Spring s : focus) moving |= s.step(dt);
            invalidate();
            if (moving && running) Choreographer.getInstance().postFrameCallback(this);
            else animating = false;
        }
    }
}
