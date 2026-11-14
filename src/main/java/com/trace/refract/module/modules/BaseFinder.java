package com.trace.refract.module.modules;

import com.trace.refract.Refract;
import com.trace.refract.hud.Markers;
import com.trace.refract.hud.Notifications;
import com.trace.refract.hud.WorldLabels;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.scan.ChunkReport;
import com.trace.refract.scan.Confidence;
import com.trace.refract.scan.Tunnel;
import com.trace.refract.scan.WorldScanner;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Time;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Finds underground bases, intact or griefed, and shows them the way a base finder should: a heat square on
 * every chunk the evidence sits in, a tight box around the evidence itself, the exact coordinates of a real
 * block, and a name for what the place is.
 *
 * <p>Scoring only ever counts things the world does not generate on its own. Placed building blocks are the
 * exception, because a griefed base has nothing else left, so they are counted only when they are varied, or
 * paired with a dug-out room, or when there is a large excavated cavity with a few placed blocks in it.
 * Natural structures (villages, mineshafts, strongholds, trial chambers, ancient cities, woodland mansions,
 * ocean monuments, outposts) carry their own structure markers, and every marker present damps the building
 * score, so a structure never reads as a player base.
 */
public class BaseFinder extends Module {
    public final ModeSetting minLevel = add(new ModeSetting("Min Confidence", "Low", "Low", "Medium", "High", "Very High"));
    public final ModeSetting esp = add(new ModeSetting("Base ESP", "Box", "Off", "Squares", "Box", "Both", "Heat"));
    public final BoolSetting evidence = add(new BoolSetting("Show Evidence", false));
    public final BoolSetting hologram = add(new BoolSetting("Hologram", true));
    public final BoolSetting remember = add(new BoolSetting("Remember Unloaded", false));
    public final BoolSetting labels = add(new BoolSetting("Labels", true));
    public final BoolSetting farMarkers = add(new BoolSetting("Far Markers", true));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", false));
    public final BoolSetting throughWalls = add(new BoolSetting("Through Walls", true));
    public final NumberSetting heatThreshold = add(new NumberSetting("Heat Threshold", 0.12, 0.02, 1.0, 0.01, ""));
    /** Old mined corridors are the best pointer to a base that a client can actually read. */
    public final BoolSetting tunnelLeads = add(new BoolSetting("Tunnel Leads", true));
    public final BoolSetting freshTunnels = add(new BoolSetting("Include New Tunnels", false));
    /** Screens are only readable when they stay short, so the weakest finds are hidden by default. */
    public final NumberSetting minConfidence = add(new NumberSetting("Min Confidence", 30, 15, 80, 5, "%"));
    public final NumberSetting maxFindings = add(new NumberSetting("Max Finds", 8, 2, 30, 1, ""));
    public final BoolSetting livePrices = add(new BoolSetting("Live Prices", true));
    public final NumberSetting minTunnel = add(new NumberSetting("Min Tunnel", 24, 12, 96, 4, "m"));
    public final BoolSetting routes = add(new BoolSetting("Routes", true));
    public final BoolSetting alerts = add(new BoolSetting("Toasts", true));
    public final BoolSetting chat = add(new BoolSetting("Chat Alerts", false));

    public static final class Candidate {
        public final List<ChunkReport> chunks = new ArrayList<>();
        public final Map<String, Integer> found = new LinkedHashMap<>();
        public final Map<String, Float> weight = new HashMap<>();
        public final List<BlockPos> evidence = new ArrayList<>();
        /** Heat (0..1) of each chunk this base spans, so the squares match the suspicion map. */
        public final Map<Long, Float> chunkHeat = new HashMap<>();
        public float total, confidence, heat;
        public Confidence.Level level;
        /** Exact block the evidence centres on (shown as the base's coordinates). */
        public BlockPos anchor;
        public Vec3d center;
        /** Tight bounds around every piece of evidence. */
        public Box bounds;
        public long key;
        public String title, summary;
        /** What the base is for (Stash, Spawner Farm, Trading Hall...) and whether it looks intact. */
        public String type = "Base", status = "Unknown", statusReason = "";
        public int statusColor = 0xFF8A93A6;
        /** The old corridor that leads here, when one was found: the direction to travel. */
        public Lead lead;
        public String tunnelText = "";
        /** "NE 214m · 37m below" from the player to this base. */
        public String route = "";
        public double distance;

        public String summary(int max) { return ChunkReport.summarize(found, weight, max); }
    }

    /** A merged run of old corridors: where to enter, which way it runs and what was dug through. */
    public static final class Lead {
        public Box bounds;
        public BlockPos from, to;
        public int length, floorY, axis;
        public Tunnel.Kind kind = Tunnel.Kind.PICKAXE;
        public boolean fresh;
        public double distance;
        public String route = "", describe = "";

        public String kindLabel() {
            return switch (kind) {
                case AMETHYST_DRILL -> "amethyst drill";
                case RAILED -> "railed shaft";
                default -> "pickaxe tunnel";
            };
        }
    }

    public final List<Candidate> candidates = new ArrayList<>();
    /** Old corridors worth walking down, with the direction to them. These are leads, never bases on their own. */
    public final List<Lead> leads = new ArrayList<>();
    /** Per-chunk suspicion, 0..1, for the Heat view and for square colours. */
    private final Map<Long, Float> heat = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();
    /** Most storage ever seen per base, to notice when it is being emptied or broken. */
    private final Map<Long, Integer> peakStorage = new HashMap<>();
    /** Chunks whose 3x3 neighbourhood holds enough varied built evidence (bases straddle chunk borders). */
    private static final Set<Long> BUILT_OK = new HashSet<>();
    /** Chunks sitting inside a natural structure, where built blocks are not player evidence. */
    private static final Set<Long> STRUCT_NEAR = new HashSet<>();
    private int seenRevision = -1;
    private boolean lastRemember, lastSurfaceFilter = true;
    private int ticks;

    public BaseFinder() {
        super("Base Finder", "Underground bases and griefed bases: heat squares over the chunks, exact coordinates, type and status.", Category.HUNTING);
    }

    @Override public void onEnable() { WorldScanner.enqueueLoaded(mc); seenRevision = -1; }

    @Override public void onDisable() { candidates.clear(); heat.clear(); }

    @Override public String suffix() { return candidates.isEmpty() ? null : String.valueOf(candidates.size()); }

