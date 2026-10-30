package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Background (aurora) and glass, using the shaders in Shaders. The aurora is drawn into a ¼-scale layer, scaled up,
 * and only redrawn when it moves (advance, up to ~30 times per second); in the frames between steps the GPU just
 * reuses the layer. The glass reads the frosted aurora computed in Frost or, with a recommendation's image as the
 * background, its blurred version (setFrost).
 * Without AGSL (Android 8 to 12) the background is a gradient and the glass is drawn with plain Canvas calls (see
 * "Glass without AGSL"): the home screen is never left blank.
 */
final class Scene {
    final Shaders.Palette palette = new Shaders.Palette();
    private RuntimeShader aurora, glass, liquid;
    private final Frost frost = new Frost();
    private final Paint auroraPaint = new Paint();
    private final Paint glassPaint = new Paint();
    private final Paint liquidPaint = new Paint();
    private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint();
    private final Recording auroraNode = Recording.create("aurora");
    // The layer is drawn at a quarter size and scaled up: filtered, its soft shapes stay soft instead of stepping.
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int w = 1, h = 1, lw = 1, lh = 1;
    private float speed = 1, baseDim, dim, time;
    private boolean auroraDirty = true, imageFrost, imageGlass;
    private Bitmap frostImage, glassA, glassB;
    private BitmapShader glassShaderA;
    private final boolean withGlass;
    /** Glass Style "frosted" (Prefs.glass): no refraction anywhere. */
    boolean frosted;

    Scene() {
        this(true);
    }

    Scene(boolean withGlass) {
        this.withGlass = withGlass;
        try {
            aurora = new RuntimeShader(Shaders.AURORA);
            glass = new RuntimeShader(Shaders.GLASS);
            liquid = new RuntimeShader(Shaders.LIQUID);
            auroraPaint.setShader(aurora);
            glassPaint.setShader(glass);
            liquidPaint.setShader(liquid);
        } catch (RuntimeException | LinkageError e) {   // Android 8 to 12: no AGSL, Canvas glass instead
            AppLog.w("shader unavailable, using gradient: " + e.getMessage());
            aurora = null;
            glass = null;
            liquid = null;
            initOldGlass();
        }
        auroraNode.setUseCompositingLayer(true, layerPaint);
        flatRim.setStyle(Paint.Style.STROKE);
        flatRim.setColor(0x33FFFFFF);
        flatRim.setStrokeWidth(1.5f);
    }

