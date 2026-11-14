package com.trace.refract.scan;

import net.minecraft.block.*;
import net.minecraft.registry.Registries;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What every interesting block means. Built once from the block registry by id so it covers every
 * colour/variant (all shulker boxes, beds, signs, copper chests...).
 */
public final class Indicators {
    public enum Kind {
        /** Containers shown by Storage ESP; also count toward Base Finder. */
        STORAGE,
        /** Player-made blocks that rarely or never generate naturally. */
        PLAYER,
        /** Blocks that betray a naturally generated structure (used to avoid false positives). */
        STRUCTURE,
        KELP, KELP_PLANT, BUD, CLUSTER, CAVE_VINE_HEAD,
        /** Building blocks that do not generate underground: what is left of a base after it is griefed. */
        BUILT,
        /** Valuable ores: only used to detect anti-xray noise. */
        ORE
    }

    public enum Storage {
        CHEST("Chest", 0xFFFFB648), TRAPPED("Trapped Chest", 0xFFFF6A3D), ENDER("Ender Chest", 0xFFB06BFF),
        SHULKER("Shulker Box", 0xFFFF6FD8), BARREL("Barrel", 0xFFC8935A), HOPPER("Hopper", 0xFF9AA4B8),
        DISPENSER("Dropper/Dispenser", 0xFFB8C0CC), FURNACE("Furnace", 0xFF8A93A6), SPAWNER("Spawner", 0xFF4DE3FF),
        COPPER("Copper Chest", 0xFFE08A5C);

        public final String label;
        public final int color;

        Storage(String label, int color) { this.label = label; this.color = color; }
    }

    /**
     * @param weight      Base Finder score per block
     * @param cap         maximum blocks of this type that count per chunk (stops one big natural structure dominating)
     * @param natural     true if it also generates in natural structures (score is damped when a structure is present)
     * @param hideable    true if a Paper anti-xray config can fake it in solid stone; only counted when exposed
     */
    public record Info(Kind kind, String label, float weight, int cap, boolean natural, boolean hideable, Storage storage) {}

    private static final Map<Block, Info> TABLE = new IdentityHashMap<>();

    private Indicators() {}

    public static Info get(Block b) { return TABLE.get(b); }

    public static boolean interesting(BlockState s) { return TABLE.containsKey(s.getBlock()); }

    /** Deepslate placed sideways: world generation only ever places it upright (axis=y). */
    public static boolean rotatedDeepslate(BlockState s) {
        return s.getBlock() == Blocks.DEEPSLATE && s.get(net.minecraft.state.property.Properties.AXIS) != net.minecraft.util.math.Direction.Axis.Y;
    }

    /** Deepslate/cobbled deepslate only generate below y=8; in a section starting at y>=16 they were placed. */
    public static boolean highDeepslate(BlockState s, int sectionY) {
        return sectionY >= 16 && (s.getBlock() == Blocks.DEEPSLATE || s.getBlock() == Blocks.COBBLED_DEEPSLATE);
    }

    /** Palette-level test used to skip whole sections cheaply. */
    public static boolean interesting(BlockState s, int sectionY) {
        return TABLE.containsKey(s.getBlock()) || rotatedDeepslate(s) || highDeepslate(s, sectionY);
    }

    private static void put(Block b, Info i) { TABLE.putIfAbsent(b, i); }

    private static Info storage(Storage st, float w, int cap, boolean natural, boolean hideable) {
        return new Info(Kind.STORAGE, st.label, w, cap, natural, true, st);
    }

    private static Info player(String label, float w, int cap, boolean natural, boolean hideable) {
        return new Info(Kind.PLAYER, label, w, cap, natural, hideable, null);
    }

    private static Info simple(Kind k, String label) { return new Info(k, label, 0, 0, false, false, null); }

