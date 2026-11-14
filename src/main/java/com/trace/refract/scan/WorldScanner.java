package com.trace.refract.scan;

import com.trace.refract.Refract;
import com.trace.refract.module.ModuleManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunk scanner. The client thread only snapshots a chunk (a cheap copy of its 16x16x16 sections) and hands it
 * to a background thread, which does all per-block work and returns a {@link ChunkReport}. The game thread
 * never scans blocks, so loading chunks no longer causes frame stutter.
 *
 * Surface filter (Overworld only): on a server where the surface is griefed and covered in builds, anything at ground level is
 * noise. With the Surface Filter module on (default), player blocks, deepslate, tunnels and cave vines only
 * count when they are at or below Max Y and at least Min Depth blocks under the highest solid block of their
 * own column. Kelp and amethyst are not filtered: they grow under water and in geodes, never on land.
 *
 * Results are kept per dimension for the whole session, so things you have already seen stay found even when
 * the chunk unloads or a server plugin later hides them again.
 */
public final class WorldScanner {
    private static final long SUBMIT_BUDGET_NS = 1_000_000L; // client-thread time spent snapshotting per tick
    private static final int MAX_IN_FLIGHT = 24;
    private static final long NEW_CHUNK_WINDOW = 8_000L;
    private static final int MAX_EVIDENCE = 64;
    private static final int MAX_GROWTH = 256;

    private static final Map<String, Map<Long, ChunkReport>> MEMORY = new HashMap<>();
    private static Map<Long, ChunkReport> reports = new HashMap<>();
    private static String dimension = "";
    private static ClientWorld lastWorld;
    private static int worldToken;

    private static final ArrayDeque<Long> QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static final Map<Long, Long> LOAD_TIME = new HashMap<>();
    private static final Map<Long, Long> DIRTY = new HashMap<>();
    private static final Set<Long> FLOW_SEEN = new HashSet<>();
    private static int revision;
    private static double farthest;
    private static int farTick;
    private static boolean warnedDistance;
    private static boolean wasActive;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Refract Scanner");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private record Result(int token, long key, ChunkReport report) {}

    /** Filter settings captured on the client thread when a job is created. */
    record Filter(boolean surface, int maxY, int depth, boolean antiXray) {}

    private WorldScanner() {}

    public static boolean active() {
        return ModuleManager.baseFinder.isEnabled() || ModuleManager.susChunks.isEnabled()
                || ModuleManager.storageEsp.isEnabled() || ModuleManager.growthEsp.isEnabled();
    }

    public static Collection<ChunkReport> reports() { return reports.values(); }

    public static ChunkReport report(ChunkPos pos) { return reports.get(pos.toLong()); }

    /** Increments whenever any report changes, so consumers can cache derived data. */
    public static int revision() { return revision; }

    /** Horizontal distance to the farthest loaded, scanned chunk: how far the scanner can currently see. */
    public static double farthest() { return farthest; }

    public static int queued() { return QUEUE.size() + IN_FLIGHT.get(); }

    public static void clearAll() {
        MEMORY.clear();
        reports = new HashMap<>();
        QUEUE.clear(); QUEUED.clear(); LOAD_TIME.clear(); DIRTY.clear(); FLOW_SEEN.clear();
        RESULTS.clear();
        lastWorld = null; dimension = "";
        worldToken++;
        revision++;
    }

    /** Forget every report and rescan what is loaded (used when the filter settings change). */
    public static void rescanAll() {
        reports.clear();
        RESULTS.clear();
        worldToken++;
        QUEUE.clear(); QUEUED.clear();
        revision++;
        enqueueLoaded(MinecraftClient.getInstance());
    }

    private static void ensureWorld(ClientWorld w) {
        if (w == lastWorld) return;
        lastWorld = w;
        dimension = w.getRegistryKey().getValue().toString();
        reports = MEMORY.computeIfAbsent(dimension, k -> new HashMap<>());
        for (ChunkReport r : reports.values()) r.loaded = false;
        QUEUE.clear(); QUEUED.clear(); LOAD_TIME.clear(); DIRTY.clear(); FLOW_SEEN.clear();
        RESULTS.clear();
        worldToken++;
        wasActive = false;
        revision++;
    }

