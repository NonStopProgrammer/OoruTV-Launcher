package io.freemytv;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * The day in the Tamil calendar, worked out on the TV (no network): the Tamil month and day, the year's name, Rahu
 * Kalam, Yamagandam and Gulikai, and the coming festivals.
 *  - The Tamil month is the sidereal sign the Sun is in (Lahiri ayanamsa): it starts on the day the Sun enters the
 *    sign, or on the next day when that happens after sunset. The Sun's longitude is Meeus' low-precision formula
 *    (about 0.01°, a quarter of an hour of the Sun's motion); checked against 2026-27's calendar (Thai 1 on 14 January
 *    2026 and 15 January 2027, Chithirai 1 on 14 April, Aippasi 1 on 18 October 2026).
 *  - Rahu Kalam, Yamagandam and Gulikai: the daylight (sunrise to sunset) in eight equal parts, one of them for each
 *    weekday, as in every Tamil calendar.
 *  - Festivals: a table (Drik Panchang's 2026-27 dates, Tamil Nadu's holidays); lunar festivals can differ by a day.
 */
final class Panchang {
    private Panchang() {}

    static final String[] MONTHS = {"சித்திரை", "வைகாசி", "ஆனி", "ஆடி", "ஆவணி", "புரட்டாசி", "ஐப்பசி", "கார்த்திகை",
            "மார்கழி", "தை", "மாசி", "பங்குனி"};
    static final String[] MONTHS_EN = {"Chithirai", "Vaikasi", "Aani", "Aadi", "Aavani", "Purattasi", "Aippasi",
            "Karthigai", "Margazhi", "Thai", "Maasi", "Panguni"};
    /** The 60-year cycle, from Prabhava (1987-88). */
    private static final String[] YEARS = {"பிரபவ", "விபவ", "சுக்ல", "பிரமோதூத", "பிரஜோத்பத்தி", "ஆங்கீரச", "ஸ்ரீமுக", "பவ",
            "யுவ", "தாது", "ஈஸ்வர", "வெகுதானிய", "பிரமாதி", "விக்கிரம", "விஷு", "சித்திரபானு", "சுபானு", "தாரண", "பார்த்திப",
            "விய", "சர்வஜித்", "சர்வதாரி", "விரோதி", "விக்ருதி", "கர", "நந்தன", "விஜய", "ஜய", "மன்மத", "துன்முகி", "ஹேவிளம்பி",
            "விளம்பி", "விகாரி", "சார்வரி", "பிலவ", "சுபகிருது", "சோபகிருது", "குரோதி", "விசுவாவசு", "பராபவ", "பிலவங்க", "கீலக",
            "சௌமிய", "சாதாரண", "விரோதகிருது", "பரிதாபி", "பிரமாதீச", "ஆனந்த", "ராட்சச", "நள", "பிங்கள", "காளயுக்தி",
            "சித்தார்த்தி", "ரௌத்திரி", "துன்மதி", "துந்துபி", "ருத்ரோத்காரி", "ரக்தாட்சி", "குரோதன", "அட்சய"};

    static final class TamilDate {
        int month, day;     // month: 0 = Chithirai ... 11 = Panguni
        String year;
    }

    static final class Period {
        final String name;
        final long start, end;

        Period(String name, long start, long end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }

    static final class Festival {
        final int year, month, day;     // month 1-12
        final String tamil, english;

        Festival(int year, int month, int day, String tamil, String english) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.tamil = tamil;
            this.english = english;
        }

        String name() {
            return Ui.language.startsWith("ta") ? tamil : english;
        }
    }

    /**
     * 2026-27: the Tamil Nadu government's holiday lists (Pongal, Deepavali, Ramzan...) and, for the temple festivals,
     * Drik Panchang's dates (checked in October 2026). Past 2027 the card just has fewer rows until this is extended.
     */
    private static final Festival[] FESTIVALS = {
        new Festival(2026, 11, 8, "தீபாவளி", "Deepavali"),
        new Festival(2026, 11, 15, "சூரசம்ஹாரம்", "Soora Samharam"),
        new Festival(2026, 11, 24, "கார்த்திகை தீபம்", "Karthigai Deepam"),
        new Festival(2026, 12, 20, "வைகுண்ட ஏகாதசி", "Vaikunta Ekadasi"),
        new Festival(2026, 12, 24, "ஆருத்ரா தரிசனம்", "Arudra Darshan"),
        new Festival(2026, 12, 25, "கிறிஸ்துமஸ்", "Christmas"),
        new Festival(2027, 1, 1, "ஆங்கிலப் புத்தாண்டு", "New Year's Day"),
        new Festival(2027, 1, 14, "போகி", "Bhogi"),
        new Festival(2027, 1, 15, "தைப் பொங்கல்", "Thai Pongal"),
        new Festival(2027, 1, 16, "மாட்டுப் பொங்கல்", "Mattu Pongal"),
        new Festival(2027, 1, 22, "தைப்பூசம்", "Thaipusam"),
        new Festival(2027, 1, 26, "குடியரசு தினம்", "Republic Day"),
        new Festival(2027, 3, 6, "மகா சிவராத்திரி", "Maha Shivaratri"),
        new Festival(2027, 3, 10, "ரம்ஜான்", "Ramzan"),
        new Festival(2027, 3, 22, "பங்குனி உத்திரம்", "Panguni Uthiram"),
        new Festival(2027, 3, 26, "புனித வெள்ளி", "Good Friday"),
        new Festival(2027, 4, 14, "தமிழ்ப் புத்தாண்டு", "Tamil New Year"),
        new Festival(2027, 4, 20, "சித்ரா பௌர்ணமி", "Chithra Pournami"),
        new Festival(2027, 5, 17, "பக்ரீத்", "Bakrid"),
        new Festival(2027, 8, 3, "ஆடிப் பெருக்கு", "Aadi Perukku"),
        new Festival(2027, 8, 15, "சுதந்திர தினம்", "Independence Day"),
        new Festival(2027, 8, 25, "கிருஷ்ண ஜெயந்தி", "Krishna Jayanthi"),
        new Festival(2027, 9, 4, "விநாயகர் சதுர்த்தி", "Vinayagar Chathurthi"),
        new Festival(2027, 9, 30, "நவராத்திரி", "Navarathiri"),
        new Festival(2027, 10, 8, "ஆயுத பூஜை", "Ayutha Pooja"),
        new Festival(2027, 10, 9, "விஜயதசமி", "Vijayadasami"),
        new Festival(2027, 10, 28, "தீபாவளி", "Deepavali"),
        new Festival(2027, 11, 4, "சூரசம்ஹாரம்", "Soora Samharam"),
        new Festival(2027, 12, 12, "கார்த்திகை தீபம்", "Karthigai Deepam"),
        new Festival(2027, 12, 25, "கிறிஸ்துமஸ்", "Christmas"),
    };

    // MARK: - Tamil date

    static TamilDate tamilDate(long now, double lat, double lon) {
        TimeZone zone = TimeZone.getDefault();
        Calendar d = Calendar.getInstance(zone);
        d.setTimeInMillis(now);
        int y = d.get(Calendar.YEAR), m = d.get(Calendar.MONTH) + 1, day = d.get(Calendar.DAY_OF_MONTH);
        // The month is the sign the Sun is in at today's sunset; it began on the day of the ingress, or the next one.
        long sunset = sun(y, m, day, lat, lon, zone)[1];
        double jdSet = julian(sunset);
        int sign = (int) Math.floor(sidereal(jdSet) / 30) % 12;
        double lo = jdSet - 33, hi = jdSet;      // the ingress into `sign`: within the last month
        for (int i = 0; i < 50; i++) {
            double mid = (lo + hi) / 2;
            if ((int) Math.floor(sidereal(mid) / 30) % 12 == sign) hi = mid; else lo = mid;
        }
        long ingress = millis(hi);
        Calendar s = Calendar.getInstance(zone);
        s.setTimeInMillis(ingress);
        long setThatDay = sun(s.get(Calendar.YEAR), s.get(Calendar.MONTH) + 1, s.get(Calendar.DAY_OF_MONTH), lat, lon, zone)[1];
        if (ingress > setThatDay) s.add(Calendar.DAY_OF_MONTH, 1);
        TamilDate t = new TamilDate();
        t.month = sign;
        t.day = (int) (dayNumber(d) - dayNumber(s)) + 1;
        // The year begins on Chithirai 1 (mid-April): Jan to Chithirai 1 still belongs to the one that began the April before.
        int start = (m > 4 || (m == 4 && sign == 0)) ? y : y - 1;
        t.year = YEARS[((start - 1987) % 60 + 60) % 60];
        return t;
    }

    /** "ஐப்பசி 7" in Tamil, "Aippasi 7" otherwise. */
    static String dateText(TamilDate t) {
        return (Ui.language.startsWith("ta") ? MONTHS[t.month] : MONTHS_EN[t.month]) + " " + t.day;
    }

    private static long dayNumber(Calendar c) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.clear();
        u.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        return u.getTimeInMillis() / 86_400_000L;
    }

    private static double julian(long ms) {
        return ms / 86_400_000.0 + 2440587.5;
    }

    private static long millis(double jd) {
        return Math.round((jd - 2440587.5) * 86_400_000.0);
    }

    /** The Sun's sidereal longitude (degrees): apparent tropical longitude minus the Lahiri ayanamsa. */
    private static double sidereal(double jd) {
        double t = (jd - 2451545.0) / 36525;
        double l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t;
        double mr = Math.toRadians(357.52911 + 35999.05029 * t - 0.0001537 * t * t);
        double c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(mr) + (0.019993 - 0.000101 * t) * Math.sin(2 * mr)
                + 0.000289 * Math.sin(3 * mr);
        double om = Math.toRadians(125.04 - 1934.136 * t);
        double tropical = l0 + c - 0.00569 - 0.00478 * Math.sin(om);
        double ayanamsa = 23.853 + (jd - 2451545.0) / 365.25 * 50.2788 / 3600;
        double v = (tropical - ayanamsa) % 360;
        return v < 0 ? v + 360 : v;
    }

    /** Sunrise and sunset (epoch ms) on a date, by the sunrise equation (within a minute or two). */
    static long[] sun(int y, int m, int d, double lat, double lon, TimeZone zone) {
        Calendar noon = Calendar.getInstance(zone);
        noon.clear();
        noon.set(y, m - 1, d, 12, 0);
        double n = Math.round(julian(noon.getTimeInMillis()) - 2451545.0 + 0.0008);
        double js = n - lon / 360;
        double mdeg = (357.5291 + 0.98560028 * js) % 360, mr = Math.toRadians(mdeg);
        double c = 1.9148 * Math.sin(mr) + 0.0200 * Math.sin(2 * mr) + 0.0003 * Math.sin(3 * mr);
        double lam = Math.toRadians((mdeg + c + 180 + 102.9372) % 360);
        double transit = 2451545.0 + js + 0.0053 * Math.sin(mr) - 0.0069 * Math.sin(2 * lam);
        double sd = Math.sin(lam) * Math.sin(Math.toRadians(23.4397)), cd = Math.cos(Math.asin(sd));
        double cw = (Math.sin(Math.toRadians(-0.833)) - Math.sin(Math.toRadians(lat)) * sd) / (Math.cos(Math.toRadians(lat)) * cd);
        double w = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cw))));
        return new long[] {millis(transit - w / 360), millis(transit + w / 360)};
    }

    // MARK: - Rahu Kalam, Yamagandam, Gulikai

    // The eighth of the daylight (1 to 8) for each weekday, Sunday first.
    private static final int[] RAHU = {8, 2, 7, 5, 6, 4, 3}, YAMA = {5, 4, 3, 2, 1, 7, 6}, GULIKA = {7, 6, 5, 4, 3, 2, 1};

    /** Today's three periods, from today's sunrise and sunset. */
    static List<Period> periods(long now, long sunrise, long sunset) {
        List<Period> out = new ArrayList<>();
        if (sunrise <= 0 || sunset <= sunrise) return out;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int w = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        long part = (sunset - sunrise) / 8;
        out.add(new Period(Ui.tr("Rahu Kalam"), sunrise + (RAHU[w] - 1) * part, sunrise + RAHU[w] * part));
        out.add(new Period(Ui.tr("Yamagandam"), sunrise + (YAMA[w] - 1) * part, sunrise + YAMA[w] * part));
        out.add(new Period(Ui.tr("Gulikai"), sunrise + (GULIKA[w] - 1) * part, sunrise + GULIKA[w] * part));
        return out;
    }

    // MARK: - Festivals

    /** The next `n` festivals from today (today's included). */
    static List<Festival> upcoming(long now, int n) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int today = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
        List<Festival> out = new ArrayList<>();
        for (Festival f : FESTIVALS) {
            if (f.year * 10000 + f.month * 100 + f.day >= today) out.add(f);
            if (out.size() >= n) break;
        }
        return out;
    }

    /** Whole days from today to the festival (0 = today). */
    static int daysUntil(Festival f, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        Calendar t = Calendar.getInstance();
        t.clear();
        t.set(f.year, f.month - 1, f.day);
        return (int) (dayNumber(t) - dayNumber(c));
    }
}
