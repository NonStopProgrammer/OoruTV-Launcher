package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Line icons drawn with paths, for what assets/symbols lacks (Wi-Fi, Bluetooth, Control Center, StandBy, appearance).
 * `size` is the icon's box; drawn on the UI thread only, so the shared path and paint allocate nothing per frame.
 */
final class Icons {
    private Icons() {}

    private static final Path PATH = new Path();
    private static final RectF OVAL = new RectF();
    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SOLID = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeCap(Paint.Cap.ROUND);
        LINE.setStrokeJoin(Paint.Join.ROUND);
    }

    /** True for the keys drawn here (the rest come from Symbols). */
    static boolean has(String name) {
        return name != null && (name.equals("wifi") || name.equals("bluetooth") || name.equals("controls")
                || name.equals("standby") || name.equals("appearance") || name.equals("style") || name.equals("offline")
                || name.equals("glass") || name.equals("folder") || name.equals("folder_add") || name.equals("out")
                || name.equals("shelf_add") || name.equals("shelf_remove") || name.equals("pencil") || name.equals("trash")
                || name.equals("recents"));
    }

    static void draw(Canvas c, String name, float cx, float cy, float size, int color) {
        LINE.setColor(color);
        SOLID.setColor(color);
        LINE.setStrokeWidth(Math.max(1.5f, size * 0.09f));
        switch (name) {
            case "wifi": wifi(c, cx, cy, size, false); break;
            case "offline": wifi(c, cx, cy, size, true); break;
            case "bluetooth": bluetooth(c, cx, cy, size); break;
            case "controls": controls(c, cx, cy, size); break;
            case "standby": standby(c, cx, cy, size); break;
            case "appearance": appearance(c, cx, cy, size); break;
            case "style": style(c, cx, cy, size); break;
            case "glass": drop(c, cx, cy, size); break;
            case "folder": folder(c, cx, cy, size, 0); break;
            case "folder_add": folder(c, cx, cy, size, 1); break;
            case "out": out(c, cx, cy, size); break;
            case "shelf_add": shelf(c, cx, cy, size, true); break;
            case "shelf_remove": shelf(c, cx, cy, size, false); break;
            case "pencil": pencil(c, cx, cy, size); break;
            case "trash": trash(c, cx, cy, size); break;
            case "recents": recents(c, cx, cy, size); break;
            default: break;
        }
    }

    /** Three arcs over a dot (slashed when offline). */
    private static void wifi(Canvas c, float cx, float cy, float s, boolean off) {
        float base = cy + s * 0.32f;
        for (int i = 1; i <= 3; i++) {
            float r = s * 0.2f * i;
            OVAL.set(cx - r, base - r, cx + r, base + r);
            c.drawArc(OVAL, 225, 90, false, LINE);
        }
        c.drawCircle(cx, base - s * 0.02f, s * 0.07f, SOLID);
        if (off) c.drawLine(cx - s * 0.4f, cy - s * 0.4f, cx + s * 0.4f, cy + s * 0.4f, LINE);
    }

    /** The Bluetooth rune. */
    private static void bluetooth(Canvas c, float cx, float cy, float s) {
        float x0 = cx - s * 0.06f, top = cy - s * 0.4f, bottom = cy + s * 0.4f, w = s * 0.22f;
        PATH.reset();
        PATH.moveTo(cx - w, cy - s * 0.2f);
        PATH.lineTo(x0 + w, cy + s * 0.2f);
        PATH.lineTo(x0, bottom);
        PATH.lineTo(x0, top);
        PATH.lineTo(x0 + w, cy - s * 0.2f);
        PATH.lineTo(cx - w, cy + s * 0.2f);
        c.drawPath(PATH, LINE);
    }

    /** Two toggles (Control Center). */
    private static void controls(Canvas c, float cx, float cy, float s) {
        float w = s * 0.78f, h = s * 0.3f, r = h / 2;
        for (int i = 0; i < 2; i++) {
            float y = cy + (i == 0 ? -s * 0.2f : s * 0.2f);
            OVAL.set(cx - w / 2, y - h / 2, cx + w / 2, y + h / 2);
            c.drawRoundRect(OVAL, r, r, LINE);
            float knob = i == 0 ? cx + w / 2 - r : cx - w / 2 + r;
            c.drawCircle(knob, y, r * 0.55f, SOLID);
        }
    }

    /** A crescent moon with a small star (StandBy, the clock screensaver). */
    private static void standby(Canvas c, float cx, float cy, float s) {
        moon(c, cx - s * 0.04f, cy + s * 0.04f, s * 0.36f);
        c.drawCircle(cx + s * 0.3f, cy - s * 0.28f, s * 0.05f, SOLID);
    }

    /** Half-filled circle (light / dark appearance). */
    private static void appearance(Canvas c, float cx, float cy, float s) {
        float r = s * 0.36f;
        c.drawCircle(cx, cy, r, LINE);
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, 90, 180, true, SOLID);
    }

    /** A screen with a row of tiles (home screen style). */
    private static void style(Canvas c, float cx, float cy, float s) {
        float w = s * 0.82f, h = s * 0.58f;
        OVAL.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        c.drawRoundRect(OVAL, s * 0.08f, s * 0.08f, LINE);
        float tw = w * 0.2f, th = h * 0.22f, y = cy + h * 0.12f;
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * tw * 1.3f;
            OVAL.set(x - tw / 2, y - th / 2, x + tw / 2, y + th / 2);
            c.drawRoundRect(OVAL, th * 0.3f, th * 0.3f, SOLID);
        }
    }

    /** A drop (Glass Style). */
    private static void drop(Canvas c, float cx, float cy, float s) {
        float r = s * 0.24f, by = cy + s * 0.14f;
        PATH.reset();
        PATH.moveTo(cx, cy - s * 0.4f);
        PATH.cubicTo(cx + r * 0.5f, cy - s * 0.18f, cx + r * 1.25f, by - r * 0.6f, cx + r, by);
        OVAL.set(cx - r, by - r, cx + r, by + r);
        PATH.arcTo(OVAL, 0, 180, false);
        PATH.cubicTo(cx - r * 1.25f, by - r * 0.6f, cx - r * 0.5f, cy - s * 0.18f, cx, cy - s * 0.4f);
        PATH.close();
        c.drawPath(PATH, LINE);
        c.drawCircle(cx - r * 0.35f, by + r * 0.1f, s * 0.05f, SOLID);
    }

    /** A folder (with a plus inside: a new folder). */
    private static void folder(Canvas c, float cx, float cy, float s, int mark) {
        float l = cx - s * 0.4f, r = cx + s * 0.4f, t = cy - s * 0.3f, b = cy + s * 0.32f;
        PATH.reset();
        PATH.moveTo(l, b);
        PATH.lineTo(l, t);
        PATH.lineTo(cx - s * 0.1f, t);
        PATH.lineTo(cx, t + s * 0.1f);
        PATH.lineTo(r, t + s * 0.1f);
        PATH.lineTo(r, b);
        PATH.close();
        c.drawPath(PATH, LINE);
        if (mark == 1) {
            float my = cy + s * 0.08f, a = s * 0.13f;
            c.drawLine(cx - a, my, cx + a, my, LINE);
            c.drawLine(cx, my - a, cx, my + a, LINE);
        }
    }

    /** An arrow rising out of a tray (out of the folder). */
    private static void out(Canvas c, float cx, float cy, float s) {
        PATH.reset();
        PATH.moveTo(cx - s * 0.36f, cy);
        PATH.lineTo(cx - s * 0.36f, cy + s * 0.34f);
        PATH.lineTo(cx + s * 0.36f, cy + s * 0.34f);
        PATH.lineTo(cx + s * 0.36f, cy);
        c.drawPath(PATH, LINE);
        c.drawLine(cx, cy + s * 0.14f, cx, cy - s * 0.38f, LINE);
        c.drawLine(cx - s * 0.16f, cy - s * 0.22f, cx, cy - s * 0.38f, LINE);
        c.drawLine(cx + s * 0.16f, cy - s * 0.22f, cx, cy - s * 0.38f, LINE);
    }

    /** A card with a plus or a minus (on or off the shelf). */
    private static void shelf(Canvas c, float cx, float cy, float s, boolean add) {
        OVAL.set(cx - s * 0.4f, cy - s * 0.26f, cx + s * 0.4f, cy + s * 0.26f);
        c.drawRoundRect(OVAL, s * 0.09f, s * 0.09f, LINE);
        float a = s * 0.14f;
        c.drawLine(cx - a, cy, cx + a, cy, LINE);
        if (add) c.drawLine(cx, cy - a, cx, cy + a, LINE);
    }

    /** A pencil (rename). */
    private static void pencil(Canvas c, float cx, float cy, float s) {
        float d = s * 0.3f, n = s * 0.09f;
        PATH.reset();
        PATH.moveTo(cx - d - n * 0.2f, cy + d + n * 0.2f);
        PATH.lineTo(cx - d + n * 0.1f, cy + d - n * 1.3f);
        PATH.lineTo(cx + d - n * 0.6f, cy - d - n * 0.2f);
        PATH.lineTo(cx + d + n * 0.2f, cy - d + n * 0.6f);
        PATH.lineTo(cx - d + n * 1.3f, cy + d - n * 0.1f);
        PATH.close();
        c.drawPath(PATH, LINE);
    }

    /** A bin (remove the folder). */
    private static void trash(Canvas c, float cx, float cy, float s) {
        float t = cy - s * 0.24f;
        c.drawLine(cx - s * 0.36f, t, cx + s * 0.36f, t, LINE);
        c.drawLine(cx - s * 0.1f, t - s * 0.12f, cx + s * 0.1f, t - s * 0.12f, LINE);
        PATH.reset();
        PATH.moveTo(cx - s * 0.27f, t);
        PATH.lineTo(cx - s * 0.22f, cy + s * 0.38f);
        PATH.lineTo(cx + s * 0.22f, cy + s * 0.38f);
        PATH.lineTo(cx + s * 0.27f, t);
        c.drawPath(PATH, LINE);
    }

    /** Two stacked cards (recent apps). */
    private static void recents(Canvas c, float cx, float cy, float s) {
        OVAL.set(cx - s * 0.3f, cy - s * 0.18f, cx + s * 0.38f, cy + s * 0.3f);
        c.drawRoundRect(OVAL, s * 0.07f, s * 0.07f, LINE);
        PATH.reset();
        PATH.moveTo(cx - s * 0.4f, cy + s * 0.18f);
        PATH.lineTo(cx - s * 0.4f, cy - s * 0.3f);
        PATH.lineTo(cx + s * 0.24f, cy - s * 0.3f);
        c.drawPath(PATH, LINE);
    }

    private static void moon(Canvas c, float cx, float cy, float r) {
        PATH.reset();
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        PATH.addOval(OVAL, Path.Direction.CW);
        Path cut = CUT;
        cut.reset();
        float o = r * 0.55f;
        OVAL.set(cx - r + o, cy - r - o * 0.6f, cx + r + o, cy + r - o * 0.6f);
        cut.addOval(OVAL, Path.Direction.CW);
        PATH.op(cut, Path.Op.DIFFERENCE);
        c.drawPath(PATH, SOLID);
    }

    private static final Path CUT = new Path();
}
