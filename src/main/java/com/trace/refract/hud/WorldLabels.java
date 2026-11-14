package com.trace.refract.hud;

import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import com.trace.refract.util.WorldToScreen;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact tags pinned to world positions: a flat dark plate with a colour bar, a bold title and an optional
 * second line (coordinates, distance). Built from solid fills only, so dozens per frame cost almost nothing.
 * Tags are nudged upward so they never overlap each other; that nudge eases in and out over time (per tag), so
 * tags glide instead of popping between rows. The anchor itself is the exact projection of the interpolated world
 * position for this frame, so tags stay locked to what they label while the camera moves.
 */
public final class WorldLabels {
    private static final List<float[]> PLACED = new ArrayList<>();
    private static int count;
    private static final int MAX_PER_FRAME = 24;
    private static final float TITLE = Gfx.SMALL + 0.5f, SUB = Gfx.SMALL;
    /** Eased vertical nudge per tag: key -> {offset, last seen (seconds)}. */
    private static final java.util.Map<Long, float[]> NUDGE = new java.util.HashMap<>();
    private static float lastSweep;

    private WorldLabels() {}

    public static void beginFrame() {
        PLACED.clear();
        count = 0;
        float now = com.trace.refract.util.Time.seconds();
        if (now - lastSweep > 1f) {
            lastSweep = now;
            NUDGE.values().removeIf(v -> now - v[1] > 1.5f);
        }
    }

    private static long key(String title, Vec3d world) {
        long h = title.hashCode();
        h = h * 31 + (long) Math.floor(world.x);
        h = h * 31 + (long) Math.floor(world.y);
        h = h * 31 + (long) Math.floor(world.z);
        return h;
    }

    private static boolean overlaps(float x, float y, float w, float h) {
        for (float[] r : PLACED) {
            if (x < r[0] + r[2] + 2 && x + w + 2 > r[0] && y < r[1] + r[3] + 2 && y + h + 2 > r[1]) return true;
        }
        return false;
    }

    /** Draws a tag above {@code world}. Returns the projected point (so callers can draw arrows when off-screen). */
    public static WorldToScreen.Point draw(Theme t, Vec3d world, String title, String sub, int color, float alpha, float sw, float sh, boolean dot) {
        WorldToScreen.Point p = WorldToScreen.project(world);
        if (!p.onScreen() || count >= MAX_PER_FRAME || alpha <= 0.02f) return p;
        count++;
        float prev = Gfx.alpha;
        Gfx.alpha = prev * alpha;
        float x = p.x(), y = p.y();
        Gfx.pushPlace(x, y, x, y, com.trace.refract.module.ModuleManager.hud.labelScale.asFloat());
        float tw = Gfx.width(title, TITLE, Font.Weight.BOLD);
        float subW = sub == null ? 0 : Gfx.width(sub, SUB);
        float w = Math.max(tw, subW) + 12;
        float h = sub == null ? 13 : 22;
        float lx = Math.max(2, Math.min(sw - w - 2, x - w / 2));
        float base = y - 7 - h, ly = base;
        for (int i = 0; i < 8 && overlaps(lx, ly, w, h); i++) ly -= h + 2;
        PLACED.add(new float[]{lx, ly, w, h});
        float now = com.trace.refract.util.Time.seconds();
        float[] n = NUDGE.get(key(title, world));
        if (n == null) NUDGE.put(key(title, world), n = new float[]{ly - base, now});
        n[0] += (ly - base - n[0]) * (1f - (float) Math.exp(-16f * com.trace.refract.util.Time.dt()));
        n[1] = now;
        ly = base + n[0];
        if (dot) Gfx.circle(x, y, 1.7f, color);
        if (ly + h < y - 2) Gfx.round(x - 0.4f, ly + h, 0.8f, y - 2 - (ly + h), 0.4f, Colors.withAlpha(color, 0x90));
        Gfx.roundGradient(lx, ly, w, h, 6, 0xF0141720, 0xE00A0C10);
        Gfx.outline(lx, ly, w, h, 6, 1, 0x22FFFFFF, 0x0CFFFFFF);
        Gfx.roundGradient(lx + 3.5f, ly + 4, 2, h - 8, 1f, color, Colors.withAlpha(color, 0x50));
        Gfx.text(title, lx + 8, ly + 2.5f, TITLE, Font.Weight.BOLD, t.text());
        if (sub != null) Gfx.text(sub, lx + 8, ly + 12, SUB, Colors.lerp(t.textDim(), color, 0.25f));
        Gfx.popPlace();
        Gfx.alpha = prev;
        return p;
    }
}
