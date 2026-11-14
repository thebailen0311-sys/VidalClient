package com.trace.refract.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.trace.refract.Refract;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Inter (bundled, SIL OFL) rasterised by Java2D into anti-aliased glyph atlases at the real physical pixel
 * size. Glyphs Inter lacks fall back to a symbol font, so nothing ever renders as a box or '?'.
 */
public final class Font {
    public enum Weight {
        REGULAR("manrope-medium.ttf"), SEMIBOLD("manrope-semibold.ttf"), BOLD("manrope-bold.ttf");

        final String file;

        Weight(String file) { this.file = file; }
    }

    private static final String CHARS;

    static {
        StringBuilder sb = new StringBuilder();
        for (char c = 32; c < 127; c++) sb.append(c);
        sb.append("•·×…←↑→↓↖↗↘↙▸▾✓°±§∞–—");
        CHARS = sb.toString();
    }

    private static final Map<Weight, java.awt.Font> BASE = new HashMap<>();
    private static java.awt.Font symbolBase;

    /** One rasterised font at a given pixel size. */
    public static final class Atlas {
        final Identifier id;
        final int texW, texH;
        final Map<Character, int[]> glyphs = new HashMap<>(); // x, y, w, h
        final Map<Character, Float> advance = new HashMap<>();
        final float ascent, lineHeight;
        final int pad;
        /** GPU texture + sampler for batched text elements, resolved once. */
        net.minecraft.client.texture.TextureSetup setup;

        Atlas(Identifier id, int texW, int texH, float ascent, float lineHeight, int pad) {
            this.id = id; this.texW = texW; this.texH = texH; this.ascent = ascent; this.lineHeight = lineHeight; this.pad = pad;
        }

        /** Latin-1 lookups go through flat arrays (no boxing or hashing per glyph). */
        final int[][] fastGlyph = new int[256][];
        final float[] fastAdvance = new float[256];

        public float advance(char c) {
            if (c < 256) {
                float a = fastAdvance[c];
                if (a > 0 || fastGlyph[c] != null) return a;
            } else {
                Float a = advance.get(c);
                if (a != null) return a;
            }
            return fastAdvance['?'] > 0 ? fastAdvance['?'] : lineHeight * 0.5f;
        }

        int[] glyph(char c) {
            int[] g = c < 256 ? fastGlyph[c] : glyphs.get(c);
            return g != null ? g : fastGlyph['?'];
        }
    }

    private static final class AtlasTexture extends NativeImageBackedTexture {
        AtlasTexture(String name, NativeImage img) {
            super(() -> name, img);
            this.sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        }
    }

    private static final Map<String, Atlas> ATLASES = new HashMap<>();

    private Font() {}

    private static java.awt.Font base(Weight w) {
        return BASE.computeIfAbsent(w, k -> {
            try (InputStream in = Font.class.getResourceAsStream("/refract_fonts/" + k.file)) {
                if (in == null) throw new IllegalStateException("missing " + k.file);
                return java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in);
            } catch (Exception e) {
                Refract.LOG.error("Could not load {}, using SansSerif", k.file, e);
                return new java.awt.Font(java.awt.Font.SANS_SERIF, k == Weight.REGULAR ? java.awt.Font.PLAIN : java.awt.Font.BOLD, 12);
            }
        });
    }

    private static java.awt.Font symbols() {
        if (symbolBase == null) {
            String[] names = {"Segoe UI Symbol", "Apple Symbols", "DejaVu Sans", java.awt.Font.DIALOG};
            java.util.Set<String> have = new java.util.HashSet<>();
            try {
                for (String s : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)) have.add(s);
            } catch (Throwable ignored) { }
            for (String n : names) if (have.contains(n) || n.equals(java.awt.Font.DIALOG)) { symbolBase = new java.awt.Font(n, java.awt.Font.PLAIN, 12); break; }
        }
        return symbolBase;
    }

    public static String family() { return base(Weight.REGULAR).getFamily(); }

    /** Lookup without building a string key (this runs for every text draw and width measure). */
    private static final Atlas[][] FAST = new Atlas[Weight.values().length][512];

    public static Atlas atlas(Weight weight, int px) {
        px = Math.max(6, px);
        if (px < 512) {
            Atlas f = FAST[weight.ordinal()][px];
            if (f != null) return f;
        }
        String key = weight + ":" + px;
        Atlas a = ATLASES.get(key);
        if (a == null) {
            a = build(weight, px);
            ATLASES.put(key, a);
        }
        if (px < 512) FAST[weight.ordinal()][px] = a;
        return a;
    }

    private static Atlas build(Weight weight, int px) {
        java.awt.Font f = base(weight).deriveFont((float) px);
        java.awt.Font sym = symbols().deriveFont((float) px);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics fm = pg.getFontMetrics(f);
        pg.dispose();
        int pad = 2;
        int cellH = fm.getAscent() + fm.getDescent() + pad * 2;
        int texW = 512;
        while (texW < px * 24 && texW < 4096) texW *= 2;

        Map<Character, int[]> layout = new HashMap<>();
        Map<Character, java.awt.Font> fontOf = new HashMap<>();
        int x = 0, y = 0;
        for (char c : CHARS.toCharArray()) {
            java.awt.Font use = f.canDisplay(c) ? f : (sym.canDisplay(c) ? sym : null);
            if (use == null) continue;
            int w = (int) Math.ceil(use.getStringBounds(String.valueOf(c), frc).getWidth()) + pad * 2 + 2;
            if (x + w > texW) { x = 0; y += cellH; }
            layout.put(c, new int[]{x, y, w, cellH});
            fontOf.put(c, use);
            x += w;
        }
        int texH = 1;
        while (texH < y + cellH) texH *= 2;

        BufferedImage img = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);
        Identifier id = Identifier.of("refract", "font/" + weight.name().toLowerCase(Locale.ROOT) + "_" + px);
        Atlas atlas = new Atlas(id, texW, texH, fm.getAscent(), fm.getAscent() + fm.getDescent(), pad);
        for (Map.Entry<Character, int[]> e : layout.entrySet()) {
            char c = e.getKey();
            int[] r = e.getValue();
            java.awt.Font use = fontOf.get(c);
            g.setFont(use);
            g.drawString(String.valueOf(c), r[0] + pad, r[1] + pad + fm.getAscent());
            float adv = (float) use.getStringBounds(String.valueOf(c), frc).getWidth();
            atlas.glyphs.put(c, r);
            atlas.advance.put(c, adv);
            if (c < 256) { atlas.fastGlyph[c] = r; atlas.fastAdvance[c] = adv; }
        }
        g.dispose();

        NativeImage ni = new NativeImage(texW, texH, true);
        for (int yy = 0; yy < texH; yy++) {
            for (int xx = 0; xx < texW; xx++) {
                int a = img.getRGB(xx, yy) >>> 24;
                ni.setColorArgb(xx, yy, (a << 24) | 0xFFFFFF);
            }
        }
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new AtlasTexture("refract-font-" + weight + px, ni));
        return atlas;
    }
}
