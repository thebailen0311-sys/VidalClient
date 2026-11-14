package com.trace.refract.module.modules;

import com.trace.refract.hud.WorldLabels;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.scan.ChunkReport;
import com.trace.refract.scan.WorldScanner;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Fmt;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Overgrown kelp and amethyst ESP. Both only grow while a chunk is loaded (random ticks), so they mark places
 * where a player has been AFK or living for a long time.
 * <ul>
 *   <li><b>Kelp</b>: worldgen tops every column at age 20-23 with at most 11 blocks. Only columns whose top is
 *       at age 25 (the growth cap) or that are taller than 11 are shown. Age 24 is skipped because a kelp
 *       placed by hand can roll any age up to 24.</li>
 *   <li><b>Amethyst</b>: a fresh geode's buds are 1/4 clusters. Clusters are shown when their geode (this chunk
 *       plus neighbours) has at least 8 clusters, at least 60% of all buds fully grown, and a binomial z-score
 *       of 3 or more against a 30% natural share. "All Clusters" shows every cluster instead.</li>
 * </ul>
 */
public class GrowthEsp extends Module {
    public final BoolSetting kelp = add(new BoolSetting("Overgrown Kelp", true));
    public final ModeSetting kelpShape = add(new ModeSetting("Kelp Shape", "Column", "Top", "Column"));
    public final BoolSetting amethyst = add(new BoolSetting("Overgrown Amethyst", true));
    public final ModeSetting amethystMode = add(new ModeSetting("Amethyst", "Mature Geodes", "Mature Geodes", "All Clusters"));
    public final NumberSetting range = add(new NumberSetting("Range", 1024, 32, 1024, 16, "m"));
    public final NumberSetting limit = add(new NumberSetting("Max Boxes", 600, 50, 2000, 50, ""));
    public final BoolSetting labels = add(new BoolSetting("Labels", true));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", false));
    public final BoolSetting throughWalls = add(new BoolSetting("Through Walls", true));

    public static final int KELP_COLOR = 0xFF3DFF8A, AMETHYST_COLOR = 0xFFC77DFF;

    private record Item(Box box, Vec3d center, int color, double dist) {}

    private record Group(Vec3d at, BlockPos coord, String title, int color) {}

    private final List<Item> items = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private int kelpCount, clusterCount, ticks, seenRevision = -1;

    public GrowthEsp() { super("Growth ESP", "Overgrown kelp (age 25) and matured amethyst geodes: signs of long AFK or a base.", Category.HUNTING); }

    @Override public void onEnable() { WorldScanner.enqueueLoaded(mc); seenRevision = -1; }

    @Override public void onDisable() { items.clear(); groups.clear(); }

    public int kelpCount() { return kelpCount; }

    public int clusterCount() { return clusterCount; }

    public String rangeText() { return range.get() >= range.max ? "∞" : range.display(); }

    @Override public String suffix() { return kelpCount + clusterCount == 0 ? null : kelpCount + " · " + clusterCount; }

    @Override public void onTick() {
        // Rebuild on new scan data, and twice a second for distance ordering as you move.
        if (++ticks % 10 != 0 && seenRevision == WorldScanner.revision()) return;
        seenRevision = WorldScanner.revision();
        rebuild();
    }

