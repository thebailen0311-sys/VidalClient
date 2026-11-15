package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Combat;
import com.trace.refract.util.Rotations;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Respawn anchor macro: place, optionally block the blast, look back at the anchor, pop it, restore your slot.
 *
 * <p>Every step is an ordinary block interaction, and each step waits for the previous one to actually arrive
 * before starting: the anchor is only charged once the anchor block exists in the world, and glowstone is only
 * used once it is really in your hand and the rotation has settled on the anchor. That ordering is what keeps
 * the sequence looking like a player who is clicking quickly rather than a client inventing interactions.
 *
 * <p>It runs from either bind: the module bind (hold or toggle), or the separate <b>Place Bind</b>, which fires
 * one complete run even when the module is off.
 */
public class AnchorMacro extends Module {
    public final ModeSetting mode = add(new ModeSetting("Mode", "Place & Pop", "Place & Pop", "Place Only", "Pop Only"));
    public final BoolSetting safe = add(new BoolSetting("Safe Anchor", true));
    public final ModeSetting safeBlock = add(new ModeSetting("Safe Block", "Glowstone", "Glowstone", "Obsidian"));
    public final NumberSetting placeDelay = add(new NumberSetting("Place Delay", 2, 0, 20, 1, "t"));
    public final NumberSetting explodeDelay = add(new NumberSetting("Explode Delay", 2, 0, 20, 1, "t"));
    public final NumberSetting snapSpeed = add(new NumberSetting("Snap Speed", 24, 4, 60, 1, "°/t"));
    public final BoolSetting restore = add(new BoolSetting("Restore Slot", true));
    /** Off by default: one press is one pop, instead of re-placing the anchor every tick you hold the bind. */
    public final BoolSetting autoRepeat = add(new BoolSetting("Auto Repeat", false));

    /** Extra bind that starts a whole run without switching the module on. -1 is unbound. */
    public int placeKey = -1;

    private static final int IDLE = 0, PLACE = 1, WAIT_ANCHOR = 2, SAFE_AIM = 3, SAFE_PLACE = 4, CHARGE = 5, DETONATE = 6, WAIT_POP = 7, FINISH = 8, DONE = 9;

    private int step, timer, clicks, wait, originalSlot = -1;
    private BlockPos support, anchor, lastDone;
    private Direction side;
    private boolean safeDone, oneShot;

    public AnchorMacro() {
        super("Anchor Macro", "Places a respawn anchor, optionally blocks the blast, then pops it with glowstone and restores your slot.", Category.COMBAT);
    }

    @Override public void onEnable() {
        originalSlot = mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
        step = IDLE;
        timer = 0;
        clicks = 0;
        wait = 0;
        anchor = null;
        support = null;
        safeDone = false;
        lastDone = null;
    }

    @Override public void onDisable() {
        restoreSlot();
        Rotations.stop();
        step = IDLE;
        wait = 0;
    }

    /** Runs the macro once from the Place bind, even while the module is switched off. */
    public void triggerOnce() {
        if (!isEnabled()) { oneShot = true; setEnabled(true, false); return; }
        if (step == IDLE || step == DONE) { oneShot = true; onEnable(); }
    }

    @Override public boolean keepWhileRunning() { return isEnabled() && step != IDLE && step != DONE; }

    private void restoreSlot() {
        if (!restore.on() || mc.player == null) return;
        if (originalSlot >= 0 && originalSlot < 9) mc.player.getInventory().setSelectedSlot(originalSlot);
    }

    private static boolean nether() {
        return mc.world != null && mc.world.getRegistryKey() == World.NETHER;
    }

    /**
     * Turns toward {@code at} silently: the rotation rides in the movement packet, the camera stays where the
     * player put it. A visible turn here was moving the view (and pushing the player around) mid-macro.
     */
    private void lookAt(Vec3d at) {
        Rotations.aim(Rotations.yawTo(at), Rotations.pitchTo(at), snapSpeed.asFloat(), true);
    }

