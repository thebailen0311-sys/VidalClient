package com.trace.refract.render;

import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2fStack;

/**
 * Refract's 2D renderer. Callers use GUI units (floats); inside a {@link #begin}/{@link #end} bracket
 * everything is snapped to real physical pixels, so corners, text and edges are crisp and anti-aliased
 * at every GUI scale. Shapes are built from generated alpha masks plus solid fills.
 */
public final class Gfx {
    private static DrawContext ctx;
    private static float s = 2f;
    private static int depth;
    /** Global opacity multiplier for fade animations. */
    public static float alpha = 1f;

    private Gfx() {}

    public static void begin(DrawContext context) {
        if (depth++ > 0) return;
        ctx = context;
        CLIP.clear();
        s = Math.max(1, MinecraftClient.getInstance().getWindow().getScaleFactor());
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(1f / s, 1f / s);
    }

    public static void end() {
        if (--depth > 0) return;
        ctx.getMatrices().popMatrix();
        ctx = null;
        alpha = 1f;
    }

    public static float scale() { return s; }

    private static final float[] PLACE_STACK = new float[32];
    private static int placeDepth;

    /**
     * Draws what follows so that local point (ox, oy) lands on screen point (tx, ty), scaled by k around it. The
     * scale goes into the pixel ratio (not a texture stretch), so text and edges stay sharp at any size.
     */
    public static void pushPlace(float ox, float oy, float tx, float ty, float k) {
        PLACE_STACK[placeDepth++] = s;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(Math.round(tx * s - ox * s * k), Math.round(ty * s - oy * s * k));
        s = s * k;
    }

    public static void popPlace() {
        s = PLACE_STACK[--placeDepth];
        ctx.getMatrices().popMatrix();
    }

    private static boolean measuring;
    private static float mx0, my0, mx1, my1;

    /** Starts recording the bounds (local units) of every panel drawn until {@link #endMeasure}. */
    public static void beginMeasure() { measuring = true; mx0 = my0 = Float.MAX_VALUE; mx1 = my1 = -Float.MAX_VALUE; }

    /** Bounds drawn since {@link #beginMeasure} as {x0, y0, x1, y1}, or null when nothing was drawn. */
    public static float[] endMeasure() {
        measuring = false;
        return mx1 < mx0 ? null : new float[]{mx0, my0, mx1, my1};
    }

    private static void measure(float x, float y, float w, float h) {
        if (!measuring || w <= 0 || h <= 0) return;
        mx0 = Math.min(mx0, x); my0 = Math.min(my0, y); mx1 = Math.max(mx1, x + w); my1 = Math.max(my1, y + h);
    }

    /**
     * Starts a new GUI layer. The game batches quads by texture inside one layer, which can draw an earlier
     * element's text over a later panel; anything that must sit on top (drawers, tooltips) starts its own layer.
     */
    public static void newLayer() { ctx.createNewRootLayer(); }

    public static Matrix3x2fStack matrices() { return ctx.getMatrices(); }

    static int px(float v) { return Math.round(v * s); }

    private static int c(int color) { return alpha >= 0.999f ? color : Colors.mulAlpha(color, alpha); }

    private static boolean skip(int color) { return Colors.a(color) == 0 || alpha <= 0.002f; }

    // ------------------------------------------------------------------ rects

    public static void rect(float x, float y, float w, float h, int color) {
        if (skip(color) || w <= 0 || h <= 0) return;
        int x1 = px(x), y1 = px(y), x2 = px(x + w), y2 = px(y + h);
        if (x2 <= x1) x2 = x1 + 1;
        if (y2 <= y1) y2 = y1 + 1;
        ctx.fill(x1, y1, x2, y2, c(color));
    }

    /** A line exactly {@code physical} device pixels thick (hairlines stay hairlines at any scale). */
    public static void hline(float x, float y, float w, int physical, int color) {
        if (skip(color)) return;
        int x1 = px(x), y1 = px(y);
        ctx.fill(x1, y1, px(x + w), y1 + physical, c(color));
    }

    public static void vline(float x, float y, float h, int physical, int color) {
        if (skip(color)) return;
        int x1 = px(x), y1 = px(y);
        ctx.fill(x1, y1, x1 + physical, px(y + h), c(color));
    }

