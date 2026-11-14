package com.trace.refract.module.modules;

import com.trace.refract.hud.Markers;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.scan.ChunkReport;
import com.trace.refract.scan.Indicators;
import com.trace.refract.scan.WorldScanner;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Fmt;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Highlights containers and spawners from the shared chunk scan, nearest first. */
public class StorageEsp extends Module {
    public final BoolSetting chests = add(new BoolSetting("Chests", true));
    public final BoolSetting ender = add(new BoolSetting("Ender Chests", true));
    public final BoolSetting shulkers = add(new BoolSetting("Shulker Boxes", true));
    public final BoolSetting barrels = add(new BoolSetting("Barrels", true));
    public final BoolSetting hoppers = add(new BoolSetting("Hoppers & Droppers", false));
    public final BoolSetting furnaces = add(new BoolSetting("Furnaces", false));
    public final BoolSetting spawners = add(new BoolSetting("Spawners", true));
    public final NumberSetting range = add(new NumberSetting("Range", 1024, 16, 1024, 16, "m"));
    public final NumberSetting limit = add(new NumberSetting("Max Boxes", 400, 50, 2000, 50, ""));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", false));
    public final BoolSetting labels = add(new BoolSetting("Far Markers", true));
    public final BoolSetting throughWalls = add(new BoolSetting("Through Walls", true));

    private int lastCount;
    /** What was drawn this frame, so the HUD can mark the distant ones without walking the scan twice. */
    private List<Item> shown = new ArrayList<>();

    public StorageEsp() { super("Storage ESP", "Boxes around chests, shulkers, ender chests, barrels and spawners.", Category.HUNTING); }

    @Override public void onEnable() { WorldScanner.enqueueLoaded(mc); }

    @Override public String suffix() { return String.valueOf(lastCount); }

    private boolean wanted(Indicators.Storage s) {
        return switch (s) {
            case CHEST, TRAPPED, COPPER -> chests.on();
            case ENDER -> ender.on();
            case SHULKER -> shulkers.on();
            case BARREL -> barrels.on();
            case HOPPER, DISPENSER -> hoppers.on();
            case FURNACE -> furnaces.on();
            case SPAWNER -> spawners.on();
        };
    }

    private record Item(ChunkReport.StorageBlock block, double dist) {}

    @Override public void onWorldRender(float tickDelta) {
        if (mc.player == null) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double r2 = range.get() >= range.max ? Double.MAX_VALUE / 4 : range.get() * range.get(); // max = unlimited
        List<Item> items = new ArrayList<>();
        for (ChunkReport r : WorldScanner.reports()) {
            if (!r.loaded) continue;
            double cx = r.pos.getStartX() + 8 - cam.x, cz = r.pos.getStartZ() + 8 - cam.z;
            if (cx * cx + cz * cz > r2 + 2048) continue;
            for (ChunkReport.StorageBlock s : r.storage) {
                if (!wanted(s.type())) continue;
                double d = s.pos().getSquaredDistance(cam);
                if (d <= r2) items.add(new Item(s, d));
            }
        }
        int max = limit.asInt();
        if (items.size() > max) {
            items.sort((a, b) -> Double.compare(a.dist, b.dist));
            items = items.subList(0, max);
        }
        lastCount = items.size();
        shown = items;
        boolean walls = throughWalls.on();
        Vec3d origin = tracers.on() ? Esp.tracerOrigin() : null;
        int i = 0;
        for (Item it : items) {
            int col = it.block.type().color;
            // The closest few get the full gradient treatment; the rest stay flat so a storage hall costs nothing.
            if (i < 70) {
                Esp.gradientBox(boxOf(it.block.pos()), Colors.withAlpha(col, 0x3A), Colors.withAlpha(col, 0x06), 2f, walls, 3);
                if (i < 16) Esp.glow(boxOf(it.block.pos()), col, walls);
            } else {
                Esp.prettyBlock(it.block.pos(), 0.0625, col, walls);
            }
            i++;
            if (origin != null) Esp.line(origin, it.block.pos().toCenterPos(), Colors.withAlpha(col, 0x90), 1.5f, true);
        }
    }

    /** A container's own 1x1x1 box, inset slightly so neighbours stay readable. */
    private static net.minecraft.util.math.Box boxOf(net.minecraft.util.math.BlockPos p) {
        return new net.minecraft.util.math.Box(p).contract(0.0625);
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (!labels.on() || mc.player == null) return;
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        int n = 0;
        for (Item it : shown) {
            if (n++ > 36) break;
            Vec3d c = it.block.pos().toCenterPos();
            double d = eye.distanceTo(c);
            if (d <= Markers.FAR) continue;
            Markers.draw(d, c, it.block.type().color, it.block.type().label, 1f);
        }
    }
}
