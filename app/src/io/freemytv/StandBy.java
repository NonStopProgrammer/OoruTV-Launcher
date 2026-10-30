package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * The StandBy screensaver (Home Settings › Screensaver style), like an iPhone on its side in StandBy: two big widgets
 * on black, an analog clock with a sweeping orange second hand and this month's calendar with today in red.
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
    private Paint numerals, month, weekday, day, today;
    private final RectF rect = new RectF();
    private final Calendar cal = Calendar.getInstance();
    private float w, h, k;

    // The month on screen: made again when the day changes.
    private int shownDay = -1, firstColumn, daysInMonth, todayDay, firstDayOfWeek;
    private String monthName = "";
    private final String[] dayText = new String[32], letters = new String[7];
    private static final String[] HOURS = {"12", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"};

    StandBy() {
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
        shownDay = -1;
    }

    void draw(Canvas c, long now) {
        if (w == 0) return;
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        boolean night = hour >= 22 || hour < 6;
        if (cal.get(Calendar.DAY_OF_YEAR) != shownDay) makeMonth(now);
        c.drawColor(0xFF000000);
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
        numerals.setColor(ink);
        float half = Ui.halfCap(numerals);
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            c.drawText(HOURS[i], cx + (float) Math.sin(a) * r * 0.73f, cy - (float) Math.cos(a) * r * 0.73f + half, numerals);
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