    private void rebuild() {
        items.clear();
        groups.clear();
        kelpCount = clusterCount = 0;
        if (mc.player == null) return;
        Vec3d me = mc.player.getEntityPos();
        double r2 = range.get() >= range.max ? Double.MAX_VALUE / 4 : range.get() * range.get(); // max = unlimited
        boolean column = kelpShape.is("Column"), allClusters = amethystMode.is("All Clusters");
        for (ChunkReport r : WorldScanner.reports()) {
            double cx = r.pos.getStartX() + 8 - me.x, cz = r.pos.getStartZ() + 8 - me.z;
            if (cx * cx + cz * cz > r2 + 2048) continue;
            if (kelp.on() && !r.grownKelp.isEmpty()) {
                double sx = 0, sy = 0, sz = 0;
                for (ChunkReport.KelpColumn k : r.grownKelp) {
                    BlockPos top = k.top();
                    Box b = column ? new Box(top.getX() + 0.2, k.bottomY(), top.getZ() + 0.2, top.getX() + 0.8, top.getY() + 1, top.getZ() + 0.8)
                            : new Box(top).contract(0.12);
                    add(b, top.toCenterPos(), KELP_COLOR, me, r2);
                    sx += top.getX(); sy += top.getY(); sz += top.getZ();
                    kelpCount++;
                }
                int n = r.grownKelp.size();
                groups.add(group(sx / n, sy / n, sz / n,
                        n + " grown kelp" + (r.kelpMinutes > 0 ? "  ·  ~" + com.trace.refract.scan.Confidence.duration(r.kelpMinutes) + " loaded" : ""), KELP_COLOR));
            }
            if (amethyst.on() && !r.clusterEvidence.isEmpty() && (allClusters || r.geodeMature)) {
                double sx = 0, sy = 0, sz = 0;
                for (BlockPos p : r.clusterEvidence) {
                    add(new Box(p).contract(0.18), p.toCenterPos(), AMETHYST_COLOR, me, r2);
                    sx += p.getX(); sy += p.getY(); sz += p.getZ();
                    clusterCount++;
                }
                int n = r.clusterEvidence.size();
                String title = r.geodeMature
                        ? String.format(Locale.ROOT, "Mature geode  %d/%d  (%d%%)", r.geodeClusters, r.geodeTotal, Math.round(100.0 * r.geodeClusters / Math.max(1, r.geodeTotal)))
                        : n + " amethyst clusters";
                groups.add(group(sx / n, sy / n, sz / n, title, AMETHYST_COLOR));
            }
        }
        groups.sort((a, b) -> Double.compare(a.at.squaredDistanceTo(me), b.at.squaredDistanceTo(me)));
        items.sort((a, b) -> Double.compare(a.dist, b.dist));
        int max = limit.asInt();
        if (items.size() > max) items.subList(max, items.size()).clear();
    }

    private static Group group(double x, double y, double z, String title, int color) {
        BlockPos coord = BlockPos.ofFloored(x + 0.5, y + 0.5, z + 0.5);
        return new Group(new Vec3d(x + 0.5, y + 1.6, z + 0.5), coord, title, color);
    }

    private void add(Box b, Vec3d center, int color, Vec3d me, double r2) {
        double dx = center.x - me.x, dz = center.z - me.z;
        double d = dx * dx + dz * dz;
        if (d <= r2) items.add(new Item(b, center, color, d));
    }

    @Override public void onWorldRender(float tickDelta) {
        boolean walls = throughWalls.on();
        Vec3d origin = tracers.on() ? Esp.tracerOrigin() : null;
        int i = 0;
        for (Item it : items) {
            if (i++ < 110) {
                Esp.gradientBox(it.box, Colors.withAlpha(it.color, 0x3C), Colors.withAlpha(it.color, 0x04), 1.25f, walls, 3);
            } else {
                Esp.box(it.box, Colors.withAlpha(it.color, 0x26), Colors.withAlpha(it.color, 0xD0), 1.25f, walls);
            }
            if (origin != null) Esp.line(origin, it.center, Colors.withAlpha(it.color, 0x80), 1f, true);
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (!labels.on() || mc.player == null) return;
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        int n = 0;
        for (Group g : groups) {
            double d = eye.distanceTo(g.at);
            if (d <= com.trace.refract.hud.Markers.FAR && n++ < 16) WorldLabels.draw(t, g.at, g.title, Fmt.xyz(g.coord) + "  ·  " + Fmt.dist(d), g.color, 1f, sw, sh, false);
            else com.trace.refract.hud.Markers.draw(g.at, g.color, (g.color == KELP_COLOR ? "Kelp  " : "Geode  ") + Fmt.dist(d), 1f);
        }
    }
}
