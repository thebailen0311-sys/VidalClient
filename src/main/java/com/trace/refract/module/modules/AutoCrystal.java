package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Combat;
import com.trace.refract.util.Rotations;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Crystal cycle on one latched block: obsidian, crystal, break, repeat.
 *
 * <p>Everything is a state machine with exactly one action per state, and the spot is chosen <b>once</b> per cycle.
 * The previous version re-derived the target from the crosshair every tick, so the moment obsidian existed the ray
 * pointed at the new block and it went back to placing obsidian — the visible symptom being a rapid obsidian/crystal
 * swap that also spammed the server with placements.
 *
 * <p>Rotations are silent and every action is gated on that rotation having settled, so the camera never moves and a
 * placement never travels in the same tick as the turn that made it valid.
 */
public class AutoCrystal extends Module {
    public final NumberSetting placeRange = add(new NumberSetting("Place Range", 4.2, 2, 4.2, 0.1, "m"));
    public final NumberSetting breakRange = add(new NumberSetting("Break Range", 2.9, 1, 3, 0.05, "m"));
    public final NumberSetting crystalDelay = add(new NumberSetting("Crystal Speed", 0, 0, 20, 1, "t"));
    public final NumberSetting obsidianDelay = add(new NumberSetting("Obsidian Speed", 0, 0, 20, 1, "t"));
    /** The spot has to be in front of the camera, not just under a stale silent rotation. */
    public final BoolSetting requireLook = add(new BoolSetting("Require Look", true));
    public final NumberSetting lookAngle = add(new NumberSetting("Look Angle", 25, 5, 90, 5, "°"));
    public final BoolSetting autoObsidian = add(new BoolSetting("Place Obsidian", true));
    public final BoolSetting swapBack = add(new BoolSetting("Swap Back", true));

    private enum Step { PICK, SWAP_OBSIDIAN, PLACE_OBSIDIAN, WAIT_OBSIDIAN, SWAP_CRYSTAL, PLACE_CRYSTAL, BREAK, DONE }

    private Step step = Step.PICK;
    private int timer, wait, originalSlot = -1, swapAttempts;
    /** Latched for the whole cycle: the block the crystal goes on top of. */
    private BlockPos base;
    private BlockPos support;
    private Direction side = Direction.UP;

    public AutoCrystal() {
        super("Auto Crystal", "Places obsidian, drops a crystal on it and breaks it at your chosen speed while held.", Category.COMBAT);
    }

    @Override public void onEnable() {
        timer = wait = swapAttempts = 0;
        base = null;
        support = null;
        step = Step.PICK;
        if (mc.player != null) originalSlot = mc.player.getInventory().getSelectedSlot();
    }

    @Override public void onDisable() {
        base = null;
        support = null;
        step = Step.PICK;
        Rotations.stop();
        if (swapBack.on() && mc.player != null && originalSlot >= 0 && originalSlot < 9) {
            mc.player.getInventory().setSelectedSlot(originalSlot);
        }
    }

    @Override public String suffix() { return isEnabled() && timer > 0 ? String.valueOf(timer) : null; }

    private static void aimAt(Vec3d at) {
        Rotations.aim(Rotations.yawTo(at), Rotations.pitchTo(at), 180f, true);
    }

    private static boolean settled(Vec3d at) { return Rotations.onTarget(at, 10f); }

    @Override     public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (timer > 0) { timer--; return; }
        if (Combat.internalAction()) return;
        // A latched spot does not stay valid when you turn away: keep acting only on what you are actually facing.
        if (requireLook.on() && base != null && !lookingAt(base)) { base = null; support = null; step = Step.PICK; return; }


