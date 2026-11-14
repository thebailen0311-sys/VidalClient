package com.trace.refract.module.modules;

import com.trace.refract.Refract;
import com.trace.refract.hud.WorldLabels;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Time;
import com.trace.refract.util.WorldToScreen;
import net.minecraft.block.*;
import net.minecraft.block.enums.SculkSensorPhase;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pins an ESP box, a tracer and a coordinate label on whatever made a noise or changed state. Four sources:
 * <ul>
 *   <li>Block-state changes (levers, buttons, pistons, lamps, doors, barrels, hoppers, droppers, furnaces,
 *       sculk sensors, redstone): sent for every loaded chunk, so they reach your full render distance at any
 *       height, above or below you.</li>
 *   <li>World events: dispensers, crafters, spawners spawning mobs, anvils, brewing, blocks broken by others.
 *       Global events (wither spawn, dragon death, end portal opening) arrive from any distance.</li>
 *   <li>Block events: chest/shulker lids, pistons, note blocks, bells.</li>
 *   <li>Sound packets: whatever else the server lets you hear.</li>
 * </ul>
 * Range is horizontal only, so height never limits detection. Your own actions are filtered out: anything you
 * clicked, broke or placed, the redstone it set off, sounds from your own player, and anything right at you.
 */
public class SoundLocator extends Module {
    public enum Kind {
        PISTON("Piston", 0xFFFFB347), REDSTONE("Redstone", 0xFFFF4D5E), CONTAINER("Container", 0xFFFFD84D),
        DOOR("Door", 0xFF7CFF9A), FURNACE("Furnace", 0xFFFF8A3D), SPAWNER("Spawner", 0xFF4DE3FF),
        WORKSTATION("Workstation", 0xFFE0C38A), BUILD("Building", 0xFF5CC8FF), GLOBAL("Global", 0xFFFF5CF0),
        OTHER("Sound", 0xFFB4BCFF);

        public final String label;
        public final int color;

        Kind(String label, int color) { this.label = label; this.color = color; }
    }

    public enum Source { SOUND, EVENT, STATE, WORLD }

    public static final class Ping {
        public final Kind kind;
        public final BlockPos pos;
        public String label;
        public Source source;
        public long time;
        public int count = 1;

        Ping(Kind kind, BlockPos pos, String label, Source source) {
            this.kind = kind; this.pos = pos; this.label = label; this.source = source; this.time = Time.ms();
        }

        public Vec3d center() { return pos.toCenterPos(); }
    }

    /** Something the local player did: clicked, attacked, broke or placed at {@code pos}. */
    private record SelfAction(BlockPos pos, long time, boolean redstone) {}

    public final BoolSetting pistons = add(new BoolSetting("Pistons", true));
    public final BoolSetting redstone = add(new BoolSetting("Redstone", true));
    public final BoolSetting containers = add(new BoolSetting("Containers", true));
    public final BoolSetting doors = add(new BoolSetting("Doors & Gates", true));
    public final BoolSetting furnaces = add(new BoolSetting("Furnaces", true));
    public final BoolSetting spawnersSet = add(new BoolSetting("Spawners", true));
    public final BoolSetting workstations = add(new BoolSetting("Workstations", true));
    public final BoolSetting global = add(new BoolSetting("Global Events", true));
    public final BoolSetting building = add(new BoolSetting("Building & Mining", false));
    public final BoolSetting others = add(new BoolSetting("Other Sounds", false));
    public final BoolSetting ignoreSelf = add(new BoolSetting("Ignore My Sounds", true));
    public final BoolSetting stateSource = add(new BoolSetting("Render-Distance Detection", true));
    public final NumberSetting range = add(new NumberSetting("Range", 1024, 16, 1024, 16, "m"));
    public final NumberSetting duration = add(new NumberSetting("Duration", 10, 1, 60, 1, "s"));
    public final BoolSetting boxes = add(new BoolSetting("ESP Boxes", true));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", true));
    public final BoolSetting labels = add(new BoolSetting("Labels", true));
    public final BoolSetting arrows = add(new BoolSetting("Edge Arrows", true));
    public final BoolSetting coords = add(new BoolSetting("Show Coords", true));
    public final BoolSetting chat = add(new BoolSetting("Chat Alerts", false));