    public static void init() {
        if (!TABLE.isEmpty()) return;
        put(Blocks.CHEST, storage(Storage.CHEST, 1.0f, 12, true, true));
        put(Blocks.TRAPPED_CHEST, storage(Storage.TRAPPED, 2.5f, 8, true, false));
        put(Blocks.ENDER_CHEST, storage(Storage.ENDER, 9f, 6, false, true));
        put(Blocks.BARREL, storage(Storage.BARREL, 2f, 12, true, false));
        put(Blocks.HOPPER, storage(Storage.HOPPER, 3.5f, 10, false, false));
        put(Blocks.DISPENSER, storage(Storage.DISPENSER, 1.5f, 6, true, false));
        put(Blocks.DROPPER, storage(Storage.DISPENSER, 3f, 6, false, false));
        put(Blocks.FURNACE, storage(Storage.FURNACE, 1.5f, 8, true, false));
        put(Blocks.BLAST_FURNACE, storage(Storage.FURNACE, 2f, 6, true, false));
        put(Blocks.SMOKER, storage(Storage.FURNACE, 1.5f, 6, true, false));
        // Spawners: natural ones sit in dungeons/mineshafts (damped by those markers); elsewhere they were carried in.
        put(Blocks.SPAWNER, new Info(Kind.STORAGE, "Spawner", 6f, 6, true, true, Storage.SPAWNER));

        put(Blocks.BEACON, player("Beacon", 14, 4, false, false));
        put(Blocks.RESPAWN_ANCHOR, player("Respawn Anchor", 10, 3, false, false));
        put(Blocks.NETHERITE_BLOCK, player("Netherite Block", 12, 6, false, false));
        put(Blocks.NETHER_PORTAL, player("Lit Portal", 1.2f, 12, false, false));
        put(Blocks.OBSERVER, player("Observer", 5, 8, false, false));
        put(Blocks.COMPARATOR, player("Comparator", 5, 8, false, false));
        put(Blocks.REPEATER, player("Repeater", 2, 8, true, false));
        put(Blocks.DAYLIGHT_DETECTOR, player("Daylight Sensor", 5, 4, false, false));
        put(Blocks.NOTE_BLOCK, player("Note Block", 3, 6, false, false));
        put(Blocks.JUKEBOX, player("Jukebox", 5, 3, false, false));
        put(Blocks.ENCHANTING_TABLE, player("Enchanting Table", 7, 3, false, false));
        put(Blocks.ANVIL, player("Anvil", 5, 3, false, false));
        put(Blocks.CHIPPED_ANVIL, player("Anvil", 5, 3, false, false));
        put(Blocks.DAMAGED_ANVIL, player("Anvil", 5, 3, false, false));
        put(Blocks.BREWING_STAND, player("Brewing Stand", 2.5f, 4, true, false));
        put(Blocks.POWERED_RAIL, player("Powered Rail", 2, 10, false, false));
        put(Blocks.DETECTOR_RAIL, player("Detector Rail", 2, 6, false, false));
        put(Blocks.ACTIVATOR_RAIL, player("Activator Rail", 2, 6, false, false));
        put(Blocks.CRAFTING_TABLE, player("Crafting Table", 2, 4, true, false));
        put(Blocks.DIAMOND_BLOCK, player("Diamond Block", 8, 8, false, false));
        put(Blocks.EMERALD_BLOCK, player("Emerald Block", 6, 8, false, false));
        put(Blocks.IRON_BLOCK, player("Iron Block", 2.5f, 8, false, false));
        put(Blocks.GOLD_BLOCK, player("Gold Block", 1.5f, 6, true, false));
        put(Blocks.OBSIDIAN, player("Obsidian", 0.35f, 40, true, true));
        put(Blocks.REDSTONE_TORCH, player("Redstone Torch", 1.5f, 6, false, false));
        put(Blocks.REDSTONE_WALL_TORCH, player("Redstone Torch", 1.5f, 6, false, false));
        put(Blocks.REDSTONE_WIRE, player("Redstone Dust", 0.5f, 16, true, false));
        put(Blocks.PISTON, player("Piston", 1.5f, 8, true, false));
        put(Blocks.STICKY_PISTON, player("Sticky Piston", 1.5f, 8, true, false));
        put(Blocks.TNT, player("TNT", 0.5f, 8, true, false));
        put(Blocks.TORCH, player("Torch", 0.35f, 16, true, false));
        put(Blocks.WALL_TORCH, player("Torch", 0.35f, 16, true, false));
        put(Blocks.END_ROD, player("End Rod", 1.5f, 8, true, false));
        put(Blocks.CRAFTER, player("Crafter", 5, 4, false, false));
        put(Blocks.LODESTONE, player("Lodestone", 8, 2, false, false));
        put(Blocks.CONDUIT, player("Conduit", 9, 2, false, false));
        put(Blocks.SCAFFOLDING, player("Scaffolding", 0.6f, 12, false, false));

        put(Blocks.BELL, simple(Kind.STRUCTURE, "Village"));
        put(Blocks.DIRT_PATH, simple(Kind.STRUCTURE, "Village"));
        put(Blocks.HAY_BLOCK, simple(Kind.STRUCTURE, "Village"));
        put(Blocks.CAMPFIRE, simple(Kind.STRUCTURE, "Village"));
        put(Blocks.MOSSY_STONE_BRICKS, simple(Kind.STRUCTURE, "Dungeon"));
        put(Blocks.END_PORTAL_FRAME, simple(Kind.STRUCTURE, "Stronghold"));
        put(Blocks.MOSSY_COBBLESTONE, simple(Kind.STRUCTURE, "Dungeon"));
        put(Blocks.SUSPICIOUS_SAND, simple(Kind.STRUCTURE, "Ruins"));
        put(Blocks.SUSPICIOUS_GRAVEL, simple(Kind.STRUCTURE, "Ruins"));
        put(Blocks.REINFORCED_DEEPSLATE, simple(Kind.STRUCTURE, "Ancient City"));
        put(Blocks.SCULK_SHRIEKER, simple(Kind.STRUCTURE, "Ancient City"));
        put(Blocks.TRIAL_SPAWNER, simple(Kind.STRUCTURE, "Trial Chamber"));
        put(Blocks.VAULT, simple(Kind.STRUCTURE, "Trial Chamber"));
        put(Blocks.PRISMARINE_BRICKS, simple(Kind.STRUCTURE, "Monument"));
        put(Blocks.PURPUR_BLOCK, simple(Kind.STRUCTURE, "End City"));
        put(Blocks.GILDED_BLACKSTONE, simple(Kind.STRUCTURE, "Bastion"));
        put(Blocks.NETHER_BRICK_FENCE, simple(Kind.STRUCTURE, "Fortress"));
        put(Blocks.CRYING_OBSIDIAN, simple(Kind.STRUCTURE, "Ruined Portal"));
        put(Blocks.CHISELED_SANDSTONE, simple(Kind.STRUCTURE, "Temple"));
        put(Blocks.CHISELED_STONE_BRICKS, simple(Kind.STRUCTURE, "Stronghold"));

        put(Blocks.COBWEB, simple(Kind.STRUCTURE, "Mineshaft"));
        put(Blocks.RAIL, simple(Kind.STRUCTURE, "Mineshaft"));
        put(Blocks.TUFF_BRICKS, simple(Kind.STRUCTURE, "Trial Chamber"));
        put(Blocks.CAVE_VINES, simple(Kind.CAVE_VINE_HEAD, "Cave Vines"));
        put(Blocks.KELP, simple(Kind.KELP, "Kelp"));
        put(Blocks.KELP_PLANT, simple(Kind.KELP_PLANT, "Kelp"));
        put(Blocks.SMALL_AMETHYST_BUD, simple(Kind.BUD, "Amethyst Bud"));
        put(Blocks.MEDIUM_AMETHYST_BUD, simple(Kind.BUD, "Amethyst Bud"));
        put(Blocks.LARGE_AMETHYST_BUD, simple(Kind.BUD, "Amethyst Bud"));
        put(Blocks.AMETHYST_CLUSTER, simple(Kind.CLUSTER, "Amethyst Cluster"));

        for (Block b : new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
                Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
                Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.ANCIENT_DEBRIS}) {
            put(b, simple(Kind.ORE, "Ore"));
        }