    public static void gradientV(float x, float y, float w, float h, int top, int bottom) {
        if ((skip(top) && skip(bottom)) || w <= 0 || h <= 0) return;
        ctx.fillGradient(px(x), px(y), px(x + w), px(y + h), c(top), c(bottom));
    }

    public static void gradientH(float x, float y, float w, float h, int left, int right) {
        int x1 = px(x), x2 = px(x + w), y1 = px(y), y2 = px(y + h);
        int n = x2 - x1;
        if (n <= 0) return;
        int step = Math.max(1, n / 16);
        for (int i = 0; i < n; i += step) {
            int col = Colors.lerp(left, right, (i + step * 0.5f) / n);
            if (!skip(col)) ctx.fill(x1 + i, y1, Math.min(x2, x1 + i + step), y2, c(col));
        }
    }

    // ------------------------------------------------------------------ masks

    private static void mask(Tex.Mask m, int x, int y, int w, int h, float u, float v, int rw, int rh, int color) {
        if (skip(color) || w <= 0 || h <= 0) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, m.id(), x, y, u, v, w, h, rw, rh, m.w(), m.h(), c(color));
    }

    public static void round(float x, float y, float w, float h, float r, int color) { roundGradient(x, y, w, h, r, color, color); }

    public static void roundGradient(float x, float y, float w, float h, float r, int top, int bottom) {
        measure(x, y, w, h);
        int X = px(x), Y = px(y), X2 = px(x + w), Y2 = px(y + h), W = X2 - X, H = Y2 - Y;
        if (W <= 0 || H <= 0) return;
        int R = Math.min(px(r), Math.min(W, H) / 2);
        if (R <= 0) { ctx.fillGradient(X, Y, X2, Y2, c(top), c(bottom)); return; }
        Tex.Mask m = Tex.circle(R);
        int ct = Colors.lerp(top, bottom, R * 0.5f / H), cb = Colors.lerp(top, bottom, (H - R * 0.5f) / H);
        mask(m, X, Y, R, R, 0, 0, R, R, ct);
        mask(m, X2 - R, Y, R, R, R, 0, R, R, ct);
        mask(m, X, Y2 - R, R, R, 0, R, R, R, cb);
        mask(m, X2 - R, Y2 - R, R, R, R, R, R, R, cb);
        int at = Colors.lerp(top, bottom, (float) R / H), ab = Colors.lerp(top, bottom, (float) (H - R) / H);
        if (X2 - R > X + R) {
            if (!(skip(top) && skip(at))) ctx.fillGradient(X + R, Y, X2 - R, Y + R, c(top), c(at));
            if (!(skip(ab) && skip(bottom))) ctx.fillGradient(X + R, Y2 - R, X2 - R, Y2, c(ab), c(bottom));
        }
        if (Y2 - R > Y + R && !(skip(at) && skip(ab))) ctx.fillGradient(X, Y + R, X2, Y2 - R, c(at), c(ab));
    }

    /** Rounded outline {@code t} physical pixels thick, colour fading top to bottom (glass rim light). */
    public static void outline(float x, float y, float w, float h, float r, int t, int top, int bottom) {
        int X = px(x), Y = px(y), X2 = px(x + w), Y2 = px(y + h), W = X2 - X, H = Y2 - Y;
        if (W <= 2 * t || H <= 2 * t) return;
        int R = Math.max(t, Math.min(px(r), Math.min(W, H) / 2));
        Tex.Mask m = Tex.ring(R, t);
        int ct = Colors.lerp(top, bottom, R * 0.5f / H), cb = Colors.lerp(top, bottom, (H - R * 0.5f) / H);
        mask(m, X, Y, R, R, 0, 0, R, R, ct);
        mask(m, X2 - R, Y, R, R, R, 0, R, R, ct);
        mask(m, X, Y2 - R, R, R, 0, R, R, R, cb);
        mask(m, X2 - R, Y2 - R, R, R, R, R, R, R, cb);
        if (X2 - R > X + R) {
            if (!skip(top)) ctx.fill(X + R, Y, X2 - R, Y + t, c(top));
            if (!skip(bottom)) ctx.fill(X + R, Y2 - t, X2 - R, Y2, c(bottom));
        }
        int at = Colors.lerp(top, bottom, (float) R / H), ab = Colors.lerp(top, bottom, (float) (H - R) / H);
        if (Y2 - R > Y + R && !(skip(at) && skip(ab))) {
            ctx.fillGradient(X, Y + R, X + t, Y2 - R, c(at), c(ab));
            ctx.fillGradient(X2 - t, Y + R, X2, Y2 - R, c(at), c(ab));
        }
    }

    /** Soft shadow around (never under) a rounded rect. */
    public static void shadow(float x, float y, float w, float h, float r, float blur, int color) {
        int X = px(x), Y = px(y), X2 = px(x + w), Y2 = px(y + h), W = X2 - X, H = Y2 - Y;
        if (W <= 0 || H <= 0 || skip(color)) return;
        int R = Math.min(px(r), Math.min(W, H) / 2), B = Math.max(1, px(blur));
        Tex.Mask m = Tex.shadow(R, B);
        int cc = R + B;
        int OX = X - B, OY = Y - B, OX2 = X2 + B, OY2 = Y2 + B;
        mask(m, OX, OY, cc, cc, 0, 0, cc, cc, color);
        mask(m, OX2 - cc, OY, cc, cc, cc + 1, 0, cc, cc, color);
        mask(m, OX, OY2 - cc, cc, cc, 0, cc + 1, cc, cc, color);
        mask(m, OX2 - cc, OY2 - cc, cc, cc, cc + 1, cc + 1, cc, cc, color);
        int lw = (OX2 - cc) - (OX + cc), lh = (OY2 - cc) - (OY + cc);
        if (lw > 0) {
            mask(m, OX + cc, OY, lw, cc, cc, 0, 1, cc, color);
            mask(m, OX + cc, OY2 - cc, lw, cc, cc, cc + 1, 1, cc, color);
        }
        if (lh > 0) {
            mask(m, OX, OY + cc, cc, lh, 0, cc, cc, 1, color);
            mask(m, OX2 - cc, OY + cc, cc, lh, cc + 1, cc, cc, 1, color);
        }
    }

    public static void circle(float cx, float cy, float r, int color) {
        int R = Math.max(1, px(r));
        mask(Tex.circle(R), px(cx) - R, px(cy) - R, 2 * R, 2 * R, 0, 0, 2 * R, 2 * R, color);
    }

    public static void ring(float cx, float cy, float r, int t, int color) {
        int R = Math.max(t + 1, px(r));
        mask(Tex.ring(R, t), px(cx) - R, px(cy) - R, 2 * R, 2 * R, 0, 0, 2 * R, 2 * R, color);
    }

    /** Soft radial light (scaled texture, linear filtering). */
    public static void glow(float cx, float cy, float radius, int color) {
        int R = Math.max(1, px(radius));
        Tex.Mask m = Tex.glow();
        mask(m, px(cx) - R, px(cy) - R, 2 * R, 2 * R, 0, 0, m.w(), m.h(), color);
    }

    /** Vertical soft light band, used for the moving sheen across glass. */
    public static void band(float cx, float y, float halfWidth, float h, int color) {
        Tex.Mask m = Tex.band();
        int W = Math.max(2, px(halfWidth * 2));
        mask(m, px(cx) - W / 2, px(y), W, px(h), 0, 0, m.w(), m.h(), color);
    }

    /** Smooth arrowhead pointing along {@code angle} (radians, screen space). */
    public static void arrow(float cx, float cy, float size, float angle, int color) {
        if (skip(color)) return;
        Tex.Mask m = Tex.arrow();
        int S = Math.max(4, px(size));
        Matrix3x2fStack ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.translate(px(cx), px(cy));
        ms.rotate(angle);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, m.id(), -S / 2, -S / 2, 0, 0, S, S, m.w(), m.h(), m.w(), m.h(), c(color));
        ms.popMatrix();
    }

    // ------------------------------------------------------------------ scissor

    private static final java.util.ArrayDeque<net.minecraft.client.gui.ScreenRect> CLIP = new java.util.ArrayDeque<>();

    public static void scissor(float x, float y, float w, float h) {
        int x1 = px(x), y1 = px(y), x2 = px(x + w), y2 = px(y + h);
        ctx.enableScissor(x1, y1, x2, y2);
        var r = new net.minecraft.client.gui.ScreenRect(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1)).transform(ctx.getMatrices());
        var top = CLIP.peek();
        if (top != null) { r = top.intersection(r); if (r == null) r = new net.minecraft.client.gui.ScreenRect(0, 0, 0, 0); }
        CLIP.push(r);
    }

    public static void unscissor() { ctx.disableScissor(); if (!CLIP.isEmpty()) CLIP.pop(); }

    // ------------------------------------------------------------------ text

    public static final float SMALL = 7f, BODY = 8.5f, LARGE = 11f, TITLE = 14f;

    private static Font.Atlas atlas(float size, Font.Weight w) { return Font.atlas(w, Math.round(size * s)); }

    public static float width(String str, float size, Font.Weight w) {
        Font.Atlas a = atlas(size, w);
        float sum = 0;
        for (int i = 0; i < str.length(); i++) sum += a.advance(str.charAt(i));
        return sum / s;
    }

    public static float width(String str, float size) { return width(str, size, Font.Weight.REGULAR); }

    public static float lineHeight(float size) { return atlas(size, Font.Weight.REGULAR).lineHeight / s; }

    /** Draws text with its line box top at y. Returns the advance width in GUI units. */
    public static float text(String str, float x, float y, float size, Font.Weight w, int color) {
        if (skip(color) || str.isEmpty()) return width(str, size, w);
        Font.Atlas a = atlas(size, w);
        float pen = x * s;
        int top = px(y);
        int col = c(color);
        // The whole line goes out as one GUI element (see TextRun): far less per-frame work than one per glyph.
        int n = str.length(), k = 0;
        int[] rect = new int[n * 4];
        float[] uv = new float[n * 4];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, y1 = top - a.pad, y2 = y1;
        for (int i = 0; i < n; i++) {
            char ch = str.charAt(i);
            int[] g = a.glyph(ch);
            if (g != null && ch != ' ') {
                int gx = Math.round(pen) - a.pad;
                rect[k * 4] = gx; rect[k * 4 + 1] = y1; rect[k * 4 + 2] = gx + g[2]; rect[k * 4 + 3] = y1 + g[3];
                uv[k * 4] = (float) g[0] / a.texW; uv[k * 4 + 1] = (float) g[1] / a.texH;
                uv[k * 4 + 2] = (float) (g[0] + g[2]) / a.texW; uv[k * 4 + 3] = (float) (g[1] + g[3]) / a.texH;
                minX = Math.min(minX, gx); maxX = Math.max(maxX, gx + g[2]); y2 = Math.max(y2, y1 + g[3]);
                k++;
            }
            pen += a.advance(ch);
        }
        if (k > 0) {
            if (a.setup == null) {
                var tex = MinecraftClient.getInstance().getTextureManager().getTexture(a.id);
                a.setup = net.minecraft.client.texture.TextureSetup.of(tex.getGlTextureView(), tex.getSampler());
            }
            var pose = new org.joml.Matrix3x2f(ctx.getMatrices());
            var clip = CLIP.peek();
            var bounds = new net.minecraft.client.gui.ScreenRect(minX, y1, maxX - minX, y2 - y1).transformEachVertex(pose);
            if (clip != null) bounds = clip.intersection(bounds);
            if (bounds != null)
                ((com.trace.refract.mixin.DrawContextAccessor) ctx).refract$state().addSimpleElement(new TextRun(a.setup, pose, rect, uv, k, col, clip, bounds));
        }
        return pen / s - x;
    }

    public static float text(String str, float x, float y, float size, int color) { return text(str, x, y, size, Font.Weight.REGULAR, color); }

    public static float bold(String str, float x, float y, float size, int color) { return text(str, x, y, size, Font.Weight.BOLD, color); }

    /** Text with a soft dark under-copy for legibility over the world. */
    public static float textShadow(String str, float x, float y, float size, Font.Weight w, int color) {
        float off = 1f / s;
        text(str, x + off, y + off, size, w, Colors.withAlpha(0x000000, (int) (Colors.a(color) * 0.55f)));
        return text(str, x, y, size, w, color);
    }

    public static void textCentered(String str, float cx, float y, float size, Font.Weight w, int color) {
        text(str, cx - width(str, size, w) / 2f, y, size, w, color);
    }

    /** Text drawn per glyph along an accent -> second-accent gradient. {@code phase} scrolls the gradient. */
    public static float gradientText(Theme t, String str, float x, float y, float size, Font.Weight w, float phase) {
        float pen = x;
        int n = Math.max(1, str.length() - 1);
        for (int i = 0; i < str.length(); i++) {
            float u = i / (float) n;
            float s = 0.5f + 0.5f * (float) Math.sin((u + phase) * Math.PI * 2);
            pen += text(String.valueOf(str.charAt(i)), pen, y, size, w, Colors.lerp(t.accent(), t.accent2(), s));
        }
        return pen - x;
    }

    public static String ellipsize(String str, float maxW, float size, Font.Weight w) {
        if (width(str, size, w) <= maxW) return str;
        // Binary search for the longest prefix that fits with the ellipsis (was one measure per removed character).
        int lo = 1, hi = str.length() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width(str.substring(0, mid) + "…", size, w) <= maxW) lo = mid; else hi = mid - 1;
        }
        return str.substring(0, lo) + "…";
    }

    // ------------------------------------------------------------------ acrylic

    /** Rounded rect with a horizontal gradient (left to right). */
    public static void roundGradientH(float x, float y, float w, float h, float r, int left, int right) {
        measure(x, y, w, h);
        int X = px(x), Y = px(y), X2 = px(x + w), Y2 = px(y + h), W = X2 - X, H = Y2 - Y;
        if (W <= 0 || H <= 0) return;
        int R = Math.min(px(r), Math.min(W, H) / 2);
        Tex.Mask m = Tex.circle(Math.max(1, R));
        if (R > 0) {
            mask(m, X, Y, R, R, 0, 0, R, R, left);
            mask(m, X, Y2 - R, R, R, 0, R, R, R, left);
            mask(m, X2 - R, Y, R, R, R, 0, R, R, right);
            mask(m, X2 - R, Y2 - R, R, R, R, R, R, R, right);
            if (Y2 - R > Y + R) {
                if (!skip(left)) ctx.fill(X, Y + R, X + R, Y2 - R, c(left));
                if (!skip(right)) ctx.fill(X2 - R, Y + R, X2, Y2 - R, c(right));
            }
        }
        int n = (X2 - R) - (X + R);
        if (n <= 0) return;
        int step = Math.max(1, n / 14);
        for (int i = 0; i < n; i += step) {
            int col = Colors.lerp(left, right, (R + i + step * 0.5f) / W);
            if (!skip(col)) ctx.fill(X + R + i, Y, Math.min(X2 - R, X + R + i + step), Y2, c(col));
        }
    }

    /** Film grain over the inner area of a rounded rect (kept inside the curved corners). */
    public static void grain(float x, float y, float w, float h, float r, int color) {
        if (skip(color)) return;
        Tex.Mask m = Tex.noise();
        float in = r * 0.3f;
        int X = px(x + in), Y = px(y + in), X2 = px(x + w - in), Y2 = px(y + h - in);
        if (X2 <= X || Y2 <= Y) return;
        // Repeating sampler: a region larger than the texture tiles it, 1 texel per device pixel.
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, m.id(), X, Y, X % m.w(), Y % m.h(), X2 - X, Y2 - Y, X2 - X, Y2 - Y, m.w(), m.h(), c(color));
    }

    /**
     * Main window surface: one soft drop shadow, a flat opaque fill and a hairline border. Deliberately plain:
     * no grain, gradients or blur, so it reads cleanly and costs only a handful of draws.
     */
    public static void acrylic(Theme t, float x, float y, float w, float h, float r) {
        shadow(x, y, w, h, r, 18, 0x66000000);
        round(x, y, w, h, r, t.glassBottom());
        outline(x, y, w, h, r, 1, t.rim(), t.rim());
    }

    /** True when the Liquid Glass style is selected (otherwise Acrylic). */
    public static boolean liquid() {
        var cg = com.trace.refract.module.ModuleManager.clickGui;
        return cg == null || cg.glass.is("Liquid Glass");
    }

    /**
     * Glass body (no drop shadow), in the selected style:
     * <ul>
     *   <li><b>Liquid Glass</b>: a clear lens. Light tint, a bright specular band across the top, light pooling
     *       along the bottom edge, a bevelled double rim (bright top, shaded bottom) and a faint accent-to-second-accent
     *       dispersion on the edge, like light splitting at the rim of real glass.</li>
     *   <li><b>Acrylic</b>: frosted and tinted. Denser body, fine frost grain, a soft luminosity layer and a hairline.</li>
     * </ul>
     * {@code density} 0..1 sets how much of what is behind shows through (1 = nearly solid).
     */
    public static void glassBody(Theme t, float x, float y, float w, float h, float r, float density) {
        boolean liq = liquid();
        // The glass itself runs on the GPU (liquid_glass.fsh): shape, lens, colour fringes, reflection and rim light.
        float bend = Math.max(5 * s, Math.min(24 * s, Math.min(w, h) * s * 0.12f));
        Glass.panel(ctx, CLIP, x * s, y * s, w * s, h * s, r * s, liq ? bend : bend * 0.5f, liq ? 0 : 1, alpha);
        if (liq) {
            // Clear for the GUI's floating glass, shaded for HUD panels over the busy world (density near 1).
            int a = (int) (0x04 + 0xB8 * density * density);
            roundGradient(x, y, w, h, r, Colors.withAlpha(t.glassTop(), Math.max(0, a - 0x08)), Colors.withAlpha(t.glassBottom(), a));
            float in = 1.25f / s;
            outline(x - in, y - in, w + 2 * in, h + 2 * in, r + in, 1, 0x14000000, 0x2A000000);
        } else {
            // Frosted glass is light and milky, not a dark acrylic slab: a pale wash, a little theme tint for text.
            int a = (int) (0x10 + 0x90 * density * density);
            roundGradient(x, y, w, h, r, 0x16FFFFFF, 0x08FFFFFF);
            roundGradient(x, y, w, h, r, Colors.withAlpha(t.glassTop(), a), Colors.withAlpha(t.glassBottom(), Math.min(255, a + 6)));
            outline(x, y, w, h, r, 1, 0x50FFFFFF, 0x14FFFFFF);
        }
    }

    /** A batch of soft additive lights (glow.fsh): {x, y, radius, colour bits} per light, in GUI units. One element. */
    public static void lights(float[] data, int count) {
        if (count <= 0) return;
        for (int i = 0; i < count; i++) { data[i * 4] *= s; data[i * 4 + 1] *= s; data[i * 4 + 2] *= s; }
        Glass.glows(ctx, data, count);
    }

    /** Full-screen post-processing (postfx.fsh); sizes in GUI units. */
    public static void post(float w, float h, float bloom, float saturation, float vignette, float grade, int preset, float hue) {
        post(ctx, w, h, bloom, saturation, vignette, grade, preset, hue);
    }

    /**
     * Same pass with an explicit context. HUD elements are not drawn between {@code begin()}/{@code end()}, so the
     * static context is null there — the caller has to hand over the one it was given.
     */
    public static void post(net.minecraft.client.gui.DrawContext dc, float w, float h, float bloom, float saturation, float vignette, float grade, int preset, float hue) {
        if (dc == null) return;
        Glass.post(dc, w * s, h * s, bloom, saturation, vignette, grade, preset, hue);
    }

    /** Space sky over the sky pixels (skyfx.fsh); scene 1 planets, 2 black hole, 3 nebula, 4 galaxy. Sizes in GUI units. */
    public static void sky(float w, float h, int scene) { Glass.sky(ctx, s, w, h, scene); }

    /** Full-screen shader rain (rainfx.fsh); sizes in GUI units. */
    public static void rain(float w, float h, float k, float lean) { Glass.rain(ctx, w * s, h * s, k, lean); }

    /** One soft additive light (glow.fsh). */
    public static void light(float cx, float cy, float radius, int color) {
        lights(new float[]{cx, cy, radius, Float.intBitsToFloat(c(color))}, 1);
    }

    /** Main window: halo, shadow and the glass body in the selected style (the blurred world shows through). */
    public static void liquidGlass(Theme t, float x, float y, float w, float h, float r, float time) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 1.4);
        shadow(x, y, w, h, r, 26, Colors.withAlpha(t.accent(), (int) (0x14 + 0x0E * pulse)));
        shadow(x, y, w, h, r, 14, 0x60000000);
        glassBody(t, x, y, w, h, r, liquid() ? 0.25f : 0.2f);
        outline(x, y, w, h, r, 1, Colors.withAlpha(t.accent(), (int) (0x10 + 0x14 * pulse)), Colors.withAlpha(t.accent2(), 0x08));
    }

    /**
     * Panel shared by every Refract surface (HUD tables, pills, toasts, inspector, labels): soft drop shadow plus
     * the glass body in the selected style. Dense enough to read over the world, fully rounded, static.
     */
    public static void frost(Theme t, float x, float y, float w, float h, float r, float lift) {
        if (lift > 0.01f) shadow(x, y + 1, w, h, r, 4 + 6 * lift, Colors.withAlpha(0x000000, (int) (0x30 + 0x20 * lift)));
        glassBody(t, x, y, w, h, r, 0.82f);
    }

    /** Set once per HUD frame from the HUD module; see {@code Hud.fastPanels}. */
    public static boolean fastPanels;

    /** HUD panel (tables, pills, toasts): the shared frosted-glass recipe. */
    public static void hudGlass(Theme t, float x, float y, float w, float h, float r) {
        if (fastPanels) {
            // One tinted rounded rect and a hairline instead of the lens shader. Every HUD panel used to cost a
            // full shader pass; with a findings table, toasts, labels and module list that is a lot of fill rate.
            roundGradient(x, y, w, h, r, Colors.withAlpha(t.glassTop(), 0xD8), Colors.withAlpha(t.glassBottom(), 0xE6));
            outline(x, y, w, h, r, 1, 0x2EFFFFFF, 0x18000000);
            return;
        }
        frost(t, x, y, w, h, r, 0.4f);
    }

    /** A frosted panel with an accent gradient rim and soft accent glow, for something active or focused. */
    public static void accentGlass(Theme t, float x, float y, float w, float h, float r, float glow) {
        if (glow > 0.01f) shadow(x, y, w, h, r, 9, Colors.withAlpha(t.accent(), (int) (0x40 * glow)));
        frost(t, x, y, w, h, r, 0.4f);
        roundGradientH(x, y, w, h, r, Colors.withAlpha(t.accent(), 0x18), Colors.withAlpha(t.accent2(), 0x04));
        outline(x, y, w, h, r, 1, Colors.withAlpha(t.accent(), 0xC0), Colors.withAlpha(t.accent2(), 0x80));
    }

    /** Rounded vertical bar in the theme gradient (section markers, active rows). */
    public static void accentBar(Theme t, float x, float y, float w, float h) {
        roundGradient(x, y, w, h, w / 2, t.accent(), t.accent2());
    }

    /**
     * The Vanta mark: a faceted prism gem. An accent-to-second-accent diamond with a darker cut through its lower
     * half, a bright crown facet and a white glint, on a soft accent glow. Vector shapes only, sharp at any size.
     */
    public static void logo(Theme t, float x, float y, float size) {
        float cx = x + size / 2, cy = y + size / 2, r = size / 2;
        glow(cx, cy, size * 0.95f, Colors.withAlpha(t.accent(), 0x55));
        diamond(cx, cy, r, t.accent(), t.accent2());
        diamond(cx, cy + r * 0.28f, r * 0.62f, Colors.withAlpha(Colors.shade(t.accent2(), -0.35f), 0xE0), Colors.withAlpha(Colors.shade(t.accent(), -0.45f), 0xE0));
        diamond(cx, cy - r * 0.36f, r * 0.36f, 0xF0FFFFFF, Colors.withAlpha(t.accent3(), 0xD0));
        circle(cx - r * 0.18f, cy - r * 0.42f, Math.max(0.6f, r * 0.08f), 0xFFFFFFFF);
    }

    /** A rounded square turned 45 degrees (a diamond) of half-diagonal {@code r}, gradient left to right. */
    public static void diamond(float cx, float cy, float r, int left, int right) {
        float side = r * 1.4142f;
        Matrix3x2fStack ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.translate(px(cx), px(cy));
        ms.rotate((float) (Math.PI / 4));
        ms.translate(-px(cx), -px(cy));
        roundGradientH(cx - side / 2, cy - side / 2, side, side, side * 0.16f, left, right);
        ms.popMatrix();
    }

    /** Filled circle with a white tick: "enabled / done". */
    public static void tickCircle(float cx, float cy, float r, int color) {
        glow(cx, cy, r * 2.2f, Colors.withAlpha(color, 0x50));
        circle(cx, cy, r, color);
        float k = r / 6f;
        arrowLine(cx - 2.6f * k, cy + 0.2f * k, cx - 0.7f * k, cy + 2.1f * k, 1.25f * k);
        arrowLine(cx - 0.7f * k, cy + 2.1f * k, cx + 2.9f * k, cy - 2.0f * k, 1.25f * k);
    }

    /** Filled circle with a white cross: "disabled / failed". */
    public static void crossCircle(float cx, float cy, float r, int color) {
        glow(cx, cy, r * 2.2f, Colors.withAlpha(color, 0x50));
        circle(cx, cy, r, color);
        float k = r / 6f, d = 2.3f * k;
        arrowLine(cx - d, cy - d, cx + d, cy + d, 1.25f * k);
        arrowLine(cx - d, cy + d, cx + d, cy - d, 1.25f * k);
    }

    private static void arrowLine(float x1, float y1, float x2, float y2, float thick) { stroke(x1, y1, x2, y2, thick, 0xFFFFFFFF); }

    /** A stroke with round caps between two points (a rotated rounded bar), anti-aliased like every other shape. */
    public static void stroke(float x1, float y1, float x2, float y2, float thick, int color) {
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        Matrix3x2fStack ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.translate(px(x1), px(y1));
        ms.rotate((float) Math.atan2(dy, dx));
        ms.translate(-px(x1), -px(y1));
        round(x1 - thick / 2, y1 - thick / 2, len + thick, thick, thick / 2, color);
        ms.popMatrix();
    }

    /** Raised frosted card, used for menu rows and module tiles. */
    public static void frostCard(Theme t, float x, float y, float w, float h, float r) {
        frost(t, x, y, w, h, r, 1f);
    }

    /** Compact rounded tag (fill, hairline and a rounded accent bar); no square corners anywhere. */
    public static void tag(float x, float y, float w, float h, int fill, int border, int accent) {
        float r = Math.min(5, h / 2);
        roundGradient(x, y, w, h, r, Colors.shade(fill, 0.06f), fill);
        outline(x, y, w, h, r, 1, border, border);
        round(x + 2, y + 3, 2f, h - 6, 1f, accent);
    }

    /** Accent hairline, used as a subtle top highlight on the window. */
    public static void accentLine(Theme t, float x, float y, float w, int alpha) {
        hline(x, y, w, 1, Colors.withAlpha(t.accent(), alpha));
    }

    /** Switch with an accent-to-second-accent gradient track and a soft glow when on. {@code on} is the eased 0..1 state. */
    public static void toggle(Theme t, float x, float y, float w, float h, float on) {
        on = com.trace.refract.util.Anim.clamp01(on);
        if (on > 0.01f) shadow(x, y, w, h, h / 2, 4, Colors.withAlpha(Colors.lerp(t.accent(), t.accent3(), 0.5f), (int) (0x55 * on)));
        round(x, y, w, h, h / 2, 0x2EFFFFFF);
        if (on > 0.01f) {
            float prev = alpha;
            alpha = prev * on;
            roundGradientH(x, y, w, h, h / 2, t.accent(), t.accent2());
            alpha = prev;
        }
        float kr = h / 2 - 2f;
        float kx = x + h / 2 + (w - h) * com.trace.refract.util.Anim.easeInOutCubic(on);
        circle(kx, y + h / 2, kr, Colors.lerp(0xFFB8BEC8, 0xFFFFFFFF, on));
    }

    /** An item icon (3D models included) as a square of {@code size} GUI units. */
    public static void item(net.minecraft.item.ItemStack stack, float x, float y, float size) {
        if (stack.isEmpty() || alpha < 0.05f) return;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(px(x), px(y));
        float k = size * s / 16f;
        m.scale(k, k);
        ctx.drawItem(stack, 0, 0);
        m.popMatrix();
    }

    /** A player's skin face (with hat layer) as a square of {@code size} GUI units. */
    public static void skin(net.minecraft.entity.player.SkinTextures tex, float x, float y, float size) {
        if (tex == null || alpha <= 0.002f) return;
        int X = px(x), S = Math.max(8, px(x + size) - X);
        net.minecraft.client.gui.PlayerSkinDrawer.draw(ctx, tex, X, px(y), S, c(0xFFFFFFFF));
    }
}
