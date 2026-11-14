package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Combat;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;

/**
 * Crystal cycle at the block you are looking at: place obsidian, put a crystal on it, break the crystal, repeat
 * while the bind is held. Bind it in hold mode and it runs only for as long as you hold the button; let go, look
 * at another block, press again. Placement and breaking are ordinary client interactions with configurable
 * tick delays, so the rate stays inside what a player can physically click.
 */
public class AutoCrystal extends Module {
    public final NumberSetting placeRange = add(new NumberSetting("Place Range", 4.4, 2, 6, 0.1, "m"));
    public final NumberSetting breakRange = add(new NumberSetting("Break Range", 4.4, 2, 6, 0.1, "m"));
    public final NumberSetting crystalDelay = add(new NumberSetting("Crystal Speed", 2, 0, 20, 1, "t"));
    public final NumberSetting obsidianDelay = add(new NumberSetting("Obsidian Speed", 2, 0, 20, 1, "t"));
    public final BoolSetting autoObsidian = add(new BoolSetting("Place Obsidian", true));
    public final BoolSetting swapBack = add(new BoolSetting("Swap Back", true));

    private int timer, originalSlot = -1;
    /**
     * The spot this cycle is working on, latched when the cycle starts. Re-deriving it from the crosshair every
     * tick made the target walk one block up each placement: once obsidian is there you are looking at the new
     * block, so its top face is a fresh replaceable block, and the module stacked obsidian forever without ever
     * placing a crystal.
     */
    private BlockPos base;
    /**
     * Ticks spent waiting for the server to actually spawn the crystal we asked for. Retrying before it arrives
     * fired a placement every two ticks: the server rejected the duplicates and flagged the placement stream.
     */
    private int waiting;
    /** The face the player has already been turned towards, so every placement is preceded by its rotation. */
    /**
     * Ordering state. The client sends its rotation during its own tick, before modules run, so a rotation set
     * here only reaches the server on the NEXT tick. Interacting in the same tick as the turn therefore reaches
     * the server tagged with the previous look direction, which is exactly what a post-placement check rejects.
     * So: turn on one tick, act on the next.
     */
    private BlockPos turnedTo;
    private int turnedToCrystal = -1;
    /** The spot we just filled with obsidian; it stays the cycle base while we work on its crystal. */
    private BlockPos lastPlaced;

    public AutoCrystal() {
        super("Auto Crystal", "Places obsidian, drops a crystal on it and breaks it at your chosen speed while held.", Category.COMBAT);
    }

    @Override public void onEnable() {
        timer = 0;
        base = null;
        originalSlot = mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
    }

    @Override public void onDisable() {
        base = null;
        turnedTo = null;
        turnedToCrystal = -1;
        lastPlaced = null;
        if (swapBack.on() && mc.player != null && originalSlot >= 0 && originalSlot < 9) {
            mc.player.getInventory().setSelectedSlot(originalSlot);
        }
    }

    @Override public String suffix() {
        return isEnabled() && timer > 0 ? String.valueOf(timer) : null;
    }

    /** Points the player at a world position; the rotation packet goes out on this tick, the action on the next. */
    private static void aimAt(net.minecraft.client.MinecraftClient mc, Vec3d target) {
        if (mc.player == null) return;
        double dx = target.x - mc.player.getX(), dz = target.z - mc.player.getZ();
        double dy = target.y - mc.player.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        // Yaw must stay inside -180..180: a raw atan2 result can land outside that range and the server's aim
        // check reports it (AimModulo360) even though the camera looks correct locally.
        mc.player.setYaw(net.minecraft.util.math.MathHelper.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0)));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, flat)));
    }

    @Override public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (timer > 0) { timer--; return; }

        // Never act while another module is turning the player: the rotation would land in the wrong packet.
        if (Combat.internalAction()) return;

        // The placement target has to come from the ray the player's look actually produces: aiming at a
        // remembered face and hoping the ray lands there just loops forever when another block is in the way.
        BlockHitResult hit = Combat.aimedBlock(placeRange.get() + 0.5);
        if (hit == null) return;
        BlockPos support = hit.getBlockPos();
        Direction side = hit.getSide();
        BlockPos aimed = support.offset(side);

        // Follow the new aim unless it is the block we just placed: that one stays the cycle's base so the
        // module moves on to the crystal instead of stacking obsidian on top of itself.
        if (base == null || !base.equals(aimed)) {
            if (lastPlaced == null || !lastPlaced.equals(base)) base = aimed;
        }

        // 1. An existing crystal on the spot is broken first, whatever the surface is.
        EndCrystalEntity crystal = Combat.crystalNear(base, breakRange.get());

        if (crystal != null) {
            if (Combat.eyeDistance(crystal) > breakRange.get() + 1.5) return;
            // Look at the crystal before hitting it. Swinging while your crosshair points somewhere else is an
            // instant hitbox flag: the server checks the attack ray against the entity's box.
            if (turnedToCrystal != crystal.getId()) {
                turnedToCrystal = crystal.getId();
                aimAt(mc, crystal.getBoundingBox().getCenter());
                timer = 1; // the turn goes out this tick, the swing on the next
                return;
            }
            turnedToCrystal = -1;
            Combat.attack(crystal);
            timer = crystalDelay.asInt();
            return;
        }

        // 2. Nothing there yet: obsidian first when configured.
        if (mc.world.getBlockState(base).isReplaceable()) {
            if (!autoObsidian.on() || Combat.reachTo(base) > placeRange.get()) return;
            // One tick between the swap and the placement, so the held-item change always arrives first.
            if (!Combat.holding(Items.OBSIDIAN)) { Combat.holdItem(Items.OBSIDIAN); timer = 1; return; }
            // Look at the face we are about to place on. A placement whose face is not in the player's view is
            // rejected by the server (and is exactly what the anticheat reports as a bad block placement).
            boolean placed = Combat.placeAgainst(support, side);
            if (placed) {
                lastPlaced = base;
                timer = obsidianDelay.asInt();
            }
            return;
        }

        // 3. Obsidian in place: put a crystal on top of it.
        if (mc.world.getBlockState(base).getBlock() == Blocks.OBSIDIAN) {
            if (Combat.reachTo(base) > placeRange.get() + 1.0) return;
            if (!Combat.holding(Items.END_CRYSTAL)) { Combat.holdItem(Items.END_CRYSTAL); timer = 1; return; }
            boolean used = Combat.useBlock(base, Direction.UP);
            if (used) timer = crystalDelay.asInt();
        }
    }
}
