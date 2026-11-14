package com.trace.refract.module.modules;

import com.trace.refract.hud.Markers;
import com.trace.refract.hud.WorldLabels;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/** Boxes, tracers and name tags for other players, visible through walls. */
public class PlayerEsp extends Module {
    public final BoolSetting boxes = add(new BoolSetting("Boxes", true));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", false));
    public final BoolSetting labels = add(new BoolSetting("Name Tags", true));
    public final BoolSetting throughWalls = add(new BoolSetting("Through Walls", true));
    public final BoolSetting farMarkers = add(new BoolSetting("Far Markers", true));
    public final NumberSetting range = add(new NumberSetting("Range", 512, 16, 1024, 16, "m"));

    public PlayerEsp() { super("Player ESP", "Highlights other players through walls with distance and health.", Category.RENDER); }

    private int color(double dist) {
        float t = (float) Math.min(1, dist / 64.0);
        return Colors.lerp(0xFFFF4D6D, 0xFF5CE1FF, t);
    }

    @Override public void onWorldRender(float td) {
        if (mc.world == null || mc.player == null) return;
        Vec3d origin = tracers.on() ? Esp.tracerOrigin() : null;
        double r2 = range.get() * range.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            double d2 = p.squaredDistanceTo(mc.player);
            if (d2 > r2) continue;
            int col = color(Math.sqrt(d2));
            Vec3d lerp = Esp.pos(p);
            Box b = Esp.box(p).expand(0.05);
            boolean walls = throughWalls.on();
            if (boxes.on()) {
                Esp.gradientBox(b, Colors.withAlpha(col, 0x38), Colors.withAlpha(col, 0x04), 2f, walls, 4);
                if (d2 < 48 * 48) { Esp.corners(b, Colors.withAlpha(col, 0xE8), 1.5f, walls); Esp.glow(b, col, walls); }
            }
            if (origin != null) Esp.line(origin, lerp.add(0, p.getHeight() / 2, 0), Colors.withAlpha(col, 0xB0), 1.5f, true);
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (!labels.on() || mc.world == null || mc.player == null) return;
        double r2 = range.get() * range.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            double d2 = p.squaredDistanceTo(mc.player);
            if (d2 > r2) continue;
            Vec3d pos = Esp.pos(p).add(0, p.getHeight() + 0.6, 0);
            double d = Math.sqrt(d2);
            int col = color(d);
            if (d > Markers.FAR && farMarkers.on()) {
                Markers.draw(d, Esp.pos(p).add(0, p.getHeight() / 2, 0), col, p.getName().getString(), 1f);
                continue;
            }
            String sub = String.format(Locale.ROOT, "%.0fm  ·  %.0f hp", d, p.getHealth() + p.getAbsorptionAmount());
            WorldLabels.draw(t, pos, p.getName().getString(), sub, col, 1f, sw, sh, false);
        }
    }
}
