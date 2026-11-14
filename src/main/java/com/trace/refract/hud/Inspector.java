package com.trace.refract.hud;

import com.trace.refract.module.ModuleManager;
import com.trace.refract.module.modules.BaseFinder;
import com.trace.refract.module.modules.SusChunks;
import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.scan.ChunkReport;
import com.trace.refract.scan.Confidence;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Sfx;
import com.trace.refract.util.Time;
import com.trace.refract.util.WorldToScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Look at a base or activity marker and a card slides in beside the crosshair with the full breakdown:
 * confidence, exact coordinates, distance and every piece of evidence with its numbers.
 */
public final class Inspector {
    private record Target(Object key, Vec3d at, BlockPos coord, String title, float confidence, int color, List<String> lines,
                          String value, String valueDetail) {}

    private static final float AIM_RADIUS = 46, W = 168;
    private static final Anim SHOW = new Anim(0f, 14f);
    private static Target shown;
    private static Object lastKey;

    private Inspector() {}

    /** Key of the base or activity spot being looked at right now (null when none), for the hologram. */
    public static Object focusedKey() { return lastKey; }

    /** How far the card is open, 0..1 (eased in and out over time). */
    public static float focusAmount() { return SHOW.value; }

    public static void render(Theme t, float sw, float sh) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Target best = null;
        if (mc.currentScreen == null && mc.player != null) best = pick(sw, sh);
        if (best != null) shown = best;
        Object key = best == null ? null : best.key;
        if (key != null && !key.equals(lastKey)) Sfx.focus();
        lastKey = key;
        SHOW.to(best != null ? 1 : 0).update(Time.dt());
        if (shown == null || SHOW.value < 0.01f) return;
        draw(mc, t, shown, sw, sh, Anim.easeOutCubic(SHOW.value));
    }

    private static Target pick(float sw, float sh) {
        List<Target> all = new ArrayList<>();
        if (ModuleManager.baseFinder.isEnabled()) for (BaseFinder.Candidate c : ModuleManager.baseFinder.candidates) all.add(base(c));
        if (ModuleManager.susChunks.isEnabled()) for (SusChunks.Hit h : ModuleManager.susChunks.hits()) all.add(activity(h));
        Target best = null;
        double bestD = AIM_RADIUS * AIM_RADIUS;
        for (Target tg : all) {
            WorldToScreen.Point p = WorldToScreen.project(tg.at);
            if (!p.onScreen()) continue;
            double dx = p.x() - sw / 2, dy = p.y() - sh / 2, d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = tg; }
        }
        return best;
    }

    private static Target base(BaseFinder.Candidate c) {
        List<String> lines = new ArrayList<>();
        lines.add(c.status + ": " + c.statusReason);
        var entries = new ArrayList<>(c.found.entrySet());
        entries.sort((a, b) -> Float.compare(c.weight.getOrDefault(b.getKey(), 0f), c.weight.getOrDefault(a.getKey(), 0f)));
        for (Map.Entry<String, Integer> e : entries) {
            if (c.weight.getOrDefault(e.getKey(), 0f) <= 0 || lines.size() >= 5) continue;
            lines.add(e.getValue() + " × " + e.getKey());
        }
        var b = c.bounds;
        lines.add(String.format(Locale.ROOT, "Area %d × %d × %d  ·  Y %d to %d", (int) b.getLengthX(), (int) b.getLengthY(), (int) b.getLengthZ(), (int) Math.ceil(b.minY), (int) Math.floor(b.maxY)));
        var est = com.trace.refract.scan.BaseValue.of(c.found);
        String value = est.total() > 0 ? com.trace.refract.scan.BaseValue.money(est.total()) + "+" : null;
        StringBuilder vd = new StringBuilder();
        for (int i = 0; i < Math.min(2, est.lines().size()); i++) {
            var l = est.lines().get(i);
            if (vd.length() > 0) vd.append("  ·  ");
            vd.append(l.count()).append(' ').append(l.label()).append(' ').append(com.trace.refract.scan.BaseValue.money(l.value()));
        }
        return new Target(c.key, c.center, c.anchor, c.type, c.confidence, c.level.color, lines, value,
                value == null ? null : vd + (vd.length() > 0 ? "  ·  " : "") + "contents unseen");
    }

    private static Target activity(SusChunks.Hit h) {
        ChunkReport r = h.report();
        List<String> lines = new ArrayList<>();
        if (r.pKelp > 0) lines.add("Grown kelp: " + r.kelpGrown + " of " + r.kelpColumns + " columns"
                + (r.kelpMinutes > 0 ? "  ·  ~" + Confidence.duration(r.kelpMinutes) + " loaded" : ""));
        if (r.pAmethyst > 0) lines.add(String.format(Locale.ROOT, "Geode: %d/%d clusters (%d%%)  ·  z %.1f", r.geodeClusters, r.geodeTotal,
                Math.round(100.0 * r.geodeClusters / Math.max(1, r.geodeTotal)), r.amethystZ));
        if (!r.rooms.isEmpty()) { var b = r.rooms.get(0); lines.add("Dug-out rooms: " + r.rooms.size() + "  (" + (int) b.getLengthX() + " × " + (int) b.getLengthZ() + ", " + (int) b.getLengthY() + " tall)"); }
        if (r.built > 0) lines.add(String.format(Locale.ROOT, "Placed building blocks: score %.1f, %d kinds", r.built, r.builtTypes));
        if (r.pRotated > 0) lines.add("Rotated deepslate: " + r.rotated);
        if (r.pTunnel > 0) lines.add("Strip-mine tunnels: " + r.tunnelLength + " m");
        if (r.pHigh > 0.1f) lines.add("Deepslate placed above y16: " + r.highDeepslate);
        if (r.pPlants > 0.15f) lines.add("Full-length cave vines: " + r.caveVinesGrown);
        if (r.pActivity > 0.2f) lines.add("Player blocks: " + r.summary(3));
        if (r.structure != null) lines.add("Near structure: " + r.structure + " (damped)");
        if (r.surfaceSkipped > 0) lines.add("Surface blocks ignored: " + r.surfaceSkipped);
        if (r.antiXray) lines.add("Anti-xray detected (hidden blocks filtered)");
        if (!r.loaded) lines.add("Remembered (chunk unloaded)");
        while (lines.size() > 5) lines.remove(lines.size() - 1);
        return new Target(r.pos, h.focus(), BlockPos.ofFloored(h.focus()), "Activity  ·  " + h.level().label, r.susConfidence, h.level().color, lines, null, null);
    }

    /** Compact frosted card beside the crosshair: title, confidence, coordinates, value floor and evidence. */
    private static void draw(MinecraftClient mc, Theme t, Target tg, float sw, float sh, float e) {
        float lineH = 9.5f;
        boolean hasValue = tg.value != null;
        float h = 40 + (hasValue ? 22 : 0) + tg.lines.size() * lineH + 5;
        float x = sw / 2 + 20 + (1 - e) * 8, y = Math.max(6, sh / 2 - h / 2);
        if (x + W > sw - 6) x = sw / 2 - 20 - W - (1 - e) * 8;
        x = Math.max(6, Math.min(sw - W - 6, x)); // never off-screen on small windows
        float prev = Gfx.alpha;
        Gfx.alpha = prev * e;
        Gfx.frost(t, x, y, W, h, 8, 0.8f);
        Gfx.outline(x, y, W, h, 8, 1, Colors.withAlpha(tg.color, 0x70), Colors.withAlpha(tg.color, 0x14));
        Gfx.round(x + 8, y + 8, 2.5f, 11, 1.25f, tg.color);
        String pct = Math.round(tg.confidence * 100) + "%";
        float pw = Gfx.width(pct, Gfx.SMALL + 0.5f, Font.Weight.BOLD);
        Gfx.text(Gfx.ellipsize(tg.title, W - 30 - pw, Gfx.SMALL + 1, Font.Weight.BOLD), x + 15, y + 6.5f, Gfx.SMALL + 1, Font.Weight.BOLD, t.text());
        Gfx.text(pct, x + W - 9 - pw, y + 7, Gfx.SMALL + 0.5f, Font.Weight.BOLD, tg.color);
        double dist = mc.gameRenderer.getCamera().getCameraPos().distanceTo(tg.at);
        Gfx.text(Fmt.xyz(tg.coord) + "  ·  " + Fmt.dist(dist), x + 15, y + 18, Gfx.SMALL - 0.5f, t.textDim());
        // Confidence bar fills as the card opens.
        Gfx.round(x + 8, y + 30, W - 16, 2.5f, 1.25f, 0x22FFFFFF);
        Gfx.roundGradientH(x + 8, y + 30, Math.max(2.5f, (W - 16) * tg.confidence * e), 2.5f, 1.25f, tg.color, Colors.lerp(tg.color, 0xFFFFFFFF, 0.35f));
        float ly = y + 38;
        if (hasValue) {
            Gfx.round(x + 7, ly, W - 14, 19, 6, Colors.withAlpha(t.accent(), 0x1C));
            Gfx.text("Worth at least", x + 12, ly + 2, Gfx.SMALL - 0.5f, Font.Weight.SEMIBOLD, t.textDim());
            float vw = Gfx.width(tg.value, Gfx.SMALL + 0.5f, Font.Weight.BOLD);
            Gfx.text(tg.value, x + W - 12 - vw, ly + 1.5f, Gfx.SMALL + 0.5f, Font.Weight.BOLD, t.accent());
            if (tg.valueDetail != null) Gfx.text(Gfx.ellipsize(tg.valueDetail, W - 24, Gfx.SMALL - 1.5f, Font.Weight.REGULAR), x + 12, ly + 10.5f, Gfx.SMALL - 1.5f, t.textDim());
            ly += 22;
        }
        for (String line : tg.lines) {
            Gfx.circle(x + 11, ly + 3.6f, 1.2f, Colors.withAlpha(tg.color, 0xC0));
            Gfx.text(Gfx.ellipsize(line, W - 25, Gfx.SMALL - 0.5f, Font.Weight.REGULAR), x + 16, ly, Gfx.SMALL - 0.5f, t.text());
            ly += lineH;
        }
        Gfx.alpha = prev;
    }
}
