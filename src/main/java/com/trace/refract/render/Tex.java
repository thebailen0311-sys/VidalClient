package com.trace.refract.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Procedurally generated, anti-aliased alpha masks (circles, rings, soft shadows, glows, arrows).
 * Everything is white with an alpha channel and is tinted at draw time. Shapes are generated at the exact
 * physical pixel size they are drawn at, so edges are perfectly smooth at any GUI scale.
 */
public final class Tex {
    /** A texture with a linear or nearest sampler. */
    private static final class MaskTexture extends NativeImageBackedTexture {
        MaskTexture(String name, NativeImage img, boolean linear, boolean repeat) {
            super(() -> name, img);
            FilterMode f = linear ? FilterMode.LINEAR : FilterMode.NEAREST;
            this.sampler = repeat ? RenderSystem.getSamplerCache().getRepeated(f) : RenderSystem.getSamplerCache().get(f);
        }
    }

    public record Mask(Identifier id, int w, int h) {}

    private static final Map<String, Mask> CACHE = new HashMap<>();
    private static int counter;

    private Tex() {}

    interface Shader { float alpha(float x, float y); }

    /** Builds a mask by supersampling the coverage function 4x4 per pixel. */
    private static Mask build(String key, int w, int h, boolean linear, Shader s) { return build(key, w, h, linear, false, s); }

    private static Mask build(String key, int w, int h, boolean linear, boolean repeat, Shader s) {
        Mask m = CACHE.get(key);
        if (m != null) return m;
        NativeImage img = new NativeImage(Math.max(1, w), Math.max(1, h), true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++) {
                    sum += s.alpha(x + (sx + 0.5f) / 4f, y + (sy + 0.5f) / 4f);
                }
                int a = Math.round(Math.max(0, Math.min(1, sum / 16f)) * 255);
                img.setColorArgb(x, y, (a << 24) | 0xFFFFFF);
            }
        }
        Identifier id = Identifier.of("refract", "mask/" + (counter++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new MaskTexture("refract-" + key, img, linear, repeat));
        m = new Mask(id, w, h);
        CACHE.put(key, m);
        return m;
    }

    /** Filled disc of radius r (texture is 2r x 2r). */
    public static Mask circle(int r) {
        return build("c" + r, 2 * r, 2 * r, false, (x, y) -> {
            float dx = x - r, dy = y - r;
            return dx * dx + dy * dy <= (float) r * r ? 1 : 0;
        });
    }

    /** Ring of outer radius r and given thickness. */
    public static Mask ring(int r, int t) {
        float inner = Math.max(0, r - t);
        return build("r" + r + "_" + t, 2 * r, 2 * r, false, (x, y) -> {
            float dx = x - r, dy = y - r, d = dx * dx + dy * dy;
            return d <= (float) r * r && d >= inner * inner ? 1 : 0;
        });
    }

    /**
     * Outer-only soft shadow for a rounded rect of corner radius r with blur b, 9-sliced:
     * texture is (2c+1)^2 where c = r + b; the centre texel row/column is the stretchable edge.
     * Pixels inside the shape are transparent so the shadow never darkens translucent glass.
     */
    public static Mask shadow(int r, int b) {
        int c = r + b;
        int size = 2 * c + 1;
        return build("s" + r + "_" + b, size, size, false, (x, y) -> {
            // Rounded box occupying [b, size-b] with radius r.
            float hx = (size - 2f * b) / 2f, hy = hx;
            float px = Math.abs(x - size / 2f) - (hx - r), py = Math.abs(y - size / 2f) - (hy - r);
            float ox = Math.max(px, 0), oy = Math.max(py, 0);
            float d = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(px, py), 0) - r;
            if (d <= 0) return 0;
            float t = 1f - Math.min(1f, d / b);
            return t * t * (3 - 2 * t) * 0.9f;
        });
    }

    /** Radial glow, used for aurora blobs and soft highlights (drawn scaled, linear filtering). */
    public static Mask glow() {
        int n = 128;
        return build("glow", n, n, true, (x, y) -> {
            float dx = (x - n / 2f) / (n / 2f), dy = (y - n / 2f) / (n / 2f);
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float t = Math.max(0, 1 - d);
            return t * t * (3 - 2 * t);
        });
    }

    /** Tileable film grain for the acrylic material (sampled with a repeating sampler at 1:1). */
    public static Mask noise() {
        int n = 128;
        java.util.Random rnd = new java.util.Random(0x5EEDL);
        float[] v = new float[n * n];
        for (int i = 0; i < v.length; i++) v[i] = rnd.nextFloat();
        return build("noise", n, n, false, true, (x, y) -> v[((int) y % n) * n + ((int) x % n)]);
    }

    /** Horizontal soft band, bright in the middle; used for the moving glass sheen. */
    public static Mask band() {
        return build("band", 64, 4, true, (x, y) -> {
            float t = 1 - Math.abs(x - 32) / 32f;
            return t * t;
        });
    }

    /** A rounded arrowhead pointing right (+X), drawn rotated with linear filtering. */
    public static Mask arrow() {
        int n = 64;
        return build("arrow", n, n, true, (x, y) -> {
            // Triangle with tip at (56,32), base from (12,10) to (12,54), notched back.
            boolean in = inTri(x, y, 56, 32, 12, 10, 24, 32) || inTri(x, y, 56, 32, 24, 32, 12, 54);
            return in ? 1 : 0;
        });
    }

    private static boolean inTri(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {
        float d1 = edge(ax, ay, bx, by, px, py), d2 = edge(bx, by, cx, cy, px, py), d3 = edge(cx, cy, ax, ay, px, py);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    private static float edge(float ax, float ay, float bx, float by, float px, float py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /** Release cached masks (called on resource reload / scale change is not needed: keys include size). */
    public static int cached() { return CACHE.size(); }
}
