package com.trace.refract.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Shared plumbing for the combat modules. Everything here goes through the ordinary client calls the vanilla
 * game uses (attackEntity, interactBlock, interactItem, clickSlot), so the server sees normal interactions
 * rather than client-built packets.
 */
public final class Combat {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    /** Actions started by a module: the Stealth guard must not cancel those. */
    private static int internal;

    private Combat() {}

    public static void beginInternal() { internal++; }

    public static void endInternal() { if (internal > 0) internal--; }

    public static boolean internalAction() { return internal > 0; }

    /**
     * True while an interaction is in flight or another module is turning the player. Modules that place, use
     * or swap items check this first so two actions never land in the same tick; the server sees one action at a
     * time, in the same order a player would produce them.
     */
    public static boolean busy() { return internal > 0 || Rotations.driving(); }

    /** True when {@code item} is already the main-hand stack, i.e. the swap has reached the server. */
    public static boolean holding(Item item) {
        return mc.player != null && item != null && mc.player.getMainHandStack().isOf(item);
    }

    // ------------------------------------------------------------------ items

    /** Sword, axe, trident or mace: what "weapon only" means. Swords are data-driven in this version, so the
     *  registry id is what identifies one. */
    public static boolean isWeapon(ItemStack s) {
        return s != null && !s.isEmpty() && isWeapon(s.getItem());
    }

    public static boolean isWeapon(Item i) {
        if (i == null) return false;
        if (i instanceof AxeItem || i instanceof TridentItem || i instanceof MaceItem) return true;
        String p = Registries.ITEM.getId(i).getPath();
        return p.endsWith("_sword") || p.endsWith("_axe") || p.equals("trident") || p.equals("mace");
    }

    public static boolean isAxe(Item i) {
        return i != null && (i instanceof AxeItem || Registries.ITEM.getId(i).getPath().endsWith("_axe"));
    }

    public static boolean isSword(Item i) {
        return i != null && Registries.ITEM.getId(i).getPath().endsWith("_sword");
    }

    public static boolean holdingWeapon() { return mc.player != null && isWeapon(mc.player.getMainHandStack()); }

    /** True while the left mouse button is held down (the vanilla attack key). */
    public static boolean holdingAttack() {
        return mc.options != null && mc.options.attackKey.isPressed();
    }

    public static boolean holdingUse() {
        return mc.options != null && mc.options.useKey.isPressed();
    }