    public final List<Ping> pings = new ArrayList<>();
    /** Farthest event of each kind received this session: shows how far your server actually sends them. */
    private double reachState, reachSound, reachEvent;

    /** e.g. "reach: blocks 312m · events 64m · sounds 41m" (what the server has sent you so far). */
    public String reachText() {
        if (reachState + reachSound + reachEvent == 0) return "range " + rangeText() + "  ·  any height";
        return "reach  blocks " + Fmt.dist(reachState) + "  ·  events " + Fmt.dist(reachEvent) + "  ·  sounds " + Fmt.dist(reachSound);
    }
    private final List<SelfAction> selfActions = new ArrayList<>();

    private static final long SELF_WINDOW = 1500, CASCADE_WINDOW = 1200;
    private static final double SELF_RADIUS_SQ = 2.5 * 2.5, ACTION_RADIUS_SQ = 2.0 * 2.0, CASCADE_RADIUS_SQ = 16.0 * 16.0;

    public SoundLocator() { super("Sound Locator", "Tracers to every piston, lever, chest, door, spawner and dispenser others use. Any height, ignores you.", Category.HUNTING); }

    @Override public void onDisable() { pings.clear(); }

    @Override public void onTick() {
        long life = lifeMs(), now = Time.ms();
        pings.removeIf(p -> now - p.time > life + 400);
        selfActions.removeIf(a -> now - a.time > SELF_WINDOW);
    }

    @Override public String suffix() { return String.valueOf(pings.size()); }

    public long lifeMs() { return (long) (duration.get() * 1000); }

    public String rangeText() { return range.get() >= range.max ? "∞" : range.display(); }

    private boolean inRange(double x, double z) {
        if (range.get() >= range.max || mc.player == null) return true;
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ();
        return dx * dx + dz * dz <= range.get() * range.get();
    }

    private boolean enabled(Kind k) {
        return switch (k) {
            case PISTON -> pistons.on();
            case REDSTONE -> redstone.on();
            case CONTAINER -> containers.on();
            case DOOR -> doors.on();
            case FURNACE -> furnaces.on();
            case SPAWNER -> spawnersSet.on();
            case WORKSTATION -> workstations.on();
            case GLOBAL -> global.on();
            case BUILD -> building.on();
            case OTHER -> others.on();
        };
    }

    // ------------------------------------------------------------------ self filter

    /** Called by the interaction mixin whenever you click, attack or break a block (client thread). */
    public void onSelfAction(BlockPos pos, boolean redstoneSource) {
        selfActions.add(new SelfAction(pos.toImmutable(), Time.ms(), redstoneSource));
        if (selfActions.size() > 64) selfActions.remove(0);
    }

    /** True when a ping at {@code pos} was caused by the local player. */
    private boolean isSelf(Kind kind, BlockPos pos) {
        if (!ignoreSelf.on() || mc.player == null) return false;
        Vec3d c = pos.toCenterPos();
        if (mc.player.getBoundingBox().getCenter().squaredDistanceTo(c) <= SELF_RADIUS_SQ) {
            // Plates and tripwires you walk over set off redstone further away: treat them as your action.
            if (kind == Kind.REDSTONE) onSelfAction(pos, true);
            return true;
        }
        long now = Time.ms();
        boolean chain = kind == Kind.REDSTONE || kind == Kind.PISTON || kind == Kind.DOOR;
        for (SelfAction a : selfActions) {
            long age = now - a.time;
            double d = a.pos.getSquaredDistance(pos);
            if (age <= SELF_WINDOW && d <= ACTION_RADIUS_SQ) return true;
            if (chain && a.redstone && age <= CASCADE_WINDOW && d <= CASCADE_RADIUS_SQ) return true;
        }
        return false;
    }

