package com.trace.refract.render.knife;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Time;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.model.special.SpecialModelRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * Draws a {@link KnifeMesh} as real 3D geometry (vertex-coloured, lit by the world, with a baked metal highlight and
 * glowing accents), in the chosen wrap. In first person it plays a draw animation when the knife comes out and an
 * inspect animation on the Inspect key: the karambit spins on its ring, the butterfly knife swings its latch
 * handle round and rolls, the tactical and bowie knives toss end over end.
 */
public final class KnifeRenderer implements SpecialModelRenderer<Void> {
    public static final Identifier TYPE = Identifier.of("refract", "knife");
    private static final Identifier WHITE = Identifier.of("refract", "textures/item/knife_white.png");
    private static final Vector3f LIGHT = new Vector3f(-0.35f, 0.75f, 0.55f).normalize();

    /** When the knife was last drawn / inspected (seconds), set by Cosmetics. */
    public static float drawStart = -10, inspectStart = -10;
    /** How many times the arm has been drawn holding the knife (read by the game test). */
    public static int armRenders;
    /** True while the knife is drawn in your first-person hand (third-person placement), so animations still play. */
    public static boolean inHand;
    /** Current attack swing progress (0..1) while drawn in hand. */
    public static float swing;

    private final String kind;
    private final KnifeMesh mesh;

    public KnifeRenderer(String kind) {
        this.kind = kind;
        this.mesh = KnifeMesh.create(kind);
    }

