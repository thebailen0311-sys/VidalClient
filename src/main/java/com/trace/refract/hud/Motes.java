package com.trace.refract.hud;

import com.trace.refract.render.Esp;
import com.trace.refract.render.Gfx;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Colors;
import com.trace.refract.util.WorldToScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Refract's own particles: soft anti-aliased glow motes simulated in the world and drawn smooth (no Minecraft
 * particle textures, nothing pixelated). Each mote drifts, fades in and out, shifts between two colours over its
 * life, shrinks with distance, and is hidden when terrain blocks the view of it.
 */
public final class Motes {
    private static final class Mote {
        double x, y, z, px, py, pz, vx, vy, vz;
        int age, life, c1, c2;
        float size;
        boolean visible = true;
    }

    private static final List<Mote> LIST = new ArrayList<>();
    private static final int MAX = 160;
    private static int ticks;

    private Motes() {}

    /** Adds a mote: position, velocity (blocks/tick), life in ticks, size (roughly blocks), colour from c1 to c2. */
    public static void spawn(double x, double y, double z, double vx, double vy, double vz, int life, float size, int c1, int c2) {
        if (LIST.size() >= MAX) LIST.remove(0);
        Mote m = new Mote();
        m.x = m.px = x; m.y = m.py = y; m.z = m.pz = z;
        m.vx = vx; m.vy = vy; m.vz = vz;
        m.life = life; m.size = size; m.c1 = c1; m.c2 = c2;
        LIST.add(m);
    }

    /** Once per client tick: move, age, and re-check line of sight a quarter of the motes at a time. */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) { LIST.clear(); return; }
        ticks++;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        for (int i = LIST.size() - 1; i >= 0; i--) {
            Mote m = LIST.get(i);
            if (++m.age >= m.life) { LIST.remove(i); continue; }
            m.px = m.x; m.py = m.y; m.pz = m.z;
            m.x += m.vx; m.y += m.vy; m.z += m.vz;
            m.vx *= 0.97; m.vy *= 0.97; m.vz *= 0.97;
            if ((i + ticks) % 4 == 0) {
                var hit = mc.world.raycast(new RaycastContext(cam, new Vec3d(m.x, m.y, m.z), RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, mc.player));
                m.visible = hit.getType() == HitResult.Type.MISS;
            }
        }
    }

    /** Draws every visible mote at its interpolated position (inside a Gfx bracket, before the HUD panels). */
    public static void render() {
        if (LIST.isEmpty() || !WorldToScreen.ready()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        float td = Esp.tickDelta();
        float fov = mc.options.getFov().getValue();
        float pxPerBlock = mc.getWindow().getScaledHeight() / (2f * (float) Math.tan(Math.toRadians(fov) / 2));
        // All motes go out as one batch of shader lights (glow.fsh, additive): smooth cores, soft halos, a faint flare.
        float[] data = new float[LIST.size() * 4];
        int n = 0;
        for (Mote m : LIST) {
            if (!m.visible) continue;
            double x = m.px + (m.x - m.px) * td, y = m.py + (m.y - m.py) * td, z = m.pz + (m.z - m.pz) * td;
            double dist = cam.distanceTo(new Vec3d(x, y, z));
            if (dist < 0.4) continue;
            WorldToScreen.Point p = WorldToScreen.project(new Vec3d(x, y, z));
            if (!p.onScreen()) continue;
            float f = (m.age + td) / m.life;
            float a = Anim.clamp01(f / 0.15f) * Anim.clamp01((1 - f) / 0.35f) * Gfx.alpha;
            float r = Math.max(1.2f, Math.min(22f, m.size * pxPerBlock / (float) dist * 3.4f));
            int col = Colors.withAlpha(Colors.lerp(m.c1, m.c2, f), (int) (255 * a));
            data[n * 4] = p.x(); data[n * 4 + 1] = p.y(); data[n * 4 + 2] = r; data[n * 4 + 3] = Float.intBitsToFloat(col);
            n++;
        }
        Gfx.lights(data, n);
    }
}
