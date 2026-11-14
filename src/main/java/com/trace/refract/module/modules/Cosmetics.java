package com.trace.refract.module.modules;

import com.trace.refract.hud.Motes;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.render.knife.KnifeRenderer;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Time;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Client-side cosmetics in the theme colours (only you see them):
 * <ul>
 *   <li>Knife: swords are drawn as a real 3D knife (karambit, butterfly, tactical or bowie) in the chosen wrap, with
 *       a draw animation and an inspect animation on the Inspect key (V).</li>
 *   <li>A glowing gradient trail that retracts when you stop, sparkle motes as you move, a spinning halo (third
 *       person) and an aura of glowing motes. All motes are Refract's own smooth glows, not Minecraft particles.</li>
 * </ul>
 */
public class Cosmetics extends Module {
    public final ModeSetting knife = add(new ModeSetting("Knife", "Karambit", "Off", "Karambit", "Butterfly", "Tactical", "Bowie"));
    public final ModeSetting knifeWrap = add(new ModeSetting("Knife Wrap", "Fade", "Stainless", "Fade", "Theme", "Ruby", "Sapphire", "Emerald", "Gold", "Midnight"));
    public final BoolSetting cape = add(new BoolSetting("Vanta Cape", true));
    public final BoolSetting elytra = add(new BoolSetting("Vanta Elytra", true));
    public final BoolSetting trail = add(new BoolSetting("Trail", true));
    public final NumberSetting trailLength = add(new NumberSetting("Trail Length", 40, 10, 120, 5, " pts"));
    public final BoolSetting sparkles = add(new BoolSetting("Trail Sparkles", true));
    public final BoolSetting halo = add(new BoolSetting("Halo", true));
    public final BoolSetting aura = add(new BoolSetting("Aura", false));
    public final KeyBinding inspectKey;
    private final ArrayDeque<Vec3d> points = new ArrayDeque<>();
    private final Random rng = new Random();
    private int ticks, lastSlot = -1;
    private boolean lastKnife;

    public Cosmetics() {
        super("Cosmetics", "3D knives with wraps and animations (Inspect: V), glowing trail, sparkles, halo and aura.", Category.RENDER);
        com.trace.refract.mixin.SpecialModelTypesAccessor.refract$idMapper().put(KnifeRenderer.TYPE, KnifeRenderer.Unbaked.CODEC);
        inspectKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.refract.inspect", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
                KeyBinding.Category.create(Identifier.of("refract", "cosmetics"))));
    }

    /** True when swords should be drawn as the chosen knife. */
    public boolean knifeOn() { return isEnabled() && !knife.is("Off"); }

    /** Item model id for the chosen knife. */
    public Identifier knifeModel() { return Identifier.of("refract", knife.get().toLowerCase(java.util.Locale.ROOT)); }

    @Override public void onDisable() { points.clear(); }

    @Override public void onTick() {
        if (mc.player == null || mc.world == null) return;
        ticks++;
        // Knife: play the draw animation whenever a sword comes out, inspect on the key.
        ItemStack held = mc.player.getMainHandStack();
        boolean isKnife = knifeOn() && net.minecraft.registry.Registries.ITEM.getId(held.getItem()).getPath().endsWith("_sword");
        int slot = mc.player.getInventory().getSelectedSlot();
        if (isKnife && (!lastKnife || slot != lastSlot)) KnifeRenderer.drawStart = Time.seconds();
        lastKnife = isKnife;
        lastSlot = slot;
        while (inspectKey.wasPressed()) if (isKnife) KnifeRenderer.inspectStart = Time.seconds();

        Vec3d p = mc.player.getEntityPos();
        boolean moving = points.isEmpty() || points.peekLast().squaredDistanceTo(p) > 0.0025;
        if (moving) points.addLast(p);
        else if (!points.isEmpty()) { points.pollFirst(); if (!points.isEmpty()) points.pollFirst(); } // retract when still
        while (points.size() > trailLength.asInt()) points.pollFirst();
        Theme t = Themes.current();
        double speed = mc.player.getVelocity().horizontalLength();
        if (sparkles.on() && speed > 0.08) {
            for (int i = 0; i < 2; i++) {
                Motes.spawn(p.x + (rng.nextDouble() - 0.5) * 0.5, p.y + 0.1 + rng.nextDouble() * 0.3, p.z + (rng.nextDouble() - 0.5) * 0.5,
                        (rng.nextDouble() - 0.5) * 0.02, 0.025 + rng.nextDouble() * 0.02, (rng.nextDouble() - 0.5) * 0.02,
                        18 + rng.nextInt(14), 0.05f + rng.nextFloat() * 0.04f, t.accent(), t.accent2());
            }
        }
        if (aura.on() && ticks % 2 == 0) {
            double a = rng.nextDouble() * Math.PI * 2, r = 0.55 + rng.nextDouble() * 0.25;
            Motes.spawn(p.x + Math.cos(a) * r, p.y + rng.nextDouble() * mc.player.getHeight(), p.z + Math.sin(a) * r,
                    0, 0.03, 0, 26, 0.06f, Colors.lerp(t.accent(), 0xFFFFFFFF, 0.3f), t.accent2());
        }
    }

    @Override public void onWorldRender(float tickDelta) {
        if (mc.player == null) return;
        Theme t = Themes.current();
        Vec3d now = Esp.pos(mc.player);
        if (trail.on() && points.size() > 1) {
            List<Vec3d> pts = new ArrayList<>(points);
            pts.add(now);
            int n = pts.size();
            for (int i = 1; i < n; i++) {
                float f = (float) i / (n - 1);
                int col = Colors.lerp(t.accent2(), t.accent(), f);
                int a = (int) (0xE8 * f * f);
                Vec3d p0 = pts.get(i - 1), p1 = pts.get(i);
                // A soft wide glow under a bright core, at two heights: reads as a glowing ribbon.
                Esp.line(p0.add(0, 0.08, 0), p1.add(0, 0.08, 0), Colors.withAlpha(col, a / 3), 6f, false);
                Esp.line(p0.add(0, 0.08, 0), p1.add(0, 0.08, 0), Colors.withAlpha(Colors.lerp(col, 0xFFFFFFFF, 0.35f), a), 2.2f, false);
                Esp.line(p0.add(0, 0.32, 0), p1.add(0, 0.32, 0), Colors.withAlpha(col, a / 2), 1.6f, false);
            }
        }
        if (halo.on() && !mc.options.getPerspective().isFirstPerson()) {
            float time = Time.seconds();
            double cy = now.y + mc.player.getHeight() + 0.32 + 0.035 * Math.sin(time * 2.2);
            double r = 0.3, spin = time * 1.4;
            int segs = 36;
            for (int i = 0; i < segs; i++) {
                double a0 = spin + i * Math.PI * 2 / segs, a1 = spin + (i + 1) * Math.PI * 2 / segs;
                int col = Colors.lerp(t.accent(), t.accent2(), (float) (0.5 + 0.5 * Math.sin(i * Math.PI * 2 / segs)));
                Vec3d q0 = new Vec3d(now.x + Math.cos(a0) * r, cy, now.z + Math.sin(a0) * r);
                Vec3d q1 = new Vec3d(now.x + Math.cos(a1) * r, cy, now.z + Math.sin(a1) * r);
                Esp.line(q0, q1, Colors.withAlpha(col, 0x50), 7f, false);
                Esp.line(q0, q1, Colors.withAlpha(Colors.lerp(col, 0xFFFFFFFF, 0.4f), 0xF0), 2.4f, false);
            }
        }
    }
}
