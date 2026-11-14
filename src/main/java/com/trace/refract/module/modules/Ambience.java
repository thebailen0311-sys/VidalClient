package com.trace.refract.module.modules;

import com.trace.refract.hud.Motes;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.render.Gfx;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Time;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Client-side atmosphere in the theme colours (nothing is sent to the server):
 * <ul>
 *   <li>Sky: Aurora Night, Theme, Sunset or Void, with fog tinted to match the horizon.</li>
 *   <li>Aurora: two shimmering curtains of light rolling across the sky.</li>
 *   <li>Shooting stars streaking over now and then; drifting glow motes; fireflies near the ground.</li>
 *   <li>Colour grade: Cinematic (teal/orange), Dream (your accents), Noir or Neon, plus a soft vignette.</li>
 * </ul>
 */
public class Ambience extends Module {
    /** The only setting: a full look, tuned as a whole. "The End" is applied automatically in the End. */
    public final ModeSetting preset = add(new ModeSetting("Preset", "Aurora Night", "Off", "Aurora Night", "Deep Space", "Black Hole", "Rainy Night", "Sunset Dream", "Noir"));
    public final BoolSetting rain = new BoolSetting("Rain", false);
    private String applied = "";

    public final ModeSetting sky = new ModeSetting("Sky", "Aurora Night", "Off", "Theme", "Aurora Night", "Sunset", "Void");
    public final ModeSetting space = new ModeSetting("Space Sky", "Galaxy", "Off", "Planets", "Black Hole", "Nebula", "Galaxy", "End");
    public final NumberSetting strength = new NumberSetting("Sky Strength", 0.7, 0.1, 1.0, 0.05, "");
    public final BoolSetting fog = new BoolSetting("Tint Fog", true);
    public final BoolSetting aurora = new BoolSetting("Aurora", true);
    public final NumberSetting auroraGlow = new NumberSetting("Aurora Intensity", 0.7, 0.1, 1.0, 0.05, "");
    public final BoolSetting shootingStars = new BoolSetting("Shooting Stars", true);
    public final BoolSetting particles = new BoolSetting("Glow Motes", true);
    public final NumberSetting density = new NumberSetting("Mote Density", 3, 1, 10, 1, "");
    public final BoolSetting fireflies = new BoolSetting("Fireflies", true);
    public final ModeSetting grade = new ModeSetting("Colour Grade", "Dream", "Off", "Cinematic", "Dream", "Noir", "Neon");
    public final NumberSetting gradeStrength = new NumberSetting("Grade Strength", 0.6, 0.05, 1.0, 0.05, "");
    public final NumberSetting bloom = new NumberSetting("Bloom", 0.45, 0.0, 1.0, 0.05, "");
    public final NumberSetting saturation = new NumberSetting("Saturation", 1.2, 0.0, 2.0, 0.05, "x");
    public final NumberSetting vignetteAmt = new NumberSetting("Vignette", 0.45, 0.0, 1.0, 0.05, "");
    private final Random rng = new Random();
    private final List<float[]> stars = new ArrayList<>(); // dirX, dirY, dirZ, speedX, speedY, speedZ, start

    public Ambience() {
        super("Ambience", "Aurora sky, shooting stars, glow motes, fireflies, colour grading and vignette in your theme.", Category.RENDER);
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(this::drawSky);
    }

    /** Refract's own mote particles advance every tick, whichever module spawned them. */
    @Override public void onAlwaysTick() {
        Motes.tick();
        boolean end = mc.world != null && mc.world.getRegistryKey() == net.minecraft.world.World.END;
        String want = preset.is("Off") ? "Off" : end ? "The End" : preset.get();
        if (!want.equals(applied)) { applied = want; apply(want); }
    }