    public static int count(Item item) {
        if (mc.player == null) return 0;
        var inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(item)) n += inv.getStack(i).getCount();
        return n + (mc.player.getOffHandStack().isOf(item) ? mc.player.getOffHandStack().getCount() : 0);
    }

    /** Inventory index (0-35) of the first {@code item}, or -1. */
    public static int find(Item item) {
        if (mc.player == null) return -1;
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(item)) return i;
        return -1;
    }

    /**
     * Puts {@code item} in the main hand: selects the hotbar slot when it is already there, otherwise swaps it
     * into the selected slot with a normal inventory click. Returns true when it is in the hand right now; a
     * swap that had to move an item lands on the next tick, so callers retry then.
     */
    public static boolean holdItem(Item item) {
        if (mc.player == null || mc.interactionManager == null) return false;
        var inv = mc.player.getInventory();
        if (inv.getStack(inv.getSelectedSlot()).isOf(item)) return true;
        for (int i = 0; i < 9; i++) {
            if (!inv.getStack(i).isOf(item)) continue;
            inv.setSelectedSlot(i);
            return true;
        }
        for (int i = 9; i < 36; i++) {
            if (!inv.getStack(i).isOf(item)) continue;
            beginInternal();
            try {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, handlerSlot(i), inv.getSelectedSlot(),
                        SlotActionType.SWAP, mc.player);
            } finally {
                endInternal();
            }
            return false;
        }
        return false;
    }

    /** Inventory index -> player screen handler slot id. */
    public static int handlerSlot(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : 9 + (invIndex - 9);
    }

    /** Swaps an inventory slot into the off hand (button 40 is the off-hand button on the player screen). */
    public static boolean toOffhand(int invIndex) {
        if (mc.player == null || mc.interactionManager == null || invIndex < 0) return false;
        beginInternal();
        try {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, handlerSlot(invIndex), 40,
                    SlotActionType.SWAP, mc.player);
        } finally {
            endInternal();
        }
        return true;
    }

    // ------------------------------------------------------------------ acting

    public static void attack(Entity e) {
        if (mc.player == null || mc.interactionManager == null || e == null) return;
        beginInternal();
        try {
            mc.interactionManager.attackEntity(mc.player, e);
            mc.player.swingHand(Hand.MAIN_HAND);
            NetLog.record("attack", "target=" + e.getType().toString() + " dist=" + String.format(java.util.Locale.ROOT, "%.2f", eyeDistance(e)));
        } finally {
            endInternal();
        }
    }

    public static void swing() {
        if (mc.player != null) mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** Right-clicks the block the crosshair is on with whatever is in the hand. */
    public static boolean useCrosshair() {
        BlockHitResult hit = crosshairBlock();
        return hit != null && useBlock(hit.getBlockPos(), hit.getSide());
    }

    /** Right-clicks a specific block with whatever is in the hand. */
    public static boolean useBlock(BlockPos pos, Direction side) {
        if (mc.player == null || mc.interactionManager == null || mc.world == null) return false;
        Vec3d center = pos.toCenterPos();
        if (mc.player.getEyePos().distanceTo(center) > 4.4) return false;
        beginInternal();
        try {
            ActionResult r = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(center, side, pos, false));
            if (r.isAccepted()) {
                mc.player.swingHand(Hand.MAIN_HAND);
                NetLog.record("use", "pos=" + pos.toShortString() + " side=" + side + " dist=" + String.format(java.util.Locale.ROOT, "%.2f", reachTo(pos)));
            }
            return r.isAccepted();
        } finally {
            endInternal();
        }
    }

    /** Right-clicks {@code support}'s face so a block is placed in the air next to it. */
    public static boolean placeAgainst(BlockPos support, Direction side) {
        if (mc.player == null || mc.world == null) return false;
        BlockPos target = support.offset(side);
        if (!mc.world.getBlockState(target).isReplaceable()) return false;
        // Never right-click air or a liquid: placing against a non-solid face is what the server rejects as an
        // invalid placement (and anticheats flag as AirLiquidPlace).
        var supportState = mc.world.getBlockState(support);
        if (supportState.isAir() || !supportState.getFluidState().isEmpty() || supportState.isReplaceable()) return false;
        Vec3d faceCenter = Vec3d.ofCenter(support).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        if (mc.player.getEyePos().distanceTo(faceCenter) > 4.4) return false;
        // Use the hit our own look ray produces, not a hand-built face vector. The server compares the placement
        // against the rotation it was sent with, and a synthetic hit point is what produced the placement flags.
        BlockHitResult ray = aimedBlock(4.5);
        if (ray == null || !ray.getBlockPos().equals(support) || ray.getSide() != side) return false;
        beginInternal();
        try {
            ActionResult r = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, ray);
            if (r.isAccepted()) {
                mc.player.swingHand(Hand.MAIN_HAND);
                NetLog.record("place", "support=" + support.toShortString() + " into=" + target.toShortString()
                        + " side=" + side + " dist=" + String.format(java.util.Locale.ROOT, "%.2f", reachTo(support)));
            }
            return r.isAccepted();
        } finally {
            endInternal();
        }
    }

    /**
     * The block the player is looking at. The game only refreshes its crosshair target while a frame renders, so
     * this falls back to a raycast from the eyes along the current rotation: the same ray the game itself casts,
     * available on any tick.
     */
    public static BlockHitResult crosshairBlock() {
        if (mc.crosshairTarget instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) return b;
        return aimedBlock(4.5);
    }

    /** Straight raycast from the eyes along the facing direction, up to {@code reach} blocks. */
    public static BlockHitResult aimedBlock(double reach) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(mc.player.getRotationVec(1f).multiply(reach));
        HitResult hit = mc.world.raycast(new net.minecraft.world.RaycastContext(eye, end,
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
        return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
    }

    // ------------------------------------------------------------------ targets

    public static double eyeDistance(Entity e) {
        return mc.player.getEyePos().distanceTo(e.getBoundingBox().getCenter());
    }

    public static double reachTo(BlockPos pos) {
        return mc.player == null ? 99 : mc.player.getEyePos().distanceTo(pos.toCenterPos());
    }

    /** Degrees between where the camera points and the entity's centre. */
    public static double angleTo(Entity e) {
        if (mc.player == null) return 180;
        Vec3d d = e.getBoundingBox().getCenter().subtract(mc.player.getEyePos());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        return Math.hypot(MathHelper.wrapDegrees(yaw - mc.player.getYaw()), pitch - mc.player.getPitch());
    }

    public static boolean inFov(Entity e, double fovDeg) {
        if (fovDeg >= 360) return true;
        if (mc.player == null) return false;
        Vec3d look = mc.player.getRotationVec(1f).normalize();
        Vec3d d = e.getBoundingBox().getCenter().subtract(mc.player.getEyePos()).normalize();
        return Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(d), -1, 1))) <= fovDeg / 2.0;
    }

    public static boolean canSee(LivingEntity e) {
        return mc.player != null && mc.player.canSee(e);
    }

    /** Closest valid target that is inside {@code fov} and within {@code range}. */
    public static LivingEntity bestTarget(double range, double fov, boolean visibleOnly) {
        if (mc.world == null || mc.player == null) return null;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || le == mc.player || le.isRemoved() || !le.isAlive()) continue;
            if (le instanceof PlayerEntity p && p.isSpectator()) continue;
            double d = eyeDistance(le);
            if (d > range) continue;
            if (!inFov(le, fov)) continue;
            if (visibleOnly && !canSee(le)) continue;
            double score = d + angleTo(le) * 0.02;
            if (score < bestScore) { bestScore = score; best = le; }
        }
        return best;
    }

    public static EndCrystalEntity crystalNear(BlockPos base, double range) {
        if (mc.world == null) return null;
        double r2 = range * range;
        EndCrystalEntity best = null;
        double bd = Double.MAX_VALUE;
        Vec3d c = base.toCenterPos().add(0, 1, 0);
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity ec) || ec.isRemoved()) continue;
            double d = ec.getEntityPos().squaredDistanceTo(c);
            if (d <= r2 && d < bd) { bd = d; best = ec; }
        }
        return best;
    }

    // ------------------------------------------------------------------ crits

    /** The vanilla requirements for a critical hit, checked on the client before swinging. */
    public static boolean critReady() {
        var p = mc.player;
        if (p == null) return false;
        return p.fallDistance > 0f && p.getVelocity().y < 0
                && !p.isOnGround() && !p.isClimbing() && !p.isTouchingWater() && !p.hasVehicle()
                && !p.hasStatusEffect(StatusEffects.BLINDNESS) && !p.hasStatusEffect(StatusEffects.SLOW_FALLING)
                && !p.isSprinting();
    }
}
