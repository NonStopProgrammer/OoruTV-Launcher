package com.xos.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * The StandBy screensaver (Home Settings › Screensaver style), like an iPhone on its side in StandBy: two big widgets
 * on black, an analog clock with a sweeping orange second hand and this month's calendar with today in red. Other
 * faces (More Settings › StandBy clock): "digital" (big digits), "world" (four cities), "float" (digits that wander
 * the screen, slowly changing color) and "date" (the day with the weather).
 * From 10 pm to 6 am it turns a dim red, as StandBy's night mode does. The widgets drift a few pixels over the
 * minutes, so nothing stays on the same pixels all night. Drawn by DreamView (20 frames a second); nothing here
 * allocates while drawing: the calendar's text is made once a day.
 */
final class StandBy {
    private static final float CARD = 800, GAP = 64, RADIUS = 92;
    private static final int RED = 0xFFFF453A, ORANGE = 0xFFFF9F0A, NIGHT = 0xFFB3261E, NIGHT_DIM = 0xFF6E1712;

    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint numerals, month, weekday, day, today, digits, small, bigDay, label;
    private String style = "analog";
    String forceFace;   // the settings' preview of one face (from ADB: --es face digital)
    private long styleAt;
    private int shownMinute = -1;
    private String timeText = "", dateText = "", weekdayText = "", tempText = "", rangeText = "", placeText = "", icon;
    private final String[] cityTime = new String[4];
    private static final String[] CITIES = {"London", "New York", "Dubai", "Tokyo"};
    private static final String[] ZONES = {"Europe/London", "America/New_York", "Asia/Dubai", "Asia/Tokyo"};
    private final Calendar[] cityCal = new Calendar[4];
    private Context context;
    private HomeData data;
    private String dayLine = "";
    private final float[] hsv = new float[3];
    private final RectF rect = new RectF();
    private final Calendar cal = Calendar.getInstance();
    private float w, h, k;

    // The month on screen: made again when the day changes.
    private int shownDay = -1, firstColumn, daysInMonth, todayDay, firstDayOfWeek;
    private String monthName = "";
    private final String[] dayText = new String[32], letters = new String[7];
    private static final String[] HOURS = {"12", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"};

    StandBy() {
        for (int i = 0; i < 4; i++) cityCal[i] = Calendar.getInstance(TimeZone.getTimeZone(ZONES[i]));
        tick.setStrokeCap(Paint.Cap.ROUND);
        hand.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 1; i <= 31; i++) dayText[i] = Integer.toString(i);
    }

    void setSize(int width, int height) {
        w = width;
        h = height;
        k = w / 1920f;
        numerals = Ui.numbers(Ui.semibold, 58 * k, 0xFFFFFFFF);
        numerals.setTextAlign(Paint.Align.CENTER);
        month = Ui.text(Ui.semibold, 50 * k, RED);
        month.setLetterSpacing(0.02f);
        weekday = Ui.text(Ui.semibold, 34 * k, 0x8CFFFFFF);
        weekday.setTextAlign(Paint.Align.CENTER);
        day = Ui.numbers(Ui.medium, 46 * k, 0xFFFFFFFF);
        day.setTextAlign(Paint.Align.CENTER);
        today = Ui.numbers(Ui.semibold, 46 * k, 0xFFFFFFFF);
        today.setTextAlign(Paint.Align.CENTER);
        digits = Ui.numbers(Ui.semibold, 330 * k, 0xFFFFFFFF);
        digits.setTextAlign(Paint.Align.CENTER);
        small = Ui.numbers(Ui.medium, 44 * k, 0xFFFFFFFF);
        small.setTextAlign(Paint.Align.CENTER);
        bigDay = Ui.numbers(Ui.semibold, 300 * k, 0xFFFFFFFF);
        bigDay.setTextAlign(Paint.Align.CENTER);
        label = Ui.text(Ui.semibold, 40 * k, RED);
        label.setTextAlign(Paint.Align.CENTER);
        shownDay = -1;
        shownMinute = -1;
    }

    /** For the "date" face: the weather (and the symbols need a context). */
    void setData(Context c, HomeData d) {
        context = c;
        data = d;
        shownMinute = -1;
    }