    /** Score of a chunk as part of a base. */
    static float baseScore(ChunkReport r) {
        float b = builtScore(r);
        float s = 0f;
        if (r.activity > 0 || b > 0) {
            s = r.activity + b
                    + (r.rotated >= 2 ? Math.min(8, r.rotated) * 1.5f : 0f)
                    + (r.tunnelLength >= 12 ? Math.min(r.tunnelLength, 64) * 0.1f : 0f);
        } else if (r.rotated >= 6) {
            // Lots of sideways deepslate with nothing else: a hidden room walled in with mined deepslate.
            s = Math.min(r.rotated, 16) * 0.75f;
        }
        // A dug cavity with a few placed blocks in it, but no room clean enough to pass scanRooms and no
        // never-generated block left: a base that was emptied and abandoned, or a half-griefed one. This is what
        // catches the base you are standing in when nothing valuable is left behind.
        if (r.placements >= 2 && r.excavated >= 70) {
            s = Math.max(s, Math.min(9f, r.excavated / 22f) + Math.min(6f, r.placements * 0.5f));
        }
        return s;
    }

    /** Built evidence only counts when it is varied, when there is a dug-out room, or when there is a lot of it. */
    static float builtScore(ChunkReport r) { return BUILT_OK.contains(r.pos.toLong()) ? r.built : 0f; }

