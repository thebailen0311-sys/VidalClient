package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Colors;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block ESP: search for any block in the game and give it a colour; only the blocks you picked are shown, each with
 * a gradient box, corner brackets and an optional tracer.
 *
 * <p>Scanning is spread over ticks: a few chunks per tick on a ring around you, so a wide range never costs a
 * frame. Results are cached and re-scanned as you move, which is also why a block shows up a moment after it
 * comes into range instead of popping in.
 *
 */
public class BlockEsp extends Module {
    /** The blocks to show, each with its own colour. Empty until you add some with the search box. */
    public final com.trace.refract.setting.BlockListSetting blocks = add(new com.trace.refract.setting.BlockListSetting("Blocks"));
    public final NumberSetting range = add(new NumberSetting("Range", 48, 8, 128, 2, "m"));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", true));
    public final BoolSetting filled = add(new BoolSetting("Filled", true));
    public final BoolSetting glow = add(new BoolSetting("Glow", true));
    public final BoolSetting throughWalls = add(new BoolSetting("Through Walls", true));
    public final NumberSetting speed = add(new NumberSetting("Chunks/Tick", 2, 1, 8, 1, ""));
    /** One-click block sets, so the common searches don't need the search box. */
    public final ModeSetting preset = add(new ModeSetting("Preset", "Off", "Off", "Ancient Debris", "Debris + Ores"));
    /** Only box blocks the camera can actually see. A visual preference, not a server-side bypass (no such thing). */
    public final BoolSetting lineOfSight = add(new BoolSetting("Line Of Sight", false));

    /** Fills the block list from the chosen preset. Cheap and idempotent, so it can run every tick. */
    private void applyPreset() {
        if (preset.is("Ancient Debris")) {
            blocks.add(Blocks.ANCIENT_DEBRIS);
        } else if (preset.is("Debris + Ores")) {
            blocks.add(Blocks.ANCIENT_DEBRIS);
            blocks.add(Blocks.NETHER_GOLD_ORE);
            blocks.add(Blocks.NETHER_QUARTZ_ORE);
            blocks.add(Blocks.ANCIENT_DEBRIS);
        }
    }


    private final Map<BlockPos, Block> found = new HashMap<>();
    private final List<ChunkPos> ring = new ArrayList<>();
    private int cursor, listHash;
    private Object world;

    public BlockEsp() {
        super("Block ESP", "Search for any block and give it a colour; only the blocks you pick are shown, with boxes and tracers.", Category.RENDER);
    }

    @Override public void onEnable() {
        found.clear();
        ring.clear();
        cursor = 0;
        world = mc.world;
    }

    @Override public void onDisable() { found.clear(); ring.clear(); }

    @Override public String suffix() { return found.isEmpty() ? null : String.valueOf(found.size()); }

    /** HSB to ARGB: a hue slider needs a saturated, readable colour at any angle. */
    private static int hsb(float hue, float sat, float bri, int alpha) {
        int rgb = java.awt.Color.HSBtoRGB((hue % 360f) / 360f, sat, bri) & 0xFFFFFF;
        return (alpha << 24) | rgb;
    }

    @Override public void onTick() {
        if (mc.player == null || mc.world == null) return;
        applyPreset();
        if (mc.world != world) { found.clear(); ring.clear(); cursor = 0; world = mc.world; }

        // Changing the list rescans from scratch, so removed blocks vanish and added ones appear.
        int h = blocks.get().keySet().hashCode();
        if (h != listHash) { listHash = h; found.clear(); cursor = 0; }
        if (blocks.get().isEmpty()) return;
        double r = range.get();
        ChunkPos here = mc.player.getChunkPos();
        if (ring.isEmpty() || !ring.get(0).equals(here)) buildRing(here, (int) Math.ceil(r / 16.0) + 1);
        if (ring.isEmpty()) return;

        int n = Math.max(1, speed.asInt());
        for (int i = 0; i < n && !ring.isEmpty(); i++) {
            ChunkPos cp = ring.get(cursor % ring.size());
            cursor = (cursor + 1) % ring.size();
            scanChunk(cp, r);
        }
        if (found.size() > 4000) prune(r);
    }