    private void ping(Kind kind, BlockPos pos, String label, Source src) {
        if (!isEnabled() || mc.player == null || kind == null || !enabled(kind)) return;
        if (kind != Kind.GLOBAL && !inRange(pos.getX() + 0.5, pos.getZ() + 0.5)) return;
        if (isSelf(kind, pos)) return;
        double hd = Math.hypot(pos.getX() + 0.5 - mc.player.getX(), pos.getZ() + 0.5 - mc.player.getZ());
        if (kind != Kind.GLOBAL) {
            if (src == Source.STATE) reachState = Math.max(reachState, hd);
            else if (src == Source.SOUND) reachSound = Math.max(reachSound, hd);
            else reachEvent = Math.max(reachEvent, hd);
        }
        double merge = kind == Kind.REDSTONE ? 6.25 : 2.25;
        for (Ping p : pings) {
            if (p.kind == kind && p.pos.getSquaredDistance(pos) <= merge) {
                p.time = Time.ms();
                p.count++;
                p.label = label;
                if (src == Source.STATE || p.source == null) p.source = src;
                return;
            }
        }
        if (pings.size() >= 200) pings.remove(0);
        pings.add(new Ping(kind, pos.toImmutable(), label, src));
        if (chat.on() || kind == Kind.GLOBAL) {
            double d = Math.sqrt(mc.player.getBlockPos().getSquaredDistance(pos));
            Refract.chat(String.format(Locale.ROOT, "§7%s §fat §b%s §7(%s)", label, Fmt.xyz(pos), Fmt.dist(d)));
        }
    }

    // ------------------------------------------------------------------ source: sound packets

    /** @param source the entity the sound is attached to, or null for positioned sounds */
    public void onSound(Identifier id, double x, double y, double z, Entity source) {
        if (source != null && mc.player != null && ignoreSelf.on()
                && (source == mc.player || source == mc.player.getVehicle() || source.hasPassenger(mc.player))) return;
        String p = id.getPath();
        Kind kind = classifySound(p);
        if (kind != null) ping(kind, BlockPos.ofFloored(x, y, z), pretty(p), Source.SOUND);
    }

    private Kind classifySound(String p) {
        if (p.contains("piston")) return Kind.PISTON;
        if (p.contains("chest.") || p.contains("barrel.") || p.contains("shulker_box.") || p.contains("ender_chest") || p.contains("copper_chest")) return Kind.CONTAINER;
        if (p.contains("lever") || p.contains("button.click") || p.contains("pressure_plate") || p.contains("comparator")
                || p.contains("redstone_torch") || p.contains("tripwire") || p.contains("dispenser") || p.contains("note_block")
                || p.contains("crafter") || p.contains("copper_bulb") || p.contains("sculk_sensor.click") || p.contains("tnt.primed")
                || p.contains("bell.use")) return Kind.REDSTONE;
        if (p.contains("door") || p.contains("fence_gate")) return Kind.DOOR;
        if (p.contains("furnace") || p.contains("smoker") || p.contains("blastfurnace")) return Kind.FURNACE;
        if (p.contains("anvil") || p.contains("grindstone") || p.contains("smithing_table") || p.contains("brewing_stand")
                || p.contains("enchantment_table") || p.contains("lectern") || p.contains("composter")) return Kind.WORKSTATION;
        if (p.startsWith("block.") && (p.endsWith(".place") || p.endsWith(".break"))) return Kind.BUILD;
        if (p.startsWith("block.") || p.startsWith("entity.")) {
            if (p.contains(".step") || p.contains("ambient") || p.contains(".fall") || p.contains("weather") || p.contains(".hit")
                    || p.contains(".swim") || p.contains(".splash")) return null;
            return Kind.OTHER;
        }
        return null;
    }

    // ------------------------------------------------------------------ source: world events