    /** Recompute the neighbourhood sets both scorers depend on. */
    private static void prep() {
        BUILT_OK.clear();
        STRUCT_NEAR.clear();
        for (ChunkReport r : WorldScanner.reports()) {
            if (r.built > 0) {
                float b = 0;
                int types = 0, rooms = 0;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    ChunkReport n = WorldScanner.report(new ChunkPos(r.pos.x + dx, r.pos.z + dz));
                    if (n == null) continue;
                    b += n.built; types += n.builtTypes; rooms += n.rooms.size();
                }
                if (b >= 3 && (types >= 2 || rooms > 0 || b >= 8)) BUILT_OK.add(r.pos.toLong());
            }
            if (r.structureCount >= 1) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    STRUCT_NEAR.add(ChunkPos.toLong(r.pos.x + dx, r.pos.z + dz));
                }
            }
        }
    }

    /**
     * True when a chunk only has weak, structure-shaped evidence: no player-only block, no dug room, no real
     * tunnel and no rotated deepslate, but a natural structure next door. Those chunks are what used to be
     * reported as bases out in the open.
     */
    private static boolean structureNoise(ChunkReport r) {
        return STRUCT_NEAR.contains(r.pos.toLong())
                && r.strong <= 0 && r.rooms.isEmpty() && r.rotated < 2 && r.tunnelLength < 16 && r.excavated < 70;
    }

    private int minOrdinal() { return minLevel.index() + 1; } // Level.LOW has ordinal 1

    @Override public void onTick() {
        if (remember.on() != lastRemember) { lastRemember = remember.on(); seenRevision = -1; }
        if (++ticks % 10 != 0 || WorldScanner.revision() == seenRevision) return;
        seenRevision = WorldScanner.revision();
        recluster();
    }

    private void recluster() {
        boolean sf = com.trace.refract.module.ModuleManager.surfaceFilter.isEnabled();
        if (sf != lastSurfaceFilter) { lastSurfaceFilter = sf; peakStorage.clear(); }
        prep();
        computeHeat();
        if (tunnelLeads.on()) buildLeads(); else leads.clear();
        Map<Long, ChunkReport> hot = new HashMap<>();
        for (ChunkReport r : WorldScanner.reports()) {
            if (structureNoise(r) || (!r.loaded && !remember.on())) continue;
            // A chunk joins a base on its own score, or on any built evidence when its neighbourhood qualifies.
            if (baseScore(r) >= 3f || (builtScore(r) > 0 && r.built >= 1f)) hot.put(r.pos.toLong(), r);
        }
        Set<Long> visited = new HashSet<>();
        List<Candidate> out = new ArrayList<>();
        for (long start : hot.keySet()) {
            if (!visited.add(start)) continue;
            Candidate c = new Candidate();
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(start);
            while (!q.isEmpty()) {
                long k = q.poll();
                c.chunks.add(hot.get(k));
                c.chunkHeat.put(k, heat.getOrDefault(k, 0f));
                int x = ChunkPos.getPackedX(k), z = ChunkPos.getPackedZ(k);
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    long n = ChunkPos.toLong(x + dx, z + dz);
                    if (hot.containsKey(n) && visited.add(n)) q.add(n);
                }
            }
            ChunkReport best = c.chunks.get(0);
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            List<BlockPos> all = new ArrayList<>();
            for (ChunkReport r : c.chunks) {
                float bs = baseScore(r);
                c.total += bs;
                if (bs > baseScore(best)) best = r;
                r.found.forEach((k, v) -> c.found.merge(k, v, Integer::sum));
                r.weight.forEach((k, v) -> c.weight.merge(k, v, Float::sum));
                if (r.rotated >= 2) { c.found.merge("Rotated Deepslate", r.rotated, Integer::sum); c.weight.merge("Rotated Deepslate", Math.min(8, r.rotated) * 1.5f, Float::sum); }
                if (r.tunnelLength >= 12 && r.activity > 0) { c.found.merge("m of tunnel", r.tunnelLength, Integer::sum); c.weight.merge("m of tunnel", Math.min(r.tunnelLength, 64) * 0.1f, Float::sum); }
                all.addAll(r.evidence);
                for (Box room : r.rooms) all.add(BlockPos.ofFloored(room.getCenter().x, room.minY, room.getCenter().z));
                if (r.rotated >= 2) all.addAll(r.rotatedEvidence);
            }
            if (all.isEmpty()) continue;
            double sx = 0, sy = 0, sz = 0;
            for (BlockPos p : all) {
                sx += p.getX(); sy += p.getY(); sz += p.getZ();
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX() + 1); maxY = Math.max(maxY, p.getY() + 1); maxZ = Math.max(maxZ, p.getZ() + 1);
            }
            int n = all.size();
            Vec3d mean = new Vec3d(sx / n, sy / n, sz / n);
            // Anchor on the real evidence block nearest the centre, so the coordinates point at something real.
            BlockPos anchor = all.get(0);
            double bd = Double.MAX_VALUE;
            for (BlockPos p : all) {
                double d = p.getSquaredDistance(mean);
                if (d < bd) { bd = d; anchor = p; }
            }
            c.evidence.addAll(all);
            c.anchor = anchor;
            c.center = anchor.toCenterPos();
            c.bounds = new Box(minX, minY, minZ, maxX, maxY, maxZ).expand(0.5);
            // A single chest, furnace or shulker is scenery, not a base. Require real container evidence before
            // a cluster is allowed to become a candidate at all.
            int containers = n(c, "Chest") + n(c, "Trapped Chest") + n(c, "Barrel") + n(c, "Shulker Box")
                    + n(c, "Spawner") + n(c, "Hopper") + n(c, "Furnace")
                    + n(c, "Blast Furnace") + n(c, "Smoker") + n(c, "Dropper/Dispenser");
            // Real bases are built of containers; anything with fewer must carry high-value evidence of its own
            // (a carried-in spawner, a beacon, an anchor, netherite, dug-out deepslate) to count. One chest in a
            // stone hut is scenery and used to slip through as a "Stash".
            int highValue = n(c, "Spawner") + n(c, "Beacon") + n(c, "Respawn Anchor")
                    + n(c, "Netherite Block") + n(c, "Rotated Deepslate");
            // A corridor that ends at this cluster is the best "how do I get there" evidence there is.
            Lead lead = leadFor(c);
            if (lead != null) {
                c.lead = lead;
                c.tunnelText = lead.describe;
                c.found.merge("Old Tunnel", 1, Integer::sum);
                c.weight.merge("Old Tunnel", 6f, Float::sum);
                c.total += 2.5f;
            }

            // A griefed base has no containers left by definition: it needs a dug-out room with varied placed blocks,
            // or a swept cavity with real building work in it. Two stray blocks in a cave used to pass here and
            // became "Abandoned Builds": that is what filled the map with ruined random blocks.
            float builtSum = 0;
            int placed = 0, dug = 0, builtTypes = 0;
            for (ChunkReport r : c.chunks) { builtSum += builtScore(r); placed += r.placements; dug += r.excavated; builtTypes += r.builtTypes; }
            boolean griefedEvidence = (n(c, "Dug-out Room") > 0 && builtSum >= 3)
                    || (dug >= 80 && placed >= 8 && builtTypes >= 2 && builtSum > 0);
            boolean playerOnly = n(c, "Beacon") > 0 || n(c, "Netherite Block") > 0 || n(c, "Shulker Box") > 0
                    || n(c, "Respawn Anchor") > 0 || n(c, "Spawner") >= 3;
            if (containers < 3 && highValue == 0 && !griefedEvidence) continue;

            // Two independent kinds of evidence, or one unmistakable player block. A single ruined wall, one chest in
            // a hole, a cave with a couple of placed blocks: scenery, not a base.
            int families = 0;
            if (containers >= 3) families++;
            if (n(c, "Dug-out Room") > 0) families++;
            if (placed >= 6 && builtTypes >= 2) families++;
            if (lead != null) families++;
            if (playerOnly) families++;
            if (families < 2) continue;

            // Containers that never generate naturally. Dungeons and mineshafts only hold plain chests, so a cluster
            // with no shulker/hopper/barrel and no tunnel pointing at it is scenery, however much cobblestone it has.
            int playerStorage = n(c, "Shulker Box") + n(c, "Hopper") + n(c, "Trapped Chest")
                    + n(c, "Beacon") + n(c, "Netherite Block") + n(c, "Respawn Anchor") + n(c, "Conduit");
            int plainChests = n(c, "Chest") + n(c, "Barrel") + n(c, "Copper Chest");
            boolean structHere = STRUCT_NEAR.contains(best.pos.toLong());

            // Inside or next to a natural structure only unmistakable player evidence wins. Villages, mineshafts,
            // dungeons, trial chambers and ruined portals are built from the same blocks a base is, and a dungeon is
            // literally a cobblestone room with a spawner, a flat floor and two chests: every "griefed base" escape
            // hatch that ignored this reported dungeons as bases.
            if (structHere && !playerOnly && playerStorage < 2) continue;

            // Containers that never generate naturally. A cluster with no shulker/hopper/barrel and no tunnel
            // pointing at it is loot, not a stash. A genuinely emptied base needs two dug rooms of its own and must
            // not sit inside a structure.
            boolean emptiedBase = griefedEvidence && !structHere && n(c, "Dug-out Room") >= 2;
            if (!playerOnly && lead == null && playerStorage < 2 && plainChests < 8 && !emptiedBase) continue;

            c.confidence = Confidence.fromScore(c.total, 16);
            if (c.confidence < 0.15f) continue; // nothing under 15% is worth a square on the screen
            if (c.confidence * 100f < minConfidence.get()) continue;
            c.heat = Math.min(1f, c.total / 26f);
            c.level = Confidence.level(c.confidence);
            c.key = best.pos.toLong();
            c.summary = c.summary(3);
            classify(c);
            if (c.lead != null && (c.type.equals("Base") || c.type.equals("Sus Tunnel") || c.type.equals("Hidden Room")
                    || c.type.equals("Griefed Base") || c.type.equals("Abandoned Build"))) {
                c.type = c.lead.kind == Tunnel.Kind.AMETHYST_DRILL ? "Amethyst Drill Base" : "Tunnel Base";
            }
            route(c);
            c.title = c.type + "  ·  " + c.status + "  " + Math.round(c.confidence * 100) + "%";
            if (c.level.ordinal() >= minOrdinal()) out.add(c);
        }
        out.sort(Comparator.comparingDouble((Candidate c) -> c.confidence).reversed());
        candidates.clear();
        candidates.addAll(out);

        int announcedNow = 0;
        for (Candidate c : candidates) {
            if (announced.contains(c.key)) continue;
            announced.add(c.key);
            // A cluster of bases at once used to bury the screen in toasts; the list and the HUD already show them all.
            if (c.level.ordinal() < Confidence.Level.MEDIUM.ordinal() || announcedNow >= 2) continue;
            announcedNow++;
            String msg = String.format(Locale.ROOT, "%s (%s) at %s, %d%%", c.type, c.status, Fmt.xyz(c.anchor), Math.round(c.confidence * 100));
            if (alerts.on()) { Notifications.push("Base Finder", msg, c.level.color, 6000); com.trace.refract.util.Sfx.alert(); }
            com.trace.refract.util.FindingsLog.write("base " + Math.round(c.confidence * 100) + "%", Fmt.xyz(c.anchor), c.summary);
            if (chat.on()) Refract.chat("§f" + msg + " §7" + c.summary);
            if (com.trace.refract.module.ModuleManager.waypoints.isEnabled() && com.trace.refract.module.ModuleManager.waypoints.autoBases.on())
                com.trace.refract.module.ModuleManager.waypoints.add(c.type, c.anchor, c.statusColor);
        }
    }

    /**
     * Smoothed suspicion per chunk: a chunk's own score plus its eight neighbours', so a base spanning a few
     * chunks reads as one hot area instead of eight isolated dots. This is what the square colours use.
     */
    private void computeHeat() {
        heat.clear();
        Map<Long, Float> raw = new HashMap<>();
        for (ChunkReport r : WorldScanner.reports()) {
            if (structureNoise(r)) continue;
            float s = baseScore(r);
            if (s > 0) raw.put(r.pos.toLong(), s);
        }
        for (Map.Entry<Long, Float> e : raw.entrySet()) {
            int x = ChunkPos.getPackedX(e.getKey()), z = ChunkPos.getPackedZ(e.getKey());
            float sum = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                sum += raw.getOrDefault(ChunkPos.toLong(x + dx, z + dz), 0f);
            }
            heat.put(e.getKey(), Math.min(1f, sum / 26f));
        }
    }

    /** Blue -> mint -> amber -> red as suspicion rises. */
    private static int heatColor(float h) {
        h = Math.max(0f, Math.min(1f, h));
        if (h < 0.34f) return Colors.lerp(0xFF3D8BFF, 0xFF38E8C8, h / 0.34f);
        if (h < 0.68f) return Colors.lerp(0xFF38E8C8, 0xFFFFD84D, (h - 0.34f) / 0.34f);
        return Colors.lerp(0xFFFFD84D, 0xFFFF4D5E, (h - 0.68f) / 0.32f);
    }

    private static int n(Candidate c, String k) { return c.found.getOrDefault(k, 0); }

    /** Container-like blocks: the minimum evidence before anything counts as a base. */
    public static int containers(Candidate c) {
        return n(c, "Chest") + n(c, "Trapped Chest") + n(c, "Barrel") + n(c, "Shulker Box")
                + n(c, "Spawner") + n(c, "Hopper") + n(c, "Furnace")
                + n(c, "Blast Furnace") + n(c, "Smoker") + n(c, "Dropper/Dispenser");
    }

    /**
     * Base type from what it contains, and status from what is still there:
     * <ul>
     *   <li>Being raided: its storage dropped below 60% of the most ever seen (blocks broken or carried off).</li>
     *   <li>Opened up: sky light reaches a third or more of its blocks, so someone dug down to it or it was griefed open.</li>
     *   <li>Intact: still sealed underground with nothing missing. (No client can see what is inside containers.)</li>
     * </ul>
     */
    private void classify(Candidate c) {
        if (livePrices.on()) com.trace.refract.util.MarketPrices.maybeSync();
        int spawners = n(c, "Spawner"), shulk = n(c, "Shulker Box"), ender = n(c, "Ender Chest");
        int chests = n(c, "Chest") + n(c, "Trapped Chest") + n(c, "Barrel") + n(c, "Copper Chest");
        int redstone = n(c, "Observer") + n(c, "Comparator") + n(c, "Piston") + n(c, "Sticky Piston") + n(c, "Repeater")
                + n(c, "Redstone Dust") + n(c, "Redstone Torch") + n(c, "Hopper") + n(c, "Dropper/Dispenser") + n(c, "Crafter");
        int living = n(c, "Bed") + n(c, "Enchanting Table") + n(c, "Anvil") + n(c, "Brewing Stand") + n(c, "Respawn Anchor")
                + n(c, "Crafting Table") + n(c, "Furnace");
        int villagers = mc.world == null ? 0 : mc.world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class, c.bounds.expand(8), e -> true).size();
        int strongStorage = spawners + shulk + ender;
        int rooms = n(c, "Dug-out Room");
        float built = 0;
        int placements = 0, excavated = 0, builtTypes = 0;
        for (ChunkReport r : c.chunks) { built += builtScore(r); placements += r.placements; excavated += r.excavated; builtTypes += r.builtTypes; }
        boolean swept = excavated >= 80 && placements >= 8 && builtTypes >= 2; // emptied and abandoned: cavity, real work, no storage
        int hoppers = n(c, "Hopper");
        int tnt = n(c, "TNT");
        int machinery = n(c, "Piston") + n(c, "Sticky Piston") + n(c, "Observer") + n(c, "Redstone Torch")
                + n(c, "Repeater") + n(c, "Dropper/Dispenser");
        int beds = n(c, "Bed");
        int amethyst = n(c, "Amethyst Cluster") + n(c, "Amethyst Bud");
        int armorStands = mc.world == null ? 0 : mc.world.getEntitiesByClass(
                net.minecraft.entity.decoration.ArmorStandEntity.class, c.bounds.expand(8), e -> true).size();
        int tunnel = 0;
        for (ChunkReport r : c.chunks) tunnel = Math.max(tunnel, r.tunnelLength);

        if (amethyst >= 4 && (rooms > 0 || built > 1)) {
            // Only a geode somebody has opened up: an untouched one is world generation, not a base.
            c.type = "Amethyst Geode";
        } else if (tnt >= 2 && machinery >= 6 && chests <= 5) {
            c.type = "Trap Zone";
        } else if (beds >= 2 && armorStands >= 1 && (n(c, "Beacon") > 0 || n(c, "Respawn Anchor") > 0 || chests >= 6)) {
            c.type = "Raid Base";
        } else if (hoppers >= 4 && chests >= 3) {
            c.type = "Farm Stash";
        } else if (shulk >= 4 && chests >= 4 && redstone < 6) {
            c.type = "Vault";
        } else if (shulk >= 3 && redstone < 6) {
            c.type = "Stash";
        } else if (strongStorage == 0 && chests <= 2 && tunnel >= 24 && (built > 0 || swept)) {
            c.type = "Sus Tunnel";
        } else if (strongStorage == 0 && chests <= 2 && (built > 0 || swept) && (rooms > 0 || built >= 5 || swept)) {
            c.type = rooms > 0 || built >= 5 ? "Griefed Base" : "Abandoned Build";
        } else if (spawners >= 3) c.type = "Spawner Farm";
        else if (villagers >= 3) c.type = "Trading Hall";
        else if (n(c, "Beacon") > 0 && (living >= 2 || chests >= 4)) c.type = "Main Base";
        else if (chests >= 8) c.type = "Storage Room";
        else if (redstone >= 8) c.type = "Redstone Farm";
        else if (living >= 3) c.type = "Living Quarters";
        else if (n(c, "Lit Portal") >= 4) c.type = "Portal Hub";
        else if (n(c, "Rotated Deepslate") > 0 && shulk + ender + chests == 0) c.type = "Hidden Room";
        else c.type = "Base";

        int storage = shulk + ender + chests + spawners;
        int peak = Math.max(storage, peakStorage.getOrDefault(c.key, 0));
        peakStorage.put(c.key, peak);
        int loaded = 0, sky = 0;
        if (mc.world != null) {
            int step = Math.max(1, c.evidence.size() / 24);
            for (int i = 0; i < c.evidence.size(); i += step) {
                BlockPos p = c.evidence.get(i);
                if (!mc.world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
                loaded++;
                if (mc.world.getLightLevel(net.minecraft.world.LightType.SKY, p.up()) >= 10) sky++;
            }
        }
        // A real raid removes several containers at once. Small swings happen when a cluster picks up or drops a
        // border chunk, which used to make untouched stashes read as "Being raided".
        if (peak >= 4 && peak - storage >= 3 && storage < peak * 0.5) {
            c.status = "Being raided"; c.statusColor = 0xFFFF4D6D;
            c.statusReason = "storage fell from " + peak + " to " + storage + " blocks";
        } else if (c.type.equals("Griefed Base") || c.type.equals("Abandoned Build")) {
            c.status = sky * 3 >= Math.max(1, loaded) ? "Griefed (opened)" : "Emptied";
            c.statusColor = 0xFFFF9A3D;
            c.statusReason = "rooms and placed blocks remain but no storage: raided or abandoned";
        } else if (loaded == 0) {
            c.status = "Out of range"; c.statusColor = 0xFF8A93A6;
            c.statusReason = "remembered; come closer to re-check";
        } else if (sky * 3 >= loaded) {
            c.status = "Opened up"; c.statusColor = 0xFFFF9A3D;
            c.statusReason = Math.round(100.0 * sky / loaded) + "% open to the sky: dug into or griefed";
        } else if (storage >= 3) {
            c.status = "Intact"; c.statusColor = 0xFF5CFFB0;
            c.statusReason = storage + " storage blocks, no sky access; contents unreadable";
        } else {
            // A sealed hole with nothing in it is not "intact": a client cannot see inside a chest either way, so
            // only claim what the scan can actually prove.
            c.status = "Sealed"; c.statusColor = 0xFF7FD1FF;
            c.statusReason = storage == 0 ? "no sky access; no storage visible" : "no sky access; only " + storage + " container(s)";
        }
    }

    /** Displayed box per base and when it first appeared, so boxes ease and fade instead of jumping. */
    private final Map<Long, double[]> shownBox = new HashMap<>();
    private final Map<Long, Float> appearAt = new HashMap<>();

    /** Eases the drawn bounds toward the latest cluster bounds (frame-rate independent). */
    private Box smoothBox(Candidate c) {
        Box b = c.bounds;
        double[] tg = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
        double[] v = shownBox.get(c.key);
        if (v == null) {
            shownBox.put(c.key, v = tg.clone());
            appearAt.put(c.key, Time.seconds());
        }
        double k = 1 - Math.exp(-8.0 * Time.dt());
        for (int i = 0; i < 6; i++) v[i] += (tg[i] - v[i]) * k;
        return new Box(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    /** 0..1 fade-in over the first half second a base is shown. */
    private float fadeIn(Candidate c) {
        Float a = appearAt.get(c.key);
        if (a == null) return 1f;
        return Anim.easeOutCubic(Anim.clamp01((Time.seconds() - a) / 0.5f));
    }

    @Override public void onWorldRender(float tickDelta) {
        boolean walls = throughWalls.on();
        Set<Long> live = new HashSet<>();
        for (Candidate c : candidates) live.add(c.key);
        shownBox.keySet().retainAll(live);
        appearAt.keySet().retainAll(live);
        if (tunnelLeads.on()) renderLeads(walls);
        if (esp.is("Heat")) { renderHeat(walls); renderHologram(walls); return; }
        boolean squares = esp.is("Squares") || esp.is("Both"), box = esp.is("Box") || esp.is("Both");
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        int ev = 0;
        for (Candidate c : candidates) {
            float a = fadeIn(c);
            int col = c.level.color;
            Box b = smoothBox(c);
            if (box) {
                // Clean box: a faint two-tone fill, a soft outline and bright corner brackets.
                Esp.gradientBox(b, Colors.withAlpha(col, (int) (0x26 * a)), Colors.withAlpha(col, (int) (0x08 * a)), 1.25f, walls, 2);
                Esp.corners(b, Colors.withAlpha(col, (int) (0xF0 * a)), 2.25f, walls);
            }
            if (squares) {
                // Outline squares over each chunk the base spans, lifted just off the floor so they never flicker.
                double y = Math.floor(b.minY + 0.5) + 0.06;
                for (ChunkReport r : c.chunks) {
                    float h = Math.max(c.chunkHeat.getOrDefault(r.pos.toLong(), 0f), 0.05f);
                    int hc = heatColor(h);
                    Box sq = new Box(r.pos.getStartX() + 0.5, y, r.pos.getStartZ() + 0.5, r.pos.getStartX() + 15.5, y + 0.02, r.pos.getStartZ() + 15.5);
                    Esp.box(sq, Colors.withAlpha(hc, (int) ((10 + 30 * h) * a)), Colors.withAlpha(hc, (int) (0xB0 * a)), 2f, walls);
                }
            }
            if (!esp.is("Off")) Esp.box(new Box(c.anchor).expand(0.04), Colors.withAlpha(c.statusColor, (int) (0x50 * a)), Colors.withAlpha(c.statusColor, (int) (0xF0 * a)), 1.75f, walls);
            if (evidence.on() && c.center.squaredDistanceTo(cam) < 96 * 96) {
                for (ChunkReport r : c.chunks) for (Box room : r.rooms) Esp.box(room, 0, Colors.withAlpha(col, 0x70), 1.25f, walls);
                for (BlockPos p : c.evidence) {
                    if (ev++ > 160) break;
                    Esp.block(p, 0.08, Colors.withAlpha(col, 0x1C), Colors.withAlpha(col, 0xB0), 1f, walls);
                }
            }
            if (tracers.on()) Esp.line(Esp.tracerOrigin(), c.center, Colors.withAlpha(col, (int) (0xA0 * a)), 1.25f, true);
        }
        renderHologram(walls);
    }

    // ------------------------------------------------------------------ hologram

    private Candidate holoBase;
    private final Anim holoShow = new Anim(0f, 7f);
    private List<com.trace.refract.render.HoloRenderer.Block> holoBlocks = List.of();
    private float holoSpan = 1;
    private long holoBuiltAt;
    private float holoStart;
    private Vec3d holoCenter;
    private float holoExtentY = 4;

    /** Key of the base whose hologram is showing (null when none), for tests. */
    public Long hologramKey() { return holoBase != null && holoShow.value > 0.01f ? holoBase.key : null; }

    public int hologramVoxels() { return holoBlocks.size(); }

    /**
     * Look at a base's marker and a hologram of the real base flies up into the sky above it: its actual blocks
     * (true textures and shapes, translucent, full-bright), about eighteen blocks across, popping to size, building
     * bottom-up and turning slowly over a projector ring, tethered to the base by a light beam.
     */
    private void renderHologram(boolean walls) {
        if (!hologram.on() || mc.world == null) { holoShow.snap(0); return; }
        Object fk = com.trace.refract.hud.Inspector.focusedKey();
        Candidate focus = null;
        if (fk instanceof Long k) for (Candidate c : candidates) if (c.key == k) { focus = c; break; }
        // Looking up at the hologram itself keeps it open.
        if (focus == null && holoBase != null && holoShow.value > 0.3f && holoCenter != null) {
            Vec3d look = mc.player.getRotationVec(1f), to = holoCenter.subtract(mc.gameRenderer.getCamera().getCameraPos()).normalize();
            if (look.dotProduct(to) > Math.cos(Math.toRadians(28))) for (Candidate c : candidates) if (c.key == holoBase.key) { focus = c; break; }
        }
        if (focus != null && (holoBase == null || focus.key != holoBase.key)) {
            holoBase = focus;
            holoShow.snap(0);
            holoStart = Time.seconds();
            buildBlocks(focus);
        }
        if (focus != null) holoBase = focus;
        holoShow.to(focus != null ? 1 : 0).update(Time.dt());
        if (holoBase == null || holoShow.value < 0.01f) return;
        if (System.currentTimeMillis() - holoBuiltAt > 8000) buildBlocks(holoBase);

        float show = holoShow.value, t = Time.seconds();
        var th = com.trace.refract.theme.Themes.current();
        Box b = holoBase.bounds;
        double bx = (b.minX + b.maxX) / 2, bz = (b.minZ + b.maxZ) / 2;
        int ground = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(bx), (int) Math.floor(bz));
        double size = 22.0;
        // Above the build, high enough to read as "in the sky", low enough to stay in view from where you stand.
        Vec3d camPos = mc.gameRenderer.getCamera().getCameraPos();
        double horiz = Math.hypot(bx - camPos.x, bz - camPos.z);
        // Clear of everything: the hologram's bottom floats 5 blocks over the highest ground above the base.
        double halfH = holoExtentY * (size / holoSpan) / 2;
        double skyY = Math.max(ground, b.maxY) + 5 + halfH;
        float fly = Anim.clamp01((t - holoStart) / 1.1f);
        float rise = Anim.easeInOutCubic(fly);
        float pop = Anim.easeOutBack(Anim.clamp01((fly - 0.55f) / 0.45f));
        float grow = (0.15f + 0.85f * pop) * (0.3f + 0.7f * show);
        double cy0 = b.maxY + 0.5, cyNow = cy0 + (skyY - cy0) * rise + (fly >= 1 ? 0.4 * Math.sin(t * 1.1) : 0);
        float scale = (float) (size / holoSpan) * Math.max(0.05f, grow);
        holoCenter = new Vec3d(bx, cyNow, bz);
        float ang = (t - holoStart) * 0.28f;
        com.trace.refract.render.HoloRenderer.scene = new com.trace.refract.render.HoloRenderer.Scene(holoBlocks,
                new Vec3d(bx, cyNow, bz), scale, ang, show * (0.55f + 0.45f * rise), fly, holoSpan, Colors.lerp(th.accent(), 0xFFFFFFFF, 0.25f));

        // Projector ring under the hologram, a scanline ring sweeping up through it, and the beam to the base.
        double half = halfH * grow;
        double ringR = size * 0.62 * grow, ry = cyNow - half - 0.8;
        int segs = 56;
        double scanY = cyNow - half + ((t * 0.3) % 1.0) * half * 2;
        for (int i = 0; i < segs; i++) {
            double a0 = ang + i * Math.PI * 2 / segs, a1 = ang + (i + 1) * Math.PI * 2 / segs;
            int col = Colors.lerp(th.accent(), th.accent2(), (float) i / segs);
            Esp.line(new Vec3d(bx + Math.cos(a0) * ringR, ry, bz + Math.sin(a0) * ringR), new Vec3d(bx + Math.cos(a1) * ringR, ry, bz + Math.sin(a1) * ringR),
                    Colors.withAlpha(col, (int) ((i % 7 == 0 ? 0x30 : 0xE0) * show)), 2.5f, walls);
            if (fly >= 1) Esp.line(new Vec3d(bx + Math.cos(a0) * ringR * 0.8, scanY, bz + Math.sin(a0) * ringR * 0.8),
                    new Vec3d(bx + Math.cos(a1) * ringR * 0.8, scanY, bz + Math.sin(a1) * ringR * 0.8), Colors.withAlpha(col, (int) (0x50 * show)), 1.5f, walls);
        }
        Esp.line(new Vec3d(bx, b.maxY, bz), new Vec3d(bx, ry, bz), Colors.withAlpha(th.accent(), (int) (0x80 * show)), 2f, walls);
        Esp.beam(new Vec3d(bx, 0, bz), b.maxY, ry, Colors.withAlpha(th.accent(), (int) (0x50 * show)), Colors.withAlpha(th.accent2(), (int) (0x18 * show)), walls);
    }

    private static boolean natural(net.minecraft.block.BlockState st) {
        return st.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD) || st.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_NETHER)
                || st.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || st.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                || st.isOf(net.minecraft.block.Blocks.GRAVEL) || st.isOf(net.minecraft.block.Blocks.BEDROCK) || st.isOf(net.minecraft.block.Blocks.CLAY);
    }

    /**
     * Samples the base: every placed (non-natural) block in its bounds, up to 1100, and the natural rock that lines
     * the room (touching air), thinned to 500, as a faint ghost of the room's shape. Centred on the origin.
     */
    private void buildBlocks(Candidate c) {
        holoBuiltAt = System.currentTimeMillis();
        Box b = c.bounds;
        BlockPos an = c.anchor;
        int x0 = Math.max((int) Math.floor(b.minX), an.getX() - 24), x1 = Math.min((int) Math.ceil(b.maxX) - 1, an.getX() + 24);
        int y0 = Math.max((int) Math.floor(b.minY), an.getY() - 10), y1 = Math.min((int) Math.ceil(b.maxY) - 1, an.getY() + 10);
        int z0 = Math.max((int) Math.floor(b.minZ), an.getZ() - 24), z1 = Math.min((int) Math.ceil(b.maxZ) - 1, an.getZ() + 24);
        float cx = (x0 + x1 + 1) / 2f, cy = (y0 + y1 + 1) / 2f, cz = (z0 + z1 + 1) / 2f;
        List<com.trace.refract.render.HoloRenderer.Block> placed = new ArrayList<>(), rock = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable(), nb = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            m.set(x, y, z);
            var st = mc.world.getBlockState(m);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            boolean nat = natural(st);
            if (nat) {
                boolean exposed = false;
                for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                    nb.set(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ());
                    if (mc.world.getBlockState(nb).isAir()) { exposed = true; break; }
                }
                if (!exposed) continue;
            }
            int col = st.getMapColor(mc.world, m).color | 0xFF000000;
            (nat ? rock : placed).add(new com.trace.refract.render.HoloRenderer.Block(x + 0.5f - cx, y + 0.5f - cy, z + 0.5f - cz, st, !nat, col));
        }
        List<com.trace.refract.render.HoloRenderer.Block> out = new ArrayList<>(placed.subList(0, Math.min(1100, placed.size())));
        double step = Math.max(1.0, rock.size() / 500.0);
        for (double i = 0; i < rock.size(); i += step) out.add(rock.get((int) i));
        holoBlocks = out;
        holoSpan = Math.max(4, Math.max(x1 - x0 + 1, Math.max(y1 - y0 + 1, z1 - z0 + 1)));
        holoExtentY = y1 - y0 + 1;
    }

    /** The chunk with the most heat in a candidate, used for the light column. */
    private static ChunkPos hottest(Candidate c) {
        ChunkReport best = c.chunks.get(0);
        float bh = -1;
        for (ChunkReport r : c.chunks) {
            float h = c.chunkHeat.getOrDefault(r.pos.toLong(), 0f);
            if (h > bh) { bh = h; best = r; }
        }
        return best.pos;
    }

    /** Suspicion view: a square on every chunk with any heat at all, coloured by how sure the scan is. */
    private void renderHeat(boolean walls) {
        double t = heatThreshold.get();
        for (ChunkReport r : WorldScanner.reports()) {
            float h = heat.getOrDefault(r.pos.toLong(), 0f);
            if (h < t) continue;
            Vec3d f = r.focus();
            double y = (f != null ? Math.floor(f.y) : 8) + 0.02;
            int hc = heatColor(h);
            Box sq = new Box(r.pos.getStartX() + 0.5, y, r.pos.getStartZ() + 0.5, r.pos.getStartX() + 15.5, y + 0.06, r.pos.getStartZ() + 15.5);
            Esp.box(sq, Colors.withAlpha(hc, 16 + Math.round(54 * h)), Colors.withAlpha(hc, 0xB8), 2.5f, walls);
            if (h >= 0.45f) {
                Esp.beam(new Vec3d(r.pos.getStartX() + 8.5, y, r.pos.getStartZ() + 8.5), y, y + 6,
                        Colors.withAlpha(hc, 0xC0), 0x00FFFFFF, walls);
            }
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (!labels.on() || mc.player == null) return;
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        // Nearest first, and only as many as the user asked for: an endless label list is unreadable.
        java.util.List<Candidate> shown = new java.util.ArrayList<>(candidates);
        shown.sort((a, b) -> Double.compare(eye.distanceTo(a.center), eye.distanceTo(b.center)));
        int idx = 0, labelsDrawn = 0, maxLabels = Math.max(1, maxFindings.asInt());
        for (Candidate c : shown) {
            double d = eye.distanceTo(c.center);
            if (d > Markers.FAR && farMarkers.on()) {
                if (idx++ > 10) break;
                Markers.draw(d, c.center, c.level.color, c.type, 1f);
                continue;
            }
            if (labelsDrawn >= maxLabels) continue;
            if (d > Markers.VERY_FAR) continue;
            labelsDrawn++;
            if (holoBase != null && holoBase.key == c.key && holoShow.value > 0.05f) continue; // the hologram and card show it
            String sub = Fmt.xyz(c.anchor) + "  ·  " + (c.route.isEmpty() ? Fmt.dist(d) : c.route);
            WorldLabels.draw(t, new Vec3d(c.center.x, c.bounds.maxY, c.center.z), c.title, sub, c.level.color, 1f, sw, sh, false);
        }
        // Leads: old tunnels with a direction, never dressed up as bases.
        int li = 0;
        for (Lead l : leads) {
            if (li++ > 4) break;
            Vec3d end = l.to.toCenterPos();
            if (holoBase != null && holoBase.key == ChunkPos.toLong(l.to.getX() >> 4, l.to.getZ() >> 4)) continue;
            WorldLabels.draw(t, new Vec3d(end.x, l.floorY + 1.5, end.z), "Old tunnel · " + l.kindLabel(),
                    l.route + "  ·  " + l.length + "m " + (l.axis == 0 ? "E–W" : "N–S"), 0xFF38BDF8, 1f, sw, sh, false);
        }
    }

    // ------------------------------------------------------------------ tunnels and directions

    /** Bearing from the player to a point, in eight compass points. */
    public static String bearing(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[Math.floorMod((int) Math.round(deg / 45.0), 8)];
    }

    /** "NE 214m · 37m below" from the player to {@code at}. */
    public static String routeTo(Vec3d at) {
        if (mc.player == null) return "";
        Vec3d p = mc.player.getEntityPos();
        double dx = at.x - p.x, dz = at.z - p.z, dy = at.y - p.y;
        String vert = dy < -2.5 ? "  ·  " + Math.round(-dy) + "m below" : dy > 2.5 ? "  ·  " + Math.round(dy) + "m above" : "  ·  level";
        return bearing(dx, dz) + " " + Fmt.dist(Math.hypot(dx, dz)) + vert;
    }

    private void route(Candidate c) {
        if (mc.player == null) return;
        c.distance = mc.player.getEntityPos().distanceTo(c.center);
        c.route = routes.on() ? routeTo(c.center) : "";
    }

    /** The nearest old corridor that ends within reach of a base's anchor. */
    private Lead leadFor(Candidate c) {
        Lead best = null;
        double bd = 34 * 34;
        for (Lead l : leads) {
            double d = Math.min(sq(l.to, c.anchor.toCenterPos()), sq(l.from, c.anchor.toCenterPos()));
            if (d < bd) { bd = d; best = l; }
        }
        return best;
    }

    private static double sq(BlockPos p, Vec3d v) {
        double dx = p.getX() + 0.5 - v.x, dy = p.getY() + 0.5 - v.y, dz = p.getZ() + 0.5 - v.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Merges corridors that continue one another into one lead per route, then sorts them by distance. */
    private void buildLeads() {
        leads.clear();
        if (mc.player == null) return;
        List<Tunnel> pool = new ArrayList<>();
        for (ChunkReport r : WorldScanner.reports()) {
            // No structure skip here: a mineshaft corridor is 3 wide with wood floors and fences, which the strict
            // 1-wide solid-walled test below already rejects, while skipping whole chunks also hid real tunnels that
            // happened to run beneath a village or trial chamber.
            for (Tunnel t : r.tunnels) {
                // Length is judged after merging: a corridor is split into one run per chunk, so each piece is at
                // most 16 blocks long on its own.
                if (t.age == Tunnel.Age.FRESH && !freshTunnels.on()) continue;
                pool.add(t);
            }
        }
        if (pool.isEmpty()) return;
        Vec3d eye = mc.player.getEntityPos();
        boolean[] used = new boolean[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            List<Tunnel> group = new ArrayList<>();
            group.add(pool.get(i));
            boolean grew = true;
            while (grew) {
                grew = false;
                for (int j = 0; j < pool.size(); j++) {
                    if (used[j]) continue;
                    for (Tunnel g : group) if (joinable(g, pool.get(j))) { used[j] = true; group.add(pool.get(j)); grew = true; break; }
                }
            }
            Lead lead = makeLead(group, eye);
            if (lead.length >= minTunnel.asInt()) leads.add(lead);
        }
        leads.sort(Comparator.comparingDouble((Lead l) -> l.distance));
    }

    /** Two corridors continue each other when they share axis and height and their ends nearly touch. */
    private static boolean joinable(Tunnel a, Tunnel b) {
        if (a.axis != b.axis || Math.abs(a.floorY - b.floorY) > 1) return false;
        Box x = a.bounds, y = b.bounds;
        if (a.axis == 0) {
            if (Math.abs((x.minZ + x.maxZ) - (y.minZ + y.maxZ)) > 4) return false;
            return y.minX <= x.maxX + 8 && x.minX <= y.maxX + 8;
        }
        if (Math.abs((x.minX + x.maxX) - (y.minX + y.maxX)) > 4) return false;
        return y.minZ <= x.maxZ + 8 && x.minZ <= y.maxZ + 8;
    }

    private Lead makeLead(List<Tunnel> group, Vec3d eye) {
        Lead l = new Lead();
        Tunnel first = group.get(0);
        l.axis = first.axis;
        l.floorY = first.floorY;
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        int longest = 0;
        boolean amethyst = false, railed = false, fresh = false;
        for (Tunnel t : group) {
            minX = Math.min(minX, t.bounds.minX); maxX = Math.max(maxX, t.bounds.maxX);
            minZ = Math.min(minZ, t.bounds.minZ); maxZ = Math.max(maxZ, t.bounds.maxZ);
            minY = Math.min(minY, t.bounds.minY); maxY = Math.max(maxY, t.bounds.maxY);
            longest = Math.max(longest, t.length);
            amethyst |= t.kind == Tunnel.Kind.AMETHYST_DRILL;
            railed |= t.kind == Tunnel.Kind.RAILED;
            fresh |= t.age == Tunnel.Age.FRESH;
        }
        l.kind = amethyst ? Tunnel.Kind.AMETHYST_DRILL : railed ? Tunnel.Kind.RAILED : Tunnel.Kind.PICKAXE;
        l.fresh = fresh;
        l.bounds = new Box(minX, minY, minZ, maxX, maxY, maxZ);
        l.length = Math.max(longest, (int) Math.round(l.axis == 0 ? maxX - minX : maxZ - minZ));
        BlockPos a = l.axis == 0
                ? new BlockPos((int) Math.floor(minX), l.floorY, (int) Math.floor((minZ + maxZ) / 2))
                : new BlockPos((int) Math.floor((minX + maxX) / 2), l.floorY, (int) Math.floor(minZ));
        BlockPos b = l.axis == 0
                ? new BlockPos((int) Math.ceil(maxX) - 1, l.floorY, (int) Math.floor((minZ + maxZ) / 2))
                : new BlockPos((int) Math.floor((minX + maxX) / 2), l.floorY, (int) Math.ceil(maxZ) - 1);
        boolean aNear = sq(a, eye) <= sq(b, eye);
        l.from = aNear ? a : b;
        l.to = aNear ? b : a;
        Vec3d end = l.to.toCenterPos();
        l.distance = Math.hypot(end.x - eye.x, end.z - eye.z);
        l.route = routes.on() ? routeTo(end) : "";
        l.describe = l.kindLabel() + ", " + l.length + "m " + (l.axis == 0 ? "E–W" : "N–S") + (fresh ? ", new" : ", old");
        return l;
    }

    /** Old corridors on screen: a translucent box along the tunnel, a beam at its far end and a route line to it. */
    private void renderLeads(boolean walls) {
        if (leads.isEmpty() || mc.player == null) return;
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        int n = 0;
        for (Lead l : leads) {
            if (n++ > 10) break;
            if (l.distance > 384) continue;
            int col = l.kind == Tunnel.Kind.AMETHYST_DRILL ? 0xFFC77DFF : 0xFF38BDF8;
            Esp.pretty(l.bounds, Colors.withAlpha(col, 0x88), walls);
            Vec3d end = l.to.toCenterPos();
            Esp.beam(end, l.floorY, l.floorY + 5, Colors.withAlpha(col, 0x90), 0x00FFFFFF, walls);
            if (routes.on()) Esp.line(eye, end, Colors.withAlpha(col, 0x70), 1.25f, true);
        }
    }
}
