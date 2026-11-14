package com.trace.refract.hud;

import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Time;
import com.trace.refract.util.WorldToScreen;
import net.minecraft.util.math.Vec3d;

/**
 * Screen-space markers: a glowing ring drawn at the projected position of a world point, the same size at any
 * distance. 3D boxes shrink to a pixel past ~100 m; these keep far finds (amethyst, bases, sounds) visible.
 * Close things use full labels; past {@link #FAR} metres callers switch to a marker plus a compact gradient tag.
 */
public final class Markers {
    /** Beyond this distance a full label is replaced by a compact marker. */
    public static final double FAR = 128;
    /** Past this distance the marker is drawn extra bright with a distance-first tag. */
    public static final double VERY_FAR = 256;

    private Markers() {}

    /** Draws a marker with an optional one-line tag. Returns false when the point is off-screen. */
    public static boolean draw(Vec3d world, int color, String tag, float alpha) {
        WorldToScreen.Point p = WorldToScreen.project(world);
        if (!p.onScreen() || alpha <= 0.02f) return false;
        float prev = Gfx.alpha;
        Gfx.alpha = prev * alpha;
        float x = p.x(), y = p.y();
        Gfx.pushPlace(x, y, x, y, com.trace.refract.module.ModuleManager.hud.labelScale.asFloat());
        float pulse = 0.5f + 0.5f * (float) Math.sin(Time.seconds() * 3 + (world.x + world.z) * 0.1);

        Gfx.glow(x, y, 11 + 4 * pulse, Colors.withAlpha(color, 0x4C));
        Gfx.glow(x, y, 6, Colors.withAlpha(color, 0x66));
        Gfx.ring(x, y, 5f + 0.6f * pulse, 1, Colors.withAlpha(color, 0xF0));
        Gfx.circle(x, y, 1.8f, 0xFFFFFFFF);

        if (tag != null) {
            float tw = Gfx.width(tag, Gfx.SMALL, Font.Weight.SEMIBOLD);
            float w = tw + 12, h = 13;
            float tx = x + 9, ty = y - h / 2f;
            Gfx.roundGradient(tx, ty, w, h, h / 2f,
                    0xE6141720, 0xD0090B0F);
            Gfx.outline(tx, ty, w, h, h / 2f, 1, Colors.withAlpha(color, 0x66), Colors.withAlpha(color, 0x14));
            Gfx.circle(tx + 5.5f, ty + h / 2f, 1.8f, Colors.withAlpha(color, 0xF0));
            Gfx.text(tag, tx + 10, ty + 2.5f, Gfx.SMALL, Font.Weight.SEMIBOLD, Colors.lerp(0xFFFFFFFF, color, 0.30f));
        }
        Gfx.popPlace();
        Gfx.alpha = prev;
        return true;
    }

    /** A marker with a small distance badge underneath, used for finds that are simply too far for a label. */
    public static boolean draw(double distance, Vec3d world, int color, String shortLabel, float alpha) {
        WorldToScreen.Point p = WorldToScreen.project(world);
        if (!p.onScreen() || alpha <= 0.02f) return false;
        float prev = Gfx.alpha;
        Gfx.alpha = prev * alpha;
        float x = p.x(), y = p.y();
        Gfx.pushPlace(x, y, x, y, com.trace.refract.module.ModuleManager.hud.labelScale.asFloat());
        float pulse = 0.5f + 0.5f * (float) Math.sin(Time.seconds() * 3 + (world.x + world.z) * 0.1);
        Gfx.glow(x, y, 9 + 3 * pulse, Colors.withAlpha(color, 0x40));
        Gfx.ring(x, y, 4.5f, 1, Colors.withAlpha(color, 0xE0));
        Gfx.circle(x, y, 1.6f, 0xFFFFFFFF);
        String tag = Math.round(distance) + "m  " + shortLabel;
        float tw = Gfx.width(tag, Gfx.SMALL, Font.Weight.BOLD);
        Gfx.round(x - tw / 2f - 4, y + 7, tw + 8, 11, 5.5f, 0xB80A0C10);
        Gfx.text(tag, x - tw / 2f, y + 9, Gfx.SMALL, Font.Weight.BOLD, Colors.lerp(0xFFFFFFFF, color, 0.25f));
        Gfx.popPlace();
        Gfx.alpha = prev;
        return true;
    }
}
