package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.util.Rotations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Keeps the client's own behaviour inside what a server considers possible.
 *
 * <p>This is the honest half of "anti-detection". A client cannot hide entity packets from the server, and it
 * cannot fake the render distance the server tracks for you; those packets are the server's, not yours. What a
 * server <i>can</i> see is what you send: a click through a wall, a reach longer than vanilla, or an interaction
 * from a detached camera. Those are the things that get hunting clients flagged, and they are all avoidable
 * without giving anything up, because an ESP never needs to touch the world.
 *
 * <p>So: {@link #interactionGuard} cancels any block interaction whose target is not the block under the
 * crosshair (i.e. through a wall) or is further than vanilla reach, and {@link #freecamLock} makes sure a
 * detached camera can never reach the world at all.
 */
public class Stealth extends Module {
    public final BoolSetting interactionGuard = add(new BoolSetting("Interaction Guard", true));
    public final BoolSetting freecamLock = add(new BoolSetting("Freecam Lock", true));

    /** Blocks cancelled since the module was switched on, shown in the suffix. */
    private int blocked;

    public Stealth() {
        super("Stealth", "Cancels through-wall and out-of-reach interactions, and locks the detached camera out of the world.", Category.MISC);
        setEnabled(true, false);
    }

    @Override public String suffix() { return blocked == 0 ? null : String.valueOf(blocked); }

    @Override public void onEnable() { blocked = 0; }

    /** True when this block interaction should be dropped instead of sent. */
    public static boolean shouldCancel(BlockPos pos) {
        Stealth s = ModuleManager.stealth;
        if (s == null || !s.isEnabled()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;
        Freecam f = ModuleManager.freecam;
        if (f != null && f.isEnabled() && s.freecamLock.on() && f.reachLocked()) { s.blocked++; return true; }
        if (!s.interactionGuard.on()) return false;
        // A silently aimed hit is legitimate: the server is sent the rotation that aims at the block.
        if (Rotations.driving()) return false;
        if (!visible(pos)) { s.blocked++; return true; }
        return false;
    }

    /**
     * True when {@code pos} is either the block the crosshair is on or is reached by a ray that is not stopped
     * by something else first. The vanilla reach is checked too, so a modified reach is never sent.
     */
    public static boolean visible(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;
        Vec3d eye = mc.player.getEyePos();
        Vec3d target = pos.toCenterPos();
        if (eye.squaredDistanceTo(target) > 4.5 * 4.5) return false;
        RaycastContext ctx = new RaycastContext(eye, target, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player);
        HitResult hit = mc.world.raycast(ctx);
        if (hit.getType() == HitResult.Type.MISS) return true;
        if (hit instanceof BlockHitResult bhr) {
            BlockPos h = bhr.getBlockPos();
            // The ray stopping on the target, or on a block touching it (clicking a corner), is normal. The
            // ray stopping on anything further away means there is a wall in between.
            return h.equals(pos) || h.getManhattanDistance(pos) <= 1;
        }
        return false;
    }
}
