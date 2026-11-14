package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Combat;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;

/**
 * Attacks the entity under the crosshair. It only ever uses the vanilla attack call, waits for the vanilla
 * attack cooldown and adds a per-weapon delay on top, so it swings at a human-plausible rate instead of
 * spamming the server. Crits are real jumps: it will jump and swing on the way down when a crit is wanted.
 */
public class Triggerbot extends Module {
    public final BoolSetting holdOnly = add(new BoolSetting("Hold Only", true));
    public final BoolSetting weaponOnly = add(new BoolSetting("Weapon Only", true));
    /** Vanilla survival attack reach is 3.0 blocks from the eye; anything past it is a guaranteed flag. */
    public static final float SAFE_REACH = 2.95f;
    public final NumberSetting range = add(new NumberSetting("Range", 2.9, 1.0, 3.0, 0.05, "m"));
    public final NumberSetting fov = add(new NumberSetting("FOV", 120, 10, 360, 5, "°"));
    public final NumberSetting cooldown = add(new NumberSetting("Cooldown", 0.95, 0.4, 1.0, 0.01, ""));
    public final NumberSetting delay = add(new NumberSetting("Delay", 0, 0, 10, 1, "t"));
    public final NumberSetting swordDelay = add(new NumberSetting("Sword Delay", 0, 0, 10, 1, "t"));
    public final NumberSetting axeDelay = add(new NumberSetting("Axe Delay", 2, 0, 10, 1, "t"));
    public final NumberSetting otherDelay = add(new NumberSetting("Other Delay", 1, 0, 10, 1, "t"));
    public final ModeSetting crits = add(new ModeSetting("Crits", "Prefer", "Off", "Prefer", "Only"));
    public final BoolSetting critJump = add(new BoolSetting("Jump For Crits", true));
    public final BoolSetting lineOfSight = add(new BoolSetting("Line Of Sight", true));

    private int wait, jumpTicks;
    private boolean jumped;

    public Triggerbot() {
        super("Trigger Bot", "Attacks the target under your crosshair while you hold left click. Weapon cooldowns, real jump crits.", Category.COMBAT);
    }

    @Override public void onDisable() {
        wait = jumpTicks = 0;
        releaseJump();
    }

    private void releaseJump() {
        if (jumped) {
            mc.options.jumpKey.setPressed(false);
            jumped = false;
            jumpTicks = 0;
        }
    }

    /** Delay for the weapon in hand: axes land slower than swords on purpose. */
    private int weaponDelay() {
        Item item = mc.player == null ? null : mc.player.getMainHandStack().getItem();
        int d = Combat.isAxe(item) ? axeDelay.asInt() : Combat.isSword(item) ? swordDelay.asInt() : otherDelay.asInt();
        return d + delay.asInt();
    }

    @Override public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (jumped && ++jumpTicks > 2) releaseJump();

        if (holdOnly.on() && !Combat.holdingAttack()) { wait = 0; return; }
        if (weaponOnly.on() && !Combat.holdingWeapon()) return;
        if (mc.player.getAttackCooldownProgress(0f) < cooldown.get()) return;
        if (wait > 0) { wait--; return; }

        // Hard clamp: a slider above 3.0 would just make the server reject (and flag) every hit.
        LivingEntity target = Combat.bestTarget(Math.min(range.get(), SAFE_REACH), fov.get(), lineOfSight.on());
        if (target == null) return;
        // Only swing at what the crosshair is genuinely on. Attacking a target the client's own look ray does not
        // intersect is an interaction the server cannot account for, and that is what the post-interaction check
        // reports. It also means a kill only lands when you really are aiming at them.
        if (!(mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult ehr) || ehr.getEntity() != target) return;

        if (!Combat.critReady() && critJump.on()) {
            if (crits.is("Only") || (crits.is("Prefer") && mc.player.isOnGround())) {
                if (mc.player.isOnGround()) {
                    mc.options.jumpKey.setPressed(true);
                    jumped = true;
                    jumpTicks = 0;
                }
                if (crits.is("Only")) return;
                // Prefer: on the ground we jump, in the air we swing now.
                if (jumped) return;
            }
        }

        Combat.attack(target);
        wait = weaponDelay();
    }
}
