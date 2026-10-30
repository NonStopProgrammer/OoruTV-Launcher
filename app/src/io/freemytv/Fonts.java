package io.freemytv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The fonts on offer (OoruTV › Home Settings › Font), loaded into Ui's seven faces (thin, light, display medium and
 * semibold for large text; regular, medium and semibold for small text):
 *  - "sf" (SF Pro) and "sfrounded" (SF Pro Rounded): Apple's fonts. Their licence doesn't allow building them into an
 *    app, so OoruTV doesn't: it reads the files you put in its fonts folder on the TV,
 *    /sdcard/Android/data/io.freemytv/files/fonts (scripts/install.ps1 copies them there from a "fonts" folder next
 *    to it). Until they are there, SF Pro shows as Inter and SF Rounded as Google Sans Flex with round ends.
 *  - "inter" (Inter), "googlesans" (Google Sans Flex, the Google I/O font) and "outfit" (Outfit): variable fonts under
 *    the SIL Open Font License, in assets/fonts.
 *  - "system": the TV's own font.
 */
final class Fonts {
    private Fonts() {}

    static final String[] KEYS = {"sf", "sfrounded", "inter", "googlesans", "outfit", "system"};
    private static final String INTER = "fonts/InterVariable.ttf", GOOGLE_SANS = "fonts/GoogleSansFlex.ttf",
            OUTFIT = "fonts/Outfit.ttf";

    /** The name shown in the settings. */
    static String name(String key) {
        switch (key) {
            case "sf": return "SF Pro";
            case "sfrounded": return "SF Rounded";
            case "googlesans": return "Google Sans";
            case "outfit": return "Outfit";
            case "system": return Ui.tr("The TV's font");
            default: return "Inter";
        }
    }

