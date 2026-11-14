package com.trace.refract.module.modules;

import com.trace.refract.hud.Notifications;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.scan.ChunkReport;
import com.trace.refract.scan.Indicators;
import com.trace.refract.scan.WorldScanner;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Sfx;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Announces every monster spawner the scan can see, once each, with coordinates and a compass route.
 *
 * <p>Spawners only exist in the client's world when the server has sent that chunk, so the practical reach is the
 * loaded area, not the range slider. Nothing here pretends otherwise: the slider only hides old finds you have flown
 * away from.
 */
public class SpawnerNotifier extends Module {
    public final BoolSetting boxes = add(new BoolSetting("ESP", true));
    public final BoolSetting labels = add(new BoolSetting("Labels", true));
    public final BoolSetting toasts = add(new BoolSetting("Toasts", true));
    public final BoolSetting sound = add(new BoolSetting("Sound", true));
    public final NumberSetting range = add(new NumberSetting("Show Within", 1024, 32, 4096, 32, "m"));

    private final Map<BlockPos, Long> found = new LinkedHashMap<>();
    private int timer;

    public SpawnerNotifier() {
        super("Spawner Notifier", "Alerts once per spawner with its coordinates and route.", Category.HUNTING);
    }

    @Override public void onEnable() {
        WorldScanner.enqueueLoaded(mc);
        timer = 0;
    }

    @Override public String suffix() { return String.valueOf(found.size()); }

    @Override public void onTick() {
        if (mc.player == null) return;
        if (++timer < 10) return;
        timer = 0;
        for (ChunkReport r : WorldScanner.reports()) {
            if (!r.loaded || r.storage.isEmpty()) continue;
            for (ChunkReport.StorageBlock s : r.storage) {
                if (s.type() != Indicators.Storage.SPAWNER) continue;
                BlockPos p = s.pos().toImmutable();
                if (found.containsKey(p)) continue;
                found.put(p, System.currentTimeMillis());
                double d = mc.player.getEntityPos().distanceTo(p.toCenterPos());
                if (toasts.on()) {
                    Notifications.push("Spawner", Fmt.xyz(p) + "  ·  " + BaseFinder.routeTo(p.toCenterPos()), 0xFFFFD84D, 3200);
                }
                if (sound.on()) Sfx.alert();
            }
        }
    }

    @Override public void onWorldRender(float tickDelta) {
        if (!boxes.on() || mc.player == null) return;
        Vec3d eye = mc.player.getEntityPos();
        for (BlockPos p : found.keySet()) {
            if (eye.distanceTo(p.toCenterPos()) > range.get()) continue;
            Box b = new Box(p).expand(0.4);
            Esp.gradientBox(b, 0x55FFD84D, 0x08FFD84D, 2f, true, 3);
            Esp.beam(p.toCenterPos(), p.getY(), p.getY() + 4, 0xAAFFD84D, 0x00FFD84D, true);
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (!labels.on() || mc.player == null) return;
        Vec3d eye = mc.player.getEntityPos();
        for (BlockPos p : found.keySet()) {
            Vec3d c = p.toCenterPos();
            double d = eye.distanceTo(c);
            if (d > range.get()) continue;
            com.trace.refract.hud.WorldLabels.draw(t, c.add(0, 1.4, 0), "Spawner",
                    Fmt.xyz(p) + "  ·  " + Fmt.dist(d), 0xFFFFD84D, 1f, sw, sh, false);
        }
    }
}