        switch (step) {
            case PICK -> {
                if (base == null && !pick()) return;
                if (requireLook.on() && !lookingAt(base)) { base = null; step = Step.PICK; return; }
                EndCrystalEntity crystal = Combat.crystalNear(base, breakRange.get());
                if (crystal != null) { step = Step.BREAK; return; }
                var state = mc.world.getBlockState(base);
                if (state.getBlock() == Blocks.OBSIDIAN) { step = Step.SWAP_CRYSTAL; return; }
                if (state.isReplaceable() && autoObsidian.on()) { step = Step.SWAP_OBSIDIAN; return; }
                step = Step.DONE;
            }
            case SWAP_OBSIDIAN -> {
                if (!Combat.holding(Items.OBSIDIAN)) {
                    // Bounded: if the item cannot be selected, stop instead of swapping in place forever.
                    if (++swapAttempts > 5 || !Combat.holdItem(Items.OBSIDIAN)) { step = Step.DONE; return; }
                    timer = 1; // the held item has to reach the server before the placement does
                    return;
                }
                swapAttempts = 0;
                step = Step.PLACE_OBSIDIAN;
            }
            case PLACE_OBSIDIAN -> {
                if (base == null || support == null) { step = Step.DONE; return; }
                if (mc.world.getBlockState(base).getBlock() == Blocks.OBSIDIAN) { step = Step.PICK; return; }
                if (Combat.reachTo(base) > placeRange.get()) { step = Step.DONE; return; }
                Vec3d face = support.toCenterPos();
                aimAt(face);
                if (!settled(face)) return; // turn first, act on a later tick
                if (Combat.placeAgainst(support, side)) {
                    timer = obsidianDelay.asInt();
                    wait = 0;
                    step = Step.WAIT_OBSIDIAN;
                } else {
                    timer = 2;
                }
            }
            case WAIT_OBSIDIAN -> {
                if (mc.world.getBlockState(base).getBlock() == Blocks.OBSIDIAN) { step = Step.PICK; return; }
                if (++wait > 20) { step = Step.DONE; return; } // the placement never arrived
                timer = 2;
            }
            case SWAP_CRYSTAL -> {
                if (!Combat.holding(Items.END_CRYSTAL)) {
                    if (++swapAttempts > 5 || !Combat.holdItem(Items.END_CRYSTAL)) { step = Step.DONE; return; }
                    timer = 1;
                    return;
                }
                swapAttempts = 0;
                step = Step.PLACE_CRYSTAL;
            }
            case PLACE_CRYSTAL -> {
                if (base == null) { step = Step.PICK; return; }
                if (Combat.reachTo(base) > placeRange.get() + 1.0) { step = Step.DONE; return; }
                Vec3d at = base.toCenterPos();
                aimAt(at);
                if (!settled(at)) return;
                if (Combat.useBlock(base, Direction.UP)) {
                    timer = crystalDelay.asInt();
                    step = Step.PICK;
                } else {
                    timer = 2;
                }
            }
            case BREAK -> {
                EndCrystalEntity crystal = Combat.crystalNear(base, breakRange.get());
                if (crystal == null) { step = Step.PICK; return; }
                Vec3d c = crystal.getBoundingBox().getCenter();
                aimAt(c);
                if (!settled(c)) return;
                Combat.attack(crystal);
                timer = crystalDelay.asInt();
                // Keep the spot: it is now obsidian, so the next PICK goes straight back to a crystal on the same
                // block. Clearing it here made the module re-read the crosshair and walk one block per cycle.
                step = Step.PICK;
            }
            case DONE -> { /* wait for the bind to be released */ }
        }
    }

    /**
     * Chooses the spot under the crosshair once per cycle and keeps it.
     *
     * <p>Looking straight at obsidian has to mean "put the crystal on this", not "place obsidian on top of it".
     * The old version only ever considered the block <em>adjacent</em> to the face, so aiming at an obsidian block
     * (for example one placed a moment earlier) resolved to the air above it and placed another obsidian there,
     * which is the stacking you saw.
     */
    /** True when the camera itself is pointed at the block, independent of any silent rotation. */
    private boolean lookingAt(BlockPos pos) {
        if (mc.player == null) return false;
        Vec3d eye = mc.player.getEyePos();
        Vec3d to = pos.toCenterPos().subtract(eye).normalize();
        Vec3d look = mc.player.getRotationVec(1f).normalize();
        double deg = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, look.dotProduct(to)))));
        return deg <= lookAngle.get();
    }

    private boolean pick() {
        var hit = Combat.aimedBlock(placeRange.get() + 0.5);
        if (hit == null) return false;
        BlockPos hitPos = hit.getBlockPos();
        Direction face = hit.getSide();
        var hitState = mc.world.getBlockState(hitPos);
        if (hitState.getBlock() == Blocks.OBSIDIAN) {
            if (Combat.reachTo(hitPos) > placeRange.get()) return false;
            support = hitPos; // crystal goes on the obsidian itself
            side = Direction.UP;
            base = hitPos;
            return true;
        }
        BlockPos target = hitPos.offset(face);
        if (Combat.reachTo(target) > placeRange.get()) return false;
        var state = mc.world.getBlockState(target);
        if (!state.isReplaceable() && state.getBlock() != Blocks.OBSIDIAN) return false;
        support = hitPos;
        side = face;
        base = target;
        return true;
    }
}