    private static void enqueue(long key) {
        if (QUEUED.add(key)) QUEUE.add(key);
    }

    public static void onChunkLoad(ClientWorld w, WorldChunk c) {
        ensureWorld(w);
        long key = c.getPos().toLong();
        LOAD_TIME.put(key, System.currentTimeMillis());
        FLOW_SEEN.remove(key);
        enqueue(key);
    }

    public static void onChunkUnload(ClientWorld w, WorldChunk c) {
        if (w != lastWorld) return;
        ChunkReport r = reports.get(c.getPos().toLong());
        if (r != null) r.loaded = false;
        LOAD_TIME.remove(c.getPos().toLong());
    }

    /** Called (client thread) for every block change the server sends, before it is applied. */
    public static void onBlockChange(BlockPos pos, BlockState old, BlockState neu) {
        long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        var fluid = neu.getFluidState();
        if (!fluid.isEmpty() && !fluid.isStill()) {
            Long loaded = LOAD_TIME.get(key);
            if (loaded != null && System.currentTimeMillis() - loaded < NEW_CHUNK_WINDOW && FLOW_SEEN.add(key)) {
                ChunkReport r = reports.get(key);
                if (r != null) { r.age = ChunkReport.Age.NEW; revision++; }
            }
        }
        // Rescan when an indicator appears/disappears, deepslate is rotated or placed high, or stone is mined
        // into air underground (tunnels). Rapid changes coalesce into one rescan.
        int sectionY = Math.floorDiv(pos.getY(), 16) * 16;
        boolean dirty = Indicators.interesting(old, sectionY) || Indicators.interesting(neu, sectionY)
                || (pos.getY() < 64 && old.isAir() != neu.isAir());
        if (dirty) DIRTY.put(key, System.currentTimeMillis() + 750);
    }

    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        ensureWorld(mc.world);
        long now = System.currentTimeMillis();

        if (++farTick % 40 == 0) {
            double f = 0, px = mc.player.getX(), pz = mc.player.getZ();
            for (ChunkReport r : reports.values()) {
                if (!r.loaded) continue;
                f = Math.max(f, Math.hypot(r.pos.getStartX() + 8 - px, r.pos.getStartZ() + 8 - pz));
            }
            farthest = f;
            // The client drops every chunk past its own render distance, so that, not the scanner, caps how far
            // anything can be found. Say so once per session when it is low.
            int rd = mc.options.getClampedViewDistance();
            if (!warnedDistance && active() && rd < 12) {
                warnedDistance = true;
                com.trace.refract.hud.Notifications.push("Scanner", "Render distance is " + rd + " chunks: finds stop at ~" + (rd * 16) + "m. Raise it in Video Settings to scan farther.", 0xFFFF9A3D, 9000);
            }
        }

        // 1. Collect finished scans.
        Result res;
        while ((res = RESULTS.poll()) != null) {
            if (res.token != worldToken) continue;
            ChunkReport prev = reports.get(res.key);
            ChunkReport r = res.report;
            if (prev != null && r.age == ChunkReport.Age.UNKNOWN) r.age = prev.age;
            if (FLOW_SEEN.contains(res.key)) r.age = ChunkReport.Age.NEW;
            int cx = ChunkPos.getPackedX(res.key), cz = ChunkPos.getPackedZ(res.key);
            r.loaded = mc.world.getChunkManager().isChunkLoaded(cx, cz);
            reports.put(res.key, r);
            regroupGeodes(res.key);
            revision++;
        }

        // 2. Chunk age (only used for the optional new/old overlay; it never affects scores).
        Iterator<Map.Entry<Long, Long>> lt = LOAD_TIME.entrySet().iterator();
        while (lt.hasNext()) {
            var e = lt.next();
            if (now - e.getValue() < NEW_CHUNK_WINDOW) continue;
            ChunkReport r = reports.get(e.getKey());
            if (r == null) continue;
            if (r.age == ChunkReport.Age.UNKNOWN && r.hasFlowing && !FLOW_SEEN.contains(e.getKey())) {
                r.age = ChunkReport.Age.OLD;
                revision++;
            }
        }

