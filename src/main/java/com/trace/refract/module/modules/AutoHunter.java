package com.trace.refract.module.modules;

import com.trace.refract.Refract;
import com.trace.refract.hud.Notifications;
import com.trace.refract.mixin.FireworkRocketEntityAccessor;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.render.Esp;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.FindingsLog;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Rotations;
import com.trace.refract.util.Sfx;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.world.Heightmap;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Hunter: flies an elytra search spiral at cruise altitude while the scanner runs, and when Base Finder
 * reaches the target confidence it lands beside the base and digs a staircase down to it.
 * <ul>
 *   <li>Fireworks: a new rocket is only used once your current boost has ended. The hotbar rocket stack is used
 *       until empty, then the next stack is moved in from your inventory.</li>
 *   <li>Low on rockets: lands on dry ground and, with Auto Restock on, runs /ah and buys full stacks of 64
 *       (never above Max Price per stack), then takes off again.</li>
 *   <li>Digging: uses an item named "Drill" if you carry one, otherwise your fastest pickaxe. It stops on lava or
 *       water behind a wall, unbreakable blocks, drops over 3 blocks, low tool durability, or if it gets stuck.</li>
 *   <li>Safety: stops and glides down if health or elytra durability get low; pressing back, left, right or sneak
 *       hands control back to you immediately.</li>
 * </ul>
 */
public class AutoHunter extends Module {
    public enum State { IDLE, TAKEOFF, CRUISE, APPROACH, LAND, RESTOCK_OPEN, RESTOCK_PICK, RESTOCK_CONFIRM, DIG_CLEAR, DIG_MOVE, ARRIVED }

    public final NumberSetting cruiseY = add(new NumberSetting("Cruise Altitude", 290, 120, 380, 5, ""));
    public final NumberSetting target = add(new NumberSetting("Target Confidence", 100, 50, 100, 1, "%"));
    public final ModeSetting boost = add(new ModeSetting("Rocket Use", "When Boost Ends", "When Boost Ends", "When Slow"));
    public final NumberSetting minRockets = add(new NumberSetting("Land Below", 8, 2, 64, 1, " rockets"));
    public final BoolSetting autoDig = add(new BoolSetting("Dig To Base", true));
    public final BoolSetting silentRot = add(new BoolSetting("Silent Rotations", true));
    public final NumberSetting turnSpeed = add(new NumberSetting("Turn Speed", 14, 3, 40, 1, " deg/t"));
    public final BoolSetting restock = add(new BoolSetting("Auto Restock (/ah)", false));
    public final NumberSetting stacks = add(new NumberSetting("Stacks To Buy", 2, 1, 6, 1, ""));
    public final NumberSetting maxPrice = add(new NumberSetting("Max Price / Stack", 5000, 100, 100000, 100, "$"));
    public final NumberSetting spacing = add(new NumberSetting("Lane Spacing", 0, 0, 512, 16, "m"));

    private State state = State.IDLE;
    private int timer, tries;
    private String status = "";
    // Search spiral
    private BlockPos origin;
    private int leg, legStep;
    private BlockPos waypoint;
    // Target base
    private BaseFinder.Candidate targetBase;
    private BlockPos landSpot, digTarget;
    private final Set<Long> visited = new HashSet<>();
    private final ArrayDeque<BlockPos> toClear = new ArrayDeque<>();
    private BlockPos digNext, mining;
    private boolean pendingRestock;
    // Restock
    private int rocketsBefore, bought, restockAttempts;

    public AutoHunter() { super("Auto Hunter", "Elytra search with auto rockets; lands at 100% bases and digs down. Restocks rockets from /ah.", Category.HUNTING); }

    public State state() { return state; }

    @Override public String suffix() { return state == State.IDLE ? null : state.name().toLowerCase(Locale.ROOT).replace('_', ' '); }

    @Override public void onEnable() {
        if (mc.player == null) { setEnabled(false, false); return; }
        if (!ModuleManager.baseFinder.isEnabled()) ModuleManager.baseFinder.setEnabled(true, false);
        origin = mc.player.getBlockPos();
        leg = 0; legStep = 0; waypoint = null; targetBase = null; toClear.clear(); visited.clear();
        go(mc.player.isGliding() ? State.CRUISE : State.TAKEOFF);
    }

    @Override public void onDisable() { Rotations.stop(); releaseKeys(); state = State.IDLE; if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking(); }

    private void go(State s) { state = s; timer = 0; }

