package com.trace.refract.render;

import com.trace.refract.module.ModuleManager;
import com.trace.refract.theme.Theme;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Time;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Cosmetic hats on your own player (third person and in the inventory), built as 3D geometry on the head bone in
 * the theme colours: a glassy China hat with a glowing rim, or a crown with spikes and gems. Only you see them.
 */
public final class HatFeature extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    private static final Identifier WHITE = Identifier.of("refract", "textures/item/knife_white.png");

    public HatFeature(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> ctx) { super(ctx); }

    @Override
    public void render(MatrixStack m, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState s, float yaw, float pitch) {
        var c = ModuleManager.cosmetics;
        var mc = MinecraftClient.getInstance();
        if (c == null || c.preview || !c.isEnabled() || c.hat.is("Off") || mc.player == null || s.id != mc.player.getId()) return;
        m.push();
        getContextModel().getHead().applyTransform(m);
        Theme t = Themes.current();
        String kind = c.hat.get();
        float time = Time.seconds();
        queue.submitCustom(m, RenderLayers.entityTranslucent(WHITE), (e, vc) -> {
            if (kind.equals("Crown")) crown(e, vc, t, time, light);
            else chinaHat(e, vc, t, time, light);
        });
        m.pop();
    }

    private static void v(VertexConsumer vc, MatrixStack.Entry e, float x, float y, float z, int col, int light, float nx, float ny, float nz) {
        vc.vertex(e, x, y, z).color(col).texture(0.5f, 0.5f).overlay(net.minecraft.client.render.OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
    }

    /** Both windings, so the hat reads from inside and out. */
    private static void quad(VertexConsumer vc, MatrixStack.Entry e, float[] a, float[] b, float[] c, float[] d, int ca, int cb, int light, float[] n) {
        v(vc, e, a[0], a[1], a[2], ca, light, n[0], n[1], n[2]); v(vc, e, b[0], b[1], b[2], ca, light, n[0], n[1], n[2]);
        v(vc, e, c[0], c[1], c[2], cb, light, n[0], n[1], n[2]); v(vc, e, d[0], d[1], d[2], cb, light, n[0], n[1], n[2]);
        v(vc, e, d[0], d[1], d[2], cb, light, -n[0], -n[1], -n[2]); v(vc, e, c[0], c[1], c[2], cb, light, -n[0], -n[1], -n[2]);
        v(vc, e, b[0], b[1], b[2], ca, light, -n[0], -n[1], -n[2]); v(vc, e, a[0], a[1], a[2], ca, light, -n[0], -n[1], -n[2]);
    }

    /** Head space: model units/16, y points down; the top of the head is y = -0.5. */
    private static void chinaHat(MatrixStack.Entry e, VertexConsumer vc, Theme t, float time, int light) {
        int seg = 40;
        float apex = -0.86f, brimY = -0.5f, R = 0.62f;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float[] p0 = {(float) Math.cos(a0) * R, brimY, (float) Math.sin(a0) * R}, p1 = {(float) Math.cos(a1) * R, brimY, (float) Math.sin(a1) * R};
            float[] top = {0, apex, 0};
            float shimmer = (float) (0.5 + 0.5 * Math.sin(a0 * 3 + time * 2.0));
            int cBrim = Colors.withAlpha(Colors.lerp(t.accent(), 0xFFFFFFFF, 0.15f * shimmer), 0xC8);
            int cTop = Colors.withAlpha(Colors.lerp(t.accent2(), 0xFFFFFFFF, 0.25f), 0xE0);
            float nx = (float) Math.cos((a0 + a1) / 2) * 0.55f, nz = (float) Math.sin((a0 + a1) / 2) * 0.55f;
            quad(vc, e, p0, p1, top, top, cBrim, cTop, light, new float[]{nx, -0.83f, nz});
            // Glowing rim band just under the edge.
            float[] q0 = {p0[0] * 1.01f, brimY + 0.025f, p0[2] * 1.01f}, q1 = {p1[0] * 1.01f, brimY + 0.025f, p1[2] * 1.01f};
            int rim = Colors.withAlpha(Colors.lerp(t.accent(), 0xFFFFFFFF, 0.5f), 0xFF);
            quad(vc, e, p0, p1, q1, q0, rim, rim, 0xF000F0, new float[]{nx, 0, nz});
        }
    }

    private static void crown(MatrixStack.Entry e, VertexConsumer vc, Theme t, float time, int light) {
        int seg = 32, spikes = 8;
        float R = 0.3f, y0 = -0.47f, y1 = -0.6f, tip = -0.8f;
        int gold = Colors.lerp(0xFFFFC94D, t.accent(), 0.25f) | 0xFF000000, deep = Colors.shade(gold, -0.35f);
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float c0 = (float) Math.cos(a0) * R, s0 = (float) Math.sin(a0) * R, c1 = (float) Math.cos(a1) * R, s1 = (float) Math.sin(a1) * R;
            float nx = (float) Math.cos((a0 + a1) / 2), nz = (float) Math.sin((a0 + a1) / 2);
            quad(vc, e, new float[]{c0, y0, s0}, new float[]{c1, y0, s1}, new float[]{c1, y1, s1}, new float[]{c0, y1, s0}, deep, gold, light, new float[]{nx, 0, nz});
        }
        for (int k = 0; k < spikes; k++) {
            double a = k * Math.PI * 2 / spikes, w = Math.PI / spikes * 0.85;
            float[] l = {(float) Math.cos(a - w) * R, y1, (float) Math.sin(a - w) * R}, r = {(float) Math.cos(a + w) * R, y1, (float) Math.sin(a + w) * R};
            float[] p = {(float) Math.cos(a) * R * 0.92f, tip, (float) Math.sin(a) * R * 0.92f};
            quad(vc, e, l, r, p, p, gold, Colors.lerp(gold, 0xFFFFFFFF, 0.35f), light, new float[]{(float) Math.cos(a), -0.3f, (float) Math.sin(a)});
            // Gem near the base of each spike, glowing in the accent colours.
            float gx = (float) Math.cos(a) * R * 1.02f, gz = (float) Math.sin(a) * R * 1.02f, gy = (y0 + y1) / 2, gs = 0.03f;
            int gem = (k % 2 == 0 ? t.accent() : t.accent2()) | 0xFF000000;
            float pulse = (float) (0.75 + 0.25 * Math.sin(time * 3 + k));
            int gc = Colors.lerp(gem, 0xFFFFFFFF, 0.3f * pulse);
            float tx = -(float) Math.sin(a) * gs, tz = (float) Math.cos(a) * gs;
            quad(vc, e, new float[]{gx - tx, gy, gz - tz}, new float[]{gx, gy - gs, gz}, new float[]{gx + tx, gy, gz + tz}, new float[]{gx, gy + gs, gz}, gc, gc, 0xF000F0,
                    new float[]{(float) Math.cos(a), 0, (float) Math.sin(a)});
        }
    }
}
