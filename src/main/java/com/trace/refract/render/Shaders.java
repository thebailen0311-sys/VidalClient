package com.trace.refract.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/**
 * Vanta's own GLSL render pipelines (shaders in assets/refract/shaders/core):
 * <ul>
 *   <li>{@link #LIQUID_GLASS}: per-pixel liquid glass: rounded-rect distance field (anti-aliased edge), lens
 *       refraction, chromatic dispersion, coloured rim reflection and specular light, or a frosted disk blur.</li>
 *   <li>{@link #GLOW}: procedural soft light (core, halo, flare) with additive blending, for particles and glows.</li>
 * </ul>
 * Same uniform layout and vertex format as the game's GUI textured pipeline, so they drop into the GUI renderer.
 */
public final class Shaders {
    private Shaders() {}

    private static RenderPipeline.Builder base(String name) {
        return RenderPipeline.builder()
                .withLocation(Identifier.of("refract", "pipeline/" + name))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader(Identifier.of("refract", "core/" + name))
                .withFragmentShader(Identifier.of("refract", "core/" + name))
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withCull(false);
    }

    public static final RenderPipeline LIQUID_GLASS = base("liquid_glass").withSampler("Sampler0").withBlend(BlendFunction.TRANSLUCENT).build();

    public static final RenderPipeline GLOW = base("glow").withBlend(BlendFunction.ADDITIVE).build();

    /** Full-screen post-processing over the world: bloom, HSL saturation/vibrance, colour grades, vignette, grain. */
    /** Space sky on sky pixels only (depth-tested in the shader): stars, nebula, planets, a lensing black hole. */
    public static final RenderPipeline SKYFX = base("skyfx").withSampler("Sampler0").withSampler("Sampler1").withoutBlend().build();

    /** Procedural rain streaks (additive). */
    public static final RenderPipeline RAINFX = base("rainfx").withSampler("Sampler0").withBlend(BlendFunction.TRANSLUCENT).build();

    public static final RenderPipeline POSTFX = base("postfx").withSampler("Sampler0").withoutBlend().build();
}
