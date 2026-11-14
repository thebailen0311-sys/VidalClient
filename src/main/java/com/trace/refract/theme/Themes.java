package com.trace.refract.theme;

import com.trace.refract.util.Anim;
import com.trace.refract.util.Time;

import java.util.List;

/** Built-in palettes plus a smooth cross-fade when the theme changes. */
public final class Themes {
    /** name, accent, accent2, HUD panel fill, window fill, border, text, dim text, backdrop tint, (unused x3) */
    public static final List<Theme> ALL = List.of(
            new Theme("Ocean", 0xFF4DA3FF, 0xFF6E8BFF, 0xD60E1116, 0xF6111419, 0x24FFFFFF,
                    0xFFEDEFF3, 0xFF8C94A3, 0x66000000, 0, 0, 0),
            new Theme("Rose", 0xFFF2617A, 0xFFE58BD8, 0xD6120E10, 0xF6151113, 0x24FFFFFF,
                    0xFFF3EDEF, 0xFFA3929A, 0x66000000, 0, 0, 0),
            new Theme("Mint", 0xFF2FC98F, 0xFF27B8D6, 0xD60D1211, 0xF6101514, 0x24FFFFFF,
                    0xFFECF2F0, 0xFF8BA39B, 0x66000000, 0, 0, 0),
            new Theme("Amber", 0xFFF59E0B, 0xFFF97316, 0xD612100D, 0xF6151310, 0x24FFFFFF,
                    0xFFF3F0EA, 0xFFA39A8B, 0x66000000, 0, 0, 0),
            new Theme("Violet", 0xFFA855F7, 0xFF7C3AED, 0xE61B1029, 0xF5140B20, 0x30A855F7,
                    0xFFF3EDFB, 0xFF9C8FB2, 0x66000000, 0, 0, 0),
            new Theme("Graphite", 0xFFD4D7DD, 0xFF9CA3AF, 0xD60F1012, 0xF6121315, 0x24FFFFFF,
                    0xFFF0F0F1, 0xFF8F939A, 0x66000000, 0, 0, 0),
            new Theme("Aurora", 0xFF3CFFB4, 0xFF7C5CFF, 0xD60C1214, 0xF60E1418, 0x2A3CFFB4,
                    0xFFEAFBF4, 0xFF8FB3A8, 0x66000000, 0, 0, 0),
            new Theme("Cyber", 0xFF00E5FF, 0xFFFF2BD6, 0xD60B0E16, 0xF60D101A, 0x2A00E5FF,
                    0xFFEAF6FF, 0xFF8C9BB3, 0x66000000, 0, 0, 0),
            new Theme("Sakura", 0xFFFF8FC8, 0xFFFFC2A8, 0xD6160F13, 0xF6181115, 0x2AFF8FC8,
                    0xFFFDF0F5, 0xFFB09AA5, 0x66000000, 0, 0, 0),
            new Theme("Sunset", 0xFFFF7A45, 0xFFFF3D7F, 0xD6160F0E, 0xF6181110, 0x2AFF7A45,
                    0xFFFFF1EA, 0xFFB39C92, 0x66000000, 0, 0, 0),
            new Theme("Glacier", 0xFF9BE7FF, 0xFFB8C6FF, 0xD60E1218, 0xF610151C, 0x2A9BE7FF,
                    0xFFF0F8FF, 0xFF97A6B8, 0x66000000, 0, 0, 0),
            new Theme("Crimson", 0xFFFF2E4D, 0xFFB0103A, 0xD6160C0F, 0xF6180D11, 0x2AFF2E4D,
                    0xFFFFEDEF, 0xFFB3939A, 0x66000000, 0, 0, 0)
    );

    /** How long a theme change takes to cross-fade, in milliseconds. */
    public static final long FADE_MS = 250;

    /** Third accent per theme, so palettes carry three colours, not two. */
    static final java.util.Map<String, Integer> THIRD = java.util.Map.ofEntries(
            java.util.Map.entry("Ocean", 0xFF4DE3FF), java.util.Map.entry("Rose", 0xFFFFB86B), java.util.Map.entry("Mint", 0xFFB6FF6A),
            java.util.Map.entry("Amber", 0xFFFF4D6D), java.util.Map.entry("Violet", 0xFFFF6FD8), java.util.Map.entry("Graphite", 0xFF8FA6C8),
            java.util.Map.entry("Aurora", 0xFF4DA3FF), java.util.Map.entry("Cyber", 0xFFFFE066), java.util.Map.entry("Sakura", 0xFFB59CFF),
            java.util.Map.entry("Sunset", 0xFFFFD25A), java.util.Map.entry("Glacier", 0xFFE0C3FF), java.util.Map.entry("Crimson", 0xFFFF9A3D));

    static int third(Theme t) {
        Integer c = THIRD.get(t.name());
        if (c == null) return com.trace.refract.util.Colors.lerp(t.accent(), t.accent2(), 0.5f);
        // While cross-fading, ease the third colour along with the accents.
        Theme target = Themes.target();
        Integer tc = THIRD.get(target.name());
        return tc == null || target == t ? c : com.trace.refract.util.Colors.lerp(c, tc, 0.5f);
    }

    private static Theme from = ALL.get(0), to = ALL.get(0);
    private static long fadeStart = 0;
    private static Theme cached = ALL.get(0);

    private Themes() {}

    public static String[] names() { return ALL.stream().map(Theme::name).toArray(String[]::new); }

    public static Theme byName(String name) {
        for (Theme t : ALL) if (t.name().equalsIgnoreCase(name)) return t;
        return ALL.get(0);
    }

    public static void set(String name) {
        Theme next = byName(name);
        if (next == to) return;
        from = current();
        to = next;
        fadeStart = System.currentTimeMillis();
    }

    public static Theme target() { return to; }

    /** Advance the cross-fade; called once per frame. A theme change always takes exactly FADE_MS. */
    public static void tick() {
        if (fadeStart == 0) { cached = to; return; }
        float p = Math.min(1f, (System.currentTimeMillis() - fadeStart) / (float) FADE_MS);
        if (p >= 1f) { fadeStart = 0; cached = to; return; }
        cached = Theme.lerp(from, to, Anim.easeInOutCubic(p));
    }

    /** The live (possibly cross-fading) theme. */
    public static Theme current() { return cached; }
}