    private void stop(String why, boolean bad) {
        Notifications.push("Auto Hunter", why, bad ? 0xFFFF4D6D : 0xFF5CFFB0, 7000);
        Refract.chat((bad ? "§c" : "§a") + "Auto Hunter: §f" + why);
        if (bad) Sfx.warn(); else Sfx.alert();
        setEnabled(false, false);
    }

    private void releaseKeys() {
        mc.options.jumpKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);
        walkYaw = null;
    }

    /**
     * World yaw to walk toward between dig steps, or null. Read by {@code KeyboardInputMixin}, which turns it into
     * movement input relative to where the camera really faces, so the camera never has to turn to walk there.
     */
    private Float walkYaw;

    public Float walkYaw() { return isEnabled() ? walkYaw : null; }

    // ------------------------------------------------------------------ helpers

    private int rockets() {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(Items.FIREWORK_ROCKET)) n += inv.getStack(i).getCount();
        return n;
    }

    private boolean boostActive() {
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof FireworkRocketEntity f && !f.isRemoved() && ((FireworkRocketEntityAccessor) f).refract$getShooter() == mc.player) return true;
        }
        return false;
    }

    /** Puts the item at inventory index {@code idx} in the hand (moving it into the hotbar if needed). */
    private void hold(int idx) {
        var inv = mc.player.getInventory();
        if (idx < 9) { inv.setSelectedSlot(idx); return; }
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, idx, inv.getSelectedSlot(), SlotActionType.SWAP, mc.player);
    }

    private boolean useRocket() {
        var inv = mc.player.getInventory();
        int idx = -1;
        if (inv.getStack(inv.getSelectedSlot()).isOf(Items.FIREWORK_ROCKET)) idx = inv.getSelectedSlot();
        for (int i = 0; i < 36 && idx < 0; i++) if (inv.getStack(i).isOf(Items.FIREWORK_ROCKET)) idx = i;
        if (idx < 0) return false;
        hold(idx);
        if (!mc.player.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) return false; // swap lands next tick
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        return true;
    }

    private int elytraLeft() {
        ItemStack s = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        return s.isOf(Items.ELYTRA) ? s.getMaxDamage() - s.getDamage() : -1;
    }

    /** Desired rotation for this tick; flushed once through {@link Rotations} at the end of onTick. */
    private Float wantYaw, wantPitch;
    private boolean wantSilent;

    /** Aim at a point. Used for mining, so it may be silent: the camera stays where you left it. */
    private void face(Vec3d at) {
        wantYaw = Rotations.yawTo(at);
        wantPitch = Rotations.pitchTo(at);
        wantSilent = silentRot.on();
    }

    /**
     * Point the body at (x, z). Never silent: elytra physics runs on the client and pushes you where the
     * body really faces, so the rotation the server sees has to be the one you are actually flying.
     */
    private void yawTo(double x, double z) {
        wantYaw = yawToward(x, z);
    }

    private float yawToward(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-(x - mc.player.getX()), z - mc.player.getZ()));
    }

    private double horiz(BlockPos p) { return Math.hypot(p.getX() + 0.5 - mc.player.getX(), p.getZ() + 0.5 - mc.player.getZ()); }

    private int ground(int x, int z) { return mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z); }

    private boolean dry(int x, int z) {
        BlockState top = mc.world.getBlockState(new BlockPos(x, ground(x, z) - 1, z));
        return top.getFluidState().isEmpty() && !top.isAir();
    }

    /** Nearest dry ground to (x, z), searching outward up to 32 blocks. */
    private BlockPos drySpot(int x, int z) {
        for (int r = 0; r <= 32; r += 4) for (int a = 0; a < 8; a++) {
            int dx = (int) Math.round(Math.cos(a * Math.PI / 4) * r), dz = (int) Math.round(Math.sin(a * Math.PI / 4) * r);
            if (dry(x + dx, z + dz)) return new BlockPos(x + dx, ground(x + dx, z + dz), z + dz);
            if (r == 0) break;
        }
        return null;
    }

    private boolean userOverride() {
        return mc.options.backKey.isPressed() || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed() || mc.options.sneakKey.isPressed();
    }

    // ------------------------------------------------------------------ main loop

    @Override public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) { setEnabled(false, false); return; }
        timer++;
        if (userOverride()) { stop("Manual control taken back", false); return; }
        if (mc.player.getHealth() < 8) { stop("Health low: stopped", true); return; }
        boolean flying = state == State.TAKEOFF || state == State.CRUISE || state == State.APPROACH;
        if (flying && elytraLeft() >= 0 && elytraLeft() < 20) { status = "elytra almost broken"; go(State.LAND); landSpot = drySpot(mc.player.getBlockX(), mc.player.getBlockZ()); }
        switch (state) {
            case TAKEOFF -> takeoff();
            case CRUISE -> cruise();
            case APPROACH -> approach();
            case LAND -> land();
            case RESTOCK_OPEN, RESTOCK_PICK, RESTOCK_CONFIRM -> restock();
            case DIG_CLEAR -> digClear();
            case DIG_MOVE -> digMove();
            default -> { }
        }
        if (wantYaw != null || wantPitch != null) {
            Rotations.aim(wantYaw != null ? wantYaw : Rotations.currentYaw(),
                    wantPitch != null ? wantPitch : Rotations.currentPitch(),
                    turnSpeed.asFloat(), wantSilent);
        }
        wantYaw = null;
        wantPitch = null;
        wantSilent = false;
    }

    private void takeoff() {
        if (elytraLeft() < 0) { stop("Wear an elytra to start", true); return; }
        if (elytraLeft() < 20) { stop("Elytra almost broken", true); return; }
        if (rockets() == 0) {
            if (restock.on()) { pendingRestock = true; go(State.RESTOCK_OPEN); } else stop("No firework rockets", true);
            return;
        }
        if (mc.player.isGliding()) {
            mc.options.jumpKey.setPressed(false);
            wantPitch = -50f;
            if (!boostActive()) useRocket();
            if (timer > 5) go(targetBase != null ? State.APPROACH : State.CRUISE);
            return;
        }
        if (mc.player.isOnGround()) {
            for (int i = 2; i < 12; i++) {
                if (!mc.world.getBlockState(mc.player.getBlockPos().up(i)).getCollisionShape(mc.world, mc.player.getBlockPos().up(i)).isEmpty()) {
                    stop("No open sky above you to take off", true);
                    return;
                }
            }
        }
        // Jump, release, then press again in mid-air: the same input that opens an elytra by hand.
        int t = timer % 12;
        mc.options.jumpKey.setPressed(t == 1 || (t >= 5 && t <= 6 && !mc.player.isOnGround()));
        if (timer > 120) stop("Could not take off", true);
    }

    private BlockPos nextWaypoint() {
        int lane = spacing.asInt() > 0 ? spacing.asInt() : Math.max(64, mc.options.getClampedViewDistance() * 16 * 3 / 2);
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int len = (leg / 2 + 1) * lane;
        BlockPos from = waypoint == null ? origin : waypoint;
        int[] d = dirs[leg % 4];
        leg++;
        return from.add(d[0] * len, 0, d[1] * len);
    }

    private void steerAltitude(double wantY) {
        double dy = wantY - mc.player.getY();
        wantPitch = (float) MathHelper.clamp(-dy * 0.8, -40, 25);
    }

    private void keepBoosted() {
        if (boostActive()) return;
        boolean slow = mc.player.getVelocity().horizontalLength() < 1.0;
        if (boost.is("When Boost Ends") || slow || mc.player.getY() < cruiseY.get() - 10) useRocket();
    }

    private BaseFinder.Candidate goodBase() {
        for (BaseFinder.Candidate c : ModuleManager.baseFinder.candidates) {
            if (Math.round(c.confidence * 100) >= target.asInt() && !visited.contains(c.key)) return c;
        }
        return null;
    }

    private void cruise() {
        if (!mc.player.isGliding()) { go(State.TAKEOFF); return; }
        if (rockets() < minRockets.asInt()) {
            pendingRestock = true;
            landSpot = drySpot(mc.player.getBlockX(), mc.player.getBlockZ());
            status = "low on rockets";
            go(State.LAND);
            return;
        }
        BaseFinder.Candidate c = goodBase();
        if (c != null) {
            targetBase = c;
            visited.add(c.key);
            String msg = c.type + " (" + c.status + ") at " + Fmt.xyz(c.anchor) + ", " + Math.round(c.confidence * 100) + "%";
            Notifications.push("Auto Hunter", "Target: " + msg, 0xFFFF4D6D, 8000);
            Sfx.alert();
            FindingsLog.write("autohunter", Fmt.xyz(c.anchor), msg);
            if (ModuleManager.waypoints != null && ModuleManager.waypoints.isEnabled() && ModuleManager.waypoints.autoBases.on())
                ModuleManager.waypoints.add(c.type, c.anchor, c.statusColor);
            planLanding();
            go(State.APPROACH);
            return;
        }
        if (waypoint == null || horiz(waypoint) < 32) waypoint = nextWaypoint();
        yawTo(waypoint.getX() + 0.5, waypoint.getZ() + 0.5);
        steerAltitude(cruiseY.get());
        keepBoosted();
        status = "searching  ·  next " + Fmt.xyz(waypoint);
    }

    /** Lands one staircase-length away from the base so a straight 45° staircase ends right at it. */
    private void planLanding() {
        BlockPos a = targetBase.anchor;
        digTarget = a;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int depth = Math.max(4, ground(a.getX(), a.getZ()) - a.getY());
            for (int it = 0; it < 2; it++) depth = Math.max(4, ground(a.getX() + d[0] * depth, a.getZ() + d[1] * depth) - a.getY());
            int x = a.getX() + d[0] * depth, z = a.getZ() + d[1] * depth;
            if (dry(x, z)) { landSpot = new BlockPos(x, ground(x, z), z); return; }
        }
        landSpot = drySpot(a.getX(), a.getZ());
    }

    private void approach() {
        if (landSpot == null) { stop("No dry ground near the base to land on", true); return; }
        if (!mc.player.isGliding()) { go(State.TAKEOFF); return; }
        yawTo(landSpot.getX() + 0.5, landSpot.getZ() + 0.5);
        steerAltitude(cruiseY.get());
        keepBoosted();
        status = "flying to base  ·  " + Fmt.dist(horiz(landSpot));
        if (horiz(landSpot) < 48) go(State.LAND);
    }

    private void land() {
        if (landSpot == null) landSpot = drySpot(mc.player.getBlockX(), mc.player.getBlockZ());
        if (landSpot == null) { stop("No dry ground below to land on", true); return; }
        if (mc.player.isOnGround() || mc.player.isTouchingWater()) {
            releaseKeys();
            if (pendingRestock && restock.on()) { go(State.RESTOCK_OPEN); return; }
            if (pendingRestock) { stop("Landed: out of rockets (turn on Auto Restock to buy more)", true); return; }
            if (targetBase != null && autoDig.on()) { startDig(); return; }
            if (targetBase != null) { go(State.ARRIVED); stop("Landed beside " + targetBase.type + " at " + Fmt.xyz(targetBase.anchor), false); return; }
            stop("Landed", false);
            return;
        }
        // Spiral down over the spot without rockets; level out just above the ground so you never take fall damage.
        double d = horiz(landSpot);
        if (d > 20) yawTo(landSpot.getX() + 0.5, landSpot.getZ() + 0.5);
        else wantYaw = Rotations.currentYaw() + 7;
        double above = mc.player.getY() - ground(mc.player.getBlockX(), mc.player.getBlockZ());
        wantPitch = above < 6 ? -8f : 22f;
        status = "landing  ·  " + Math.round(above) + "m up";
    }

    // ------------------------------------------------------------------ restock from /ah

    private static final Pattern PRICE = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?");

    /** Price from an auction listing's lore ("$1.5k", "Price: $1,500"...), or -1 if there is none. */
    static double price(ItemStack s) {
        var lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            Matcher m = PRICE.matcher(line.getString());
            if (!m.find()) continue;
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            String suf = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            return v * switch (suf) { case "k" -> 1e3; case "m" -> 1e6; case "b" -> 1e9; default -> 1; };
        }
        return -1;
    }

    private void restock() {
        var screen = mc.currentScreen instanceof GenericContainerScreen g ? g : null;
        switch (state) {
            case RESTOCK_OPEN -> {
                if (timer == 1) {
                    if (restockAttempts == 0) bought = 0;
                    rocketsBefore = rockets();
                    mc.getNetworkHandler().sendChatCommand("ah firework rocket");
                    status = "opening /ah";
                }
                if (screen != null && timer > 10) go(State.RESTOCK_PICK);
                else if (timer > 100) finishRestock("The auction house did not open");
            }
            case RESTOCK_PICK -> {
                if (screen == null) { finishRestock("Auction house closed"); return; }
                if (timer < 6) return; // let the listing load
                GenericContainerScreenHandler h = screen.getScreenHandler();
                int size = h.getRows() * 9;
                Slot best = null;
                double bestPrice = Double.MAX_VALUE;
                for (int i = 0; i < size; i++) {
                    Slot sl = h.slots.get(i);
                    ItemStack s = sl.getStack();
                    if (!s.isOf(Items.FIREWORK_ROCKET) || s.getCount() != 64) continue;
                    double p = price(s);
                    if (p > 0 && p <= maxPrice.get() && p < bestPrice) { bestPrice = p; best = sl; }
                }
                if (best == null) { finishRestock("No full stack of rockets under $" + maxPrice.asInt()); return; }
                mc.interactionManager.clickSlot(h.syncId, best.id, 0, SlotActionType.PICKUP, mc.player);
                status = "buying 64 rockets for $" + Math.round(bestPrice);
                restockAttempts++;
                go(State.RESTOCK_CONFIRM);
            }
            case RESTOCK_CONFIRM -> {
                if (rockets() >= rocketsBefore + 64) { boughtOne(); return; } // some menus buy on the first click
                if (screen != null && timer > 6 && timer % 10 == 6) {
                    GenericContainerScreenHandler h = screen.getScreenHandler();
                    int size = h.getRows() * 9;
                    for (int i = 0; i < size; i++) {
                        ItemStack s = h.slots.get(i).getStack();
                        String name = s.getName().getString().toLowerCase(Locale.ROOT);
                        boolean green = s.isOf(Items.LIME_STAINED_GLASS_PANE) || s.isOf(Items.GREEN_STAINED_GLASS_PANE)
                                || s.isOf(Items.LIME_CONCRETE) || s.isOf(Items.GREEN_CONCRETE) || s.isOf(Items.LIME_WOOL) || s.isOf(Items.GREEN_WOOL);
                        if (name.contains("confirm") || (green && !name.contains("cancel"))) {
                            mc.interactionManager.clickSlot(h.syncId, h.slots.get(i).id, 0, SlotActionType.PICKUP, mc.player);
                            break;
                        }
                    }
                }
                if (timer > 80) finishRestock("Purchase was not confirmed (listing gone or not enough money)");
            }
            default -> { }
        }
    }

    private void boughtOne() {
        bought++;
        Notifications.push("Auto Hunter", "Bought 64 rockets (" + bought + "/" + stacks.asInt() + ")", 0xFF5CFFB0, 3000);
        if (bought >= stacks.asInt() || restockAttempts >= stacks.asInt() * 3) { finishRestock(null); return; }
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();
        go(State.RESTOCK_OPEN);
    }

    private void finishRestock(String problem) {
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();
        restockAttempts = 0;
        pendingRestock = false;
        if (rockets() < minRockets.asInt()) { stop(problem != null ? problem : "Still low on rockets", true); return; }
        if (problem != null) Notifications.push("Auto Hunter", problem, 0xFFFF9A3D, 5000);
        go(State.TAKEOFF);
    }

    // ------------------------------------------------------------------ dig a staircase to the base

    private void startDig() {
        if (digTarget == null) { stop("No base to dig to", true); return; }
        Notifications.push("Auto Hunter", "Digging down to " + Fmt.xyz(digTarget), 0xFF4DE3FF, 4000);
        planStep();
    }

    private static boolean passable(net.minecraft.client.world.ClientWorld w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return s.getCollisionShape(w, p).isEmpty() && s.getFluidState().isEmpty();
    }

    private void planStep() {
        BlockPos feet = mc.player.getBlockPos();
        int dx = digTarget.getX() - feet.getX(), dz = digTarget.getZ() - feet.getZ();
        if (Math.abs(dx) + Math.abs(dz) <= 2 && Math.abs(feet.getY() - digTarget.getY()) <= 2) {
            releaseKeys();
            go(State.ARRIVED);
            stop("Arrived at " + targetBase.type + " " + Fmt.xyz(digTarget), false);
            return;
        }
        Direction dir = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (dx == 0 && dz == 0) dir = Direction.EAST;
        boolean down = feet.getY() > digTarget.getY();
        BlockPos next = feet.offset(dir);
        toClear.clear();
        toClear.add(next.up());
        toClear.add(next);
        if (down) toClear.add(next.down());
        digNext = down ? next.down() : next;
        // Ground under the new step: refuse long drops and anything over lava.
        int drop = 0;
        BlockPos below = digNext.down();
        while (drop < 6 && passable(mc.world, below)) { drop++; below = below.down(); }
        if (!mc.world.getBlockState(below).getFluidState().isEmpty() && drop < 6) { stop("Lava or water under the next step: stopped", true); return; }
        if (drop > 3) { stop("Drop of more than 3 blocks ahead: stopped", true); return; }
        for (BlockPos p : toClear) {
            for (Direction d : Direction.values()) {
                if (!mc.world.getBlockState(p.offset(d)).getFluidState().isEmpty()) { stop("Liquid behind the wall at " + Fmt.xyz(p.offset(d)) + ": stopped", true); return; }
            }
        }
        mining = null;
        go(State.DIG_CLEAR);
    }

    /** Fastest tool for this block; an item named "Drill" wins whenever it can mine it. */
    private int bestTool(BlockState st) {
        var inv = mc.player.getInventory();
        int best = -1;
        float bestScore = 1.0f;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || (s.isDamageable() && s.getMaxDamage() - s.getDamage() < 8)) continue;
            float score = s.getMiningSpeedMultiplier(st);
            if (score > 1f && s.getName().getString().toLowerCase(Locale.ROOT).contains("drill")) score *= 4;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private void digClear() {
        while (!toClear.isEmpty() && passable(mc.world, toClear.peek())) toClear.poll();
        if (toClear.isEmpty()) { mc.interactionManager.cancelBlockBreaking(); go(State.DIG_MOVE); return; }
        BlockPos p = toClear.peek();
        BlockState st = mc.world.getBlockState(p);
        if (st.getHardness(mc.world, p) < 0) { stop("Unbreakable block at " + Fmt.xyz(p) + ": stopped", true); return; }
        var info = com.trace.refract.scan.Indicators.get(st.getBlock());
        if (info != null && info.storage() != null) {
            // Never break the base's own storage: reaching it means you have arrived.
            releaseKeys();
            mc.interactionManager.cancelBlockBreaking();
            go(State.ARRIVED);
            stop("Arrived at " + targetBase.type + ": " + info.storage().label + " at " + Fmt.xyz(p), false);
            return;
        }
        face(p.toCenterPos());
        if (!Rotations.onTarget(p.toCenterPos(), 10f)) { status = "aiming at " + Fmt.xyz(p); return; }
        if (!p.equals(mining)) {
            int tool = bestTool(st);
            if (tool >= 0) hold(tool);
            mining = p;
            timer = 0;
            mc.interactionManager.attackBlock(p, Direction.UP);
        } else {
            mc.interactionManager.updateBlockBreakingProgress(p, Direction.UP);
        }
        mc.player.swingHand(Hand.MAIN_HAND);
        if (st.getBlock() instanceof FallingBlock) timer = Math.min(timer, 100); // gravel keeps falling in; keep going
        status = "digging  ·  " + Fmt.dist(Math.sqrt(mc.player.getBlockPos().getSquaredDistance(digTarget))) + " to go";
        if (timer > 200) stop("Could not break block at " + Fmt.xyz(p), true);
    }

    private void digMove() {
        BlockPos feet = mc.player.getBlockPos();
        if (feet.getX() == digNext.getX() && feet.getZ() == digNext.getZ() && mc.player.isOnGround()) {
            walkYaw = null;
            planStep();
            return;
        }
        // Walking is part of digging: the head turns silently (camera stays still) and the walk direction is fed
        // straight into movement input, so the body walks there whichever way the camera faces.
        float dir = yawToward(digNext.getX() + 0.5, digNext.getZ() + 0.5);
        wantYaw = dir;
        wantPitch = 20f;
        wantSilent = silentRot.on();
        walkYaw = dir;
        if (timer > 60) { releaseKeys(); stop("Stuck while digging at " + Fmt.xyz(feet), true); }
    }

    // ------------------------------------------------------------------ rendering

    @Override public void onWorldRender(float tickDelta) {
        if (mc.player == null) return;
        Vec3d me = Esp.pos(mc.player);
        if (waypoint != null && state == State.CRUISE) Esp.line(me, new Vec3d(waypoint.getX() + 0.5, me.y, waypoint.getZ() + 0.5), 0x9038BDF8, 1.5f, true);
        if (landSpot != null && (state == State.APPROACH || state == State.LAND)) Esp.block(landSpot, 0, 0x405CFFB0, 0xF05CFFB0, 2f, true);
        if (digTarget != null && state.name().startsWith("DIG")) {
            Esp.line(me, digTarget.toCenterPos(), 0xB0FF4D6D, 1.5f, true);
            for (BlockPos p : toClear) Esp.block(p, 0.02, 0x304DE3FF, 0xE04DE3FF, 1.5f, true);
        }
    }

    public String status() { return status; }

}
