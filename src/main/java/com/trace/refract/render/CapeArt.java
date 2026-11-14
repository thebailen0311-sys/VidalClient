package com.trace.refract.render;

import com.trace.refract.theme.Theme;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Colors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

/**
 * Vanta cape and elytra textures, painted in code in the current theme (no Minecraft textures) and repainted when
 * the theme changes: a deep accent-to-second-accent gradient, a diagonal shimmer, a glowing gem on the back of the
 * cape and feathered bands across the wings.
 */
public final class CapeArt {
    private static final Identifier CAPE = Identifier.of("refract", "textures/entity/vanta_cape.png");
    private static final Identifier ELYTRA = Identifier.of("refract", "textures/entity/vanta_elytra.png");
    public static final AssetInfo.TextureAsset CAPE_ASSET = new AssetInfo.TextureAssetInfo(CAPE, CAPE);
    public static final AssetInfo.TextureAsset ELYTRA_ASSET = new AssetInfo.TextureAssetInfo(ELYTRA, ELYTRA);
    private static int paintedFor = 0;

    private CapeArt() {}

    /** Makes sure both textures exist in the current theme's colours. */
    public static void ensure() {
        Theme t = Themes.target();
        int key = t.accent() * 31 + t.accent2();
        if (key == paintedFor) return;
        paintedFor = key;
        var tm = MinecraftClient.getInstance().getTextureManager();
        tm.registerTexture(CAPE, new NativeImageBackedTexture(() -> "vanta-cape", paint(t, false)));
        tm.registerTexture(ELYTRA, new NativeImageBackedTexture(() -> "vanta-elytra", paint(t, true)));
    }

    private static NativeImage paint(Theme t, boolean elytra) {
        NativeImage img = new NativeImage(64, 32, true);
        int a = t.accent(), b = t.accent2();
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                float fy = y / 31f;
                int c = Colors.lerp(Colors.shade(a, -0.15f), Colors.shade(b, -0.45f), fy);
                if (((x + y) % 7) == 0) c = Colors.lerp(c, 0xFFFFFFFF, 0.18f); // diagonal shimmer
                if (elytra && (y % 3) == 0) c = Colors.shade(c, -0.25f);       // feather bands
                if (elytra && x > 34 && (x - 34) % 4 == 0) c = Colors.lerp(c, b, 0.35f);
                img.setColorArgb(x, y, c | 0xFF000000);
            }
        }
        if (!elytra) {
            // Gem on the outer face of the cape (u 1..11, v 1..17), with a bright rim.
            int cx = 6, cy = 8;
            for (int y = 1; y <= 16; y++) for (int x = 1; x <= 10; x++) {
                int d = Math.abs(x - cx) + Math.abs(y - cy);
                if (d <= 3) img.setColorArgb(x, y, d == 3 ? 0xFFFFFFFF : Colors.lerp(a, 0xFFFFFFFF, 0.55f - d * 0.12f) | 0xFF000000);
            }
            for (int x = 1; x <= 10; x++) { img.setColorArgb(x, 1, Colors.lerp(a, 0xFFFFFFFF, 0.5f) | 0xFF000000); img.setColorArgb(x, 16, b | 0xFF000000); }
        }
        return img;
    }
}