    /** Sets every internal look parameter for a preset. */
    private void apply(String p) {
        // defaults
        sky.set("Off"); space.set("Off"); strength.set(0.7); fog.set(true); aurora.set(false); auroraGlow.set(0.7); shootingStars.set(false);
        particles.set(true); density.set(3.0); fireflies.set(false); rain.set(false); grade.set("Off"); gradeStrength.set(0.5); bloom.set(0.35); saturation.set(1.1); vignetteAmt.set(0.4);
        switch (p) {
            case "Aurora Night" -> { sky.set("Aurora Night"); space.set("Nebula"); aurora.set(true); shootingStars.set(true); fireflies.set(true); grade.set("Dream"); bloom.set(0.45); saturation.set(1.2); }
            case "Deep Space" -> { sky.set("Void"); space.set("Galaxy"); strength.set(0.95); shootingStars.set(true); density.set(2.0); grade.set("Dream"); gradeStrength.set(0.35); bloom.set(0.55); saturation.set(1.25); vignetteAmt.set(0.5); }
            case "Black Hole" -> { sky.set("Void"); space.set("Black Hole"); strength.set(0.95); grade.set("Cinematic"); gradeStrength.set(0.45); bloom.set(0.65); vignetteAmt.set(0.55); }
            case "Rainy Night" -> { sky.set("Theme"); strength.set(0.6); rain.set(true); particles.set(false); grade.set("Cinematic"); gradeStrength.set(0.55); bloom.set(0.35); saturation.set(0.9); vignetteAmt.set(0.55); }
            case "Sunset Dream" -> { sky.set("Sunset"); fireflies.set(true); grade.set("Dream"); gradeStrength.set(0.6); bloom.set(0.5); saturation.set(1.3); }
            case "Noir" -> { particles.set(false); grade.set("Noir"); gradeStrength.set(1.0); bloom.set(0.25); vignetteAmt.set(0.65); }
            case "The End" -> { sky.set("Void"); space.set("End"); strength.set(0.9); shootingStars.set(true); density.set(4.0); grade.set("Dream"); gradeStrength.set(0.45); bloom.set(0.6); saturation.set(1.3); vignetteAmt.set(0.5); }
            default -> { particles.set(false); bloom.set(0.0); saturation.set(1.0); vignetteAmt.set(0.0); }
        }
    }

    /** Puddle ripples: {x, y, z, start time}. */
    private final List<float[]> ripples = new ArrayList<>();
    /** Puddles near you: {x, y, z, radius}; chosen from the block position, so they stay put. */
    private final List<float[]> puddles = new ArrayList<>();
    private int puddleTick;

    @Override public void onTick() {
        if (mc.world == null || mc.player == null) return;
        if (rain.on() && ++puddleTick % 20 == 1) {
            puddles.clear();
            int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
            for (int x = px - 16; x <= px + 16; x++) for (int z = pz - 16; z <= pz + 16; z++) {
                long h = (x * 73856093L) ^ (z * 19349663L);
                float f = ((h ^ (h >>> 13)) & 1023) / 1023f;
                if (f > 0.1f) continue;
                int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
                var below = mc.world.getBlockState(new BlockPos(x, top - 1, z));
                if (Math.abs(top - mc.player.getY()) > 10 || !below.isOpaqueFullCube() || !below.getFluidState().isEmpty()) continue;
                puddles.add(new float[]{x + 0.5f, top + 0.015f, z + 0.5f, 0.55f + f * 6f});
            }
        }
        if (!rain.on()) puddles.clear();
        if (rain.on()) {
            for (int i = 0; i < 6; i++) {
                double x = mc.player.getX() + (rng.nextDouble() - 0.5) * 20, z = mc.player.getZ() + (rng.nextDouble() - 0.5) * 20;
                int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
                if (Math.abs(top - mc.player.getY()) > 12) continue;
                if (!puddles.isEmpty() && rng.nextInt(3) > 0) { float[] pd = puddles.get(rng.nextInt(puddles.size())); x = pd[0] + (rng.nextDouble() - 0.5) * pd[3]; z = pd[2] + (rng.nextDouble() - 0.5) * pd[3]; top = (int) Math.floor(pd[1]); }
                ripples.add(new float[]{(float) x, top + 0.02f, (float) z, Time.seconds()});
                if (rng.nextInt(3) == 0) Motes.spawn(x, top + 0.05, z, (rng.nextDouble() - 0.5) * 0.04, 0.06, (rng.nextDouble() - 0.5) * 0.04, 6, 0.025f, 0xFFCFE3FF, 0xFF8FB6FF);
            }
            ripples.removeIf(rp -> Time.seconds() - rp[3] > 0.9f);
            if (ripples.size() > 160) ripples.subList(0, ripples.size() - 160).clear();
        } else ripples.clear();
        Theme t = Themes.current();
        if (particles.on()) {
            for (int i = 0; i < density.asInt(); i++) {
                double x = mc.player.getX() + (rng.nextDouble() - 0.5) * 30, y = mc.player.getY() - 2 + rng.nextDouble() * 11, z = mc.player.getZ() + (rng.nextDouble() - 0.5) * 30;
                if (!mc.world.getBlockState(BlockPos.ofFloored(x, y, z)).isAir()) continue;
                Motes.spawn(x, y, z, (rng.nextDouble() - 0.5) * 0.02, 0.008 + rng.nextDouble() * 0.01, (rng.nextDouble() - 0.5) * 0.02,
                        60 + rng.nextInt(60), 0.03f + rng.nextFloat() * 0.03f, t.accent(), t.accent2());
            }
        }
        if (fireflies.on() && rng.nextInt(2) == 0) {
            double x = mc.player.getX() + (rng.nextDouble() - 0.5) * 22, z = mc.player.getZ() + (rng.nextDouble() - 0.5) * 22;
            int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            double y = Math.min(top + 0.4 + rng.nextDouble() * 2.2, mc.player.getY() + 4);
            if (mc.world.getBlockState(BlockPos.ofFloored(x, y, z)).isAir())
                Motes.spawn(x, y, z, (rng.nextDouble() - 0.5) * 0.03, (rng.nextDouble() - 0.5) * 0.01, (rng.nextDouble() - 0.5) * 0.03,
                        40 + rng.nextInt(30), 0.06f, 0xFFFFE27A, 0xFFB6FF6A);
        }
        if (shootingStars.on() && rng.nextInt(90) == 0) {
            double a = rng.nextDouble() * Math.PI * 2;
            float dx = (float) Math.cos(a), dz = (float) Math.sin(a);
            stars.add(new float[]{dx * 90, 55 + rng.nextFloat() * 25, dz * 90, -dz * 1.6f + dx * 0.3f, -0.35f, dx * 1.6f + dz * 0.3f, Time.seconds()});
        }
        stars.removeIf(s -> Time.seconds() - s[6] > 1.2f);
    }