    /** Where Apple's SF files go on the TV (the app's own folder: reading it needs no permission). */
    static File folder(Context c) {
        try {
            return c.getExternalFilesDir("fonts");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The font's name in itself, for the font picker (SF fonts not on the TV: their stand-in). */
    static Typeface preview(Context c, String key) {
        AssetManager a = c.getAssets();
        Typeface t = null;
        if (key.startsWith("sf")) {
            Apple files = Apple.scan(folder(c), key.equals("sfrounded"));
            if (files != null) t = files.make(false, 500);
        }
        if (t == null) {
            switch (key) {
                case "sfrounded": t = asset(a, GOOGLE_SANS, "'opsz' 14, 'wght' 500, 'ROND' 100"); break;
                case "googlesans": t = asset(a, GOOGLE_SANS, "'opsz' 14, 'wght' 500, 'ROND' 0"); break;
                case "outfit": t = asset(a, OUTFIT, "'wght' 500"); break;
                case "system": break;
                default: t = asset(a, INTER, "'opsz' 14, 'wght' 500"); break;
            }
        }
        return t != null ? t : Build.VERSION.SDK_INT >= 28 ? Weighted.create(500) : Typeface.DEFAULT;
    }

    /** True when Apple's SF Pro (or SF Pro Rounded) files are in the fonts folder. */
    static boolean hasApple(Context c, boolean rounded) {
        return Apple.scan(folder(c), rounded) != null;
    }

    /** One of the seven faces: large text ("display") or small, at a weight (100 to 900). */
    private interface Maker {
        Typeface make(boolean display, int weight);
    }

    /**
     * Loads the font `key` into Ui's faces. Returns false when it's an SF font whose files aren't on the TV (its stand-in
     * is loaded instead).
     */
    static boolean load(Context c, String key) {
        AssetManager a = c.getAssets();
        boolean found = true;
        Maker apple = null;
        if (key.equals("sf") || key.equals("sfrounded")) {
            Apple files = Apple.scan(folder(c), key.equals("sfrounded"));
            if (files != null) apple = files::make;
            else found = false;
        }
        final Maker standIn;
        switch (key) {
            case "sfrounded": standIn = (d, w) -> asset(a, GOOGLE_SANS, "'opsz' " + (d ? 32 : 14) + ", 'wght' " + w + ", 'ROND' 100"); break;
            case "googlesans": standIn = (d, w) -> asset(a, GOOGLE_SANS, "'opsz' " + (d ? 32 : 14) + ", 'wght' " + w + ", 'ROND' 0"); break;
            case "outfit": standIn = (d, w) -> asset(a, OUTFIT, "'wght' " + w); break;
            case "system": standIn = (d, w) -> null; break;
            default: standIn = (d, w) -> asset(a, INTER, "'opsz' " + (d ? 32 : 14) + ", 'wght' " + w); break;
        }
        Maker m = apple != null ? apple : standIn;
        Ui.thin = face(m, standIn, true, 200);
        Ui.light = face(m, standIn, true, 300);
        Ui.displayMedium = face(m, standIn, true, 500);
        Ui.displaySemibold = face(m, standIn, true, 600);
        Ui.regular = face(m, standIn, false, 400);
        Ui.medium = face(m, standIn, false, 500);
        Ui.semibold = face(m, standIn, false, 600);
        AppLog.w("font: " + key + (found ? "" : " (its files aren't on the TV: stand-in)"));
        return found;
    }

    /** The face from `m`, else from the stand-in, else the TV's font at that weight. */
    private static Typeface face(Maker m, Maker standIn, boolean display, int weight) {
        Typeface t = m.make(display, weight);
        if (t == null && m != standIn) t = standIn.make(display, weight);
        if (t != null) return t;
        // Typeface.create(family, weight, italic) is Android 9+; Android 8 only has regular and bold.
        if (Build.VERSION.SDK_INT >= 28) return Weighted.create(weight);
        return Typeface.create(Typeface.DEFAULT, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** Only this class touches the Android 9 call, so Fonts itself still verifies on Android 8. */
    private static final class Weighted {
        static Typeface create(int weight) {
            return Typeface.create(Typeface.DEFAULT, weight, false);
        }
    }

    private static Typeface asset(AssetManager a, String path, String variation) {
        try (InputStream in = a.open(path)) {
            if (in == null) return null;
        } catch (IOException e) {
            return null;
        }
        try {
            return new Typeface.Builder(a, path).setFontVariationSettings(variation).build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Typeface file(File f, String variation) {
        try {
            Typeface.Builder b = new Typeface.Builder(f);
            if (variation != null) b.setFontVariationSettings(variation);
            return b.build();
        } catch (RuntimeException e) {
            AppLog.w("font " + f.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // MARK: - Apple's files

    /** Weight names in Apple's file names, longest first ("semibold" before "bold"), and their weights. */
    private static final String[] WEIGHT_NAMES = {"ultralight", "semibold", "regular", "medium", "heavy", "black",
            "light", "thin", "bold"};
    private static final int[] WEIGHTS = {100, 600, 400, 500, 800, 900, 300, 200, 700};
    private static final int ANY = 0, DISPLAY = 1, TEXT = 2;

    /**
     * Apple's SF files in the fonts folder, for SF Pro or SF Pro Rounded. Apple's download installs static files
     * (SF-Pro-Display-Thin.otf, SF-Pro-Text-Regular.otf, SF-Pro-Rounded-Semibold.otf…) and a variable one
     * (SF-Pro.ttf); either works, also renamed ("SF Pro Display Thin.otf", "SFProText-Regular.otf"). Italics,
     * SF Compact and SF Mono are left out.
     */
    private static final class Apple {
        final List<File> files = new ArrayList<>();
        final List<int[]> kinds = new ArrayList<>();   // {DISPLAY / TEXT / ANY, weight}
        File variable;

        static Apple scan(File dir, boolean rounded) {
            File[] all = dir == null ? null : dir.listFiles();
            if (all == null) return null;
            Apple found = new Apple();
            for (File f : all) {
                String n = f.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "");
                if (!f.isFile() || !(n.endsWith(".otf") || n.endsWith(".ttf"))) continue;
                String base = n.substring(0, n.length() - 4);
                if (!base.startsWith("sf") || base.contains("italic") || base.contains("compact") || base.contains("mono")
                        || base.contains("condensed") || base.contains("expanded") || base.contains("serif")) continue;
                if (base.contains("rounded") != rounded) continue;
                int weight = 0;
                for (int i = 0; i < WEIGHT_NAMES.length && weight == 0; i++) {
                    if (base.endsWith(WEIGHT_NAMES[i])) weight = WEIGHTS[i];
                }
                if (weight == 0) {
                    if (n.endsWith(".ttf")) found.variable = f;
                    continue;
                }
                found.files.add(f);
                found.kinds.add(new int[] {base.contains("display") ? DISPLAY : base.contains("text") ? TEXT : ANY, weight});
            }
            return found.files.isEmpty() && found.variable == null ? null : found;
        }

        /** The nearest weight, Display for large text and Text for small (as Apple does); else the variable file. */
        Typeface make(boolean display, int weight) {
            int best = -1, bestScore = Integer.MAX_VALUE;
            for (int i = 0; i < files.size(); i++) {
                int[] k = kinds.get(i);
                int score = Math.abs(k[1] - weight) / 10 + (k[0] == ANY || k[0] == (display ? DISPLAY : TEXT) ? 0 : 3);
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best >= 0 && (bestScore < 10 || variable == null)) {
                Typeface t = file(files.get(best), null);
                if (t != null) return t;
            }
            return variable == null ? null : file(variable, "'wght' " + weight + ", 'opsz' " + (display ? 28 : 17));
        }
    }
}
