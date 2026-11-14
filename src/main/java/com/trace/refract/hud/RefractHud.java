package com.trace.refract.hud;

import com.trace.refract.Refract;
import com.trace.refract.gui.ClickGuiScreen;
import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.module.modules.BaseFinder;
import com.trace.refract.module.modules.Hud;
import com.trace.refract.module.modules.SoundLocator;
import com.trace.refract.module.modules.SusChunks;
import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.scan.WorldScanner;
import com.trace.refract.theme.Theme;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Time;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-game overlay: a small watermark, the active module list, a findings table and a sound log. Every panel is
 * a flat plate with aligned columns (type, exact X Y Z, distance and direction).
 */
public final class RefractHud {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final float PANEL_W = 236, ROW_H = 11, HEAD_H = 17, PAD = 8;
    private static final float COL_COORDS = 86;

    private RefractHud() {}

    /** True while the HUD editor is open: every element is drawn, even ones switched off. */
    public static boolean editing;

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        Theme t = Themes.current();
        float sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        long t0 = System.nanoTime();
        Gfx.begin(ctx);
        try {
            WorldLabels.beginFrame();
            long tm = System.nanoTime();
            Motes.render();
            com.trace.refract.util.Prof.add("hud:motes", System.nanoTime() - tm);
            boolean guiOpen = mc.currentScreen instanceof ClickGuiScreen;
            if (!guiOpen) ModuleManager.renderHud(t, sw, sh); // the GUI gets a clean background
            Gfx.newLayer(); // panels cover world labels instead of labels bleeding through them

            Hud hud = ModuleManager.hud;
            boolean show = hud.isEnabled() || editing;
            float g = hud.scale.asFloat();
            if (show) {
                if (!(mc.currentScreen instanceof ClickGuiScreen)) {
                    float[] cur = {6};
                    stack(HudLayout.WATERMARK, hud.watermark.on() || editing, g, cur, sw, sh, y -> watermark(mc, t, y));
                    stack(HudLayout.COORDS, hud.coords.on() || editing, g, cur, sw, sh, y -> coords(mc, t, y));
                    stack(HudLayout.HUNTER, ModuleManager.autoHunter.isEnabled() || editing, g, cur, sw, sh, y -> hunterPill(t, y));
                    stack(HudLayout.FINDINGS, hud.hunting.on() || editing, g, cur, sw, sh, y -> findings(mc, t, y));
                    stack(HudLayout.SOUNDS, (hud.soundLog.on() && ModuleManager.soundLocator.isEnabled()) || editing, g, cur, sw, sh, y -> { soundLog(mc, t, y); return 0f; });
                } else {
                    for (HudLayout.Elem e : new HudLayout.Elem[]{HudLayout.WATERMARK, HudLayout.COORDS, HudLayout.HUNTER, HudLayout.FINDINGS, HudLayout.SOUNDS}) e.drawn = false;
                }
                if ((hud.arrayList.on() && !guiOpen) || editing) HudLayout.draw(HudLayout.MODULES, g, sw - 6, 6, sw, sh, () -> arrayList(t, sw));
                else HudLayout.MODULES.drawn = false;
            }
            Gfx.newLayer();
            Gfx.pushPlace(sw / 2, sh / 2, sw / 2, sh / 2, hud.cardScale.asFloat());
            long ti = System.nanoTime();
            Inspector.render(t, sw, sh);
            com.trace.refract.util.Prof.add("hud:inspector", System.nanoTime() - ti);
            Gfx.popPlace();
            if (show) {
                Gfx.newLayer();
                HudLayout.draw(HudLayout.TOASTS, g, sw - 8, sh - 54, sw, sh, () -> Notifications.render(t, sw, sh)); // above the card
            }
        } finally {
            Gfx.end();
            com.trace.refract.util.Prof.add("hud:total", System.nanoTime() - t0);
        }
    }

    /** Draws a left-column element; unmoved ones stack under the previous one. */
    private static void stack(HudLayout.Elem e, boolean on, float g, float[] cur, float sw, float sh, java.util.function.Function<Float, Float> body) {
        if (!on) { e.drawn = false; return; }
        float y0 = cur[0];
        HudLayout.draw(e, g, 6, y0, sw, sh, () -> body.apply(y0));
        if (!e.placed && e.drawn) cur[0] = e.y + e.h + 4;
    }

    // ------------------------------------------------------------------ watermark

    private static float watermark(MinecraftClient mc, Theme t, float y0) {
        String fps = mc.getCurrentFps() + " fps";
        float nameW = Gfx.width(Refract.NAME, Gfx.BODY, Font.Weight.BOLD);
        float h = 18, logo = 12;
        float w = 4 + logo + 6 + nameW + 7 + Gfx.width(fps, Gfx.SMALL, Font.Weight.SEMIBOLD) + 9;
        Gfx.hudGlass(t, 6, y0, w, h, 8);
        Gfx.logo(t, 6 + 3.5f, y0 + (h - logo) / 2, logo);
        float tx = 6 + 4 + logo + 6;
        Gfx.text(Refract.NAME, tx, y0 + h / 2 - 5.2f, Gfx.BODY, Font.Weight.BOLD, t.text());
        Gfx.text(fps, tx + nameW + 7, y0 + h / 2 - 4.2f, Gfx.SMALL, Font.Weight.SEMIBOLD, t.textDim());
        return y0 + h;
    }

    private static float coords(MinecraftClient mc, Theme t, float y) {
        Vec3d p = mc.player.getEntityPos();
        String s = Fmt.xyz(BlockPos.ofFloored(p));
        float w = PAD + Gfx.width("XYZ", Gfx.SMALL, Font.Weight.BOLD) + 6 + Gfx.width(s, Gfx.SMALL) + PAD;
        Gfx.hudGlass(t, 6, y, w, 15, 7.5f);
        float tx = 6 + PAD + Gfx.text("XYZ", 6 + PAD, y + 3.5f, Gfx.SMALL, Font.Weight.BOLD, t.accent());
        Gfx.text(s, tx + 6, y + 3.5f, Gfx.SMALL, t.text());
        return y + 15;
    }

    private static float hunterPill(Theme t, float y) {
        var ah = ModuleManager.autoHunter;
        String s = ah.status().isEmpty() ? ah.suffix() : ah.status();
        String head = "Auto Hunter";
        float w = PAD + Gfx.width(head, Gfx.SMALL, Font.Weight.BOLD) + 8 + Gfx.width(s == null ? "" : s, Gfx.SMALL) + PAD;
        float pulse = 0.5f + 0.5f * (float) Math.sin(Time.seconds() * 2.5);
        Gfx.accentGlass(t, 6, y, w, 15, 7.5f, 0.4f + 0.4f * pulse);
        Gfx.glow(6 + PAD - 2, y + 7.5f, 5, com.trace.refract.util.Colors.withAlpha(t.accent(), (int) (0x40 + 0x40 * pulse)));
        Gfx.circle(6 + PAD - 2, y + 7.5f, 2.2f, t.accent());
        float tx = 6 + PAD + 4 + Gfx.text(head, 6 + PAD + 4, y + 3.5f, Gfx.SMALL, Font.Weight.BOLD, t.text());
        if (s != null) Gfx.text(s, tx + 6, y + 3.5f, Gfx.SMALL, t.textDim());
        return y + 15;
    }

    // ------------------------------------------------------------------ findings table

    private record Row(String type, BlockPos at, Vec3d center, int color) {}

    private static float findings(MinecraftClient mc, Theme t, float y) {
        boolean bf = ModuleManager.baseFinder.isEnabled(), sc = ModuleManager.susChunks.isEnabled();
        boolean ge = ModuleManager.growthEsp.isEnabled();
        if (!bf && !sc && !ge && !editing) return y - 4;
        List<Row> rows = new ArrayList<>();
        if (bf) {
            for (BaseFinder.Candidate c : ModuleManager.baseFinder.candidates) {
                if (rows.size() >= 4) break;
                rows.add(new Row(c.type + " · " + c.status, c.anchor, c.center, c.statusColor));
            }
        }
        if (sc) {
            for (SusChunks.Hit h : ModuleManager.susChunks.hits()) {
                if (rows.size() >= 8) break;
                rows.add(new Row("Activity " + Math.round(h.report().susConfidence * 100) + "%", BlockPos.ofFloored(h.focus()), h.focus(), h.level().color));
            }
        }
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        float h = HEAD_H + Math.max(1, rows.size()) * ROW_H + 5;
        Gfx.hudGlass(t, 6, y, PANEL_W, h, 9);
        Gfx.text("Findings", 6 + PAD, y + 4.5f, Gfx.SMALL + 0.5f, Font.Weight.BOLD, t.text());
        String status = (WorldScanner.queued() > 0 ? "scanning " + WorldScanner.queued() : WorldScanner.reports().size() + " chunks")
                + "  ·  sees " + Fmt.dist(WorldScanner.farthest());
        if (ge && ModuleManager.growthEsp.suffix() != null) status = "growth " + ModuleManager.growthEsp.suffix() + "  ·  " + status;
        Gfx.text(status, 6 + PANEL_W - PAD - Gfx.width(status, Gfx.SMALL), y + 5, Gfx.SMALL, t.textDim());
        Gfx.hline(6 + PAD, y + HEAD_H - 2, PANEL_W - PAD * 2, 1, 0x18FFFFFF);
        if (rows.isEmpty()) Gfx.text(bf || sc ? "Nothing found yet" : ge ? "Growth ESP is on" : "Turn on Base Finder", 6 + PAD, y + HEAD_H, Gfx.SMALL, t.textDim());
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float ry = y + HEAD_H + i * ROW_H;
            row(mc, t, ry, r.type, Fmt.xyz(r.at), r.center, eye, r.color, 1f);
        }
        return y + h;
    }

    /** One aligned table row: colour mark, type, exact coordinates, distance + direction. */
    private static void row(MinecraftClient mc, Theme t, float ry, String type, String coords, Vec3d target, Vec3d eye, int color, float alpha) {
        float prev = Gfx.alpha;
        Gfx.alpha = prev * alpha;
        float x = 6 + PAD;
        Gfx.round(x, ry + 2.5f, 2, 6, 1, color);
        Gfx.text(Gfx.ellipsize(type, COL_COORDS - 10, Gfx.SMALL, Font.Weight.SEMIBOLD), x + 6, ry, Gfx.SMALL, Font.Weight.SEMIBOLD, t.text());
        String right = Fmt.dist(eye.distanceTo(target)) + " " + arrowTo(mc, target);
        float rw = Gfx.width(right, Gfx.SMALL, Font.Weight.BOLD);
        Gfx.text(Gfx.ellipsize(coords, PANEL_W - COL_COORDS - PAD - rw - 8, Gfx.SMALL, Font.Weight.REGULAR), 6 + COL_COORDS, ry, Gfx.SMALL, t.textDim());
        Gfx.text(right, 6 + PANEL_W - PAD - rw, ry, Gfx.SMALL, Font.Weight.BOLD, color);
        Gfx.alpha = prev;
    }

    // ------------------------------------------------------------------ module list

    private static final Anim LIST_W = new Anim(0, 14f), LIST_H = new Anim(0, 14f);

    /**
     * Active modules as one connected frosted panel in the top-right corner: names right-aligned in Inter Bold,
     * suffixes dimmed, an accent gradient bar down the right edge. The panel's width and height ease as modules
     * come and go, and each row slides and fades in on its own.
     */
    private static void arrayList(Theme t, float sw) {
        float dt = Time.dt();
        List<Module> shown = new ArrayList<>();
        for (Module m : ModuleManager.MODULES) {
            if (!m.showsInHud()) continue;
            m.hudAnim.to(m.isEnabled() ? 1 : 0).update(dt);
            if (m.hudAnim.value > 0.01f) shown.add(m);
        }
        java.util.IdentityHashMap<Module, Float> widths = new java.util.IdentityHashMap<>();
        for (Module m : shown) widths.put(m, labelWidth(m));
        shown.sort((a, b) -> Float.compare(widths.get(b), widths.get(a)));
        final float rowH = 12, padX = 9, padY = 4, top = 6, right = sw - 6;
        float targetW = 0, targetH = 0;
        for (Module m : shown) {
            float e = Anim.easeOutCubic(m.hudAnim.value);
            targetW = Math.max(targetW, (widths.get(m) + padX * 2 + 4) * (m.isEnabled() ? 1 : e));
            targetH += rowH * e;
        }
        if (LIST_W.value <= 0.5f && targetW > 0) LIST_W.snap(targetW);
        LIST_W.to(targetW).update(dt);
        LIST_H.to(targetH).update(dt);
        float w = LIST_W.value, h = LIST_H.value + padY * 2;
        if (w < 8 || LIST_H.value < 1) return;
        float x = right - w;
        float prev = Gfx.alpha;
        Gfx.alpha = prev * Anim.clamp01(LIST_H.value / rowH);
        Gfx.hudGlass(t, x, top, w, h, 7);
        Gfx.accentBar(t, right - 4.5f, top + 4, 2, h - 8);
        Gfx.scissor(x, top, w, h);
        float y = top + padY;
        for (Module m : shown) {
            float e = Anim.easeOutCubic(m.hudAnim.value);
            String suffix = m.suffix();
            float nameW = Gfx.width(m.name, Gfx.SMALL, Font.Weight.BOLD);
            float sufW = suffix == null ? 0 : Gfx.width(suffix, Gfx.SMALL) + 4;
            float tx = right - padX - 2 - nameW - sufW + (1 - e) * 10;
            Gfx.alpha = prev * e;
            Gfx.text(m.name, tx, y + 1.5f, Gfx.SMALL, Font.Weight.BOLD, t.text());
            if (suffix != null) Gfx.text(suffix, tx + nameW + 4, y + 1.5f, Gfx.SMALL, t.textDim());
            y += rowH * e;
        }
        Gfx.unscissor();
        Gfx.alpha = prev;
    }

    private static float labelWidth(Module m) {
        String s = m.suffix();
        return Gfx.width(m.name, Gfx.SMALL, Font.Weight.BOLD) + (s != null ? Gfx.width(s, Gfx.SMALL) + 4 : 0);
    }

    // ------------------------------------------------------------------ sound log

    private static void soundLog(MinecraftClient mc, Theme t, float y) {
        SoundLocator sl = ModuleManager.soundLocator;
        List<SoundLocator.Ping> recent = new ArrayList<>(sl.pings);
        recent.sort(Comparator.comparingLong((SoundLocator.Ping p) -> p.time).reversed());
        int n = Math.min(6, recent.size());
        float h = HEAD_H + Math.max(1, n) * ROW_H + 5;
        Gfx.hudGlass(t, 6, y, PANEL_W, h, 9);
        Gfx.text("Sounds", 6 + PAD, y + 4.5f, Gfx.SMALL + 0.5f, Font.Weight.BOLD, t.text());
        String range = sl.reachText();
        Gfx.text(range, 6 + PANEL_W - PAD - Gfx.width(range, Gfx.SMALL), y + 5, Gfx.SMALL, t.textDim());
        Gfx.hline(6 + PAD, y + HEAD_H - 2, PANEL_W - PAD * 2, 1, 0x18FFFFFF);
        if (n == 0) Gfx.text("Listening…", 6 + PAD, y + HEAD_H, Gfx.SMALL, t.textDim());
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        long now = Time.ms();
        for (int i = 0; i < n; i++) {
            SoundLocator.Ping p = recent.get(i);
            float fade = 1f - Anim.clamp01((now - p.time) / (float) sl.lifeMs());
            String label = p.count > 1 ? p.label + " ×" + p.count : p.label;
            row(mc, t, y + HEAD_H + i * ROW_H, label, Fmt.xyz(p.pos), p.center(), eye, p.kind.color, 0.4f + 0.6f * fade);
        }
    }

    private static String arrowTo(MinecraftClient mc, Vec3d pos) {
        var cam = mc.gameRenderer.getCamera();
        Vec3d d = pos.subtract(cam.getCameraPos());
        double target = Math.toDegrees(Math.atan2(-d.x, d.z));
        double rel = MathHelper.wrapDegrees(target - cam.getYaw());
        return ARROWS[Math.floorMod((int) Math.round(rel / 45.0), 8)];
    }
}