        if (!DIRTY.isEmpty()) {
            Iterator<Map.Entry<Long, Long>> it = DIRTY.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue() <= now) { enqueue(e.getKey()); it.remove(); }
            }
        }

        boolean act = active();
        if (act && !wasActive) enqueueLoaded(mc);
        wasActive = act;
        if (!act) return;

        // 3. Snapshot queued chunks and hand them to the scanner thread.
        Filter filter = currentFilter(mc.world);
        long start = System.nanoTime();
        while (!QUEUE.isEmpty() && IN_FLIGHT.get() < MAX_IN_FLIGHT && System.nanoTime() - start < SUBMIT_BUDGET_NS) {
            long key = QUEUE.poll();
            QUEUED.remove(key);
            int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
            if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) continue;
            WorldChunk chunk = mc.world.getChunk(cx, cz);
            if (chunk == null) continue;
            ChunkView view = ChunkView.snapshot(mc.world, chunk);
            int token = worldToken;
            IN_FLIGHT.incrementAndGet();
            WORKER.execute(() -> {
                try {
                    RESULTS.add(new Result(token, key, scan(view, filter)));
                } catch (Throwable t) {
                    Refract.LOG.debug("Scan of chunk {} {} failed", cx, cz, t);
                } finally {
                    IN_FLIGHT.decrementAndGet();
                }
            });
        }
    }

    /** The surface filter only applies in the Overworld; the Nether and End have no griefed grass surface. */
    static Filter currentFilter(ClientWorld w) {
        var f = ModuleManager.surfaceFilter;
        boolean overworld = w.getRegistryKey() == net.minecraft.world.World.OVERWORLD;
        return new Filter(f.isEnabled() && overworld, f.maxY.asInt(), f.depth.asInt(), f.antiXray.on());
    }

    /** Queue every currently loaded chunk around the player (used when a scanning module is switched on). */
    public static void enqueueLoaded(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        int view = mc.options.getClampedViewDistance() + 2;
        ChunkPos center = mc.player.getChunkPos();
        List<Long> keys = new ArrayList<>();
        for (int dx = -view; dx <= view; dx++) for (int dz = -view; dz <= view; dz++) {
            int cx = center.x + dx, cz = center.z + dz;
            if (mc.world.getChunkManager().isChunkLoaded(cx, cz)) keys.add(ChunkPos.toLong(cx, cz));
        }
        keys.sort(Comparator.comparingInt(k -> Math.abs(ChunkPos.getPackedX(k) - center.x) + Math.abs(ChunkPos.getPackedZ(k) - center.z)));
        for (long k : keys) enqueue(k);
    }

    // =====================================================================================================
    // Everything below runs on the scanner thread and only touches the ChunkView snapshot.
    // =====================================================================================================

    /** A face is open when the neighbour is loaded and not an opaque full cube. Unknown neighbours count as closed. */
    private static boolean exposed(ChunkView v, int x, int y, int z) {
        for (Direction d : Direction.values()) {
            BlockState n = v.get(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ());
            if (n != ChunkView.UNKNOWN && !n.isOpaqueFullCube()) return true;
        }
        return false;
    }

    static ChunkReport scan(ChunkView v, Filter f) {
        ChunkReport r = new ChunkReport(v.pos);
        r.scannedAt = r.lastSeen = System.currentTimeMillis();
        Map<Indicators.Info, Integer> counted = new IdentityHashMap<>();
        Map<String, Float> structureHits = new HashMap<>();
        int baseX = v.baseX, baseZ = v.baseZ;
        int[] surface = v.surface();
        regional = v.regionalTop;
        Map<String, Integer> builtSeen = new HashMap<>();

        for (int si = 0; si < v.sections.length; si++) {
            ChunkSection sec = v.sections[si];
            if (sec == null || sec.isEmpty()) continue;
            if (!r.hasFlowing && sec.hasAny(s -> !s.getFluidState().isEmpty() && !s.getFluidState().isStill())) r.hasFlowing = true;
            int sy = v.bottomY + si * 16;
            if (sy < 48 && (!f.surface || sy <= f.maxY)) scanTunnels(v, sec, sy, surface, f, r);
            if (!f.surface || sy <= f.maxY) scanRooms(v, sec, sy, surface, f, r);
            if (sy < 48) scanExcavation(v, sec, sy, surface, f, r);
            final int fsy = sy;
            if (!sec.hasAny(s -> Indicators.interesting(s, fsy))) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState st = sec.getBlockState(x, y, z);
                int wx = baseX + x, wy = sy + y, wz = baseZ + z;
                Indicators.Info info = Indicators.get(st.getBlock());
                if (info == null) {
                    boolean rot = Indicators.rotatedDeepslate(st), high = !rot && Indicators.highDeepslate(st, sy);
                    if (!rot && !high) continue;
                    if (!underground(f, surface, x, z, wy)) { r.surfaceSkipped++; continue; }
                    if (rot) {
                        r.rotated++;
                        if (r.rotatedEvidence.size() < MAX_EVIDENCE) r.rotatedEvidence.add(new BlockPos(wx, wy, wz));
                    } else r.highDeepslate++;
                    continue;
                }
                switch (info.kind()) {
                    case ORE -> { if (!exposed(v, wx, wy, wz)) r.hiddenOres++; }
                    case STRUCTURE -> structureHits.merge(info.label(), 1f, Float::sum);
                    case KELP -> scanKelp(v, st, wx, wy, wz, r);
                    case KELP_PLANT -> { }
                    case BUD -> {
                        r.buds++;
                        if (st.getBlock() == Blocks.SMALL_AMETHYST_BUD) r.smallBuds++;
                        else if (st.getBlock() == Blocks.MEDIUM_AMETHYST_BUD) r.mediumBuds++;
                        else r.largeBuds++;
                    }
                    case CLUSTER -> {
                        r.clusters++;
                        if (r.clusterEvidence.size() < MAX_GROWTH) r.clusterEvidence.add(new BlockPos(wx, wy, wz));
                    }
                    case BUILT -> {
                        if (!underground(f, surface, x, z, wy)) { r.surfaceSkipped++; continue; }
                        r.placements++;
                        int n = builtSeen.merge(info.label(), 1, Integer::sum);
                        boolean counts = n <= info.cap();
                        r.add(info.label(), 1, counts ? info.weight() : 0f);
                        if (!counts) continue;
                        r.built += info.weight();
                        if (n == 1 && info.weight() >= 0.3f) r.builtTypes++;
                        r.extendY(wy);
                        if (r.evidence.size() < MAX_EVIDENCE && info.weight() >= 0.4f) r.evidence.add(new BlockPos(wx, wy, wz));
                    }
                    case CAVE_VINE_HEAD -> {
                        if (!underground(f, surface, x, z, wy)) continue;
                        if (st.get(Properties.AGE_25) >= 25 && column(v, wx, wy, wz, Blocks.CAVE_VINES_PLANT) >= 16) r.caveVinesGrown++;
                    }
                    case STORAGE, PLAYER -> {
                        if (info.hideable() && f.antiXray && !exposed(v, wx, wy, wz)) continue;
                        BlockPos bp = new BlockPos(wx, wy, wz);
                        // Storage ESP shows every container it can see; only base scoring is surface-filtered.
                        if (info.storage() != null) r.storage.add(new ChunkReport.StorageBlock(bp, info.storage()));
                        if (!underground(f, surface, x, z, wy)) { r.surfaceSkipped++; continue; }
                        if (info.storage() == Indicators.Storage.SPAWNER) {
                            // Scored after the loop: one spawner is usually a dungeon, a group never is.
                            r.spawners++;
                            r.extendY(wy);
                            if (r.evidence.size() < MAX_EVIDENCE) r.evidence.add(bp);
                            continue;
                        }
                        if (info.storage() == Indicators.Storage.CHEST) r.chests++;
                        if (info.weight() <= 0) continue;
                        int n = counted.merge(info, 1, Integer::sum);
                        boolean counts = n <= info.cap();
                        r.add(info.label(), 1, counts ? info.weight() : 0f);
                        if (!counts) continue;
                        if (info.natural()) r.natural += info.weight();
                        else r.strong += info.weight();
                        r.extendY(wy);
                        if (r.evidence.size() < MAX_EVIDENCE && (!info.natural() || info.weight() >= 1.5f)) r.evidence.add(bp);
                    }
                }
            }
        }

        String structure = null;
        float best = 0;
        for (var e : structureHits.entrySet()) if (e.getValue() > best) { best = e.getValue(); structure = e.getKey(); }
        boolean strongStructure = structure != null && best >= 2;
        r.structure = strongStructure ? structure : null;
        r.structureCount = (int) Math.round(best);

        // Spawners: dungeons and mineshafts hold one (occasionally two). Three or more, or two away from any
        // structure, were carried in (DonutSMP spawner farms).
        if (r.spawners > 0) {
            boolean placed = r.spawners >= 3 || (r.spawners >= 2 && !strongStructure);
            float w = Math.min(r.spawners, 8) * (placed ? 5f : 3f);
            r.add("Spawner", r.spawners, w);
            if (placed) r.strong += w; else r.natural += w;
        }
        // Many chests in one chunk away from a structure is a storage room, not loot.
        if (r.chests >= 6 && !strongStructure) r.strong += 4f;

        // Rooms and placed building blocks: what survives a griefing. Structures (mineshafts, strongholds, ancient
        // cities, trial chambers, villages, woodland mansions, trial ruins) are built too, so they are damped in
        // proportion to how many of their own markers are in the chunk. A single marker already means "could be
        // natural", which is what used to make structures read as player bases.
        r.built += r.rooms.size() * 5f;
        float structDamp = best >= 3 ? 0.10f : best >= 2 ? 0.20f : best >= 1 ? 0.40f : 1f;
        r.built *= structDamp;
        if (!r.rooms.isEmpty()) r.add("Dug-out Room", r.rooms.size(), r.rooms.size() * 5f);

        // Natural-looking blocks only count when there is also something the world never generates.
        float damp = strongStructure ? 0.5f : 1f;
        r.activity = r.strong > 0 ? r.strong * damp + r.natural * (strongStructure ? 0.25f : 1f) : 0f;

        // Anti-xray (Paper engine-mode 2/3) floods solid stone with fake ores. A natural chunk has a few dozen
        // hidden valuable ores at most; far more means obfuscation, so hideable blocks were filtered by exposure.
        r.antiXray = r.hiddenOres > 300;
        return r;
    }

    /** True when (x, y, z) is far enough below its column's surface (or the filter is off). */
    private static int regional;

    /**
     * True when (x, y, z) is deep enough to count (or the filter is off). Depth is measured from the higher of the
     * column's own top block and the regional terrain height, so a base whose roof a griefer ripped off (now
     * open to the sky at the bottom of a crater) still counts as underground.
     */
    private static boolean underground(Filter f, int[] surface, int lx, int lz, int y) {
        if (!f.surface) return true;
        return y <= f.maxY && y <= Math.max(surface[lz * 16 + lx], regional) - f.depth;
    }

    /** Length of a downward column of {@code below} blocks under (x, y, z), counting the block itself. */
    private static int column(ChunkView v, int x, int y, int z, net.minecraft.block.Block below) {
        int len = 1;
        int yy = y - 1;
        while (len < 96 && v.get(x, yy, z).getBlock() == below) { len++; yy--; }
        return len;
    }

    private static boolean air(ChunkView v, ChunkSection sec, int sy, int x, int y, int z) {
        BlockState s = (x | y | z) >= 0 && x < 16 && y < 16 && z < 16 ? sec.getBlockState(x, y, z) : v.get(v.baseX + x, sy + y, v.baseZ + z);
        return s.getBlock() == Blocks.AIR;
    }

    private static boolean solid(ChunkView v, ChunkSection sec, int sy, int x, int y, int z) {
        BlockState s = (x | y | z) >= 0 && x < 16 && y < 16 && z < 16 ? sec.getBlockState(x, y, z) : v.get(v.baseX + x, sy + y, v.baseZ + z);
        return s != ChunkView.UNKNOWN && s.isOpaqueFullCube();
    }

    /**
     * Mining tunnels: straight, axis-aligned runs of air that are exactly 1 wide and 1-2 tall with solid blocks
     * on every side, at least 8 blocks long. Natural caves are irregular and never keep that profile.
     */
    private static void scanTunnels(ChunkView v, ChunkSection sec, int sy, int[] surface, Filter f, ChunkReport r) {
        if (!sec.hasAny(s -> s.getBlock() == Blocks.AIR)) return;
        if (!sec.hasAny(s -> s.getBlock() == Blocks.STONE || s.getBlock() == Blocks.DEEPSLATE)) return;
        for (int axis = 0; axis < 2; axis++) {
            for (int y = 0; y < 16; y++) for (int a = 0; a < 16; a++) {
                int run = 0, runH = 0, start = 0;
                for (int b = 0; b <= 16; b++) {
                    int h = b < 16 ? cell(v, sec, sy, axis == 0 ? b : a, y, axis == 0 ? a : b, axis) : 0;
                    if (h > 0 && (run == 0 || h == runH)) {
                        if (run == 0) { start = b; runH = h; }
                        run++;
                        continue;
                    }
                    if (run >= 8) {
                        int x0 = axis == 0 ? start : a, z0 = axis == 0 ? a : start;
                        int mx = axis == 0 ? start + run / 2 : a, mz = axis == 0 ? a : start + run / 2;
                        if (underground(f, surface, mx, mz, sy + y)) {
                            r.tunnelLength += run;
                            if (r.tunnels.size() < 24) {
                                int x1 = axis == 0 ? start + run : a + 1, z1 = axis == 0 ? a + 1 : start + run;
                                r.tunnels.add(new Box(v.baseX + x0, sy + y, v.baseZ + z0, v.baseX + x1, sy + y + runH, v.baseZ + z1));
                            }
                        } else r.surfaceSkipped++;
                    }
                    run = 0;
                    if (h > 0) { start = b; runH = h; run = 1; }
                }
            }
        }
    }

    /**
     * Dug-out rooms: on one Y level, at least 24 floor cells (plain air with a solid block below and headroom
     * above) whose bounding box is at least 4x4 and at least 75% filled, with the same ceiling height over most
     * of its roofed part (at least 30% must still be roofed, so griefed rooms with a hole in the top still count). Noise caves have sloped, ragged floors; players dig flat rectangles.
     */
    private static void scanRooms(ChunkView v, ChunkSection sec, int sy, int[] surface, Filter f, ChunkReport r) {
        if (r.rooms.size() >= 8 || !sec.hasAny(s -> s.getBlock() == Blocks.AIR)) return;
        for (int y = 0; y < 16; y++) {
            int count = 0, roofed = 0, deep = 0, minX = 16, minZ = 16, maxX = -1, maxZ = -1;
            int[] ceil = new int[8];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!air(v, sec, sy, x, y, z) || !solid(v, sec, sy, x, y - 1, z) || !air(v, sec, sy, x, y + 1, z)) continue;
                if (underground(f, surface, x, z, sy + y)) deep++;
                int h = 2;
                while (h < 7 && air(v, sec, sy, x, y + h, z)) h++;
                // Cells open above (a griefer's hole, a shaft) still belong to the floor; only roofed cells vote on
                // the ceiling height.
                if (h < 7 && solid(v, sec, sy, x, y + h, z)) { ceil[h]++; roofed++; }
                count++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            if (count < 20) continue; // rooms split by a chunk border still leave 20+ cells on each side
            int bw = maxX - minX + 1, bd = maxZ - minZ + 1;
            if (bw < 3 || bd < 3 || count < 0.70 * bw * bd) continue;
            int mode = 0, modeH = 2;
            for (int h = 2; h < 7; h++) if (ceil[h] > mode) { mode = ceil[h]; modeH = h; }
            if (roofed < 0.25 * count || mode < 0.55 * roofed) continue;
            if (deep < 0.25 * count) continue; // enough of it under 12+ blocks of rock (a griefed room can be mostly open)
            r.rooms.add(new Box(v.baseX + minX, sy + y, v.baseZ + minZ, v.baseX + maxX + 1, sy + y + modeH, v.baseZ + maxZ + 1));
            r.extendY(sy + y);
            if (r.rooms.size() >= 8) return;
            y += modeH; // the same room's air does not count again higher up
        }
    }

    /**
     * Roofed, flat-floored air underground: the footprint of a room someone dug. Natural cave noise has sloped,
     * ragged floors and rarely keeps a 3-6 block ceiling over a whole section, so a large count here together
     * with even a couple of placed blocks is what is left of a base whose storage was taken. Griefed bases are
     * usually irregular boxes that fail the strict {@link #scanRooms} test, which is why this exists.
     */
    private static void scanExcavation(ChunkView v, ChunkSection sec, int sy, int[] surface, Filter f, ChunkReport r) {
        if (r.excavated >= 512 || !sec.hasAny(s -> s.getBlock() == Blocks.AIR)) return;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (!air(v, sec, sy, x, y, z)) continue;
            if (!underground(f, surface, x, z, sy + y)) continue;
            if (!solid(v, sec, sy, x, y - 1, z)) continue; // flat floor
            int h = 1;
            while (h <= 6 && air(v, sec, sy, x, y + h, z)) h++;
            if (h >= 3 && h <= 6 && solid(v, sec, sy, x, y + h, z)) r.excavated++; // even ceiling
        }
    }

    /** Height (1 or 2) of a tunnel cell at (x,y,z) running along X (axis 0) or Z (axis 1), else 0. */
    private static int cell(ChunkView v, ChunkSection sec, int sy, int x, int y, int z, int axis) {
        if (!air(v, sec, sy, x, y, z) || !solid(v, sec, sy, x, y - 1, z)) return 0;
        int sx = axis == 0 ? 0 : 1, sz = axis == 0 ? 1 : 0;
        if (!solid(v, sec, sy, x - sx, y, z - sz) || !solid(v, sec, sy, x + sx, y, z + sz)) return 0;
        if (solid(v, sec, sy, x, y + 1, z)) return 1;
        if (!air(v, sec, sy, x, y + 1, z) || !solid(v, sec, sy, x, y + 2, z)) return 0;
        if (!solid(v, sec, sy, x - sx, y + 1, z - sz) || !solid(v, sec, sy, x + sx, y + 1, z + sz)) return 0;
        return 2;
    }

    /**
     * Kelp: world generation tops every column with age 20-23 and builds at most 11 blocks. A top at age 25
     * (the cap) or a column taller than 11 can only come from growth while the chunk was loaded (or bone
     * meal), so those are the only columns reported. Age 24 is ignored: a hand-placed kelp can roll 0-24.
     */
    private static void scanKelp(ChunkView v, BlockState top, int x, int y, int z, ChunkReport r) {
        r.kelpColumns++;
        int age = top.get(Properties.AGE_25);
        int len = 1, yy = y - 1;
        while (len < 64 && v.get(x, yy, z).getBlock() == Blocks.KELP_PLANT) { len++; yy--; }
        boolean blocked = v.get(x, y + 1, z).getFluidState().isEmpty(); // reached the surface (or a ceiling)
        boolean grew = age >= 25 || len > 11;
        if (grew) {
            r.kelpGrown++;
            if (r.grownKelp.size() < MAX_GROWTH) r.grownKelp.add(new ChunkReport.KelpColumn(new BlockPos(x, y, z), yy + 1, age));
        }
        if (age >= 25) { r.kelpCapped++; r.kelpEligible++; }
        else if (!blocked) r.kelpEligible++;
    }

    // =====================================================================================================

    /** Sum geode counts over the 3x3 chunks around {@code key} and rescore those chunks (client thread). */
    private static void regroupGeodes(long key) {
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            ChunkReport c = reports.get(ChunkPos.toLong(cx + dx, cz + dz));
            if (c == null) continue;
            int cl = 0, tot = 0;
            if (c.buds + c.clusters > 0) {
                for (int ex = -1; ex <= 1; ex++) for (int ez = -1; ez <= 1; ez++) {
                    ChunkReport n = reports.get(ChunkPos.toLong(c.pos.x + ex, c.pos.z + ez));
                    if (n == null) continue;
                    cl += n.clusters;
                    tot += n.clusters + n.buds;
                }
            }
            c.geodeClusters = cl;
            c.geodeTotal = tot;
            Confidence.score(c);
        }
    }
}
