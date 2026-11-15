package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Combat;
import com.trace.refract.util.Rotations;
import com.trace.refract.util.Time;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Eases the camera onto the best target. Everything is ordinary client rotation: the same yaw and pitch a mouse
 * would produce, moved by the shared rotation driver at a capped speed with a smoothing curve, so the movement
 * has acceleration instead of snapping. Tremor adds a small organic wobble for looks, not for any server check.
 */
public class AimAssist extends Module {
    public final NumberSetting range = add(new NumberSetting("Range", 4.5, 1.0, 8.0, 0.1, "m"));
    public final NumberSetting fov = add(new NumberSetting("FOV", 100, 10, 360, 5, "°"));
    public final NumberSetting speed = add(new NumberSetting("Speed", 16, 1, 40, 1, "°/t"));
    public final NumberSetting smoothing = add(new NumberSetting("Smoothing", 0.55, 0.0, 0.95, 0.05, ""));
    public final NumberSetting tremor = add(new NumberSetting("Tremor", 0.0, 0.0, 3.0, 0.1, "°"));
    public final BoolSetting weaponOnly = add(new BoolSetting("Weapon Only", true));
    public final BoolSetting holdOnly = add(new BoolSetting("On Left Click", true));
    public final BoolSetting visibleOnly = add(new BoolSetting("Line Of Sight", false));
    public final BoolSetting silent = add(new BoolSetting("Silent Aim", false));

    public AimAssist() {
        super("Aim Assist", "Eases your aim onto the nearest target with smoothing, tremor and a speed cap. No packets.", Category.COMBAT);
    }

    @Override public void onDisable() { Rotations.stop(); }

    @Override public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;
        if (holdOnly.on() && !Combat.holdingAttack()) return;
        if (weaponOnly.on() && !Combat.holdingWeapon()) return;

        LivingEntity target = Combat.bestTarget(range.get(), fov.get(), visibleOnly.on());
        if (target == null) return;

        Vec3d aimPoint = target.getBoundingBox().getCenter();
        float yaw = Rotations.yawTo(aimPoint);
        float pitch = Rotations.pitchTo(aimPoint);
        double tr = tremor.get();
        if (tr > 0.001) {
            double t = Time.seconds();
            yaw += (float) (tr * Math.sin(t * 9.3) * 0.7 + tr * Math.sin(t * 3.1) * 0.3);
            pitch += (float) (tr * Math.sin(t * 7.1) * 0.6 + tr * Math.cos(t * 2.3) * 0.4);
        }

        // Smoothing lowers the effective turn cap: 0 is instant, 0.95 crawls.
        float step = (float) Math.max(0.8, speed.get() * (1.0 - smoothing.get()));
        Rotations.aim(yaw, pitch, step, silent.on());
    }
}