    @Override public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return; // a container or chat open cancels the run instead of fighting it
        if (step == DONE) {
            // Look at a different block to re-arm. One press is still one pop (Auto Repeat is opt-in), but a
            // finished run no longer requires switching the module off and on again.
            var look = Combat.crosshairBlock();
            if (look != null && (lastDone == null || !look.getBlockPos().equals(lastDone))) {
                step = IDLE; timer = 0; wait = 0; clicks = 0; safeDone = false; lastDone = null;
            }
            return;
        }
        if (timer > 0) { timer--; return; }

        switch (step) {
            case IDLE -> {
                BlockHitResult hit = Combat.crosshairBlock();
                if (hit == null) return;
                support = hit.getBlockPos();
                side = hit.getSide();
                anchor = support.offset(side);
                if (mode.is("Pop Only")) { step = CHARGE; wait = 0; return; }
                if (mc.world.getBlockState(anchor).getBlock() == Blocks.RESPAWN_ANCHOR) {
                    step = next();
                    timer = placeDelay.asInt();
                    return;
                }
                if (!mc.world.getBlockState(anchor).isReplaceable()) return;
                step = PLACE;
            }
            case PLACE -> {
                if (anchor == null || support == null) { step = FINISH; return; }
                if (mc.world.getBlockState(anchor).getBlock() == Blocks.RESPAWN_ANCHOR) {
                    step = next();
                    timer = placeDelay.asInt();
                    return;
                }
                // One tick between selecting the anchor and using it, so the held-item change reaches the
                // server before the placement does.
                if (!Combat.holding(Items.RESPAWN_ANCHOR)) { Combat.holdItem(Items.RESPAWN_ANCHOR); timer = 1; return; }
                if (Combat.placeAgainst(support, side)) { step = WAIT_ANCHOR; timer = placeDelay.asInt(); wait = 0; }
                else timer = 2;
            }
            case WAIT_ANCHOR -> {
                if (anchor != null && mc.world.getBlockState(anchor).getBlock() == Blocks.RESPAWN_ANCHOR) {
                    step = mode.is("Place Only") ? FINISH : next();
                    wait = 0;
                } else if (++wait > 40) {
                    step = IDLE; // the placement never arrived: start over instead of hanging
                    wait = 0;
                } else step = PLACE;
            }
            case SAFE_AIM -> {
                BlockPos p = safePos();
                if (p == null) { step = CHARGE; return; }
                Vec3d at = p.toCenterPos();
                lookAt(at);
                if (Rotations.onTarget(at, 12f)) { step = SAFE_PLACE; timer = 2; }
            }
            case SAFE_PLACE -> {
                BlockPos p = safePos();
                Item block = safeBlock.is("Obsidian") ? Items.OBSIDIAN : Items.GLOWSTONE;
                if (p != null && mc.world.getBlockState(p).isReplaceable()) {
                    if (!Combat.holding(block)) { Combat.holdItem(block); timer = 1; return; }
                    if (Combat.placeAgainst(p.down(), Direction.UP)) safeDone = true;
                } else {
                    safeDone = true; // nowhere to put it: do not stall the pop
                }
                if (anchor != null) lookAt(anchor.toCenterPos());
                step = CHARGE;
                timer = placeDelay.asInt();
                wait = 0;
            }
            case CHARGE -> charge();
            case DETONATE -> detonate();
            case WAIT_POP -> {
                if (anchor == null || mc.world.getBlockState(anchor).getBlock() != Blocks.RESPAWN_ANCHOR) { step = FINISH; return; }
                if (++wait > 12) {
                    if (clicks < 9) { step = DETONATE; wait = 0; timer = 1; }
                    else step = FINISH;
                    return;
                }
                timer = 2;
            }
            case FINISH -> {
                restoreSlot();
                lastDone = support != null ? support : anchor;
                clicks = 0;
                safeDone = false;
                // One run per press unless Auto Repeat is on. The old code fell straight back to IDLE, which made a
                // held bind place and re-place an anchor forever and locked the hotbar on glowstone.
                if (oneShot) { oneShot = false; step = DONE; setEnabled(false, false); }
                else if (autoRepeat.on()) { step = IDLE; timer = placeDelay.asInt(); wait = 0; }
                else { step = DONE; }
            }
            default -> { }
        }
    }

    /** The block between you and the anchor: putting a blast block there shields the pop. */
    private BlockPos safePos() {
        if (anchor == null || mc.player == null) return null;
        BlockPos me = mc.player.getBlockPos();
        BlockPos p = new BlockPos(anchor.getX() + Integer.signum(me.getX() - anchor.getX()), anchor.getY(),
                anchor.getZ() + Integer.signum(me.getZ() - anchor.getZ()));
        if (mc.world.getBlockState(p).isReplaceable() && !mc.world.getBlockState(p.down()).isAir()) return p;
        BlockPos below = anchor.down();
        return !mc.world.getBlockState(below).isAir() ? below : null;
    }

    private int next() {
        return safe.on() && !safeDone && mc.player != null ? SAFE_AIM : CHARGE;
    }

    private void charge() {
        if (anchor == null) { step = FINISH; return; }
        if (mc.world.getBlockState(anchor).getBlock() != Blocks.RESPAWN_ANCHOR) {
            // The anchor block may still be on its way from the server; only give up after a full second.
            if (++wait < 20) { timer = 1; return; }
            step = FINISH;
            return;
        }
        wait = 0;
        lookAt(anchor.toCenterPos());
        if (!Rotations.onTarget(anchor.toCenterPos(), 12f)) return;
        if (!Combat.holding(Items.GLOWSTONE)) { Combat.holdItem(Items.GLOWSTONE); timer = 1; return; }
        if (!Combat.useBlock(anchor, Direction.UP)) { timer = 1; return; }
        clicks++;
        timer = explodeDelay.asInt();
        // Nether: glowstone detonates the anchor outright. Overworld: it only charges it, and the anchor has to be
        // right-clicked with anything that is NOT glowstone to explode. The old code stopped after charging, which
        // is why it placed and charged but never exploded.
        if (nether()) step = WAIT_POP;
        else step = DETONATE;
    }

    /** Overworld pop: hold anything except glowstone and right-click the charged anchor. */
    private void detonate() {
        if (anchor == null) { step = FINISH; return; }
        if (!holdNonGlowstone()) { timer = 1; return; }
        lookAt(anchor.toCenterPos());
        if (!Rotations.onTarget(anchor.toCenterPos(), 12f)) return;
        if (Combat.useBlock(anchor, Direction.UP)) {
            clicks++;
            timer = explodeDelay.asInt();
            step = WAIT_POP;
            wait = 0;
        } else {
            timer = 1;
        }
    }

    /** True when the main hand holds something that can trigger the pop (anything but glowstone). */
    private boolean holdNonGlowstone() {
        if (mc.player == null) return false;
        var inv = mc.player.getInventory();
        if (!inv.getStack(inv.getSelectedSlot()).isOf(Items.GLOWSTONE) && !inv.getStack(inv.getSelectedSlot()).isEmpty()) return true;
        for (int i = 0; i < 9; i++) {
            var s = inv.getStack(i);
            if (!s.isEmpty() && !s.isOf(Items.GLOWSTONE)) { inv.setSelectedSlot(i); return true; }
        }
        for (int i = 9; i < 36; i++) {
            var s = inv.getStack(i);
            if (s.isEmpty() || s.isOf(Items.GLOWSTONE)) continue;
            Combat.holdItem(s.getItem());
            return false; // the swap lands next tick
        }
        return false;
    }

    public int debugStep() { return step; }

    @Override public String suffix() {
        if (!isEnabled()) return null;
        return switch (step) {
            case PLACE, WAIT_ANCHOR -> "placing";
            case SAFE_AIM, SAFE_PLACE -> "safe";
            case CHARGE -> "charging";
            case DETONATE, WAIT_POP -> "popping";
            case FINISH, DONE -> "done";
            default -> null;
        };
    }
}
