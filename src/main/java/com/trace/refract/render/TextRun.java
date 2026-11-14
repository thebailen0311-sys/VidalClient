package com.trace.refract.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2f;

/**
 * A whole string as a single GUI element: every glyph quad of the line in one record. The game's GUI renderer does
 * per-element work (layering, sorting, batching), so this replaces dozens of elements per label with one.
 */
record TextRun(TextureSetup textureSetup, Matrix3x2f pose, int[] rect, float[] uv, int count, int color,
               ScreenRect scissorArea, ScreenRect bounds) implements SimpleGuiElementRenderState {
    @Override public RenderPipeline pipeline() { return RenderPipelines.GUI_TEXTURED; }

    @Override public void setupVertices(VertexConsumer vc) {
        for (int i = 0; i < count; i++) {
            int x1 = rect[i * 4], y1 = rect[i * 4 + 1], x2 = rect[i * 4 + 2], y2 = rect[i * 4 + 3];
            float u1 = uv[i * 4], v1 = uv[i * 4 + 1], u2 = uv[i * 4 + 2], v2 = uv[i * 4 + 3];
            vc.vertex(pose, x1, y1).texture(u1, v1).color(color);
            vc.vertex(pose, x1, y2).texture(u1, v2).color(color);
            vc.vertex(pose, x2, y2).texture(u2, v2).color(color);
            vc.vertex(pose, x2, y1).texture(u2, v1).color(color);
        }
    }
}