    private void buildRing(ChunkPos center, int radius) {
        ring.clear();
        cursor = 0;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz <= radius * radius + radius) ring.add(new ChunkPos(center.x + dx, center.z + dz));
        }
        ring.sort((a, b) -> {
            int da = (a.x - center.x) * (a.x - center.x) + (a.z - center.z) * (a.z - center.z);
            int db = (b.x - center.x) * (b.x - center.x) + (b.z - center.z) * (b.z - center.z);
            return Integer.compare(da, db);
        });
    }

    /** Scans one chunk inside the radius and the vertical band around the player. */
    private void scanChunk(ChunkPos cp, double r) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.world.isChunkLoaded(cp.x, cp.z)) return;
        double r2 = r * r;
        Vec3d eye = mc.player.getEntityPos();
        int band = (int) Math.min(32, Math.max(16, r * 0.5));
        int y0 = (int) Math.floor(eye.y) - band, y1 = (int) Math.ceil(eye.y) + band;
        int bx = cp.getStartX(), bz = cp.getStartZ();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Map<Block, Integer> wanted = blocks.get();
        found.keySet().removeIf(q -> (q.getX() >> 4) == cp.x && (q.getZ() >> 4) == cp.z); // blocks mined since the last pass go away
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                double dx = (bx + x + 0.5) - eye.x, dz = (bz + z + 0.5) - eye.z;
                if (dx * dx + dz * dz > r2) continue;
                for (int y = y0; y <= y1; y++) {
                    pos.set(bx + x, y, bz + z);
                    Block b = mc.world.getBlockState(pos).getBlock();
                    if (b == Blocks.AIR || !wanted.containsKey(b)) continue;
                    found.put(pos.toImmutable(), b);
                }
            }
        }
    }

    /** True when nothing solid sits between the eye and the block. */
    private boolean visible(Vec3d eye, Vec3d center, BlockPos p) {
        if (mc.world == null || mc.player == null) return false;
        var hit = mc.world.raycast(new net.minecraft.world.RaycastContext(eye, center,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
        return hit == null || hit.getBlockPos().equals(p);
    }

    /** Drops the farthest entries once the cache grows past its cap. */
    private void prune(double r) {
        if (mc.player == null) return;
        Vec3d eye = mc.player.getEntityPos();
        double r2 = r * r;
        found.entrySet().removeIf(e -> eye.squaredDistanceTo(Vec3d.ofCenter(e.getKey())) > r2);
    }

    @Override public void onWorldRender(float tickDelta) {
        if (found.isEmpty() || mc.player == null) return;
        double r = range.get();
        Vec3d eye = mc.player.getEyePos();
        Vec3d origin = null;
        int glows = 0;
        for (Map.Entry<BlockPos, Block> e : found.entrySet()) {
            BlockPos p = e.getKey();
            Integer chosen = blocks.get().get(e.getValue());
            if (chosen == null) continue;
            Vec3d center = p.toCenterPos();
            if (eye.squaredDistanceTo(center) > r * r) continue;
            if (lineOfSight.on() && !visible(eye, center, p)) continue;
            int solid = chosen | 0xFF000000;
            Box box = new Box(p).contract(0.03);
            if (filled.on()) {
                Esp.gradientBox(box, Colors.withAlpha(solid, 0x66), Colors.withAlpha(solid, 0x10), 1.1f, throughWalls.on(), 4);
            } else {
                Esp.box(box, 0, Colors.withAlpha(solid, 0xE0), 1.2f, throughWalls.on());
            }
            Esp.corners(box, Colors.withAlpha(solid, 0xF0), 1.3f, throughWalls.on());
            if (glow.on() && glows++ < 12) Esp.glow(box, solid, throughWalls.on());
            if (tracers.on()) {
                if (origin == null) origin = Esp.tracerOrigin();
                Esp.line(origin, center, Colors.withAlpha(solid, 0x70), 1f, throughWalls.on());
            }
        }
    }
}