        // Variants by id, so every colour and future copper/wood type is covered.
        for (Block b : Registries.BLOCK) {
            String p = Registries.BLOCK.getId(b).getPath();
            if (b instanceof ShulkerBoxBlock) put(b, storage(Storage.SHULKER, 12f, 12, false, true));
            else if (p.contains("copper_chest")) put(b, storage(Storage.COPPER, 3f, 8, false, false));
            else if (b instanceof BedBlock) put(b, player("Bed", 3f, 4, true, false));
            else if (b instanceof AbstractSignBlock) put(b, player("Sign", 3.5f, 8, false, false));
            else if (b instanceof AbstractBannerBlock) put(b, player("Banner", 2f, 6, true, false));
            else if (p.endsWith("_concrete")) put(b, player("Concrete", 0.5f, 24, false, false));
            else if (p.endsWith("_shulker_box")) put(b, storage(Storage.SHULKER, 12f, 12, false, true));
            else if (p.equals("glass") || p.endsWith("stained_glass")) put(b, player("Glass", 0.3f, 16, true, false));
            else if (p.endsWith("_carpet")) put(b, player("Carpet", 0.4f, 12, true, false));
        }
        // Placed building blocks. These override earlier entries (glass, carpet, torches, concrete) on purpose:
        // they survive griefing, so they are scored as "built" evidence instead of player-only evidence.
        // Cobblestone and cobbled deepslate never generate (only structures build with them), so they are the
        // clearest sign of patched walls, pillars and grief repairs. Damped by the structure markers above.
        put(Blocks.COBBLESTONE, built("Cobblestone", 0.20f, 32));
        put(Blocks.COBBLED_DEEPSLATE, built("Cobbled Deepslate", 0.22f, 32));