    public void onWorldEvent(int id, BlockPos pos, int data, boolean isGlobal) {
        switch (id) {
            case WorldEvents.DISPENSER_DISPENSES, WorldEvents.DISPENSER_LAUNCHES_PROJECTILE -> ping(Kind.REDSTONE, pos, "Dispenser fired", Source.WORLD);
            case WorldEvents.DISPENSER_FAILS -> ping(Kind.REDSTONE, pos, "Dispenser clicked (empty)", Source.WORLD);
            case WorldEvents.CRAFTER_CRAFTS, WorldEvents.CRAFTER_SHOOTS -> ping(Kind.REDSTONE, pos, "Crafter crafted", Source.WORLD);
            case WorldEvents.CRAFTER_FAILS -> ping(Kind.REDSTONE, pos, "Crafter clicked", Source.WORLD);
            case WorldEvents.REDSTONE_TORCH_BURNS_OUT -> ping(Kind.REDSTONE, pos, "Redstone torch burnt out", Source.WORLD);
            case WorldEvents.SPAWNER_SPAWNS_MOB -> ping(Kind.SPAWNER, pos, "Spawner spawned a mob", Source.WORLD);
            case WorldEvents.ANVIL_USED -> ping(Kind.WORKSTATION, pos, "Anvil used", Source.WORLD);
            case WorldEvents.GRINDSTONE_USED -> ping(Kind.WORKSTATION, pos, "Grindstone used", Source.WORLD);
            case WorldEvents.SMITHING_TABLE_USED -> ping(Kind.WORKSTATION, pos, "Smithing table used", Source.WORLD);
            case WorldEvents.BREWING_STAND_BREWS -> ping(Kind.WORKSTATION, pos, "Brewing finished", Source.WORLD);
            case WorldEvents.LECTERN_BOOK_PAGE_TURNED -> ping(Kind.WORKSTATION, pos, "Lectern page turned", Source.WORLD);
            case WorldEvents.COMPOSTER_USED -> ping(Kind.WORKSTATION, pos, "Composter used", Source.WORLD);
            case WorldEvents.JUKEBOX_STARTS_PLAYING -> ping(Kind.WORKSTATION, pos, "Jukebox playing", Source.WORLD);
            case WorldEvents.END_PORTAL_FRAME_FILLED -> ping(Kind.OTHER, pos, "Eye placed in portal frame", Source.WORLD);
            case WorldEvents.BONE_MEAL_USED -> ping(Kind.OTHER, pos, "Bone meal used", Source.WORLD);
            case WorldEvents.FIRE_EXTINGUISHED, WorldEvents.LAVA_EXTINGUISHED -> ping(Kind.OTHER, pos, "Fire put out", Source.WORLD);
            case WorldEvents.BLOCK_BROKEN -> {
                // Never sent to the player who broke the block, so this is always someone else.
                BlockState st = Block.getStateFromRawId(data);
                if (!st.isAir() && !transientBlock(st.getBlock())) ping(Kind.BUILD, pos, name(st.getBlock()) + " broken", Source.WORLD);
            }
            case WorldEvents.WITHER_SPAWNS -> ping(Kind.GLOBAL, pos, "Wither spawned", Source.WORLD);
            case WorldEvents.ENDER_DRAGON_DIES -> ping(Kind.GLOBAL, pos, "Ender Dragon killed", Source.WORLD);
            case WorldEvents.END_PORTAL_OPENED -> ping(Kind.GLOBAL, pos, "End portal opened", Source.WORLD);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ source: block events

    public void onBlockEvent(BlockPos pos, Block block, int type, int data) {
        if (block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock) {
            if (type == 1) ping(Kind.CONTAINER, pos, name(block) + (data > 0 ? " opened" : " closed"), Source.EVENT);
        } else if (block instanceof PistonBlock) {
            ping(Kind.PISTON, pos, type == 0 ? "Piston extend" : "Piston retract", Source.EVENT);
        } else if (block instanceof NoteBlock) {
            ping(Kind.REDSTONE, pos, "Note block", Source.EVENT);
        } else if (block instanceof BellBlock) {
            ping(Kind.REDSTONE, pos, "Bell rung", Source.EVENT);
        }
    }

    // ------------------------------------------------------------------ source: block-state changes

    public void onBlockChange(BlockPos pos, BlockState old, BlockState neu) {
        if (!isEnabled() || !stateSource.on() || old == neu || mc.player == null) return;
        Block b = neu.getBlock();
        if (old.getBlock() != b) {
            // Retracting turns the piston base into a moving piston for a moment: that is the retract.
            if (old.getBlock() instanceof PistonBlock && b instanceof PistonExtensionBlock) { ping(Kind.PISTON, pos, "Piston retracted", Source.STATE); return; }
            if (b instanceof PistonExtensionBlock || old.getBlock() instanceof PistonExtensionBlock) return; // covered by piston state
            if (!building.on()) return;
            if (neu.isAir() && !old.isAir() && !old.isReplaceable() && old.getFluidState().isEmpty() && !transientBlock(old.getBlock())) {
                ping(Kind.BUILD, pos, name(old.getBlock()) + " broken", Source.STATE);
            } else if ((old.isAir() || old.isReplaceable()) && !neu.isAir() && neu.getFluidState().isEmpty() && !transientBlock(b) && !neu.isReplaceable()) {
                ping(Kind.BUILD, pos, name(b) + " placed", Source.STATE);
            }
            return;
        }
        if (changed(old, neu, Properties.EXTENDED) && b instanceof PistonBlock) {
            ping(Kind.PISTON, pos, neu.get(Properties.EXTENDED) ? "Piston extended" : "Piston retracted", Source.STATE);
        } else if (changed(old, neu, Properties.OPEN)) {
            boolean open = neu.get(Properties.OPEN);
            if (b instanceof BarrelBlock) ping(Kind.CONTAINER, pos, open ? "Barrel opened" : "Barrel closed", Source.STATE);
            else ping(Kind.DOOR, pos, name(b) + (open ? " opened" : " closed"), Source.STATE);
        } else if (changed(old, neu, Properties.LIT)) {
            boolean lit = neu.get(Properties.LIT);
            if (b instanceof AbstractFurnaceBlock) ping(Kind.FURNACE, pos, name(b) + (lit ? " started" : " stopped"), Source.STATE);
            else if (b instanceof RedstoneLampBlock || b instanceof RedstoneTorchBlock || b instanceof BulbBlock) ping(Kind.REDSTONE, pos, name(b) + (lit ? " on" : " off"), Source.STATE);
        } else if (changed(old, neu, Properties.POWERED)) {
            if (b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock) return; // OPEN covers these
            ping(Kind.REDSTONE, pos, name(b) + (neu.get(Properties.POWERED) ? " on" : " off"), Source.STATE);
        } else if (changed(old, neu, Properties.TRIGGERED)) {
            if (neu.get(Properties.TRIGGERED)) ping(Kind.REDSTONE, pos, name(b) + " fired", Source.STATE);
        } else if (changed(old, neu, Properties.ENABLED) && b instanceof HopperBlock) {
            ping(Kind.REDSTONE, pos, neu.get(Properties.ENABLED) ? "Hopper unlocked" : "Hopper locked", Source.STATE);
        } else if (b instanceof RedstoneWireBlock && old.contains(Properties.POWER)) {
            int a = old.get(Properties.POWER), c = neu.get(Properties.POWER);
            if ((a == 0) != (c == 0)) ping(Kind.REDSTONE, pos, c > 0 ? "Redstone signal" : "Signal off", Source.STATE);
        } else if (b instanceof SculkSensorBlock && old.contains(Properties.SCULK_SENSOR_PHASE)) {
            if (neu.get(Properties.SCULK_SENSOR_PHASE) == SculkSensorPhase.ACTIVE && old.get(Properties.SCULK_SENSOR_PHASE) != SculkSensorPhase.ACTIVE) {
                ping(Kind.REDSTONE, pos, name(b) + " triggered", Source.STATE);
            }
        }
    }

    private static boolean changed(BlockState a, BlockState b, BooleanProperty p) {
        return a.contains(p) && b.contains(p) && a.get(p) != b.get(p);
    }

    private static boolean transientBlock(Block b) {
        return b instanceof LeavesBlock || b instanceof AbstractFireBlock || b instanceof PlantBlock || b instanceof CropBlock
                || b instanceof SnowBlock || b instanceof IceBlock || b instanceof FallingBlock || b instanceof VineBlock
                || b instanceof SugarCaneBlock || b instanceof CactusBlock || b instanceof BambooBlock || b instanceof KelpBlock
                || b instanceof KelpPlantBlock || b instanceof SeagrassBlock || b instanceof PointedDripstoneBlock
                || b instanceof AbstractPlantPartBlock || b instanceof MossBlock || b instanceof SculkBlock
                || b instanceof FrostedIceBlock || b instanceof TurtleEggBlock || b instanceof SnifferEggBlock
                || b instanceof BubbleColumnBlock || b instanceof ScaffoldingBlock;
    }

    /** Whether clicking this block can send a redstone signal elsewhere (lever, button, note block...). */
    public static boolean redstoneSource(BlockState s) {
        Block b = s.getBlock();
        return s.emitsRedstonePower() || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock
                || b instanceof DaylightDetectorBlock || b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock;
    }

    private static String name(Block b) {
        return pretty(Registries.BLOCK.getId(b).getPath());
    }

    /** "block.piston.extend" / "oak_trapdoor" -> "Piston extend" / "Oak trapdoor" */
    static String pretty(String path) {
        String s = path;
        int dot = s.indexOf('.');
        if (dot >= 0 && (s.startsWith("block") || s.startsWith("entity") || s.startsWith("item"))) s = s.substring(dot + 1);
        s = s.replace('.', ' ').replace('_', ' ').replace("click on", "on").replace("click off", "off");
        return s.isEmpty() ? path : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ rendering

    private float fade(Ping p) {
        long age = Time.ms() - p.time;
        float out = 1f - (float) Math.pow(Anim.clamp01(age / (float) lifeMs()), 3);
        return Math.min(out, 0.35f + 0.65f * Anim.clamp01(age / 120f));
    }

    @Override public void onWorldRender(float tickDelta) {
        if (!boxes.on() && !tracers.on()) return;
        Vec3d origin = tracers.on() ? Esp.tracerOrigin() : null;
        for (Ping p : pings) {
            float a = fade(p);
            if (a <= 0.02f) continue;
            int col = p.kind.color;
            if (boxes.on()) {
                Box b = new Box(p.pos).contract(0.02);
                Esp.gradientBox(b, Colors.withAlpha(col, (int) (0x55 * a)), Colors.withAlpha(col, (int) (0x06 * a)), 2f, true, 3);
                if (a > 0.5f) { Esp.corners(b, Colors.withAlpha(col, (int) (0xE0 * a)), 1.25f, true); Esp.glow(b, col, true); }
            }
            if (origin != null) Esp.line(origin, p.center(), Colors.withAlpha(col, (int) (0xC0 * a)), 1.5f, true);
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (mc.player == null || pings.isEmpty()) return;
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        // Nearest first so close sources keep the best label positions.
        List<Ping> sorted = new ArrayList<>(pings);
        sorted.sort((a, b) -> Double.compare(a.center().squaredDistanceTo(eye), b.center().squaredDistanceTo(eye)));
        int arrowsDrawn = 0;
        for (Ping p : sorted) {
            float a = fade(p);
            if (a <= 0.02f) continue;
            double dist = eye.distanceTo(p.center());
            String title = p.count > 1 ? p.label + "  ×" + p.count : p.label;
            int dy = p.pos.getY() - (int) Math.floor(eye.y);
            String sub = (coords.on() ? Fmt.xyz(p.pos) + "  ·  " : "") + Fmt.dist(dist) + (dy == 0 ? "" : (dy > 0 ? "  ↑" + dy : "  ↓" + (-dy)));
            WorldToScreen.Point sp;
            if (labels.on() && dist <= com.trace.refract.hud.Markers.FAR) sp = WorldLabels.draw(t, p.center().add(0, 0.6, 0), title, sub, p.kind.color, a, sw, sh, false);
            else {
                sp = WorldToScreen.project(p.center());
                if (sp.onScreen()) com.trace.refract.hud.Markers.draw(p.center(), p.kind.color, p.label + "  " + Fmt.dist(dist) + (dy == 0 ? "" : dy > 0 ? "  ↑" + dy : "  ↓" + (-dy)), a);
            }
            if (!sp.onScreen() && arrows.on() && arrowsDrawn++ < 12) {
                float prev = Gfx.alpha;
                Gfx.alpha = prev * a;
                float ang = sp.angle();
                float rx = sw * 0.36f, ry = sh * 0.34f;
                float ax = sw / 2f + MathHelper.cos(ang) * rx, ay = sh / 2f + MathHelper.sin(ang) * ry;
                Gfx.arrow(ax, ay, 11, ang, p.kind.color);
                String label = p.kind.label + "  " + Fmt.dist(dist);
                float lw = Gfx.width(label, Gfx.SMALL, Font.Weight.BOLD);
                float lx = ax - MathHelper.cos(ang) * 16 - lw / 2, ly = ay - MathHelper.sin(ang) * 12 - 4;
                Gfx.textShadow(label, lx, ly, Gfx.SMALL, Font.Weight.BOLD, Colors.lerp(0xFFFFFFFF, p.kind.color, 0.35f));
                Gfx.alpha = prev;
            }
        }
    }
}
