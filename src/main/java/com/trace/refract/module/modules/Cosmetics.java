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
    /** Set while the Account page renders its preview, so capes, elytra and hats stay out of the portrait. */
    public static volatile boolean preview;
    public final ModeSetting knife = add(new ModeSetting("Knife", "Karambit", "Off", "Karambit", "Butterfly", "Tactical", "Bowie"));
    public final ModeSetting knifeWrap = add(new ModeSetting("Knife Wrap", "Fade", "Stainless", "Fade", "Theme", "Ruby", "Sapphire", "Emerald", "Gold", "Midnight"));
    public final ModeSetting hat = add(new ModeSetting("Hat", "China Hat", "Off", "China Hat", "Crown"));
    public final ModeSetting swingStyle = add(new ModeSetting("Swing Style", "Slash", "Slash", "Spin", "Stab", "Flip", "Whirl"));
    public final ModeSetting handAnim = add(new ModeSetting("Hand Animation", "Smooth", "Off", "Smooth", "Spin", "Punch"));
    public final BoolSetting killEffect = add(new BoolSetting("Kill Effect", true));
    public final BoolSetting targetHud = add(new BoolSetting("Target HUD", true));
    private net.minecraft.entity.LivingEntity target;
    private float targetAt = -10, targetShow;
    private boolean killDone;
    public final BoolSetting hitBurst = add(new BoolSetting("Hit Burst", true));
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
        net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
            if (renderer instanceof net.minecraft.client.render.entity.PlayerEntityRenderer<?> pr) helper.register(new com.trace.refract.render.HatFeature(pr));
        });
        // Hit burst: a ring of light bursts out of whatever you hit.
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() && isEnabled() && hitBurst.on() && player == mc.player) burst(entity.getBoundingBox().getCenter());
            if (world.isClient() && player == mc.player && entity instanceof net.minecraft.entity.LivingEntity le) { target = le; targetAt = Time.seconds(); killDone = false; }
            return net.minecraft.util.ActionResult.PASS;
        });
        inspectKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.refract.inspect", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
                KeyBinding.Category.create(Identifier.of("refract", "cosmetics"))));
    }

    /** Kill effect: a pillar of light rises and a ring bursts outward where something you fought dies. */
    private void kill(Vec3d at) {
        Theme t = Themes.current();
        for (int i = 0; i < 40; i++) {
            double a = rng.nextDouble() * Math.PI * 2, r = rng.nextDouble() * 0.35;
            Motes.spawn(at.x + Math.cos(a) * r, at.y + rng.nextDouble() * 0.4, at.z + Math.sin(a) * r, 0, 0.12 + rng.nextDouble() * 0.12, 0,
                    24 + rng.nextInt(16), 0.06f + rng.nextFloat() * 0.04f, Colors.lerp(t.accent3(), 0xFFFFFFFF, 0.3f), t.accent());
        }
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI * 2 / 32;
            Motes.spawn(at.x, at.y + 0.1, at.z, Math.cos(a) * 0.25, 0.01, Math.sin(a) * 0.25, 14, 0.07f, t.accent2(), t.accent3());
        }
        Motes.spawn(at.x, at.y + 1, at.z, 0, 0, 0, 10, 0.6f, 0xFFFFFFFF, t.accent());
    }

    /** Target HUD: a glass card under the crosshair with the target's name, a gradient health bar and armour. */
    @Override public void onHud(Theme t, float sw, float sh) {
        if (!targetHud.on()) return;
        boolean live = target != null && !target.isRemoved() && target.isAlive() && Time.seconds() - targetAt < 4 && mc.player != null && target.squaredDistanceTo(mc.player) < 36 * 36;
        targetShow += ((live ? 1 : 0) - targetShow) * (1 - (float) Math.exp(-10 * Time.dt()));
        if (targetShow < 0.02f || target == null) return;
        float e = com.trace.refract.util.Anim.easeOutCubic(targetShow);
        float w = 150, h = 40, x = sw / 2 - w / 2, y = sh / 2 + 30 + (1 - e) * 10;
        float prev = com.trace.refract.render.Gfx.alpha;
        com.trace.refract.render.Gfx.alpha = prev * e;
        com.trace.refract.render.Gfx.frost(t, x, y, w, h, 12, 0.6f);
        float hp = target.getHealth() + target.getAbsorptionAmount(), max = Math.max(1, target.getMaxHealth());
        float frac = Math.min(1, hp / max);
        com.trace.refract.render.Gfx.text(com.trace.refract.render.Gfx.ellipsize(target.getName().getString(), w - 70, com.trace.refract.render.Gfx.BODY, com.trace.refract.render.Font.Weight.BOLD),
                x + 10, y + 6, com.trace.refract.render.Gfx.BODY, com.trace.refract.render.Font.Weight.BOLD, t.text());
        String hpText = String.format(java.util.Locale.ROOT, "%.1f", hp);
        int hpCol = Colors.lerp(0xFFF2555A, 0xFF34D399, frac);
        float hw = com.trace.refract.render.Gfx.width(hpText, com.trace.refract.render.Gfx.BODY, com.trace.refract.render.Font.Weight.BOLD);
        com.trace.refract.render.Gfx.text(hpText, x + w - 10 - hw, y + 6, com.trace.refract.render.Gfx.BODY, com.trace.refract.render.Font.Weight.BOLD, hpCol);
        com.trace.refract.render.Gfx.round(x + 10, y + 22, w - 20, 5, 2.5f, 0x26FFFFFF);
        com.trace.refract.render.Gfx.roundGradientH(x + 10, y + 22, Math.max(5, (w - 20) * frac), 5, 2.5f, Colors.lerp(hpCol, t.accent(), 0.3f), hpCol);
        float ix = x + 10;
        for (var slot : new net.minecraft.entity.EquipmentSlot[]{net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET, net.minecraft.entity.EquipmentSlot.MAINHAND}) {
            var st = target.getEquippedStack(slot);
            if (st.isEmpty()) continue;
            com.trace.refract.render.Gfx.item(st, ix, y + 29, 9);
            ix += 11;
        }
        com.trace.refract.render.Gfx.alpha = prev;
    }

    private void burst(Vec3d c) {
        Theme t = Themes.current();
        for (int i = 0; i < 26; i++) {
            double a = i * Math.PI * 2 / 26, up = (rng.nextDouble() - 0.4) * 0.12;
            Motes.spawn(c.x, c.y, c.z, Math.cos(a) * 0.16, up, Math.sin(a) * 0.16, 10 + rng.nextInt(6), 0.05f + rng.nextFloat() * 0.03f,
                    Colors.lerp(t.accent(), 0xFFFFFFFF, 0.4f), t.accent2());
        }
        Motes.spawn(c.x, c.y, c.z, 0, 0, 0, 6, 0.35f, 0xFFFFFFFF, t.accent());
    }

    /** True when swords should be drawn as the chosen knife. */
    public boolean knifeOn() { return isEnabled() && !knife.is("Off"); }

    /** Item model id for the chosen knife. */
    public Identifier knifeModel() { return Identifier.of("refract", knife.get().toLowerCase(java.util.Locale.ROOT)); }

    @Override public void onDisable() { points.clear(); }

    @Override public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (killEffect.on() && target != null && !killDone && Time.seconds() - targetAt < 6 && (target.isDead() || target.getHealth() <= 0)) {
            killDone = true;
            kill(target.getEntityPos());
        }
        ticks++;
        // Knife: play the draw animation whenever a sword comes out, inspect on the key.
        ItemStack held = mc.player.getMainHandStack();
        boolean isKnife = knifeOn() && net.minecraft.registry.Registries.ITEM.getId(held.getItem()).getPath().endsWith("_sword");
        int slot = mc.player.getInventory().getSelectedSlot();
        if (isKnife && (!lastKnife || slot != lastSlot)) {
            KnifeRenderer.drawStart = Time.seconds();
            drawBurst();
        }
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

    /** Sparks thrown off the hand as the knife comes out, tinted by which knife it is. */
    private void drawBurst() {
        if (mc.player == null) return;
        Theme t = Themes.current();
        boolean butterfly = "Butterfly".equals(knife.get());
        int a = butterfly ? 0xFFFFD84D : t.accent();
        int b = butterfly ? 0xFFFFFFFF : t.accent2();
        Vec3d eye = mc.player.getEyePos();
        Vec3d fwd = mc.player.getRotationVec(1f);
        Vec3d hand = eye.add(fwd.multiply(0.55)).add(0, -0.28, 0);
        for (int i = 0; i < 14; i++) {
            double dx = (rng.nextDouble() - 0.5) * 0.16, dy = (rng.nextDouble() - 0.3) * 0.16, dz = (rng.nextDouble() - 0.5) * 0.16;
            Motes.spawn(hand.x + dx * 2, hand.y + dy * 2, hand.z + dz * 2,
                    dx, dy + 0.02, dz, 12 + rng.nextInt(8), 0.045f + rng.nextFloat() * 0.03f, a, b);
        }
        Motes.spawn(hand.x, hand.y, hand.z, 0, 0, 0, 6, 0.28f, 0xFFFFFFFF, a);
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
