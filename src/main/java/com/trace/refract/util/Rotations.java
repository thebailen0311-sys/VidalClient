package com.trace.refract.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Rotation driver for Auto Hunter. Modules set a <b>target</b> once per tick with {@link #aim}; the rotation itself
 * eases toward it <b>every rendered frame</b> in {@link #frame}, using real frame time, so turns are smooth at any
 * frame rate instead of stepping 20 times a second.
 *
 * <ul>
 *   <li><b>Silent</b>: the camera never moves. Only the rotation the server sees turns: {@code ClientPlayerEntityMixin}
 *       swaps it into the game's own movement packet for that moment. Used for mining and walking between dig steps.</li>
 *   <li><b>Visible</b>: the real rotation turns, eased the same way. Elytra flight needs this, because gliding
 *       physics runs on the client and pushes you where the body really faces. Both the rotation and the previous
 *       frame's rotation are moved by the same amount, exactly as mouse look does, so the camera shows the eased
 *       value with no tick interpolation steps.</li>
 * </ul>
 *
 * <p>Easing is frame-rate independent exponential smoothing ({@code 1 - e^(-rate * dt)}), with a turn-speed cap in
 * degrees per second applied to the combined yaw/pitch step so diagonal turns stay straight. No random noise.
 * When a silent turn ends, the server-side head eases back to where the camera points before handing control back,
 * so it never snaps.
 */
public final class Rotations {
    /** Exponential approach rate per second (about 8: a third of the remaining turn every tick, as before). */
    private static final float RATE = 8f;
    /** Within this many degrees of the target, settle exactly on it. */
    private static final float SETTLE = 0.05f;

    private static float yaw, pitch, targetYaw, targetPitch, maxDegPerSec = 280f;
    private static boolean silent, started, aimedThisTick, returning;

    private Rotations() {}

    /** True while the rotation the server sees differs from the camera (silent aim, or easing back after it). */
    public static boolean active() { return started && silent; }

    /** True while a module drives the rotation (silent or visible). */
    public static boolean driving() { return started && !returning; }

    public static float yaw() { return yaw; }

    public static float pitch() { return pitch; }

    /** The eased yaw, or the player's own when nothing is driving it. */
    public static float currentYaw() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return started && !returning ? yaw : (mc.player == null ? 0 : mc.player.getYaw());
    }

    /** The eased pitch, or the player's own when nothing is driving it. */
    public static float currentPitch() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return started && !returning ? pitch : (mc.player == null ? 0 : mc.player.getPitch());
    }

    /**
     * Sets the rotation to ease toward. Call once per tick for as long as you want to keep aiming.
     *
     * @param maxStepPerTick turn-speed cap in degrees per tick (converted to degrees per second internally)
     * @param silentOn       keep the camera still and turn only what the server sees
     */
    public static void aim(float tYaw, float tPitch, float maxStepPerTick, boolean silentOn) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return;
        if (!started) {
            yaw = p.getYaw();
            pitch = p.getPitch();
            started = true;
        } else if (silent && !silentOn) {
            // Silent -> visible: continue from where the camera really is, so the camera never jumps.
            yaw = p.getYaw();
            pitch = p.getPitch();
        }
        // Keep the target on the same winding as the current yaw so the shortest way round is taken.
        targetYaw = yaw + MathHelper.wrapDegrees(tYaw - yaw);
        targetPitch = MathHelper.clamp(tPitch, -90f, 90f);
        maxDegPerSec = Math.max(1f, maxStepPerTick) * 20f;
        silent = silentOn;
        returning = false;
        aimedThisTick = true;
    }

    /** Called once per rendered frame, before the camera is placed, with the real frame time in seconds. */
    public static void frame(float dt) {
        if (!started) return;
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) { reset(); return; }
        if (returning) {
            targetYaw = yaw + MathHelper.wrapDegrees(p.getYaw() - yaw);
            targetPitch = p.getPitch();
        }
        float dYaw = targetYaw - yaw, dPitch = targetPitch - pitch;
        float k = 1f - (float) Math.exp(-RATE * dt);
        float sYaw = dYaw * k, sPitch = dPitch * k;
        float len = (float) Math.sqrt(sYaw * sYaw + sPitch * sPitch), cap = maxDegPerSec * dt;
        if (len > cap && len > 0) { sYaw *= cap / len; sPitch *= cap / len; }
        if (Math.abs(dYaw) < SETTLE) sYaw = dYaw;
        if (Math.abs(dPitch) < SETTLE) sPitch = dPitch;
        float oldYaw = yaw, oldPitch = pitch;
        yaw += sYaw;
        pitch = MathHelper.clamp(pitch + sPitch, -90f, 90f);

        if (returning) {
            if (Math.abs(MathHelper.wrapDegrees(p.getYaw() - yaw)) < 0.5f && Math.abs(p.getPitch() - pitch) < 0.5f) reset();
            return;
        }
        if (!silent) {
            // Move the rotation and last frame's rotation together (as mouse look does): the camera shows exactly this.
            float ddy = yaw - oldYaw, ddp = pitch - oldPitch;
            p.setYaw(MathHelper.wrapDegrees(p.getYaw() + ddy));
            p.setPitch(MathHelper.clamp(p.getPitch() + ddp, -90f, 90f));
            p.lastYaw += ddy;
            p.lastPitch += ddp;
            yaw = p.getYaw();
            pitch = p.getPitch();
            targetYaw = yaw + MathHelper.wrapDegrees(targetYaw - yaw);
        }
    }

    /** True when the rotation the server currently sees points within {@code toleranceDeg} of {@code at}. */
    public static boolean onTarget(Vec3d at, float toleranceDeg) {
        if (!started || returning) return false;
        return Math.abs(MathHelper.wrapDegrees(yawTo(at) - yaw)) <= toleranceDeg
                && Math.abs(pitchTo(at) - pitch) <= toleranceDeg;
    }

    /** Call once per client tick, after modules have run: lets go if nobody aimed this tick. */
    public static void endTick() {
        if (!aimedThisTick) stop();
        aimedThisTick = false;
    }

    /** Hands rotation back to the player. A silent head eases back to the camera first, so it never snaps. */
    public static void stop() {
        if (!started) return;
        if (silent) returning = true;
        else reset();
    }

    private static void reset() {
        started = false;
        silent = false;
        returning = false;
    }

    /** Yaw in degrees from the player's eyes to a point. */
    public static float yawTo(Vec3d at) {
        Vec3d d = at.subtract(MinecraftClient.getInstance().player.getEyePos());
        return (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    /** Pitch in degrees from the player's eyes to a point. */
    public static float pitchTo(Vec3d at) {
        Vec3d d = at.subtract(MinecraftClient.getInstance().player.getEyePos());
        return (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
    }
}