    public record Unbaked(String kind) implements SpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(Codec.STRING.fieldOf("kind").forGetter(Unbaked::kind)).apply(i, Unbaked::new));

        @Override public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakeContext ctx) { return new KnifeRenderer(kind); }

        @Override public MapCodec<? extends SpecialModelRenderer.Unbaked> getCodec() { return CODEC; }
    }

    @Override public Void getData(ItemStack stack) { return null; }

    @Override public void collectVertices(Consumer<Vector3fc> out) {
        for (float[] q : mesh.quads) for (int i = 0; i < 4; i++) out.accept(new Vector3f(q[i * 3] / 16f, q[i * 3 + 1] / 16f, q[i * 3 + 2] / 16f));
    }

    private static void about(MatrixStack m, float px, float py, Quaternionf q) {
        m.translate(px, py, 8);
        m.multiply(q);
        m.translate(-px, -py, -8);
    }

    @Override
    public void render(Void data, ItemDisplayContext ctx, MatrixStack m, OrderedRenderCommandQueue queue, int light, int overlay, boolean glint, int outline) {
        m.push();
        m.scale(1 / 16f, 1 / 16f, 1 / 16f);
        float latch = 0;
        boolean fp = inHand || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        if (fp) {
            float now = Time.seconds();
            float d = (now - drawStart) / 0.5f;
            if (d >= 0 && d < 1) {
                float e = 1 - Anim.easeOutBack(d);
                m.translate(0, -7 * e, 0);
                about(m, 4, 4, new Quaternionf().rotateZ((float) Math.toRadians(-85 * e)));
            }
            // Slash: on each attack the blade whips through an arc around the grip and flicks back, plus a gentle idle sway.
            if (inHand) {
                float sw = (float) Math.sin(Math.PI * swing);
                float flick = (float) Math.sin(Math.PI * Math.min(1, swing * 1.6f));
                about(m, 5, 5, new Quaternionf().rotateZ((float) Math.toRadians(-80 * flick + 18 * sw * sw)).rotateX((float) Math.toRadians(22 * sw)));
                about(m, 5, 5, new Quaternionf().rotateZ((float) Math.toRadians(2.2 * Math.sin(now * 1.7))));
            }
            float t = (now - inspectStart) / mesh.inspectSeconds;
            if (t >= 0 && t < 1) {
                float[] p = mesh.pivot;
                switch (kind) {
                    case "karambit" -> about(m, p[0], p[1], new Quaternionf().rotateZ((float) Math.toRadians(-720 * Anim.easeInOutCubic(t))));
                    case "butterfly" -> {
                        latch = -360 * Anim.easeInOutCubic(Anim.clamp01(t / 0.45f)) + 360 * Anim.easeInOutCubic(Anim.clamp01((t - 0.62f) / 0.38f));
                        float roll = 360 * Anim.easeInOutCubic(Anim.clamp01((t - 0.3f) / 0.45f));
                        about(m, p[0], p[1], new Quaternionf().rotateAxis((float) Math.toRadians(roll), 0.7071f, 0.7071f, 0));
                    }
                    default -> {
                        m.translate(0, (float) Math.sin(Math.PI * t) * 5, 0);
                        about(m, p[0], p[1], new Quaternionf().rotateAxis((float) Math.toRadians(360 * Anim.easeInOutCubic(t)), -0.7071f, 0.7071f, 0));
                    }
                }
            }
        }
        RenderLayer layer = RenderLayers.entitySolid(WHITE);
        int[] pal = palette();
        queue.submitCustom(m, layer, (entry, vc) -> emit(entry, vc, 0, pal, light, overlay));
        if (mesh.latchPin != null) {
            m.push();
            if (latch != 0) about(m, mesh.latchPin[0], mesh.latchPin[1], new Quaternionf().rotateZ((float) Math.toRadians(latch)));
            queue.submitCustom(m, layer, (entry, vc) -> emit(entry, vc, 1, pal, light, overlay));
            m.pop();
        }
        m.pop();
    }

    private void emit(MatrixStack.Entry e, VertexConsumer vc, int group, int[] pal, int light, int overlay) {
        for (float[] q : mesh.quads) {
            if ((int) q[17] != group) continue;
            int mat = (int) q[15];
            int base = color(mat, q[16], pal);
            float spec = mat == KnifeMesh.HANDLE ? 0.22f : mat == KnifeMesh.ACCENT ? 0f : 0.8f;
            int li = mat == KnifeMesh.ACCENT ? 0xF000F0 : light;
            for (int i = 0; i < 4; i++) {
                float nx = q[18 + i * 3], ny = q[19 + i * 3], nz = q[20 + i * 3];
                // Baked studio light: a tight highlight plus a soft fill, per vertex, so curved parts gleam.
                float d = Math.max(0, nx * LIGHT.x + ny * LIGHT.y + nz * LIGHT.z);
                float s = ((float) Math.pow(d, 14) * 0.9f + d * d * 0.12f) * spec;
                int col = Colors.lerp(base, 0xFFFFFFFF, Math.min(1f, s)) | 0xFF000000;
                vc.vertex(e, q[i * 3], q[i * 3 + 1], q[i * 3 + 2]).color(col).texture(0.5f, 0.5f).overlay(overlay).light(li).normal(e, nx, ny, nz);
            }
        }
    }

    /** Wrap palette: blade base, blade middle, blade tip, handle, accent, fittings. */
    private static int[] palette() {
        var c = ModuleManager.cosmetics;
        String w = c == null ? "Stainless" : c.knifeWrap.get();
        var t = Themes.current();
        return switch (w) {
            case "Fade" -> new int[]{0xFF8C5BFF, 0xFFFF5FB4, 0xFFFFD25A, 0xFF1C1D24, 0xFFFF7AC8, 0xFFB6BCC9};
            case "Theme" -> new int[]{t.accent(), Colors.lerp(t.accent(), t.accent2(), 0.5f), t.accent2(), 0xFF1B1C23, t.accent(), 0xFFB6BCC9};
            case "Ruby" -> new int[]{0xFF7A0618, 0xFFC4122E, 0xFFFF4D6D, 0xFF17171C, 0xFFFF3355, 0xFFC9CED8};
            case "Sapphire" -> new int[]{0xFF0B1F7A, 0xFF1E5BE0, 0xFF5CC8FF, 0xFF15161C, 0xFF4DA3FF, 0xFFC9CED8};
            case "Emerald" -> new int[]{0xFF06502C, 0xFF0FA35A, 0xFF5CFFB0, 0xFF15181A, 0xFF34D399, 0xFFC9CED8};
            case "Gold" -> new int[]{0xFF8A5A12, 0xFFD9A43A, 0xFFFFE08A, 0xFF231A10, 0xFFFFC94D, 0xFFE8C77A};
            case "Midnight" -> new int[]{0xFF14141C, 0xFF1D1D2A, 0xFF2A2A3D, 0xFF0E0E13, t.accent(), 0xFF3A3A48};
            default -> new int[]{0xFFAFB5C2, 0xFFC9CED9, 0xFFDDE1EA, 0xFF22252D, t.accent(), 0xFFA9AFBD};
        };
    }

    private static int color(int mat, float u, int[] p) {
        int blade = u < 0.5f ? Colors.lerp(p[0], p[1], u * 2) : Colors.lerp(p[1], p[2], (u - 0.5f) * 2);
        return switch (mat) {
            case KnifeMesh.EDGE -> Colors.lerp(blade, 0xFFFFFFFF, 0.45f);
            case KnifeMesh.SPINE -> Colors.shade(blade, -0.35f);
            case KnifeMesh.HANDLE -> p[3];
            case KnifeMesh.ACCENT -> p[4];
            case KnifeMesh.STEEL -> p[5];
            default -> blade;
        };
    }
}