    /** The texts the faces show, made again once a minute (never while drawing a frame in between). */
    private void makeTexts(long now) {
        timeText = Ui.clock(now);
        dateText = new SimpleDateFormat("MMMM d", Ui.locale()).format(new Date(now));
        weekdayText = new SimpleDateFormat("EEEE", Ui.locale()).format(new Date(now)).toUpperCase(Ui.locale());
        dayLine = weekdayText + "  ·  " + dateText.toUpperCase(Ui.locale());
        String pattern = Prefs.clockHours().equals("24") ? "HH:mm" : "h:mm";
        for (int i = 0; i < 4; i++) {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Ui.locale());
            f.setTimeZone(TimeZone.getTimeZone(ZONES[i]));
            cityTime[i] = f.format(new Date(now));
        }
        HomeData.Weather wx = data == null ? null : data.weather;
        if (wx != null && !Double.isNaN(wx.temp)) {
            tempText = HomeData.degrees(wx.temp);
            rangeText = Double.isNaN(wx.max) ? "" : "↑" + HomeData.degrees(wx.max) + "  ↓" + HomeData.degrees(wx.min);
            icon = Symbols.weather(wx.code, wx.isDay);
        } else {
            tempText = rangeText = "";
            icon = null;
        }
        placeText = Prefs.city();
    }

    void draw(Canvas c, long now) {
        if (w == 0) return;
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        boolean night = hour >= 22 || hour < 6;
        if (cal.get(Calendar.DAY_OF_YEAR) != shownDay) makeMonth(now);
        if (now - styleAt > 3000 || now < styleAt) {
            style = forceFace != null ? forceFace : Prefs.standbyClock();
            styleAt = now;
        }
        int minute = (int) (now / 60_000);
        if (minute != shownMinute) {
            shownMinute = minute;
            makeTexts(now);
        }
        c.drawColor(0xFF000000);
        switch (style) {
            case "digital": drawDigital(c, now, night); return;
            case "world": drawWorld(c, now, night); return;
            case "float": drawFloat(c, now, night); return;
            case "date": drawDate(c, now, night); return;
            default: break;
        }
        // Drift: up to ±14 px across, ±10 down, over 17 and 23 minutes.
        double minutes = now / 60_000.0;
        float dx = (float) (14 * k * Math.sin(minutes * 2 * Math.PI / 17));
        float dy = (float) (10 * k * Math.sin(minutes * 2 * Math.PI / 23));
        float size = CARD * k, left = (w - 2 * size - GAP * k) / 2 + dx, top = (h - size) / 2 + dy;
        card.setColor(night ? 0xFF0E0303 : 0xFF1C1C1E);
        rect.set(left, top, left + size, top + size);
        c.drawRoundRect(rect, RADIUS * k, RADIUS * k, card);
        drawClock(c, rect.centerX(), rect.centerY(), size * 0.42f, night);
        rect.offset(size + GAP * k, 0);
        c.drawRoundRect(rect, RADIUS * k, RADIUS * k, card);
        drawCalendar(c, rect, night);
    }

    /** The clock widget: numerals, minute ticks, the hour and minute hands, then the second hand on top. */
    private void drawClock(Canvas c, float cx, float cy, float r, boolean night) {
        drawClock(c, cx, cy, r, night, cal, true);
    }

    private void drawClock(Canvas c, float cx, float cy, float r, boolean night, Calendar cal, boolean withNumerals) {
        int ink = night ? NIGHT : 0xFFFFFFFF;
        tick.setStrokeWidth(Math.max(1.5f, 0.012f * r));
        for (int i = 0; i < 60; i++) {
            boolean five = i % 5 == 0;
            tick.setColor(night ? (five ? NIGHT : NIGHT_DIM) : five ? 0xCCFFFFFF : 0x59FFFFFF);
            double a = i * Math.PI / 30;
            float sin = (float) Math.sin(a), cos = (float) Math.cos(a);
            float r0 = r * (five ? 0.9f : 0.93f), r1 = r * 0.98f;
            c.drawLine(cx + sin * r0, cy - cos * r0, cx + sin * r1, cy - cos * r1, tick);
        }
        if (withNumerals) {
            numerals.setColor(ink);
            float half = Ui.halfCap(numerals);
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                c.drawText(HOURS[i], cx + (float) Math.sin(a) * r * 0.73f, cy - (float) Math.cos(a) * r * 0.73f + half, numerals);
            }
        }
        float ms = cal.get(Calendar.MILLISECOND) / 1000f;
        float seconds = cal.get(Calendar.SECOND) + ms;
        float minutes = cal.get(Calendar.MINUTE) + seconds / 60f;
        float hours = cal.get(Calendar.HOUR) + minutes / 60f;
        drawHand(c, cx, cy, hours / 12f, r * 0.5f, r * 0.075f, r, ink);
        drawHand(c, cx, cy, minutes / 60f, r * 0.84f, r * 0.065f, r, ink);
        dot.setColor(ink);
        c.drawCircle(cx, cy, r * 0.06f, dot);
        // The second hand: thin, past the center a little, with its own round hub.
        int second = night ? 0xFFE0352B : ORANGE;
        double a = seconds / 60.0 * 2 * Math.PI;
        float sin = (float) Math.sin(a), cos = (float) Math.cos(a);
        hand.setColor(second);
        hand.setStrokeWidth(r * 0.018f);
        c.drawLine(cx - sin * r * 0.16f, cy + cos * r * 0.16f, cx + sin * r * 0.92f, cy - cos * r * 0.92f, hand);
        dot.setColor(second);
        c.drawCircle(cx, cy, r * 0.042f, dot);
        dot.setColor(night ? 0xFF0E0303 : 0xFF1C1C1E);
        c.drawCircle(cx, cy, r * 0.016f, dot);
    }

    /** A hand as tvOS draws it: a thin stem from the hub, then the wide rounded blade. */
    private void drawHand(Canvas c, float cx, float cy, float turn, float length, float width, float r, int color) {
        double a = turn * 2 * Math.PI;
        float sin = (float) Math.sin(a), cos = (float) Math.cos(a);
        hand.setColor(color);
        hand.setStrokeWidth(r * 0.024f);
        float stem = r * 0.14f;
        c.drawLine(cx, cy, cx + sin * stem, cy - cos * stem, hand);
        hand.setStrokeWidth(width);
        c.drawLine(cx + sin * stem, cy - cos * stem, cx + sin * length, cy - cos * length, hand);
    }

    // MARK: - The other faces

    private float driftX(long now) {
        return (float) (14 * k * Math.sin(now / 60_000.0 * 2 * Math.PI / 17));
    }

    private float driftY(long now) {
        return (float) (10 * k * Math.sin(now / 60_000.0 * 2 * Math.PI / 23));
    }

    /** Big digits in the middle, the day under them; the colon blinks with the seconds. */
    private void drawDigital(Canvas c, long now, boolean night) {
        float cx = w / 2f + driftX(now), cy = h / 2f + driftY(now);
        digits.setColor(night ? NIGHT : 0xFFFFFFFF);
        digits.setTextSize(330 * k);
        c.drawText(timeText, cx, cy + Ui.halfCap(digits), digits);
        label.setColor(night ? NIGHT_DIM : ORANGE);
        c.drawText(dayLine, cx, cy + Ui.halfCap(digits) + 120 * k, label);
    }

    /** Four cities, each a small analog clock on its card, with its name and time. */
    private void drawWorld(Canvas c, long now, boolean night) {
        float size = 400 * k, gap = 40 * k, left = (w - 4 * size - 3 * gap) / 2 + driftX(now), top = (h - size) / 2 - 40 * k + driftY(now);
        card.setColor(night ? 0xFF0E0303 : 0xFF1C1C1E);
        for (int i = 0; i < 4; i++) {
            cityCal[i].setTimeInMillis(now);
            rect.set(left + i * (size + gap), top, left + i * (size + gap) + size, top + size);
            c.drawRoundRect(rect, 64 * k, 64 * k, card);
            drawClock(c, rect.centerX(), rect.centerY(), size * 0.38f, night, cityCal[i], false);
            label.setColor(night ? NIGHT : 0xFFFFFFFF);
            c.drawText(CITIES[i], rect.centerX(), rect.bottom + 74 * k, label);
            small.setColor(night ? NIGHT_DIM : 0x8CFFFFFF);
            c.drawText(cityTime[i], rect.centerX(), rect.bottom + 130 * k, small);
        }
    }

    /** The time wanders slowly around the screen and through the colors (nothing stays on the same pixels). */
    private void drawFloat(Canvas c, long now, boolean night) {
        double t = now / 1000.0;
        digits.setTextSize(220 * k);
        float tw = digits.measureText(timeText), cap = Ui.cap(digits);
        float rx = Math.max(0, (w - tw) / 2 - 80 * k), ry = Math.max(0, (h - cap) / 2 - 80 * k);
        float cx = w / 2f + (float) (rx * Math.sin(t * 2 * Math.PI / 97));
        float cy = h / 2f + (float) (ry * Math.sin(t * 2 * Math.PI / 71));
        float hue = (float) ((t * 2) % 360);
        hsv[0] = hue;
        hsv[1] = 0.45f;
        hsv[2] = 1f;
        digits.setColor(night ? NIGHT : android.graphics.Color.HSVToColor(hsv));
        c.drawText(timeText, cx, cy + cap / 2, digits);
    }

    /** The day, big, on the left; the weather where you are on the right. */
    private void drawDate(Canvas c, long now, boolean night) {
        float size = CARD * k, left = (w - 2 * size - GAP * k) / 2 + driftX(now), top = (h - size) / 2 + driftY(now);
        card.setColor(night ? 0xFF0E0303 : 0xFF1C1C1E);
        rect.set(left, top, left + size, top + size);
        c.drawRoundRect(rect, RADIUS * k, RADIUS * k, card);
        label.setColor(night ? NIGHT : RED);
        c.drawText(weekdayText, rect.centerX(), rect.top + 150 * k, label);
        bigDay.setColor(night ? NIGHT : 0xFFFFFFFF);
        c.drawText(dayText[todayDay], rect.centerX(), rect.centerY() + Ui.halfCap(bigDay) + 20 * k, bigDay);
        small.setColor(night ? NIGHT_DIM : 0x8CFFFFFF);
        c.drawText(monthName, rect.centerX(), rect.bottom - 90 * k, small);
        rect.offset(size + GAP * k, 0);
        c.drawRoundRect(rect, RADIUS * k, RADIUS * k, card);
        label.setColor(night ? NIGHT : 0xFF64D2FF);
        c.drawText(placeText.isEmpty() ? timeText : placeText, rect.centerX(), rect.top + 150 * k, label);
        if (icon != null && context != null) {
            Symbols.draw(context, c, icon, rect.centerX(), rect.top + 330 * k, 150 * k, night ? NIGHT : 0xFFFFFFFF);
        }
        digits.setTextSize(200 * k);
        digits.setColor(night ? NIGHT : 0xFFFFFFFF);
        c.drawText(tempText.isEmpty() ? timeText : tempText, rect.centerX(), rect.top + 600 * k, digits);
        small.setColor(night ? NIGHT_DIM : 0x8CFFFFFF);
        c.drawText(tempText.isEmpty() ? "" : rangeText, rect.centerX(), rect.bottom - 90 * k, small);
    }

    /** The month widget: its name in red, the weekdays' initials, then the days with today in a red circle. */
    private void drawCalendar(Canvas c, RectF box, boolean night) {
        float pad = 66 * k;
        month.setColor(night ? NIGHT : RED);
        c.drawText(monthName, box.left + pad, box.top + pad + Ui.cap(month), month);
        float gridLeft = box.left + pad - 12 * k, colW = (box.width() - 2 * pad + 24 * k) / 7;
        float headY = box.top + 214 * k;
        for (int i = 0; i < 7; i++) {
            int weekdayIndex = (firstDayOfWeek - 1 + i) % 7;   // 0 = Sunday
            boolean weekend = weekdayIndex == 0 || weekdayIndex == 6;
            weekday.setColor(night ? NIGHT_DIM : weekend ? 0x66FFFFFF : 0x8CFFFFFF);
            c.drawText(letters[i], gridLeft + colW * (i + 0.5f), headY, weekday);
        }
        float rowH = 88 * k, firstRow = headY + 92 * k, half = Ui.halfCap(day);
        for (int d = 1; d <= daysInMonth; d++) {
            int cell = firstColumn + d - 1, col = cell % 7, row = cell / 7;
            float x = gridLeft + colW * (col + 0.5f), y = firstRow + row * rowH;
            if (d == todayDay) {
                dot.setColor(night ? NIGHT : RED);
                c.drawCircle(x, y, 38 * k, dot);
                today.setColor(night ? 0xFF0E0303 : 0xFFFFFFFF);
                c.drawText(dayText[d], x, y + half, today);
                continue;
            }
            int weekdayIndex = (firstDayOfWeek - 1 + col) % 7;
            boolean weekend = weekdayIndex == 0 || weekdayIndex == 6;
            day.setColor(night ? (weekend ? NIGHT_DIM : NIGHT) : weekend ? 0x8CFFFFFF : 0xFFFFFFFF);
            c.drawText(dayText[d], x, y + half, day);
        }
    }

    /** The month's name and layout in the viewer's language, once a day. */
    private void makeMonth(long now) {
        Calendar m = Calendar.getInstance(Ui.locale());
        m.setTimeInMillis(now);
        shownDay = m.get(Calendar.DAY_OF_YEAR);
        todayDay = m.get(Calendar.DAY_OF_MONTH);
        daysInMonth = m.getActualMaximum(Calendar.DAY_OF_MONTH);
        firstDayOfWeek = m.getFirstDayOfWeek();
        monthName = new SimpleDateFormat("LLLL", Ui.locale()).format(new Date(now)).toUpperCase(Ui.locale());
        m.set(Calendar.DAY_OF_MONTH, 1);
        firstColumn = (m.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7;
        SimpleDateFormat narrow = new SimpleDateFormat("EEEEE", Ui.locale());
        Calendar d = Calendar.getInstance(Ui.locale());
        d.set(Calendar.DAY_OF_WEEK, firstDayOfWeek);
        for (int i = 0; i < 7; i++) {
            letters[i] = narrow.format(d.getTime());
            d.add(Calendar.DAY_OF_MONTH, 1);
        }
    }
}