        for (Block b : Registries.BLOCK) {
            String p = Registries.BLOCK.getId(b).getPath();
            if (TABLE.containsKey(b) && TABLE.get(b).kind() != Kind.PLAYER) continue;
            Info i = null;
            if (p.endsWith("_planks")) i = built("Planks", 0.25f, 24);
            else if (p.endsWith("_stairs") || p.endsWith("_slab")) i = built("Stairs/Slabs", 0.4f, 32);
            else if (p.equals("glass") || p.endsWith("_glass") || p.endsWith("glass_pane")) i = built("Glass", 0.4f, 32);
            else if (p.endsWith("_wool") || p.endsWith("_carpet")) i = built("Wool/Carpet", 0.6f, 24);
            else if (p.endsWith("_concrete") || p.endsWith("glazed_terracotta")) i = built("Concrete", 0.6f, 32);
            else if (p.startsWith("polished_") || p.equals("smooth_stone") || p.equals("bricks") || p.equals("stone_bricks")
                    || p.equals("quartz_block") || p.equals("smooth_quartz")) i = built("Polished/Bricks", 0.35f, 32);
            else if (p.equals("ladder")) i = built("Ladder", 0.3f, 16);
            else if (p.endsWith("_door") && !p.contains("trap")) i = built("Door", 1.0f, 6);
            else if (p.endsWith("_trapdoor")) i = built("Trapdoor", 0.6f, 12);
            else if (p.endsWith("_fence_gate")) i = built("Fence Gate", 0.6f, 8);
            else if (p.endsWith("_fence")) i = built("Fence", 0.15f, 16);
            else if (p.equals("torch") || p.equals("wall_torch") || p.equals("soul_torch") || p.equals("soul_wall_torch")) i = built("Torch", 0.1f, 16);
            else if (p.equals("lantern") || p.equals("soul_lantern") || p.equals("glowstone") || p.equals("sea_lantern") || p.equals("shroomlight")) i = built("Light", 0.5f, 16);
            if (i != null) TABLE.put(b, i);
        }
    }

    private static Info built(String label, float w, int cap) { return new Info(Kind.BUILT, label, w, cap, false, false, null); }
}

