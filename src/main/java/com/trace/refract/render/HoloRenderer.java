package com.trace.refract.render;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Draws the base hologram with the base's real block models: every block keeps its actual textures and shape,
 * drawn translucent and full-bright, scaled down and spinning above the base. Placed blocks are solid enough to
 * read; the natural rock around the room is a faint ghost so the room's shape shows without hiding what is inside.
 */
public final class HoloRenderer {
    /** One block of the scene, offset from the hologram centre in block units. */
    public record Block(float x, float y, float z, BlockState state, boolean placed, int fallbackColor) {}

    /** What to draw this frame (set by Base Finder each frame; null hides it). */
    public record Scene(List<Block> blocks, Vec3d center, float scale, float angle, float alpha, float build, float span, int tint) {}

    public static Scene scene;

    private HoloRenderer() {}

    public static void register() { WorldRenderEvents.AFTER_ENTITIES.register(HoloRenderer::render); }

    private static void render(WorldRenderContext ctx) {
        long t0 = System.nanoTime();
        try { draw(ctx); } finally { com.trace.refract.util.Prof.add("esp:hologram", System.nanoTime() - t0); }
    }

    private static void draw(WorldRenderContext ctx) {
        Scene sc = scene;
        scene = null; // must be refreshed every frame
        if (sc == null || sc.alpha() < 0.02f || sc.blocks().isEmpty()) return;
        MatrixStack m = ctx.matrices();
        VertexConsumerProvider cons = ctx.consumers();
        if (m == null || cons == null) return;
        Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        RenderLayer layer = RenderLayers.entityTranslucent(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        VertexConsumer buf = cons.getBuffer(layer);
        var brm = MinecraftClient.getInstance().getBlockRenderManager();
        Tint tint = new Tint(buf);
        m.push();
        m.translate(sc.center().x - cam.x, sc.center().y - cam.y, sc.center().z - cam.z);
        m.multiply(RotationAxis.POSITIVE_Y.rotation(sc.angle()));
        m.scale(sc.scale(), sc.scale(), sc.scale());
        for (Block b : sc.blocks()) {
            // Assemble bottom-up: each block fades in as the build front passes its height.
            float h = b.y() / sc.span() + 0.5f;
            float in = Math.max(0f, Math.min(1f, (sc.build() * 1.6f - 0.3f - h) / 0.3f));
            if (in <= 0.01f) continue;
            tint.alpha = sc.alpha() * in * (b.placed() ? 0.96f : 0.3f);
            tint.tint = b.placed() ? 0xFFFFFFFF : sc.tint();
            m.push();
            m.translate(b.x() - 0.5f, b.y() - 0.5f, b.z() - 0.5f);
            if (b.state().getRenderType() == BlockRenderType.MODEL) brm.renderBlockAsEntity(b.state(), m, l -> tint, 0xF000F0, OverlayTexture.DEFAULT_UV);
            else { // chests, shulkers, signs: drawn by block-entity renderers, so a clean coloured cube stands in
                tint.tint = b.fallbackColor();
                m.translate(0.08f, 0.08f, 0.08f);
                m.scale(0.84f, 0.84f, 0.84f);
                brm.renderBlockAsEntity(net.minecraft.block.Blocks.WHITE_CONCRETE.getDefaultState(), m, l -> tint, 0xF000F0, OverlayTexture.DEFAULT_UV);
            }
            m.pop();
        }
        m.pop();
        if (cons instanceof VertexConsumerProvider.Immediate imm) imm.draw(layer);
    }

    /** Passes vertices through, multiplying their colour by a tint and alpha. */
    private static final class Tint implements VertexConsumer {
        final VertexConsumer d;
        float alpha = 1f;
        int tint = 0xFFFFFFFF;

        Tint(VertexConsumer d) { this.d = d; }

        @Override public VertexConsumer vertex(float x, float y, float z) { d.vertex(x, y, z); return this; }

        @Override public VertexConsumer color(int r, int g, int b, int a) {
            d.color(r * ((tint >> 16) & 255) / 255, g * ((tint >> 8) & 255) / 255, b * (tint & 255) / 255, (int) (a * alpha));
            return this;
        }

        @Override public VertexConsumer color(int argb) { return color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24)); }

        @Override public VertexConsumer texture(float u, float v) { d.texture(u, v); return this; }

        @Override public VertexConsumer overlay(int u, int v) { d.overlay(u, v); return this; }

        @Override public VertexConsumer light(int u, int v) { d.light(u, v); return this; }

        @Override public VertexConsumer normal(float x, float y, float z) { d.normal(x, y, z); return this; }

        @Override public VertexConsumer lineWidth(float w) { d.lineWidth(w); return this; }
    }
}