    /**
     * Sky effects as real geometry (not lines): aurora curtains are gradient quad strips drawn with additive glow, so
     * they read as soft, continuous light; shooting stars are tapered streaks that fade along the tail.
     */
    private void drawSky(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext ctx) {
        if (!isEnabled() || mc.player == null || ctx.matrices() == null || ctx.consumers() == null) return;
        Theme t = Themes.current();
        float time = Time.seconds();
        var layer = net.minecraft.client.render.RenderLayers.lightning();
        var vc = ctx.consumers().getBuffer(layer);
        var e = ctx.matrices().peek();
        if (aurora.on()) {
            float g = auroraGlow.asFloat();
            for (int band = 0; band < 3; band++) {
                double phase = band * 2.1, radius = 95 + band * 22, base = 38 + band * 9;
                int n = 96;
                int low = band == 1 ? t.accent2() : Colors.lerp(0xFF3CFFB4, t.accent(), 0.35f + band * 0.2f);
                int high = Colors.lerp(low, t.accent(), 0.65f);
                float[] px = new float[n + 1], pz = new float[n + 1], y0 = new float[n + 1], y1 = new float[n + 1], a = new float[n + 1];
                for (int i = 0; i <= n; i++) {
                    double th = -Math.PI * 0.6 + i * Math.PI * 1.2 / n + phase + time * 0.01 + 0.06 * Math.sin(time * 0.35 + i * 0.25);
                    double r = radius + 10 * Math.sin(i * 0.11 + time * 0.2 + phase);
                    px[i] = (float) (Math.cos(th) * r);
                    pz[i] = (float) (Math.sin(th) * r);
                    y0[i] = (float) (base + Math.sin(i * 0.19 + time * 0.55 + phase) * 7);
                    y1[i] = y0[i] + 24 + 14 * (float) Math.sin(i * 0.13 + time * 0.8 + phase);
                    float edge = (float) Math.sin(Math.PI * i / n); // fade at both ends of the curtain
                    a[i] = g * edge * (0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(i * 0.31 + time * 1.6 + phase)));
                }
                for (int i = 0; i < n; i++) {
                    float ym0 = y0[i] + (y1[i] - y0[i]) * 0.25f, ym1 = y0[i + 1] + (y1[i + 1] - y0[i + 1]) * 0.25f;
                    int ca0 = Colors.withAlpha(low, (int) (200 * a[i])), ca1 = Colors.withAlpha(low, (int) (200 * a[i + 1]));
                    int cb0 = Colors.withAlpha(high, 0), cb1 = Colors.withAlpha(high, 0);
                    int c00 = Colors.withAlpha(low, 0), c01 = Colors.withAlpha(low, 0);
                    // Bright band low down fading to nothing at the top, and a short soft fade at the bottom edge.
                    quad2(vc, e, px[i], y0[i], pz[i], c00, px[i + 1], y0[i + 1], pz[i + 1], c01, px[i + 1], ym1, pz[i + 1], ca1, px[i], ym0, pz[i], ca0);
                    quad2(vc, e, px[i], ym0, pz[i], ca0, px[i + 1], ym1, pz[i + 1], ca1, px[i + 1], y1[i + 1], pz[i + 1], cb1, px[i], y1[i], pz[i], cb0);
                }
            }
        }
        // Puddles: glossy patches reflecting cool sky light, brightest in the middle, soft at the edge.
        int skyRefl = Colors.lerp(0xFF7F9CC8, Themes.current().accent2(), 0.3f);
        Vec3d camP = mc.gameRenderer.getCamera().getCameraPos();
        for (float[] pd : puddles) {
            float cx = (float) (pd[0] - camP.x), cy = (float) (pd[1] - camP.y), cz = (float) (pd[2] - camP.z), r = pd[3];
            float shimmer = 0.75f + 0.25f * (float) Math.sin(time * 1.3 + pd[0] * 0.7 + pd[2]);
            int mid = Colors.withAlpha(skyRefl, (int) (70 * shimmer)), edge = Colors.withAlpha(skyRefl, 0);
            for (int k = 0; k < 18; k++) {
                double a0 = k * Math.PI * 2 / 18, a1 = (k + 1) * Math.PI * 2 / 18;
                float w0 = r * (0.8f + 0.2f * (float) Math.sin(a0 * 3 + pd[0])), w1 = r * (0.8f + 0.2f * (float) Math.sin(a1 * 3 + pd[0]));
                quad2(vc, e, cx, cy, cz, mid, cx + (float) Math.cos(a0) * w0, cy, cz + (float) Math.sin(a0) * w0, edge,
                        cx + (float) Math.cos(a1) * w1, cy, cz + (float) Math.sin(a1) * w1, edge, cx, cy, cz, mid);
            }
        }
        // Puddle ripples: thin rings of light spreading on the ground where drops land.
        Vec3d camR = mc.gameRenderer.getCamera().getCameraPos();
        for (float[] rp : ripples) {
            float age = (time - rp[3]) / 0.9f;
            if (age < 0 || age > 1) continue;
            float r0 = 0.05f + age * 0.55f, r1 = r0 + 0.035f;
            int ca = Colors.withAlpha(0xFFCFE3FF, (int) (150 * (1 - age)));
            float cx = (float) (rp[0] - camR.x), cy = (float) (rp[1] - camR.y), cz = (float) (rp[2] - camR.z);
            for (int k = 0; k < 20; k++) {
                double a0 = k * Math.PI * 2 / 20, a1 = (k + 1) * Math.PI * 2 / 20;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                quad2(vc, e, cx + c0 * r0, cy, cz + s0 * r0, ca, cx + c0 * r1, cy, cz + s0 * r1, 0, cx + c1 * r1, cy, cz + s1 * r1, 0, cx + c1 * r0, cy, cz + s1 * r0, ca);
            }
        }
        Vec3d look = Vec3d.ZERO;
        for (float[] st : stars) {
            float age = time - st[6], f = age / 1.2f;
            Vec3d head = new Vec3d(st[0] + st[3] * age * 60, st[1] + st[4] * age * 60, st[2] + st[5] * age * 60);
            Vec3d dir = new Vec3d(st[3], st[4], st[5]).normalize();
            Vec3d side = dir.crossProduct(head.subtract(look).normalize()).normalize();
            float bright = (float) Math.sin(Math.PI * Math.min(1, f));
            Vec3d tail = head.subtract(dir.multiply(18));
            int hc = Colors.withAlpha(0xFFFFFFFF, (int) (255 * bright)), tc = Colors.withAlpha(t.accent(), 0);
            float w = 0.55f;
            quad2(vc, e, (float) (head.x + side.x * w), (float) (head.y + side.y * w), (float) (head.z + side.z * w), hc,
                    (float) (head.x - side.x * w), (float) (head.y - side.y * w), (float) (head.z - side.z * w), hc,
                    (float) tail.x, (float) tail.y, (float) tail.z, tc, (float) tail.x, (float) tail.y, (float) tail.z, tc);
        }
        if (ctx.consumers() instanceof net.minecraft.client.render.VertexConsumerProvider.Immediate imm) imm.draw(layer);
    }

    /** One quad, both windings (the glow layer is seen from either side). Positions are relative to the camera. */
    private static void quad2(net.minecraft.client.render.VertexConsumer vc, net.minecraft.client.util.math.MatrixStack.Entry e,
                              float x0, float y0, float z0, int c0, float x1, float y1, float z1, int c1,
                              float x2, float y2, float z2, int c2, float x3, float y3, float z3, int c3) {
        vc.vertex(e, x0, y0, z0).color(c0); vc.vertex(e, x1, y1, z1).color(c1); vc.vertex(e, x2, y2, z2).color(c2); vc.vertex(e, x3, y3, z3).color(c3);
        vc.vertex(e, x3, y3, z3).color(c3); vc.vertex(e, x2, y2, z2).color(c2); vc.vertex(e, x1, y1, z1).color(c1); vc.vertex(e, x0, y0, z0).color(c0);
    }

    /**
     * Shader post-processing over the world (postfx.fsh): bloom on bright areas, HSL-style saturation and vibrance,
     * the chosen colour grade, a smooth vignette and fine film grain. Drawn under the HUD (called from RefractHud).
     */
    public void drawGrade(net.minecraft.client.gui.DrawContext ctx, float sw, float sh) {
        int preset = switch (grade.get()) { case "Cinematic" -> 1; case "Dream" -> 2; case "Noir" -> 3; case "Neon" -> 4; default -> 0; };
        int acc = Themes.current().accent();
        float[] hsb = java.awt.Color.RGBtoHSB(Colors.r(acc), Colors.g(acc), Colors.b(acc), null);
        Gfx.begin(ctx); // runs in its own early HUD element, so it opens its own draw bracket
        try {
            // Post first (it repaints the whole screen from the world copy), then the sky on its own layer above it.
            Gfx.post(sw, sh, bloom.asFloat(), saturation.asFloat(), vignetteAmt.asFloat(), preset == 0 ? 0 : gradeStrength.asFloat(), preset, hsb[0]);
            Gfx.newLayer();
            drawSpace(sw, sh);
            drawRain(sw, sh);
        } finally {
            Gfx.end();
        }
    }

    /** Kept for callers that already run inside a GUI frame, where the shared context is valid. */
    public void drawGrade(float sw, float sh) {
        Gfx.post(sw, sh, bloom.asFloat(), saturation.asFloat(), vignetteAmt.asFloat(),
                grade.is("Off") ? 0 : gradeStrength.asFloat(),
                switch (grade.get()) { case "Cinematic" -> 1; case "Dream" -> 2; case "Noir" -> 3; case "Neon" -> 4; default -> 0; },
                java.awt.Color.RGBtoHSB(Colors.r(Themes.current().accent()), Colors.g(Themes.current().accent()), Colors.b(Themes.current().accent()), null)[0]);
        Gfx.newLayer();
        drawSpace(sw, sh);
        drawRain(sw, sh);
    }

    private void drawRain(float sw, float sh) {
        if (!rain.on() || mc.player == null) return;
        Gfx.newLayer();
        Gfx.rain(sw, sh, 0.85f, 0.5f + mc.player.getPitch() / 180f);
    }

    private void drawSpace(float sw, float sh) {
        int scene = switch (space.get()) { case "Planets" -> 1; case "Black Hole" -> 2; case "Nebula" -> 3; case "Galaxy" -> 4; case "End" -> 5; default -> 0; };
        if (scene > 0) Gfx.sky(sw, sh, scene);
    }

    /** The colour the sky leans toward in the chosen mode. */
    private int target() {
        Theme t = Themes.current();
        return switch (sky.get()) {
            case "Theme" -> Colors.lerp(Colors.shade(t.accent2(), -0.45f), t.accent(), 0.25f);
            case "Sunset" -> Colors.lerp(t.accent(), 0xFFFF8A4C, 0.55f);
            case "Void" -> 0xFF020205;
            default -> Colors.lerp(0xFF05060C, t.accent2(), 0.16f);
        };
    }

    /** Recolours the sky for this frame (called from SkyRenderingMixin). */
    public void applySky(SkyRenderState s) {
        if (sky.is("Off")) return;
        float k = strength.asFloat();
        boolean night = sky.is("Aurora Night") || sky.is("Void");
        s.skyColor = (Colors.lerp(s.skyColor | 0xFF000000, target(), night ? k : k * 0.7f) & 0xFFFFFF) | (s.skyColor & 0xFF000000);
        if (night) s.starBrightness = Math.max(s.starBrightness, 0.95f * k);
        if (sky.is("Sunset")) s.sunriseAndSunsetColor = Colors.withAlpha(Themes.current().accent(), (int) (0xC0 * k));
    }

    /** Tints the fog toward the sky so the horizon matches (called from FogRendererMixin). */
    public void applyFog(Vector4f c) {
        if (sky.is("Off") || !fog.on()) return;
        float k = strength.asFloat() * (sky.is("Aurora Night") || sky.is("Void") ? 0.8f : 0.5f);
        int tgt = target();
        c.x += (Colors.r(tgt) / 255f - c.x) * k;
        c.y += (Colors.g(tgt) / 255f - c.y) * k;
        c.z += (Colors.b(tgt) / 255f - c.z) * k;
    }
}
