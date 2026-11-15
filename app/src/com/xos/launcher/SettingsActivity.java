package com.xos.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * X-OS's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the X-OS / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting
    private boolean firstRunShown;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n com.xos.launcher/.SettingsActivity --ez screensaver true (straight to the preview; with
        // --es saver standby, that style).
        // From ADB, for screenshots: --es open home (Home Settings), font (the font list), more (More Settings) or
        // more1 … more11 (one of its pages).
        String open = getIntent().getStringExtra("open");
        if (open != null) {
            getIntent().removeExtra("open");
            view.openScreen(open);
        } else if (!firstRunShown && !Prefs.setupDone()) {
            // First run: a few choices before switching (Back skips; About › Set up again brings them back).
            firstRunShown = true;
            view.openScreen("setup");
        }
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            preview = new DreamView(this);
            preview.forceStyle = getIntent().getStringExtra("saver");
            preview.forceFace(getIntent().getStringExtra("face"));
            setContentView(preview);
            preview.start();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                OPTIONS = 6;
        private static final float LIST_ROW_H = 56, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        // tvOS split view: a sidebar (your profile, then the sections with their colored icons) and the content pane.
        private static final float LIST_X = 730, LIST_R = 1800, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        private static final float SIDE_X = 84, SIDE_R = 654, DIVIDER = 692;
        // The main menu: eight rows, then the comparison, the picks (with the Watchmode credit) and two notices, all on
        // one 1080 px screen.
        private static final float MAIN_TOP = 300, ROW_H = 74, MAIN_GAP = 8;
        private static final int[] SIDE_COLORS = {0xFF8E5CF7, 0xFF0A84FF, 0xFF30B0C7, 0xFFFF9F0A, 0xFF5E5CE6, 0xFF8E8E93,
                0xFF34C759, 0xFF636366};
        private static final String[] SIDE_ICONS = {"home", "bedtime", "settings", "apps", "tv", "gamepad", "sparkles",
                "tv_settings"};
        private static final int MORE_ROW = 6;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop, shelfFromOptions;
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1, PICK_FONT = -2;
        private Typeface[] fontFaces;      // the font picker: each name in its own font
        private boolean[] fontMissing;     // SF fonts whose files aren't on the TV yet
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private Paint rowRim, sideLabel, profileName, emoji, pageTitle;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            scene.frosted = Prefs.glass().equals("frosted");
            setFocusable(true);
            setContentDescription("X-OS");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = busyText();
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText)
                                : freeActive() ? "X-OS" : Ui.tr("Original");
                        return Ui.trf("Home Screen, {mode}", "mode", value);
                    }
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"),
                            Ui.tr("Shelf"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("More Settings"),
                            Ui.tr("TV Settings")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "X-OS, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingLabels(), values = keys ? keyValues() : settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case OPTIONS: {
                    List<Opt> list = opts();
                    int at = pickScroll + row;
                    if (at >= list.size()) return "";
                    Opt o = list.get(at);
                    return o.label + (o.value.isEmpty() ? "" : ", " + o.value) + (o.check ? Ui.tr(", selected") : "");
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = MAIN_TOP + row * (ROW_H + MAIN_GAP);
                height = ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            if (screen == MAIN) focusBox.set(SIDE_X * k, top * k, SIDE_R * k, (top + height) * k);
            else focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
            Scenery.get(getContext()).addListener(onScenery);
            loadPhoto();
        }

        void pause() {
            Scenery.get(getContext()).removeListener(onScenery);
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            makePaints();
            dark = null;
            loadPhoto();
        }

        /** The text paints, in the font in use (made again when the font changes). */
        private void makePaints() {
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            rowRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rowRim.setStyle(Paint.Style.STROKE);
            rowRim.setStrokeWidth(Math.max(1, 1.5f * k));
            sideLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            profileName = Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF);
            emoji = new Paint(Paint.ANTI_ALIAS_FLAG);
            emoji.setTextAlign(Paint.Align.CENTER);
            pageTitle = Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF);
        }

        void openScreen(String what) {
            if (what.startsWith("more")) {
                // --es open more (the hub) or more1 … more11 (a page: Appearance … About).
                screen = OPTIONS;
                int p = 0;
                try { p = Integer.parseInt(what.substring(4)); } catch (NumberFormatException ignored) {}
                openPage(Math.max(P_HUB, Math.min(P_ABOUT, p)));
                invalidate();
                return;
            }
            if (what.equals("setup")) {
                screen = OPTIONS;
                openPage(P_SETUP);
                invalidate();
                return;
            }
            screen = HOME_SETTINGS;
            focus = what.equals("font") ? FONT_ROW : 0;
            if (what.equals("font")) openPicker(PICK_FONT);
            invalidate();
        }

        /** A new font: loaded now, and these screens draw with it right away (the home screen when it comes back). */
        private void setFont(String key) {
            if (key.equals(Prefs.font())) return;
            Prefs.setFont(key);
            Ui.init(getContext());
            if (w > 0) makePaints();
            invalidate();
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 8;
                case OPTIONS: return Math.min(OPT_VISIBLE, opts().size());
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            drawBackdrop(c);
            drawSidebar(c, seconds);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else if (screen == OPTIONS) drawOptions(c); else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            // The pane: the app, then how it is doing.
            float x = LIST_X * k + 30 * k;
            c.drawText("X-OS", x, 150 * k, pageTitle);
            subtitle.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Your TV's home screen and screensaver"), x, 198 * k, subtitle);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(versionName(getContext()), x, 236 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            float y = 320 * k;

            String error = state.optString("error");
            if (working && busyText.equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                c.drawText(Ui.tr("On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
            y = drawComparison(c, y);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: see the README (config.properties).");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, 3 new ones every hour.", "n", count);
            else line = Ui.tr("Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) {
                c.drawText(Ui.tr("Links to the titles by Watchmode."), x, y + 108 * k, version);
            }
            version.setTextAlign(Paint.Align.CENTER);
            return y + (Config.WATCHMODE_KEY.trim().isEmpty() ? 78 : 108) * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (screen != MAIN) {
                // Content rows sit on their own glass plates (tvOS).
                float r = 18 * k;
                white.setAlpha(26);
                c.drawRoundRect(rowRect, r, r, white);
                white.setAlpha(255);
                rowRim.setColor(0x24FFFFFF);
                c.drawRoundRect(rowRect, r, r, rowRim);
            }
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText("X-OS", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: X-OS or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 46 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"X-OS", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        // MARK: - tvOS look: today's photo behind a Liquid Glass window, the sidebar with your profile

        private android.graphics.Bitmap photo;
        private static final boolean PHOTO_BACKDROP = false;
        private android.graphics.Shader dark;
        private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF window = new RectF(), card = new RectF();
        private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        private final Runnable onScenery = this::loadPhoto;

        /** Today's scenery photo, darkened, and its blurred copies for the glass (as on the Today page). */
        private void loadPhoto() {
            // Black, as before: the screensaver's photo stays on the screensaver (the glass shows the dark gradient).
            if (!PHOTO_BACKDROP) return;
            if (w == 0) return;
            List<Recs.Pick> photos = Scenery.get(getContext()).photos();
            if (photos.isEmpty()) return;
            final java.io.File file = Scenery.get(getContext()).file(photos.get(0));
            if (file == null) return;
            final int pw = w, ph = h;
            worker.execute(() -> {
                try {
                    android.graphics.Bitmap[] images = Backdrop.photo(file, pw, ph, null);
                    android.graphics.Bitmap image = images[0];
                    images[1].recycle();
                    new Canvas(image).drawColor(0x8C000000);
                    android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(image, Backdrop.FW / 4, Backdrop.FH / 4, true);
                    android.graphics.Bitmap frost = android.graphics.Bitmap.createScaledBitmap(small, Backdrop.FW, Backdrop.FH, true);
                    small.recycle();
                    final int[] px = new int[Backdrop.FW * Backdrop.FH];
                    frost.getPixels(px, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
                    frost.recycle();
                    android.graphics.Bitmap half = android.graphics.Bitmap.createScaledBitmap(image, Backdrop.GW / 4, Backdrop.GH / 4, true);
                    android.graphics.Bitmap soft = android.graphics.Bitmap.createScaledBitmap(half, Backdrop.GW, Backdrop.GH, true);
                    half.recycle();
                    final int[] gpx = new int[Backdrop.GW * Backdrop.GH];
                    soft.getPixels(gpx, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
                    soft.recycle();
                    // The window shows the photo blurred (the frosted look behind the glass), not sharp.
                    android.graphics.Bitmap blurred = android.graphics.Bitmap.createScaledBitmap(image, pw / 16, ph / 16, true);
                    image.recycle();
                    handler.post(() -> {
                        android.graphics.Bitmap old = photo;
                        photo = blurred;
                        scene.setFrost(px);
                        scene.setGlassImage(gpx);
                        invalidate();
                        if (old != null) handler.postDelayed(old::recycle, 1000);
                    });
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("settings: photo: " + e.getMessage());
                }
            });
        }

        /** The photo (soft), or gray to black; then the big Liquid Glass window and the sidebar's divider. */
        private void drawBackdrop(Canvas c) {
            if (photo != null) {
                tmp.set(0, 0, w, h);
                c.drawBitmap(photo, null, tmp, photoPaint);
            } else {
                if (dark == null) dark = Ui.gradient(h, 0xFF2A2A2E, 0xFF050506);
                fillPaint.setShader(dark);
                c.drawRect(0, 0, w, h, fillPaint);
                fillPaint.setShader(null);
            }
            window.set(40 * k, 40 * k, w - 40 * k, h - 40 * k);
            scene.liquid(c, window, 60 * k, 0xFF1C1C1E, 0.46f, 0.02f, 36 * k, 28 * k, 0.9f, 1);
            fillPaint.setColor(0x1FFFFFFF);
            c.drawRect(DIVIDER * k, 80 * k, DIVIDER * k + Math.max(1, 1.5f * k), h - 80 * k, fillPaint);
        }

        /** The section a screen belongs to (highlighted in the sidebar while you are in it). */
        private int parentRow() {
            switch (screen) {
                case HOME_CHOICE: return 0;
                case HOME_SETTINGS: return 2;
                case SHELF_EDIT: return editingTop ? 4 : 3;
                case REMOTE_KEYS: return 5;
                case PICKER: return pickFor > 0 ? 5 : pickBack == OPTIONS ? MORE_ROW : 2;
                case OPTIONS: return MORE_ROW;
                default: return -1;
            }
        }

        /**
         * The sidebar: your profile (avatar and name), then the sections, each with its colored icon. On the main screen
         * they are the menu (focused: white, dark ink); elsewhere the current section stays lit.
         */
        private void drawSidebar(Canvas c, float seconds) {
            float x0 = SIDE_X * k, x1 = SIDE_R * k;
            card.set(x0, 104 * k, x1, 252 * k);
            white.setAlpha(30);
            c.drawRoundRect(card, 30 * k, 30 * k, white);
            white.setAlpha(255);
            rowRim.setColor(0x26FFFFFF);
            c.drawRoundRect(card, 30 * k, 30 * k, rowRim);
            float ax = card.left + 78 * k, ay = card.centerY(), ad = 96 * k;
            fillPaint.setColor(0xFF0A84FF);
            c.drawCircle(ax, ay, ad / 2, fillPaint);
            rowRim.setColor(0xE6FFFFFF);
            rowRim.setStrokeWidth(3 * k);
            c.drawCircle(ax, ay, ad / 2 - 1.5f * k, rowRim);
            rowRim.setStrokeWidth(Math.max(1, 1.5f * k));
            emoji.setTextSize(ad * 0.6f);
            c.drawText(Prefs.avatar(), ax, ay + ad * 0.21f, emoji);
            String name = Prefs.profileName().isEmpty() ? "X-OS" : Prefs.profileName();
            c.drawText(TextUtils.ellipsize(name, new TextPaint(profileName), x1 - ax - 90 * k, TextUtils.TruncateAt.END).toString(),
                    ax + 74 * k, ay - 4 * k, profileName);
            rowSub.setColor(0x99FFFFFF);
            c.drawText(Ui.tr("Profile"), ax + 74 * k, ay + 32 * k, rowSub);

            boolean working = screen == MAIN && busy();
            String[] labels = {Ui.tr("Home Screen"), Ui.tr("Screensaver"), Ui.tr("Home Settings"), Ui.tr("Shelf"),
                    Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("More Settings"), Ui.tr("TV Settings")};
            int parent = parentRow();
            for (int i = 0; i < labels.length; i++) {
                float y = (MAIN_TOP + i * (ROW_H + MAIN_GAP)) * k;
                rowRect.set(x0, y, x1, y + ROW_H * k);
                float f = 0;
                if (screen == MAIN) {
                    f = drawRowBackground(c, i);
                } else if (i == parent) {
                    white.setAlpha(46);
                    c.drawRoundRect(rowRect, 18 * k, 18 * k, white);
                    white.setAlpha(255);
                }
                float sq = 46 * k, sx = rowRect.left + 18 * k, sy = rowRect.centerY() - sq / 2;
                card.set(sx, sy, sx + sq, sy + sq);
                fillPaint.setColor(SIDE_COLORS[i]);
                c.drawRoundRect(card, 12 * k, 12 * k, fillPaint);
                Symbols.draw(getContext(), c, SIDE_ICONS[i], card.centerX(), card.centerY(), 28 * k, 0xFFFFFFFF);
                sideLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(sideLabel);
                c.drawText(labels[i], sx + sq + 22 * k, base, sideLabel);
                float right = rowRect.right - 22 * k;
                if (i == 0 && working) {
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, right - 12 * k, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else if (i == 0) {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(freeActive() ? "X-OS" : Ui.tr("Original"), right, base, rowValue);
                } else if (i >= 2) {
                    float cw = Symbols.width(getContext(), "chevron_right", 20 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 20 * k, blend(0x59FFFFFF, 0x991C1C1E, f));
                }
            }
            if (working) {
                String text = busyText().isEmpty() ? Ui.tr("Starting…") : busyText();
                rowSub.setColor(0xB3FFFFFF);
                float y = (MAIN_TOP + labels.length * (ROW_H + MAIN_GAP) + 30) * k;
                c.drawText(TextUtils.ellipsize(text, new TextPaint(rowSub), x1 - x0 - 20 * k, TextUtils.TruncateAt.END).toString(),
                        x0 + 18 * k, y, rowSub);
            }
        }


        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "X-OS " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "X-OS";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && screen == OPTIONS) {
                        Opt o = focusedOpt();
                        if (o != null && o.step != null) {
                            o.step.step(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                            changed();
                        }
                        invalidate();
                        spoken.focusMoved();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE || pickFor == PICK_FONT)) {
                                backFromPicker();
                            } else if (screen == OPTIONS && page == P_SETUP) {
                                finishSetup();
                            } else if (screen == OPTIONS && page != P_HUB) {
                                focus = page - 1;   // the hub lists the pages in order
                                pickScroll = 0;
                                page = P_HUB;
                                pageStatus = "";
                            } else if (screen == SHELF_EDIT && shelfFromOptions) {
                                screen = OPTIONS;
                                focus = 3;   // Top Shelf › Shelf apps
                                pickScroll = 0;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else {
                                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? (editingTop ? 4 : 3)
                                        : screen == REMOTE_KEYS ? 5 : screen == OPTIONS ? MORE_ROW : 0;
                                pickScroll = 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /**
         * Home Settings: language, temperature, theme, font, sounds, weather, city, markets, the screensaver's style, then
         * one row per index (12 rows: what the screen holds).
         */
        private static final int FONT_ROW = 3, SHELF_ROW = 4, CITY_ROW = 7, SAVER_ROW = 9, COIN_ROWS = 10;
        private static final String[] SHELVES = {"picks", "app", "all", "news", "wallpaper"};

        private static String shelfName(String v) {
            return v.equals("picks") ? Ui.tr("Picks for you") : v.equals("all") ? Ui.tr("All Continue Watching")
                    : v.equals("wallpaper") ? Ui.tr("Wallpaper and clock") : v.equals("news") ? Ui.tr("News headlines")
                    : Ui.tr("Focused app");
        }
        private static final String[] SAVERS = {"scenery", "picks", "both", "standby"};

        private static String saverName(String v) {
            return v.equals("picks") ? Ui.tr("Movies and shows") : v.equals("both") ? Ui.tr("Both")
                    : v.equals("standby") ? Ui.tr("StandBy clock") : Ui.tr("Scenery");
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Language"));
            l.add(Ui.tr("Temperature"));
            l.add(Ui.tr("Theme"));
            l.add(Ui.tr("Font"));
            l.add(Ui.tr("Top Shelf"));
            l.add(Ui.tr("Interface sounds"));
            l.add(Ui.tr("Weather widget"));
            l.add(Ui.tr("City"));
            l.add(Ui.tr("Markets widget"));
            l.add(Ui.tr("Screensaver style"));
            for (String name : HomeData.COIN_NAMES) l.add(name);
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
            v[FONT_ROW] = Fonts.name(Prefs.font());
            v[SHELF_ROW] = shelfName(Prefs.topShelf());
            v[5] = soundName(Prefs.soundMode());
            v[6] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[8] = onOff(Prefs.quotes());
            v[SAVER_ROW] = saverName(Prefs.screensaver());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            return v;
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : Ui.tr("Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case FONT_ROW: {
                    // ← → step through the fonts (OK opens the list, each name in its own font).
                    int at = java.util.Arrays.asList(Fonts.KEYS).indexOf(Prefs.font());
                    setFont(Fonts.KEYS[(at + dir + Fonts.KEYS.length) % Fonts.KEYS.length]);
                    return;
                }
                case SHELF_ROW: {
                    int at = java.util.Arrays.asList(SHELVES).indexOf(Prefs.topShelf());
                    Prefs.setTopShelf(SHELVES[(at + dir + SHELVES.length) % SHELVES.length]);
                    break;
                }
                case 5: Prefs.setSoundMode(nextOf(SOUNDS, Prefs.soundMode(), dir)); break;
                case 6: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case 8: Prefs.setQuotes(!Prefs.quotes()); break;
                case SAVER_ROW: {
                    int at = java.util.Arrays.asList(SAVERS).indexOf(Prefs.screensaver());
                    Prefs.setScreensaver(SAVERS[(at + dir + SAVERS.length) % SAVERS.length]);
                    break;
                }
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && (screen == HOME_SETTINGS || screen == OPTIONS)) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        // MARK: - More Settings: every option, page by page (the hub lists the pages)

        private static final int OPT_VISIBLE = 11;
        private static final int P_HUB = 0, P_LOOK = 1, P_STATUS = 2, P_SHELF = 3, P_SAVER = 4, P_SOUND = 5, P_NETWORK = 6,
                P_DNS = 7, P_BACKUP = 8, P_DEBLOAT = 9, P_STORAGE = 10, P_ABOUT = 11, P_SETUP = 12;
        private static final String[] PHOTO_SOURCES = {"bing", "nasa", "wikimedia"};
        private static final String[] GLASSES = {"liquid", "frosted"}, CLOCKS = {"auto", "12", "24"},
                ANIMATIONS = {"normal", "fast", "slow", "reduced"}, FOLDER_STYLES = {"window", "full"},
                SOUNDS = {"xos", "tv", "off"}, STANDBY_CLOCKS = {"analog", "digital", "world", "float", "date"};
        private static final float[] TEXT_SIZES = {0.9f, 1f, 1.1f, 1.2f};
        private static final String[] EMOJIS = {"🐼", "🦊", "🐯", "🦁", "🐸", "🐙", "🦄", "🌸", "🌵", "🍉", "⭐", "🎬"};
        // Private DNS: [name, host]; "" = Android's own (automatic).
        private static final String[][] DNS = {{"Automatic", ""}, {"AdGuard", "dns.adguard-dns.com"},
                {"Cloudflare", "one.one.one.one"}, {"Google", "dns.google"}};
        private static final String BACKUP_NAME = "x-os-backup.json", BACKUP_SHARED = "/sdcard/X-OS/" + BACKUP_NAME;

        private int page, pickBack = HOME_SETTINGS, pickBackFocus, pickBackScroll;
        private String pageStatus = "";
        private boolean optBusy;
        // What the pages show, read when one opens (not while drawing).
        private String ip = "—", netType = "—", dnsMode = "", dnsHost = "", storageText = "—", memoryText = "—";
        private final List<String[]> debloat = new ArrayList<>();   // [package, "on" | "off" | "busy"]
        private boolean debloatLoading;
        private final java.util.Map<String, String> appNames = new java.util.HashMap<>();

        interface Step { void step(int dir); }

        /** A row: its name and value; ← → (and OK) step the value, or OK opens something (`more`: another page). */
        static final class Opt {
            final String label, value;
            final Step step;
            final Runnable open;
            boolean more, check, dim;

            Opt(String label, String value, Step step, Runnable open) {
                this.label = label;
                this.value = value == null ? "" : value;
                this.step = step;
                this.open = open;
            }
        }

        private static Opt cycle(String label, String value, Step step) { return new Opt(label, value, step, null); }

        private static Opt link(String label, String value, Runnable open) {
            Opt o = new Opt(label, value, null, open);
            o.more = true;
            return o;
        }

        private static Opt action(String label, String value, Runnable run) { return new Opt(label, value, null, run); }

        private static Opt info(String label, String value) {
            Opt o = new Opt(label, value, null, null);
            o.dim = true;
            return o;
        }

        private static String nextOf(String[] all, String current, int dir) {
            int at = java.util.Arrays.asList(all).indexOf(current);
            return all[((at < 0 ? 0 : at) + dir + all.length) % all.length];
        }

        private Opt focusedOpt() {
            List<Opt> list = opts();
            int at = pickScroll + focus;
            return at >= 0 && at < list.size() ? list.get(at) : null;
        }

        /** A setting changed: the home screen and the weather pick it up (and these screens redraw with it). */
        private void changed() {
            HomeData.get(getContext()).settingsChanged();
            invalidate();
        }

        /** The text size or the animations changed: fonts and springs again, the paints at the new size. */
        private void restyle() {
            Ui.init(getContext());
            if (w > 0) makePaints();
        }

        private void backFromPicker() {
            screen = pickBack;
            focus = pickBackFocus;
            pickScroll = pickBackScroll;
            if (screen != OPTIONS && screen != HOME_SETTINGS) screen = HOME_SETTINGS;
        }

        private static String pageTitle(int p) {
            switch (p) {
                case P_LOOK: return Ui.tr("Appearance");
                case P_STATUS: return Ui.tr("Status Bar & Weather");
                case P_SHELF: return Ui.tr("Top Shelf");
                case P_SAVER: return Ui.tr("StandBy & Screensaver");
                case P_SOUND: return Ui.tr("Sound");
                case P_NETWORK: return Ui.tr("Network");
                case P_DNS: return Ui.tr("Private DNS");
                case P_BACKUP: return Ui.tr("Backup & Restore");
                case P_DEBLOAT: return Ui.tr("TV Debloat");
                case P_STORAGE: return Ui.tr("Storage Cleaner");
                case P_ABOUT: return Ui.tr("About");
                case P_SETUP: return Ui.tr("Welcome to X-OS");
                default: return Ui.tr("More Settings");
            }
        }

        private void openPage(int p) {
            page = p;
            focus = 0;
            pickScroll = 0;
            pageStatus = "";
            readFacts();
            if (p == P_DEBLOAT) loadDebloat();
        }

        /** IP, network, DNS, storage and memory: read once per page (they don't change while you look). */
        private void readFacts() {
            Context c = getContext();
            ip = ipAddress();
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                netType = ni == null || !ni.isConnected() ? Ui.tr("Not connected")
                        : ni.getType() == android.net.ConnectivityManager.TYPE_WIFI ? "Wi-Fi"
                        : ni.getType() == android.net.ConnectivityManager.TYPE_ETHERNET ? "Ethernet" : ni.getTypeName();
            } catch (RuntimeException e) {
                netType = "—";
            }
            try {
                String mode = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
                String host = Settings.Global.getString(c.getContentResolver(), "private_dns_specifier");
                dnsMode = mode == null ? "" : mode;
                dnsHost = host == null ? "" : host;
            } catch (RuntimeException e) {
                dnsMode = dnsHost = "";
            }
            try {
                android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                storageText = Ui.trf("{free} free of {total}", "free", android.text.format.Formatter.formatShortFileSize(c, fs.getAvailableBytes()),
                        "total", android.text.format.Formatter.formatShortFileSize(c, fs.getTotalBytes()));
            } catch (RuntimeException e) {
                storageText = "—";
            }
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                memoryText = Ui.trf("{free} free of {total}", "free", android.text.format.Formatter.formatShortFileSize(c, mi.availMem),
                        "total", android.text.format.Formatter.formatShortFileSize(c, mi.totalMem));
            } catch (RuntimeException e) {
                memoryText = "—";
            }
        }

        private static String ipAddress() {
            try {
                for (java.net.NetworkInterface ni : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (java.net.InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                    }
                }
            } catch (Exception ignored) {
            }
            return "—";
        }

        private String dnsName() {
            if (dnsMode.equals("hostname")) {
                for (String[] d : DNS) if (d[1].equals(dnsHost)) return d[0];
                return dnsHost;
            }
            return dnsMode.equals("off") ? Ui.tr("Off") : Ui.tr("Automatic");
        }

        private static String soundName(String v) {
            return v.equals("tv") ? Ui.tr("The TV's sounds") : v.equals("off") ? Ui.tr("Off") : Ui.tr("X-OS clicks");
        }

        private static String standbyName(String v) {
            switch (v) {
                case "digital": return Ui.tr("Digital");
                case "world": return Ui.tr("World clock");
                case "float": return Ui.tr("Floating");
                case "date": return Ui.tr("Date and weather");
                default: return Ui.tr("Analog and calendar");
            }
        }

        private static String wallpaperName(String v) {
            switch (v) {
                case "sunset": return Ui.tr("Sunset");
                case "ocean": return Ui.tr("Ocean");
                case "graphite": return Ui.tr("Graphite");
                case "emoji": return Ui.tr("Emoji");
                default: return Ui.tr("Aurora");
            }
        }

        private static String percent(float v) {
            return Math.round(v * 100) + "%";
        }

        /** Runs a slow job (ADB, files) off the screen's thread; its answer shows under the list. */
        private void work(String doing, java.util.concurrent.Callable<String> job, Runnable after) {
            if (optBusy) return;
            optBusy = true;
            pageStatus = doing;
            invalidate();
            worker.execute(() -> {
                String result;
                try {
                    result = job.call();
                } catch (Exception e) {
                    result = Ui.trf("Couldn't do it: {error}", "error", e.getMessage() == null ? e.toString() : e.getMessage());
                }
                String shown = result == null ? "" : result;
                handler.post(() -> {
                    optBusy = false;
                    pageStatus = shown;
                    readFacts();
                    if (after != null) after.run();
                    invalidate();
                    spoken.focusMoved();
                });
            });
        }

        private void openSystem(String action) {
            try {
                activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }

        /** The rows of the page on screen (made again on each call: values change as you step them). */
        private List<Opt> opts() {
            List<Opt> l = new ArrayList<>();
            switch (page) {
                case P_HUB: {
                    String theme = Prefs.theme();
                    l.add(link(pageTitle(P_LOOK), theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark")
                            : Ui.tr("Automatic"), () -> openPage(P_LOOK)));
                    l.add(link(pageTitle(P_STATUS), Prefs.clockHours().equals("auto") ? "" : Ui.trf("{n}-hour", "n", Prefs.clockHours()),
                            () -> openPage(P_STATUS)));
                    l.add(link(pageTitle(P_SHELF), shelfName(Prefs.topShelf()), () -> openPage(P_SHELF)));
                    l.add(link(pageTitle(P_SAVER), saverName(Prefs.screensaver()), () -> openPage(P_SAVER)));
                    l.add(link(pageTitle(P_SOUND), soundName(Prefs.soundMode()), () -> openPage(P_SOUND)));
                    l.add(link(pageTitle(P_NETWORK), netType, () -> openPage(P_NETWORK)));
                    l.add(link(pageTitle(P_DNS), dnsName(), () -> openPage(P_DNS)));
                    l.add(link(pageTitle(P_BACKUP), "", () -> openPage(P_BACKUP)));
                    l.add(link(pageTitle(P_DEBLOAT), "", () -> openPage(P_DEBLOAT)));
                    l.add(link(pageTitle(P_STORAGE), storageText, () -> openPage(P_STORAGE)));
                    l.add(link(pageTitle(P_ABOUT), versionName(getContext()), () -> openPage(P_ABOUT)));
                    break;
                }
                case P_LOOK: {
                    String theme = Prefs.theme();
                    l.add(cycle(Ui.tr("Theme"), theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark")
                            : Ui.tr("Automatic"), d -> Prefs.setTheme(nextOf(THEMES, Prefs.theme(), d))));
                    l.add(cycle(Ui.tr("Glass"), Prefs.glass().equals("frosted") ? Ui.tr("Frosted") : Ui.tr("Liquid Glass"), d -> {
                        Prefs.setGlass(nextOf(GLASSES, Prefs.glass(), d));
                        scene.frosted = Prefs.glass().equals("frosted");
                    }));
                    Opt font = new Opt(Ui.tr("Font"), Fonts.name(Prefs.font()), d -> {
                        int at = java.util.Arrays.asList(Fonts.KEYS).indexOf(Prefs.font());
                        setFont(Fonts.KEYS[(at + d + Fonts.KEYS.length) % Fonts.KEYS.length]);
                    }, () -> openPicker(PICK_FONT));
                    font.more = true;
                    l.add(font);
                    l.add(cycle(Ui.tr("Text size"), percent(Prefs.textSize()), d -> {
                        int at = 1;
                        for (int i = 0; i < TEXT_SIZES.length; i++) if (Math.abs(TEXT_SIZES[i] - Prefs.textSize()) < 0.01f) at = i;
                        Prefs.setTextSize(TEXT_SIZES[(at + d + TEXT_SIZES.length) % TEXT_SIZES.length]);
                        restyle();
                    }));
                    String anim = Prefs.animations();
                    l.add(cycle(Ui.tr("Animations"), anim.equals("fast") ? Ui.tr("Fast") : anim.equals("slow") ? Ui.tr("Slow")
                            : anim.equals("reduced") ? Ui.tr("Reduced") : Ui.tr("Normal"), d -> {
                        Prefs.setAnimations(nextOf(ANIMATIONS, Prefs.animations(), d));
                        restyle();
                    }));
                    l.add(cycle(Ui.tr("Folders open"), Prefs.folderStyle().equals("full") ? Ui.tr("Full screen") : Ui.tr("In a window"),
                            d -> Prefs.setFolderStyle(nextOf(FOLDER_STYLES, Prefs.folderStyle(), d))));
                    l.add(cycle(Ui.tr("Dynamic Island"), onOff(Prefs.island()), d -> Prefs.setIsland(!Prefs.island())));
                    break;
                }
                case P_STATUS: {
                    String hours = Prefs.clockHours();
                    l.add(cycle(Ui.tr("Clock"), hours.equals("12") ? Ui.tr("12-hour") : hours.equals("24") ? Ui.tr("24-hour")
                            : Ui.tr("Automatic"), d -> Prefs.setClockHours(nextOf(CLOCKS, Prefs.clockHours(), d))));
                    l.add(cycle(Ui.tr("Date next to the clock"), onOff(Prefs.clockDate()), d -> Prefs.setClockDate(!Prefs.clockDate())));
                    String unit = Prefs.temperature();
                    l.add(cycle(Ui.tr("Temperature"), unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic"),
                            d -> Prefs.setTemperature(nextOf(UNITS, Prefs.temperature(), d))));
                    l.add(cycle(Ui.tr("Weather widget"), onOff(Prefs.weather()), d -> Prefs.setWeather(!Prefs.weather())));
                    Opt city = action(Ui.tr("City"), !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city(),
                            this::askCity);
                    city.more = true;
                    l.add(city);
                    l.add(cycle(Ui.tr("Markets widget"), onOff(Prefs.quotes()), d -> Prefs.setQuotes(!Prefs.quotes())));
                    break;
                }
                case P_SHELF: {
                    l.add(cycle(Ui.tr("Shows"), shelfName(Prefs.topShelf()), d -> Prefs.setTopShelf(nextOf(SHELVES, Prefs.topShelf(), d))));
                    Opt edition = cycle(Ui.tr("News"), News.editionName(Prefs.news()), d -> Prefs.setNews(nextOf(News.EDITIONS, Prefs.news(), d)));
                    edition.dim = !Prefs.topShelf().equals("news");
                    l.add(edition);
                    boolean wall = Prefs.topShelf().equals("wallpaper") || Prefs.topShelf().equals("news");
                    Opt paper = cycle(Ui.tr("Wallpaper"), wallpaperName(Prefs.wallpaper()),
                            d -> Prefs.setWallpaper(nextOf(Prefs.WALLPAPERS, Prefs.wallpaper(), d)));
                    paper.dim = !wall;
                    l.add(paper);
                    Opt emo = cycle(Ui.tr("Emoji"), Prefs.wallpaperEmoji(), d -> Prefs.setWallpaperEmoji(nextOf(EMOJIS, Prefs.wallpaperEmoji(), d)));
                    emo.dim = !wall || !Prefs.wallpaper().equals("emoji");
                    l.add(emo);
                    l.add(link(Ui.tr("Shelf apps"), Ui.plural("plural|one:{n} app|other:{n} apps", HomeApps.shelfPackages(getContext()).size()),
                            () -> {
                                openShelfEditor(false);
                                shelfFromOptions = true;
                            }));
                    break;
                }
                case P_SAVER: {
                    l.add(cycle(Ui.tr("Style"), saverName(Prefs.screensaver()), d -> Prefs.setScreensaver(nextOf(SAVERS, Prefs.screensaver(), d))));
                    Opt clock = cycle(Ui.tr("StandBy clock"), standbyName(Prefs.standbyClock()),
                            d -> Prefs.setStandbyClock(nextOf(STANDBY_CLOCKS, Prefs.standbyClock(), d)));
                    clock.dim = !Prefs.screensaver().equals("standby");
                    l.add(clock);
                    l.add(action(Ui.tr("Preview"), "", () -> {
                        pause();
                        activity.showPreview();
                    }));
                    Opt photos = cycle(Ui.tr("Photos"), photoName(Prefs.photoSource()),
                            d -> Prefs.setPhotoSource(nextOf(PHOTO_SOURCES, Prefs.photoSource(), d)));
                    photos.dim = Prefs.screensaver().equals("standby") || Prefs.screensaver().equals("picks");
                    l.add(photos);
                    break;
                }
                case P_SOUND:
                    l.add(cycle(Ui.tr("Interface sounds"), soundName(Prefs.soundMode()), d -> Prefs.setSoundMode(nextOf(SOUNDS, Prefs.soundMode(), d))));
                    break;
                case P_NETWORK:
                    l.add(link("Wi-Fi", netType, () -> openSystem(Settings.ACTION_WIFI_SETTINGS)));
                    l.add(link("Bluetooth", "", () -> openSystem(Settings.ACTION_BLUETOOTH_SETTINGS)));
                    l.add(info(Ui.tr("IP address"), ip));
                    l.add(info(Ui.tr("From a computer"), "adb connect " + ip + ":5555"));
                    break;
                case P_DNS: {
                    if (android.os.Build.VERSION.SDK_INT < 28) {
                        l.add(info(Ui.tr("Private DNS"), Ui.tr("Needs Android 9 or newer")));
                        break;
                    }
                    String current = dnsMode.equals("hostname") ? dnsHost : "";
                    for (String[] d : DNS) {
                        String name = d[1].isEmpty() ? Ui.tr("Automatic") : d[0];
                        String what = d[1].isEmpty() ? Ui.tr("Android's own") : d[0].equals("AdGuard") ? Ui.tr("Blocks ads") : d[1];
                        Opt o = action(name, what, () -> setDns(d[0], d[1]));
                        o.check = d[1].equals(current) && !dnsMode.equals("off");
                        l.add(o);
                    }
                    break;
                }
                case P_BACKUP: {
                    java.io.File own = ownBackup();
                    String last = own != null && own.isFile() ? Ui.shortDate(own.lastModified()) + "  " + Ui.clock(own.lastModified()) : Ui.tr("Never");
                    l.add(action(Ui.tr("Back up now"), last, this::backup));
                    l.add(action(Ui.tr("Restore"), "", this::restore));
                    l.add(info(Ui.tr("Saved in"), BACKUP_SHARED.replace("/sdcard/", "")));
                    break;
                }
                case P_DEBLOAT: {
                    if (debloatLoading) {
                        l.add(info(Ui.tr("Reading the TV's apps…"), ""));
                        break;
                    }
                    for (String[] app : debloat) {
                        String state = app[1];
                        Opt o = action(appName(app[0]), state.equals("busy") ? "…" : state.equals("off") ? Ui.tr("Off") : Ui.tr("On"),
                                () -> toggleDebloat(app));
                        l.add(o);
                    }
                    if (debloat.isEmpty()) l.add(info(Ui.tr("None of the promo apps X-OS knows is on this TV."), ""));
                    break;
                }
                case P_STORAGE:
                    l.add(info(Ui.tr("Storage"), storageText));
                    l.add(info(Ui.tr("Memory"), memoryText));
                    l.add(action(Ui.tr("Clear the apps' caches"), "", this::clearCaches));
                    l.add(action(Ui.tr("Close background apps"), "", this::closeApps));
                    break;
                case P_ABOUT: {
                    l.add(info("X-OS", versionName(getContext()).replace("X-OS ", "")));
                    l.add(info(Ui.tr("TV"), android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL));
                    l.add(info("Android", android.os.Build.VERSION.RELEASE));
                    l.add(info(Ui.tr("Home screen"), freeActive() ? "X-OS" : Ui.tr("Original")));
                    l.add(info(Ui.tr("IP address"), ip));
                    l.add(info(Ui.tr("Storage"), storageText));
                    l.add(info(Ui.tr("Memory"), memoryText));
                    l.add(action(Ui.tr("Set up again"), "", () -> openPage(P_SETUP)));
                    break;
                }
                case P_SETUP: {
                    String name = Prefs.profileName();
                    Opt you = action(Ui.tr("Your name"), name.isEmpty() ? Ui.tr("Add your name") : name, this::askName);
                    you.more = true;
                    l.add(you);
                    l.add(cycle(Ui.tr("Avatar"), Prefs.avatar(), d -> Prefs.setAvatar(nextOf(Prefs.AVATARS, Prefs.avatar(), d))));
                    String lang = Prefs.language();
                    l.add(link(Ui.tr("Language"), lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang),
                            () -> openPicker(PICK_LANGUAGE)));
                    Opt city = action(Ui.tr("City"), !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city(),
                            this::askCity);
                    city.more = true;
                    l.add(city);
                    String unit = Prefs.temperature();
                    l.add(cycle(Ui.tr("Temperature"), unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic"),
                            d -> Prefs.setTemperature(nextOf(UNITS, Prefs.temperature(), d))));
                    l.add(cycle(Ui.tr("Clock"), Prefs.clockHours().equals("12") ? Ui.tr("12-hour") : Prefs.clockHours().equals("24")
                            ? Ui.tr("24-hour") : Ui.tr("Automatic"), d -> Prefs.setClockHours(nextOf(CLOCKS, Prefs.clockHours(), d))));
                    l.add(cycle(Ui.tr("Top Shelf"), shelfName(Prefs.topShelf()), d -> Prefs.setTopShelf(nextOf(SHELVES, Prefs.topShelf(), d))));
                    l.add(cycle(Ui.tr("Screensaver"), saverName(Prefs.screensaver()), d -> Prefs.setScreensaver(nextOf(SAVERS, Prefs.screensaver(), d))));
                    l.add(action(Ui.tr("Done"), Ui.tr("then Home Screen › X-OS"), this::finishSetup));
                    break;
                }
                default:
                    break;
            }
            return l;
        }

        private static String photoName(String v) {
            return v.equals("nasa") ? Ui.tr("NASA picture of the day") : v.equals("wikimedia") ? Ui.tr("Wikipedia picture of the day")
                    : Ui.tr("Bing photo of the day");
        }

        /** The first-run pages are done (or skipped with Back): to the Home Screen row, where X-OS is switched on. */
        private void finishSetup() {
            Prefs.setSetupDone();
            screen = MAIN;
            focus = 0;
            pickScroll = 0;
            page = P_HUB;
            changed();
        }

        private void askName() {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setText(Prefs.profileName());
            field.setHint(Ui.tr("Your name"));
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Your name"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("OK"), (d, which) -> { Prefs.setProfileName(field.getText().toString()); invalidate(); })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                Prefs.setProfileName(field.getText().toString());
                invalidate();
                return true;
            });
            dialog.show();
        }

        /** The line under a page: what it does, and the safe way back where it matters. */
        private String pageHint() {
            switch (page) {
                case P_HUB: return Ui.tr("Everything X-OS can change, page by page.");
                case P_DNS: return Ui.tr("Changes the TV's DNS through its own debugging. Automatic puts Android's back.");
                case P_BACKUP: return Ui.tr("Your settings, shelf, folders and remote buttons. The file stays after an uninstall.");
                case P_DEBLOAT: return Ui.tr("OK turns one off or on. Turned off, never uninstalled; Original turns them all back on.");
                case P_STORAGE: return Ui.tr("Clearing caches never removes your apps, sign-ins or settings.");
                case P_NETWORK: return Ui.tr("Wi-Fi and Bluetooth open the TV's own settings.");
                case P_ABOUT: return Ui.tr("Your TV and X-OS at a glance.");
                case P_SETUP: return Ui.tr("A few choices to start; everything is in More Settings later. Back skips.");
                default: return Ui.tr("OK or the arrows change the value.");
            }
        }

        private void drawOptions(Canvas c) {
            List<Opt> list = opts();
            Paint heading = Ui.text(Ui.displaySemibold, 46 * k, 0xFFFFFFFF);
            c.drawText(pageTitle(page), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = Math.min(OPT_VISIBLE, list.size());
            for (int i = 0; i < rows; i++) {
                Opt o = list.get(pickScroll + i);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(o.dim ? 0x80FFFFFF : 0xF2FFFFFF, o.dim ? 0x991C1C1E : INK, f));
                rowValue.setColor(blend(o.dim ? 0x66FFFFFF : 0xB3FFFFFF, o.dim ? 0x801C1C1E : 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                String label = TextUtils.ellipsize(o.label, new TextPaint(rowLabel), 560 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(label, rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (o.more) {
                    float cw = Symbols.width(getContext(), "chevron_right", 20 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 20 * k, blend(0x59FFFFFF, 0x991C1C1E, f));
                    right -= cw + 18 * k;
                }
                if (o.check) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", right - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                    right -= cw + 24 * k;
                }
                if (!o.value.isEmpty()) {
                    String value = TextUtils.ellipsize(o.value, new TextPaint(rowValue), right - rowRect.left - 620 * k,
                            TextUtils.TruncateAt.END).toString();
                    c.drawText(value, right, base, rowValue);
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            String more = list.size() > rows ? (pickScroll + focus + 1) + " / " + list.size() + "  ·  " : "";
            c.drawText(TextUtils.ellipsize(more + pageHint(), notice, 1040 * k, TextUtils.TruncateAt.END).toString(),
                    LIST_X * k + 30 * k, hy, hint);
            if (!pageStatus.isEmpty()) {
                Paint p = optBusy ? hint : warn;
                c.drawText(TextUtils.ellipsize(pageStatus, notice, 1040 * k, TextUtils.TruncateAt.END).toString(),
                        LIST_X * k + 30 * k, hy + 40 * k, p);
            }
        }

        // MARK: - More Settings' jobs

        private void setDns(String name, String host) {
            work(Ui.tr("Changing the DNS…"), () -> {
                try (Adb adb = Adb.connect(getContext(), 30_000, null)) {
                    if (host.isEmpty()) {
                        adb.shell("settings put global private_dns_mode opportunistic");
                    } else {
                        adb.shell("settings put global private_dns_specifier " + host);
                        adb.shell("settings put global private_dns_mode hostname");
                    }
                }
                return host.isEmpty() ? Ui.tr("Private DNS: Android's own again.") : Ui.trf("Private DNS: {name}.", "name", name);
            }, null);
        }

        private java.io.File ownBackup() {
            java.io.File dir = getContext().getExternalFilesDir(null);
            return dir == null ? null : new java.io.File(dir, BACKUP_NAME);
        }

        /** The settings as JSON: in the app's folder, then copied by the TV's shell to /sdcard/X-OS (kept after an uninstall). */
        private void backup() {
            work(Ui.tr("Backing up…"), () -> {
                java.io.File own = ownBackup();
                if (own == null) throw new java.io.IOException(Ui.tr("the TV's storage isn't ready"));
                byte[] data = Prefs.export().toString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(own)) {
                    out.write(data);
                }
                try (Adb adb = Adb.connect(getContext(), 30_000, null)) {
                    adb.shell("mkdir -p /sdcard/X-OS && cp '" + own.getAbsolutePath() + "' " + BACKUP_SHARED);
                    if (adb.shell("ls " + BACKUP_SHARED).contains(BACKUP_NAME)) return Ui.tr("Backed up to the X-OS folder.");
                } catch (java.io.IOException e) {
                    AppLog.w("backup copy: " + e.getMessage());
                }
                return Ui.tr("Backed up in the app's own folder (an uninstall removes it).");
            }, null);
        }

        private void restore() {
            work(Ui.tr("Restoring…"), () -> {
                String text = null;
                try (Adb adb = Adb.connect(getContext(), 30_000, null)) {
                    String out = adb.shell("cat " + BACKUP_SHARED);
                    if (out.trim().startsWith("{")) text = out;
                } catch (java.io.IOException e) {
                    AppLog.w("restore: " + e.getMessage());
                }
                java.io.File own = ownBackup();
                if (text == null && own != null && own.isFile()) {
                    byte[] data = new byte[(int) Math.min(own.length(), 1 << 20)];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(own)) {
                        int n = 0, r;
                        while (n < data.length && (r = in.read(data, n, data.length - n)) > 0) n += r;
                        text = new String(data, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                if (text == null) return Ui.tr("No backup on this TV yet.");
                int n = Prefs.restore(new JSONObject(text));
                return Ui.plural("plural|one:Restored {n} setting.|other:Restored {n} settings.", n);
            }, () -> {
                restyle();
                scene.frosted = Prefs.glass().equals("frosted");
                changed();
            });
        }

        private String appName(String pkg) {
            String name = appNames.get(pkg);
            if (name != null) return name;
            try {
                PackageManager pm = getContext().getPackageManager();
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString();
            } catch (Exception e) {
                name = pkg;
            }
            if (name.equals(pkg) || name.isEmpty()) name = pkg;
            appNames.put(pkg, name);
            return name;
        }

        private void loadDebloat() {
            debloatLoading = true;
            debloat.clear();
            worker.execute(() -> {
                List<String[]> found;
                String error = "";
                try {
                    found = HomeMode.debloatList(getContext());
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't read the apps: {error}", "error", e.getMessage());
                }
                for (String[] app : found) appName(app[0]);
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    debloatLoading = false;
                    debloat.clear();
                    debloat.addAll(result);
                    if (!message.isEmpty()) pageStatus = message;
                    invalidate();
                    spoken.focusMoved();
                });
            });
        }

        private void toggleDebloat(String[] app) {
            if (optBusy || app[1].equals("busy")) return;
            boolean turnOff = app[1].equals("on");
            String pkg = app[0], was = app[1];
            app[1] = "busy";
            work(turnOff ? Ui.tr("Turning it off…") : Ui.tr("Turning it on…"), () -> {
                String error = HomeMode.setAppOff(getContext(), pkg, turnOff);
                app[1] = error.isEmpty() ? (turnOff ? "off" : "on") : was;
                return error.isEmpty() ? (turnOff ? Ui.trf("{app} is off.", "app", appName(pkg))
                        : Ui.trf("{app} is on.", "app", appName(pkg))) : error;
            }, null);
        }

        private void clearCaches() {
            final long before = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath()).getAvailableBytes();
            work(Ui.tr("Clearing caches…"), () -> {
                try (Adb adb = Adb.connect(getContext(), 30_000, null)) {
                    adb.shell("pm trim-caches 999G");
                }
                long freed = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath()).getAvailableBytes() - before;
                return Ui.trf("Done: {size} freed.", "size", android.text.format.Formatter.formatShortFileSize(getContext(), Math.max(0, freed)));
            }, null);
        }

        private void closeApps() {
            work(Ui.tr("Closing background apps…"), () -> {
                try (Adb adb = Adb.connect(getContext(), 30_000, null)) {
                    adb.shell("am kill-all");
                }
                return Ui.tr("Done: background apps closed.");
            }, null);
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == OPTIONS;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == OPTIONS ? opts().size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            shelfFromOptions = false;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTop) Prefs.setTopButtons(shelfOrder); else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);   // three buttons: no limit to reach
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 46 * k, 0xFFFFFFFF);
            c.drawText(editingTop ? Ui.tr("Top Buttons") : Ui.tr("Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.MAX_SHELF),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            if (which <= 0 && screen != PICKER) {
                pickBack = screen;
                pickBackFocus = focus;
                pickBackScroll = screen == OPTIONS ? pickScroll : 0;
            }
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_FONT) {
                Context c = getContext();
                fontFaces = new Typeface[Fonts.KEYS.length];
                fontMissing = new boolean[Fonts.KEYS.length];
                for (int i = 0; i < Fonts.KEYS.length; i++) {
                    pickLabels.add(Fonts.name(Fonts.KEYS[i]));
                    fontFaces[i] = Fonts.preview(c, Fonts.KEYS[i]);
                    fontMissing[i] = Fonts.KEYS[i].startsWith("sf") && !Fonts.hasApple(c, Fonts.KEYS[i].equals("sfrounded"));
                }
                pickCurrent = java.util.Arrays.asList(Fonts.KEYS).indexOf(Prefs.font());
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 46 * k, 0xFFFFFFFF);
            String title = pickFor == PICK_CITY ? Ui.tr("City") : pickFor == PICK_FONT ? Ui.tr("Font")
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language") : keyLabels()[pickFor - 1];
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean font = pickFor == PICK_FONT && fontFaces != null && at < fontFaces.length;
                if (font) rowLabel.setTypeface(fontFaces[at]);
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (font) rowLabel.setTypeface(Ui.medium);
                float right = rowRect.right - 30 * k;
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", right - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                    right -= cw + 24 * k;
                }
                if (font && fontMissing[at]) {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                    c.drawText(Ui.tr("Add Apple's files"), right, rowRect.centerY() + Ui.halfCap(rowLabel), rowValue);
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
            if (pickFor == PICK_FONT) {
                c.drawText(Ui.tr("SF Pro and SF Rounded are Apple's fonts: put their files in the “fonts” folder next to the"),
                        LIST_X * k + 30 * k, hy + 38 * k, hint);
                c.drawText(Ui.tr("installer and run it again. Until then they show as Inter and Google Sans."),
                        LIST_X * k + 30 * k, hy + 72 * k, hint);
            }
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = keys ? keyLabels() : settingLabels();
            String[] values = keys ? keyValues() : settingValues();
            Paint heading = Ui.text(Ui.displaySemibold, 46 * k, 0xFFFFFFFF);
            c.drawText(keys ? Ui.tr("Remote Buttons") : Ui.tr("Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = !keys && i >= COIN_ROWS && !Prefs.quotes();
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            if (hy < h - 44 * k) c.drawText(Ui.tr("OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            if (keys) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                backFromPicker();
            } else if (screen == PICKER && pickFor == PICK_FONT) {
                setFont(Fonts.KEYS[pickScroll + focus]);
                backFromPicker();
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                backFromPicker();
            } else if (screen == OPTIONS) {
                Opt o = focusedOpt();
                if (o == null) return;
                int before = page;
                if (o.open != null) o.open.run();
                else if (o.step != null) {
                    o.step.step(1);
                    changed();
                }
                if (screen == OPTIONS && before == page) {
                    invalidate();
                    spoken.focusMoved();
                    return;
                }
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && focus == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && focus == FONT_ROW) {
                openPicker(PICK_FONT);
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == 3) {
                openShelfEditor(false);
            } else if (focus == 4) {
                openShelfEditor(true);
            } else if (focus == 5) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else if (focus == MORE_ROW) {
                screen = OPTIONS;
                openPage(P_HUB);
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
