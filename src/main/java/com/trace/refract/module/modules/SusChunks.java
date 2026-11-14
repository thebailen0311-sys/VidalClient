package com.trace.refract.module.modules;

import com.trace.refract.Refract;
import com.trace.refract.hud.Notifications;
import com.trace.refract.hud.WorldLabels;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.scan.ChunkReport;
import com.trace.refract.scan.Confidence;
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

import java.util.*;

/**
 * Activity Finder: places where someone spent a long time. Signals are grown kelp, matured amethyst geodes,
 * rotated or placed deepslate underground, long strip-mine tunnels and underground player blocks. Every hit
 * is shown as a marker on the exact evidence with its coordinates; the Surface Filter keeps griefed land out.
 */
public class SusChunks extends Module {
    public final ModeSetting minLevel = add(new ModeSetting("Min Confidence", "Medium", "Low", "Medium", "High", "Very High"));
    public final BoolSetting evidence = add(new BoolSetting("Show Evidence", true));
    public final BoolSetting labels = add(new BoolSetting("Labels", true));
    public final NumberSetting labelRange = add(new NumberSetting("Label Range", 192, 32, 1024, 16, "m"));
    public final BoolSetting throughWalls = add(new BoolSetting("Through Walls", true));
    public final BoolSetting alerts = add(new BoolSetting("Toasts", true));
    public final BoolSetting chat = add(new BoolSetting("Chat Alerts", false));

    /** A flagged chunk with its precomputed marker position and text. */
    public record Hit(ChunkReport report, Confidence.Level level, Vec3d focus, String title, String reasons) {}

    private final Set<Long> announced = new HashSet<>();
    private final List<Hit> hits = new ArrayList<>();
    private int seenRevision = -1, ticks;

    public SusChunks() { super("Activity Finder", "Where players spent time: grown kelp, matured geodes, placed deepslate, tunnels. Exact coordinates.", Category.HUNTING); }

    @Override public void onEnable() { WorldScanner.enqueueLoaded(mc); seenRevision = -1; }

    @Override public void onDisable() { hits.clear(); }

    /** Flagged chunks, strongest first (refreshed every 10 ticks). */
    public List<Hit> hits() { return hits; }

    @Override public String suffix() { return hits.isEmpty() ? null : String.valueOf(hits.size()); }

    @Override public void onTick() {
        if (++ticks % 10 != 0 || seenRevision == WorldScanner.revision()) return;
        seenRevision = WorldScanner.revision();
        int min = minLevel.index() + 1;
        List<ChunkReport> flagged = new ArrayList<>();
        for (ChunkReport r : WorldScanner.reports()) if (Confidence.level(r.susConfidence).ordinal() >= min) flagged.add(r);
        flagged.sort(Comparator.comparingDouble((ChunkReport r) -> r.susConfidence).reversed());
        hits.clear();
        for (ChunkReport r : flagged) {
            Vec3d focus = r.focus();
            if (focus == null) continue; // nothing concrete to point at: never show a bare chunk
            Confidence.Level lv = Confidence.level(r.susConfidence);
            String reasons = reasons(r);
            String head = reasons.contains("  ·  ") ? reasons.substring(0, reasons.indexOf("  ·  ")) : reasons;
            hits.add(new Hit(r, lv, focus, "Activity  " + Math.round(r.susConfidence * 100) + "%  ·  " + head, reasons));
        }
        for (Hit h : hits) {
            if (h.level.ordinal() < Confidence.Level.HIGH.ordinal() || !announced.add(h.report.pos.toLong())) continue;
            String msg = String.format(Locale.ROOT, "%s activity at %s", h.level.label, Fmt.xyz(h.focus));
            if (alerts.on()) { Notifications.push("Activity Finder", msg, h.level.color, 5000); com.trace.refract.util.Sfx.alert(); }
            com.trace.refract.util.FindingsLog.write("activity " + Math.round(h.report.susConfidence * 100) + "%", Fmt.xyz(h.focus), h.reasons);
            if (chat.on()) Refract.chat("§f" + msg + " §7" + h.reasons);
        }
    }

    /** Human-readable evidence, e.g. "14 grown kelp (~2.4 h) · geode 14/16 mature · 3 rotated deepslate". */
    public static String reasons(ChunkReport r) {
        List<String> parts = new ArrayList<>();
        if (r.pKelp > 0) {
            String s = r.kelpGrown + " grown kelp";
            if (r.kelpMinutes > 0) s += " (~" + Confidence.duration(r.kelpMinutes) + ")";
            parts.add(s);
        }
        if (r.pAmethyst > 0) parts.add(String.format(Locale.ROOT, "geode %d/%d mature", r.geodeClusters, r.geodeTotal));
        if (r.pRotated > 0) parts.add(r.rotated + " rotated deepslate");
        if (r.pTunnel > 0) parts.add(r.tunnelLength + "m tunnel");
        if (r.pHigh > 0.1f) parts.add(r.highDeepslate + " placed deepslate");
        if (r.pPlants > 0.15f) parts.add(r.caveVinesGrown + " grown cave vines");
        if (r.pActivity > 0.2f) parts.add(r.summary(2));
        if (r.antiXray) parts.add("anti-xray filtered");
        if (!r.loaded) parts.add("remembered");
        return String.join("  ·  ", parts);
    }

    @Override public void onWorldRender(float tickDelta) {
        if (mc.player == null) return;
        boolean walls = throughWalls.on();
        int ev = 0;
        for (Hit h : hits) {
            ChunkReport r = h.report;
            int col = h.level.color;
            Esp.gradientBox(new Box(h.focus, h.focus).expand(0.3), Colors.withAlpha(col, 0x70), Colors.withAlpha(col, 0x10), 1.5f, walls, 3);
            Esp.corners(new Box(h.focus, h.focus).expand(0.35), Colors.withAlpha(col, 0xE0), 1.25f, walls);
            if (!evidence.on()) continue;
            for (ChunkReport.KelpColumn k : r.grownKelp) { if (ev++ < 300) Esp.prettyBlock(k.top(), 0.12, 0xFF3DFF8A, walls); }
            if (r.geodeMature) for (BlockPos p : r.clusterEvidence) { if (ev++ < 300) Esp.prettyBlock(p, 0.18, 0xFFC77DFF, walls); }
            if (r.pRotated > 0) for (BlockPos p : r.rotatedEvidence) { if (ev++ < 300) Esp.prettyBlock(p, 0.02, 0xFFFF9A3D, walls); }
            if (r.pTunnel > 0) for (com.trace.refract.scan.Tunnel t : r.tunnels) { if (ev++ < 300) Esp.pretty(t.bounds, 0xFF38BDF8, walls); }
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (!labels.on() || mc.player == null) return;
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        double range = labelRange.get();
        int n = 0;
        for (Hit h : hits) {
            double d = eye.distanceTo(h.focus);
            if (d > range || n++ > 20) {
                com.trace.refract.hud.Markers.draw(h.focus, h.level.color, "Activity " + Math.round(h.report.susConfidence * 100) + "%  " + Fmt.dist(d), 1f);
                continue;
            }
            WorldLabels.draw(t, h.focus.add(0, 0.5, 0), h.title, Fmt.xyz(h.focus) + "  ·  " + Fmt.dist(d), h.level.color, 1f, sw, sh, false);
        }
    }
}