    void setSize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        lw = Math.max(1, Math.round(w / 4f));
        lh = Math.max(1, Math.round(h / 4f));
        auroraNode.setPosition(0, 0, lw, lh);
        placeImages();
        if (aurora != null) {
            aurora.setFloatUniform("res", w, h);
            aurora.setFloatUniform("layer", lw, lh);
            setFrostScale();
            if (imageGlass) liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
        }
        push();
    }

    /** speed: 1 on the home screen, less on the screensaver; dim: how much to darken (0 to 1). */
    void setMotion(float speed, float dim) {
        this.speed = speed;
        baseDim = dim;
        push();
    }

    /** Call after changing `palette`. Bright palettes are dimmed a little so white text stays readable. */
    void applyPalette() {
        push();
        if (aurora == null) fallback.setShader(Ui.gradient(h, palette.color(1), palette.color(0)));
    }

    private void push() {
        dim = Math.min(0.6f, baseDim + Math.max(0, Math.min(0.22f, (palette.brightest() - 0.45f) * 0.5f)));
        if (aurora == null) return;
        aurora.setFloatUniform("speed", speed);
        aurora.setFloatUniform("dim", dim);
        palette.apply(aurora);
        advance(time);
    }

    /** Moves the aurora to `seconds`: redraws the layer on the next draw, recomputes the glass's frosted aurora now. */
    void advance(float seconds) {
        time = seconds;
        if (aurora == null) return;
        aurora.setFloatUniform("t", seconds);
        if (withGlass && (!imageFrost || !imageGlass)) {
            BitmapShader s = frost.render(seconds, speed, dim, palette.c, (float) w / h);
            if (!imageFrost) glass.setInputShader("frost", s);
            if (!imageGlass) {
                liquid.setInputShader("imageA", s);
                liquid.setInputShader("imageB", s);
                liquid.setFloatUniform("mixB", 0);
                liquid.setFloatUniform("imageScale", (float) Frost.W / w, (float) Frost.H / h);
            }
        }
        auroraDirty = true;
    }

    /** The liquid glass now shows this photo (Backdrop.GW × GH, slightly blurred). */
    void setGlassImage(int[] pixels) {
        setGlassImages(pixels, null);
    }

    /** Photo transition: both photos in the glass, blended by setGlassMix (0 = the first one). */
    void setGlassImages(int[] from, int[] to) {
        imageGlass = true;
        if (liquid == null) {           // no AGSL (Android 8 to 12): the Canvas glass draws the images itself
            if (old) {
                oldGlassA = fill(oldGlassA, from, Backdrop.GW, Backdrop.GH);
                oldPaintA.setShader(new BitmapShader(oldGlassA, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                if (to != null) {
                    oldGlassB = fill(oldGlassB, to, Backdrop.GW, Backdrop.GH);
                    oldPaintB.setShader(new BitmapShader(oldGlassB, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                } else {
                    oldPaintB.setShader(null);
                }
                oldMix = 0;
                placeImages();
            }
            return;
        }
        glassA = fill(glassA, from);
        if (to != null) glassB = fill(glassB, to);
        glassShaderA = linear(glassA);
        liquid.setInputShader("imageA", glassShaderA);
        liquid.setInputShader("imageB", to != null ? linear(glassB) : glassShaderA);
        liquid.setFloatUniform("mixB", 0);
        liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
    }

    void setGlassMix(float t) {
        if (liquid != null) liquid.setFloatUniform("mixB", Math.max(0, Math.min(1, t)));
        oldMix = Math.max(0, Math.min(1, t));
    }

    /** The liquid glass goes back to the frosted aurora. */
    void clearGlass() {
        if (!imageGlass) return;
        imageGlass = false;
        advance(time);
    }

    private static Bitmap fill(Bitmap b, int[] pixels) {
        return fill(b, pixels, Backdrop.GW, Backdrop.GH);
    }

    private static Bitmap fill(Bitmap b, int[] pixels, int width, int height) {
        if (b == null) b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        b.setPixels(pixels, 0, width, 0, 0, width, height);
        return b;
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    /**
     * Liquid Glass in rectangle `r` (screen coordinates). band and refraction in px (the thick rim and how much it
     * bends the image), specular = rim highlight, alpha = opacity of the whole glass.
     */
    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band, float refraction,
                float specular, float alpha) {
        if (alpha <= 0.004f) return;
        if (frosted) {      // Glass Style: Frosted, the classic soft glass without the bending edge
            glass(c, r, radius, tint, Math.max(0.3f, tintAlpha), 0, 0, lift, 1, alpha);
            return;
        }
        if (liquid == null) {
            if (!old) {
                glass(c, r, radius, tint, Math.max(0.5f, tintAlpha), 0, 0, lift, 1, alpha);
                return;
            }
            // The photo (or the background's gray) through the glass; the frosted image if that is all there is.
            Paint a = imageGlass && oldGlassA != null ? oldPaintA : imageFrost && oldFrost != null ? oldFrostPaint : null;
            Paint b = a == oldPaintA && oldPaintB.getShader() != null ? oldPaintB : null;
            lensBand = band;
            lensShift = refraction;
            oldGlass(c, r, radius, a, b, oldMix, tint, tintAlpha, lift, true, liquidRim, specular,
                    liquidHalo, specular, 1, alpha);
            lensBand = lensShift = 0;
            return;
        }
        liquid.setFloatUniform("center", r.centerX(), r.centerY());
        liquid.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
        liquid.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
        liquid.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        liquid.setFloatUniform("lift", lift);
        liquid.setFloatUniform("band", Math.max(1, band));
        liquid.setFloatUniform("refraction", refraction);
        liquid.setFloatUniform("specular", specular);
        liquidPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, liquidPaint);
        liquidPaint.setAlpha(255);
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    void setFrost(int[] pixels) {
        imageFrost = true;
        if (glass == null) {            // as above: the Canvas glass draws it
            if (old) {
                oldFrost = fill(oldFrost, pixels, Backdrop.FW, Backdrop.FH);
                oldFrostPaint.setShader(new BitmapShader(oldFrost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                placeImages();
            }
            return;
        }
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostImage.setPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        BitmapShader s = new BitmapShader(frostImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        glass.setInputShader("frost", s);
        setFrostScale();
    }

    /** Back to the frosted aurora. */
    void clearFrost() {
        if (!imageFrost) return;
        imageFrost = false;
        setFrostScale();
        advance(time);
    }

    /** The current blurred image (so the bottom drawer can blur the background), or null with the aurora. */
    Bitmap frostImage() {
        return imageFrost ? frostImage : null;
    }

    /** Offset (px) of the layer that will draw glass, if it was moved on screen without being redrawn. */
    void setOffset(float x, float y) {
        if (glass != null) glass.setFloatUniform("offset", x, y);
        if (liquid != null) liquid.setFloatUniform("offset", x, y);
        if (old && (x != offX || y != offY)) {
            offX = x;
            offY = y;
            placeImages();
        }
    }

    private void setFrostScale() {
        if (glass == null) return;
        if (imageFrost) glass.setFloatUniform("frostScale", (float) Backdrop.FW / w, (float) Backdrop.FH / h);
        else glass.setFloatUniform("frostScale", (float) Frost.W / w, (float) Frost.H / h);
    }

    void drawBackground(Canvas c) {
        if (aurora == null) {
            if (fallback.getShader() == null) applyPalette();
            c.drawRect(0, 0, w, h, fallback);
            return;
        }
        if (auroraDirty) {
            Canvas rc = auroraNode.beginRecording(lw, lh);
            try {
                rc.drawPaint(auroraPaint);
            } finally {
                auroraNode.endRecording();
            }
            auroraDirty = false;
        }
        c.save();
        c.scale((float) w / lw, (float) h / lh);
        auroraNode.draw(c);
        c.restore();
    }

    /**
     * Glass in rectangle `r` (screen coordinates, no canvas transform: the shader uses the real position).
     * body = 1: full glass; body = 0: just the light rim, on top of something already drawn.
     */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (glass == null && old) {
            oldGlass(c, r, radius, imageFrost && oldFrost != null ? oldFrostPaint : null, null, 0, tint, tintAlpha, lift,
                    false, glassRim, 1, null, 0, body, alpha);
            return;
        }
        if (glass == null) {
            if (body > 0) {
                flatFill.setColor(Ui.alpha(tint | 0xFF000000, Math.max(0.35f, tintAlpha)));
                c.drawRoundRect(r, radius, radius, flatFill);
            }
            c.drawRoundRect(r, radius, radius, flatRim);
            return;
        }
        float hw = r.width() / 2, hh = r.height() / 2, inner = Math.min(hw, hh);
        glass.setFloatUniform("center", r.centerX(), r.centerY());
        glass.setFloatUniform("halfSize", hw, hh);
        glass.setFloatUniform("invHalf", 1 / Math.max(hw, 1), 1 / Math.max(hh, 1));
        glass.setFloatUniform("radius", radius);
        glass.setFloatUniform("top", r.top);
        glass.setFloatUniform("depth", 1 / Math.max(inner * 0.6f, 1), Math.min(24, inner * 0.5f), 1 / Math.max(inner * 0.9f, 1), 1 / Math.max(r.height(), 1));
        glass.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        glass.setFloatUniform("glow", Color.red(glow) / 255f, Color.green(glow) / 255f, Color.blue(glow) / 255f, glowAlpha);
        glass.setFloatUniform("lift", lift);
        glass.setFloatUniform("body", body);
        glassPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, glassPaint);
        glassPaint.setAlpha(255);
    }

    // MARK: - Glass without AGSL (Android 8 to 12)

    /*
     * The same recipe as the GLASS and LIQUID shaders, with plain Canvas drawing: each piece is one anti-aliased rounded
     * rect (no layers, no clip masks, so it stays cheap on old GPUs like the Mali-450). In order: the background seen
     * through the glass (the photo's small blurred copy as a BitmapShader placed on the screen, its colors lifted by a
     * ColorMatrix as the shaders do), then the tint, the lift and the light from above (a little brighter at the top, a
     * little darker at the bottom) merged into one gradient (one draw instead of three: the GPU of these TVs is short of
     * fill rate, and this glass is drawn on every frame the shelf or the top buttons move), and the rim: a thin highlight, strongest at the top where it faces the light, with a faint echo at
     * the bottom; the liquid glass also gets a soft inner halo. What it can't do is bend the picture at the edge
     * (refraction): that needs a per-pixel shader.
     */
    private boolean old;
    private Bitmap oldFrost, oldGlassA, oldGlassB;
    private final Paint oldFrostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint oldPaintA = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint oldPaintB = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint oldShade = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint oldRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix oldMatrix = new Matrix();
    private final RectF oldRect = new RectF();
    private float oldMix, offX, offY;
    // Gradients from 0 to 1 down the shape, placed on each shape with a local matrix (no allocation per frame).
    private LinearGradient glassRim, liquidRim, liquidHalo;
    /** The merged tint + lift + light gradients, by (liquid, tint, tint alpha, lift): a few per palette. */
    private final android.util.LongSparseArray<LinearGradient> oldFills = new android.util.LongSparseArray<>();
    private static final float[] GLASS_STOPS = {0, 0.45f, 0.8f, 1}, LIQUID_STOPS = {0, 0.42f, 0.55f, 1};
    private static final float[] TVOS_STOPS = {0, 0.22f, 0.5f, 0.78f, 1};
    // Light from above: GLASS's sheen (0.06 at the top) and its darker bottom edge; LIQUID's +0.045 over the top half
    // and its 12% darker bottom.
    private static final int[] GLASS_LIGHT = {0x0FFFFFFF, 0x00FFFFFF, 0x00000000, 0x14000000};
    private static final int[] LIQUID_LIGHT = {0x0BFFFFFF, 0x00FFFFFF, 0x00000000, 0x1F000000};

    private void initOldGlass() {
        old = true;
        // GLASS: frost * 1.12 + 0.02.
        oldFrostPaint.setColorFilter(new ColorMatrixColorFilter(new float[] {
            1.12f, 0, 0, 0, 5.1f,
            0, 1.12f, 0, 0, 5.1f,
            0, 0, 1.12f, 0, 5.1f,
            0, 0, 0, 1, 0}));
        // LIQUID: saturation 1.18, then * 1.06.
        ColorMatrix vivid = new ColorMatrix();
        vivid.setSaturation(1.18f);
        ColorMatrix gain = new ColorMatrix();
        gain.setScale(1.06f, 1.06f, 1.06f, 1);
        vivid.postConcat(gain);
        ColorMatrixColorFilter liquidColors = new ColorMatrixColorFilter(vivid);
        oldPaintA.setColorFilter(liquidColors);
        oldPaintB.setColorFilter(liquidColors);
        oldRim.setStyle(Paint.Style.STROKE);
        // Rims, from the top (facing the light) to the bottom: GLASS 0.58, 0.23 on the sides, 0.08; LIQUID (times
        // `specular`) 0.92, 0.22 on the sides and an echo of 0.58 at the bottom, and its halo (0.12 at the top).
        glassRim = unit(new int[] {0x94FFFFFF, 0x3BFFFFFF, 0x3BFFFFFF, 0x14FFFFFF}, new float[] {0, 0.3f, 0.7f, 1});
        // Liquid Glass rims as on tvOS: lit from the top left, so the rim runs diagonally from bright (top left) through
        // almost nothing to an echo at the bottom right; inside it, a wider, fainter sheen with the same light.
        liquidRim = new LinearGradient(0, 0, 1, 1, new int[] {0xD9FFFFFF, 0x40FFFFFF, 0x14FFFFFF, 0x26FFFFFF, 0x8CFFFFFF},
                TVOS_STOPS, Shader.TileMode.CLAMP);
        liquidHalo = new LinearGradient(0, 0, 1, 1, new int[] {0x38FFFFFF, 0x10FFFFFF, 0x00FFFFFF, 0x0AFFFFFF, 0x22FFFFFF},
                TVOS_STOPS, Shader.TileMode.CLAMP);
    }

    private static LinearGradient unit(int[] colors, float[] stops) {
        return new LinearGradient(0, 0, 0, 1, colors, stops, Shader.TileMode.CLAMP);
    }

    /** The tint, then the lift (white), then the light from above, composited into one translucent gradient. */
    private LinearGradient glassFill(int tint, float tintAlpha, float lift, boolean liquid) {
        int ta = Math.round(Ui.clamp(tintAlpha) * 255), la = Math.round(Ui.clamp(lift) * 255);
        long key = (liquid ? 1L << 48 : 0) | ((long) (tint & 0xFFFFFF) << 16) | ((long) ta << 8) | la;
        LinearGradient g = oldFills.get(key);
        if (g != null) return g;
        if (oldFills.size() > 32) oldFills.clear();
        int[] light = liquid ? LIQUID_LIGHT : GLASS_LIGHT, colors = new int[light.length];
        int base = over(Ui.alpha(tint | 0xFF000000, ta / 255f), Ui.alpha(0xFFFFFFFF, la / 255f));
        for (int i = 0; i < light.length; i++) colors[i] = over(base, light[i]);
        g = unit(colors, liquid ? LIQUID_STOPS : GLASS_STOPS);
        oldFills.put(key, g);
        return g;
    }

    /** `top` over `bottom` (both translucent, source-over) as one translucent color. */
    private static int over(int bottom, int top) {
        float ab = (bottom >>> 24) / 255f, at = (top >>> 24) / 255f, a = at + ab * (1 - at);
        if (a <= 0) return 0;
        int out = Math.round(a * 255) << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float cb = ((bottom >> shift) & 255) / 255f, ct = ((top >> shift) & 255) / 255f;
            out |= Math.round(Ui.clamp((ct * at + cb * ab * (1 - at)) / a) * 255) << shift;
        }
        return out;
    }

    /** The images cover the whole screen, seen from a layer that may be shifted by (offX, offY). */
    private void placeImages() {
        if (!old) return;
        place(oldFrostPaint, oldFrost);
        place(oldPaintA, oldGlassA);
        place(oldPaintB, oldGlassB);
    }

    private void place(Paint p, Bitmap b) {
        Shader s = p.getShader();
        if (s == null || b == null) return;
        oldMatrix.setScale((float) w / b.getWidth(), (float) h / b.getHeight());
        oldMatrix.postTranslate(-offX, -offY);
        s.setLocalMatrix(oldMatrix);
    }

    /** image (and imageB, blended in by mixB): what the glass shows; null = nothing behind, a flat tint. */
    private void oldGlass(Canvas c, RectF r, float radius, Paint image, Paint imageB, float mixB, int tint, float tintAlpha,
                          float lift, boolean liquid, LinearGradient rim, float rimAlpha, LinearGradient halo,
                          float haloAlpha, float body, float alpha) {
        alpha = Math.min(1, alpha);
        float rad = Math.max(0, Math.min(radius, Math.min(r.width(), r.height()) / 2));
        if (body > 0) {
            int a = Math.round(255 * alpha);
            if (image != null) {
                image.setAlpha(a);
                c.drawRoundRect(r, rad, rad, image);
                if (imageB != null && mixB > 0.004f) {
                    imageB.setAlpha(Math.round(a * mixB));
                    c.drawRoundRect(r, rad, rad, imageB);
                }
                if (liquid && lensShift > 0.5f && lensBand > 1) {
                    lens(c, r, rad, image, a, 0.42f, 1f, 0.62f);
                    lens(c, r, rad, image, a, 1f, 0.5f, 0.9f);
                    if (imageB != null && mixB > 0.004f) {
                        lens(c, r, rad, imageB, Math.round(a * mixB), 0.42f, 1f, 0.62f);
                        lens(c, r, rad, imageB, Math.round(a * mixB), 1f, 0.5f, 0.9f);
                    }
                }
            } else {
                tintAlpha = Math.max(0.35f, tintAlpha);     // nothing to show through: the old flat glass
            }
            LinearGradient g = glassFill(tint, tintAlpha, lift, liquid);
            vertical(g, r);
            oldShade.setShader(g);
            oldShade.setAlpha(a);
            c.drawRoundRect(r, rad, rad, oldShade);
        }
        float px = Math.max(1, h / 1080f);
        if (halo != null) edge(c, r, rad, halo, haloAlpha * alpha, (liquid ? 5.2f : 7) * px);
        edge(c, r, rad, rim, rimAlpha * alpha, Math.max(1.5f, (liquid ? 2.4f : 2) * px));
    }

    /**
     * The edge of Liquid Glass without a per-pixel shader: along the rim, the picture behind is drawn again magnified
     * around the shape's center, so near the edge it is pulled in from beyond it, as the bevel of a lens bends it (the
     * LIQUID shader's refraction). Two passes give the soft falloff: a wide, gentle one, then a narrow, strong one at
     * the very edge. Each is one stroked rounded rect with the photo's shader under a canvas scale: no layers or masks.
     * strength: share of the full shift; width: share of the band; opacity: of this pass.
     */
    private void lens(Canvas c, RectF r, float rad, Paint image, int alpha, float strength, float width, float opacity) {
        float hw = r.width() / 2, hh = r.height() / 2, cx = r.centerX(), cy = r.centerY();
        float band = Math.min(lensBand * width, Math.min(hw, hh) * 0.9f);
        float shift = lensShift * strength;
        if (band <= 1 || shift <= 0.3f) return;
        float zx = hw / Math.max(1, hw - shift), zy = hh / Math.max(1, hh - shift);
        float in = band / 2;
        c.save();
        c.scale(zx, zy, cx, cy);
        oldRect.set(cx + (r.left + in - cx) / zx, cy + (r.top + in - cy) / zy,
                cx + (r.right - in - cx) / zx, cy + (r.bottom - in - cy) / zy);
        Paint.Style style = image.getStyle();
        float stroke = image.getStrokeWidth();
        image.setStyle(Paint.Style.STROKE);
        image.setStrokeWidth(band * 2 / (zx + zy));
        image.setAlpha(Math.round(alpha * opacity));
        float rr = Math.max(0, rad - in);
        c.drawRoundRect(oldRect, rr / zx, rr / zy, image);
        image.setStyle(style);
        image.setStrokeWidth(stroke);
        image.setAlpha(alpha);
        c.restore();
    }

    private float lensBand, lensShift;

    /** A rim stroke just inside the shape's edge. */
    private void edge(Canvas c, RectF r, float rad, LinearGradient g, float alpha, float width) {
        if (alpha <= 0.004f) return;
        float in = width / 2;
        oldRect.set(r.left + in, r.top + in, r.right - in, r.bottom - in);
        if (oldRect.width() <= 0 || oldRect.height() <= 0) return;
        if (g == liquidRim || g == liquidHalo) {
            oldMatrix.setScale(Math.max(1, r.width()), Math.max(1, r.height()));
            oldMatrix.postTranslate(r.left, r.top);
            g.setLocalMatrix(oldMatrix);
        } else {
            vertical(g, r);
        }
        oldRim.setShader(g);
        oldRim.setStrokeWidth(width);
        oldRim.setAlpha(Math.round(255 * Math.min(1, alpha)));
        float rr = Math.max(0, rad - in);
        c.drawRoundRect(oldRect, rr, rr, oldRim);
    }

    /** Stretches a unit gradient over the shape's height. */
    private void vertical(LinearGradient g, RectF r) {
        oldMatrix.setScale(1, Math.max(1, r.height()));
        oldMatrix.postTranslate(0, r.top);
        g.setLocalMatrix(oldMatrix);
    }
}
