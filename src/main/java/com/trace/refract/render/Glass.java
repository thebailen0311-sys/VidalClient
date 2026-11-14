package com.trace.refract.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.trace.refract.util.Time;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2f;

/**
 * Liquid glass on the GPU. Once per frame the rendered world is copied to a texture; each glass panel is then one
 * quad drawn with the {@link Shaders#LIQUID_GLASS} fragment shader, which works out the rounded shape, the lens
 * refraction, colour fringing, reflections and specular light for every pixel (see liquid_glass.fsh).
 */
public final class Glass {
    private static GpuTexture tex;
    private static GpuTextureView view;
    private static TextureSetup setup;
    private static int texW, texH;
    private static long copiedFrame = -1;

    private Glass() {}

    /** Copies the world image for this frame (once). Returns false if the copy is not available. */
    static boolean ensure() {
        long f = Time.frameId();
        if (copiedFrame == f) return setup != null;
        copiedFrame = f;
        try {
            var fb = MinecraftClient.getInstance().getFramebuffer();
            int w = fb.textureWidth, h = fb.textureHeight;
            GpuDevice dev = RenderSystem.getDevice();
            if (tex == null || w != texW || h != texH) {
                if (view != null) view.close();
                if (tex != null) tex.close();
                tex = dev.createTexture("refract-glass", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, w, h, 1, 1);
                view = dev.createTextureView(tex);
                setup = TextureSetup.of(view, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
                texW = w;
                texH = h;
            }
            dev.createCommandEncoder().copyTextureToTexture(fb.getColorAttachment(), tex, 0, 0, 0, 0, 0, w, h);
            return true;
        } catch (Throwable t) {
            setup = null;
            return false;
        }
    }

    /**
     * One glass panel at (x, y, w, h) in physical pixels under the current matrix. {@code bend} is how far the rim pulls
     * the image (px), {@code mode} 0 liquid / 1 frosted, {@code alpha} overall opacity.
     */
    static void panel(net.minecraft.client.gui.DrawContext ctx, java.util.ArrayDeque<ScreenRect> clip, float x, float y, float w, float h, float r,
                      float bend, int mode, float alpha) {
        if (w < 4 || h < 4 || alpha <= 0.01f || !ensure()) return;
        Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
        float rf = Math.max(0f, Math.min(1f, r / (Math.min(w, h) / 2f)));
        int b = Math.round(rf * 255), a = Math.round(Math.min(1f, alpha) * 255);
        ScreenRect top = clip.peek();
        ScreenRect bounds = new ScreenRect((int) Math.floor(x), (int) Math.floor(y), (int) Math.ceil(w) + 1, (int) Math.ceil(h) + 1).transformEachVertex(pose);
        if (top != null) bounds = top.intersection(bounds);
        if (bounds == null) return;
        ((com.trace.refract.mixin.DrawContextAccessor) ctx).refract$state().addSimpleElement(new Quad(setup, pose, x, y, w, h, b, a, bend, mode, top, bounds));
    }

    private record Quad(TextureSetup textureSetup, Matrix3x2f pose, float x, float y, float w, float h, int b, int a, float bend, int mode,
                        ScreenRect scissorArea, ScreenRect bounds) implements SimpleGuiElementRenderState {
        @Override public RenderPipeline pipeline() { return Shaders.LIQUID_GLASS; }

        @Override public void setupVertices(VertexConsumer vc) {
            vc.vertex(pose, x, y).texture(bend, mode).color(0, 0, b, a);
            vc.vertex(pose, x, y + h).texture(bend, mode).color(0, 255, b, a);
            vc.vertex(pose, x + w, y + h).texture(bend, mode).color(255, 255, b, a);
            vc.vertex(pose, x + w, y).texture(bend, mode).color(255, 0, b, a);
        }
    }

    /**
     * Post-processing pass over the whole screen (postfx.fsh). Values 0..1 except {@code saturation} (0..2);
     * {@code preset} 0 none, 1 cinematic, 2 dream, 3 noir, 4 neon; {@code hue} 0..1 is the theme accent hue for Dream.
     */
    static void post(net.minecraft.client.gui.DrawContext ctx, float w, float h, float bloom, float saturation, float vignette, float grade, int preset, float hue) {
        if (!ensure()) return;
        Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
        int c = (Math.round(clamp01(grade) * 255) << 24) | (Math.round(clamp01(bloom) * 255) << 16) | (Math.round(clamp01(saturation / 2f) * 255) << 8) | Math.round(clamp01(vignette) * 255);
        float u = Time.seconds() % 1000f, v = preset + Math.min(0.999f, Math.max(0f, hue));
        ScreenRect bounds = new ScreenRect(0, 0, (int) Math.ceil(w) + 1, (int) Math.ceil(h) + 1).transformEachVertex(pose);
        ((com.trace.refract.mixin.DrawContextAccessor) ctx).refract$state().addSimpleElement(new Post(setup, pose, w, h, u, v, c, bounds));
    }

    private static float clamp01(float f) { return Math.max(0f, Math.min(1f, f)); }

    private record Post(TextureSetup textureSetup, Matrix3x2f pose, float w, float h, float u, float v, int c, ScreenRect bounds) implements SimpleGuiElementRenderState {
        @Override public RenderPipeline pipeline() { return Shaders.POSTFX; }

        @Override public ScreenRect scissorArea() { return null; }

        @Override public void setupVertices(VertexConsumer vc) {
            vc.vertex(pose, 0, 0).texture(u, v).color(c);
            vc.vertex(pose, 0, h).texture(u, v).color(c);
            vc.vertex(pose, w, h).texture(u, v).color(c);
            vc.vertex(pose, w, 0).texture(u, v).color(c);
        }
    }

    /** A batch of procedural glows (additive light): {x, y, radius, argb as float bits} per light, physical pixels. */
    static void glows(net.minecraft.client.gui.DrawContext ctx, float[] data, int count) {
        if (count <= 0) return;
        Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float x = data[i * 4], y = data[i * 4 + 1], r = data[i * 4 + 2];
            minX = Math.min(minX, x - r); minY = Math.min(minY, y - r); maxX = Math.max(maxX, x + r); maxY = Math.max(maxY, y + r);
        }
        ScreenRect bounds = new ScreenRect((int) minX, (int) minY, (int) Math.ceil(maxX - minX) + 1, (int) Math.ceil(maxY - minY) + 1).transformEachVertex(pose);
        float[] copy = java.util.Arrays.copyOf(data, count * 4);
        ((com.trace.refract.mixin.DrawContextAccessor) ctx).refract$state().addSimpleElement(new Glows(pose, copy, count, bounds));
    }

    private record Glows(Matrix3x2f pose, float[] d, int count, ScreenRect bounds) implements SimpleGuiElementRenderState {
        @Override public RenderPipeline pipeline() { return Shaders.GLOW; }

        @Override public TextureSetup textureSetup() { return TextureSetup.empty(); }

        @Override public ScreenRect scissorArea() { return null; }

        @Override public void setupVertices(VertexConsumer vc) {
            for (int i = 0; i < count; i++) {
                float x = d[i * 4], y = d[i * 4 + 1], r = d[i * 4 + 2];
                int c = Float.floatToRawIntBits(d[i * 4 + 3]);
                vc.vertex(pose, x - r, y - r).texture(-1, -1).color(c);
                vc.vertex(pose, x - r, y + r).texture(-1, 1).color(c);
                vc.vertex(pose, x + r, y + r).texture(1, 1).color(c);
                vc.vertex(pose, x + r, y - r).texture(1, -1).color(c);
            }
        }
    }
}
