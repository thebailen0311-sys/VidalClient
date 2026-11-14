package com.trace.refract.util;

/** ARGB helpers. Every colour in Refract is a full 0xAARRGGBB int. */
public final class Colors {
    private Colors() {}

    public static int a(int c) { return c >>> 24; }
    public static int r(int c) { return (c >> 16) & 0xFF; }
    public static int g(int c) { return (c >> 8) & 0xFF; }
    public static int b(int c) { return c & 0xFF; }

    public static int argb(int a, int r, int g, int b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    public static int withAlpha(int c, int alpha) { return (clamp(alpha) << 24) | (c & 0xFFFFFF); }

    public static int mulAlpha(int c, float m) { return withAlpha(c, Math.round(a(c) * Math.max(0f, Math.min(1f, m)))); }

    public static int lerp(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return argb(
                Math.round(a(c1) + (a(c2) - a(c1)) * t),
                Math.round(r(c1) + (r(c2) - r(c1)) * t),
                Math.round(g(c1) + (g(c2) - g(c1)) * t),
                Math.round(b(c1) + (b(c2) - b(c1)) * t));
    }

    /** Brighten (t>0) toward white or darken (t<0) toward black, keeping alpha. */
    public static int shade(int c, float t) {
        int target = t >= 0 ? 0xFFFFFF : 0x000000;
        int mixed = lerp(c | 0xFF000000, target | 0xFF000000, Math.abs(t));
        return (c & 0xFF000000) | (mixed & 0xFFFFFF);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
