package com.trace.refract.scan;

import java.util.*;

/**
 * Rough DonutSMP value of a base if every block Base Finder can see were mined and sold.
 *
 * <p>Prices are in-game dollars, editable in config/refract-prices.json. Defaults come from the community trackers, checked November 2026:
 * donutstats.io (live asking prices and buy orders), donut.auction, donutsmpstats.net (spawner votes) and
 * donutsmpterminal.com. Where the trackers disagree, the figure is the middle of the recent ones, and the
 * sources are noted per line below. Items the trackers do not list are marked "est." and priced from what they
 * are crafted from.
 *
 * <p>This is a <b>floor</b>, not a total: no Minecraft client is ever sent what is inside chests, shulker boxes
 * or barrels, so their contents (usually most of a stash's worth) cannot be counted. Shulker boxes are priced
 * empty.
 */
public final class BaseValue {
    private BaseValue() {}

    /** Price per block, keyed by the labels the scanner records. */
    private static final Map<String, Double> PRICE = new HashMap<>();

    private static final java.nio.file.Path FILE = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("refract-prices.json");
    private static long loadedStamp = -1, lastCheck;

    /** Defaults: middle of the November 2026 tracker readings (donutstats.io, donutsmpterminal.com, donut.auction, donutsmpstats.net). */
    private static Map<String, Double> defaults() {
        Map<String, Double> d = new LinkedHashMap<>();
        d.put("Netherite Block", 41_800_000.0);
        d.put("Spawner", 1_330_000.0);
        d.put("Conduit", 1_200_000.0);
        d.put("Beacon", 305_000.0);
        d.put("Diamond Block", 75_000.0);
        d.put("Gold Block", 44_000.0);
        d.put("Anvil", 36_000.0);
        d.put("Shulker Box", 31_000.0);
        d.put("Emerald Block", 29_000.0);
        d.put("Enchanting Table", 16_000.0);
        d.put("Ender Chest", 15_000.0);
        d.put("Iron Block", 9_900.0);
        d.put("Respawn Anchor", 8_000.0);
        d.put("Sticky Piston", 6_151.0);
        d.put("Hopper", 4_500.0);
        d.put("Piston", 3_899.0);
        d.put("Comparator", 3_303.0);
        d.put("Observer", 3_040.0);
        d.put("Lodestone", 3_000.0);
        d.put("Crafter", 2_500.0);
        d.put("Repeater", 1_765.0);
        d.put("Chest", 1_500.0);
        d.put("Trapped Chest", 1_800.0);
        d.put("Barrel", 1_200.0);
        d.put("Copper Chest", 1_500.0);
        d.put("TNT", 1_500.0);
        d.put("Brewing Stand", 1_000.0);
        d.put("Dropper/Dispenser", 800.0);
        d.put("Redstone Dust", 668.0);
        d.put("Obsidian", 300.0);
        d.put("Bed", 300.0);
        d.put("Redstone Torch", 200.0);
        d.put("Furnace", 50.0);
        d.put("Crafting Table", 20.0);
        return d;
    }

    /**
     * Prices live in config/refract-prices.json (written with the defaults the first time). Edit any number there
     * while playing: the file is re-read within a few seconds of being saved.
     */
    private static void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (loadedStamp >= 0 && now - lastCheck < 3000) return;
        lastCheck = now;
        try {
            if (!java.nio.file.Files.exists(FILE)) {
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                defaults().forEach(o::addProperty);
                java.nio.file.Files.writeString(FILE, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
            }
            long stamp = java.nio.file.Files.getLastModifiedTime(FILE).toMillis();
            if (stamp == loadedStamp) return;
            Map<String, Double> m = defaults();
            var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(FILE)).getAsJsonObject();
            for (var e : o.entrySet()) if (e.getValue().isJsonPrimitive()) m.put(e.getKey(), e.getValue().getAsDouble());
            PRICE.clear();
            PRICE.putAll(m);
            loadedStamp = stamp;
        } catch (Exception ex) {
            if (PRICE.isEmpty()) PRICE.putAll(defaults());
            loadedStamp = 0;
        }
    }

    /** Value estimate with its biggest contributors (label, count, subtotal), largest first. */
    public record Estimate(double total, List<Line> lines) {}

    public record Line(String label, int count, double value) {}

    public static Estimate of(Map<String, Integer> found) {
        ensureLoaded();
        List<Line> lines = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            Double p = PRICE.get(e.getKey());
            if (p == null || e.getValue() <= 0) continue;
            double v = p * e.getValue();
            total += v;
            lines.add(new Line(e.getKey(), e.getValue(), v));
        }
        lines.sort(Comparator.comparingDouble(Line::value).reversed());
        return new Estimate(total, lines);
    }

    /** "$1.2B", "$34.5M", "$900K", "$420". */
    public static String money(double v) {
        if (v >= 1e9) return String.format(Locale.ROOT, "$%.1fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.ROOT, "$%.1fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.ROOT, "$%.0fK", v / 1e3);
        return String.format(Locale.ROOT, "$%.0f", v);
    }
}
