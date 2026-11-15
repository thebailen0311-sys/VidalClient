package com.trace.refract.util;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Live floor prices from the official DonutSMP auction API.
 *
 * <p>Key in {@code config/refract-market.json} as {@code {"apiKey":"..."}}, made in game with {@code /api}. The sync
 * reads the cheapest current listings (four pages, sorted low to high) and recent completed transactions (three
 * pages) and writes per-item <b>unit</b> prices to {@code config/refract-prices-live.json}, which Base Value merges
 * with the scanner's block labels. Everything is cached for ten minutes, fetched off the game thread, and failures
 * back off instead of hammering the API. Without a key nothing leaves the game.
 */
public final class MarketPrices {
    private static final Path KEY = FabricLoader.getInstance().getConfigDir().resolve("refract-market.json");
    private static final Path OUT = FabricLoader.getInstance().getConfigDir().resolve("refract-prices-live.json");
    private static final String BASE = "https://api.donutsmp.net/v1";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static volatile long nextTry;
    private static volatile String status = "not checked";
    private static volatile String updated = "";
    private static volatile int entries;

    private MarketPrices() {}

    public static String status() { return status; }

    public static String updated() { return updated; }

    public static int entries() { return entries; }

    /** Kicks one background refresh when the cache is due. Never blocks the game thread. */
    public static void maybeSync() {
        long now = System.currentTimeMillis();
        if (now < nextTry) return;
        nextTry = now + 600_000L; // success or a hard failure both wait ten minutes
        Thread t = new Thread(MarketPrices::sync, "Vidal market prices");
        t.setDaemon(true);
        t.start();
    }

    private static void sync() {
        try {
            if (!Files.exists(KEY)) {
                JsonObject o = new JsonObject();
                o.addProperty("apiKey", "");
                o.addProperty("note", "Key from /api in game on DonutSMP. Or copy it and use the Account page's key button.");
                Files.writeString(KEY, new GsonBuilder().setPrettyPrinting().create().toJson(o));
                status = "no key";
                return;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(KEY));
            String key = parsed.isJsonObject() && parsed.getAsJsonObject().has("apiKey")
                    ? parsed.getAsJsonObject().get("apiKey").getAsString().trim() : "";
            if (key.isEmpty()) { status = "no key"; return; }

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            Map<String, Double> prices = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            boolean unauthorized = false;

            // Cheapest current asks, per page. These are the most useful number for a base's liquidation value.
            for (int page = 1; page <= 4 && !unauthorized; page++) {
                JsonArray rows = get(http, key, BASE + "/auction/list/" + page, true);
                if (rows == null) { unauthorized = lastUnauthorized; continue; }
                collect(rows, prices, true);
            }
            // Completed sales fill in items nobody is currently listing.
            if (!unauthorized) {
                for (int page = 1; page <= 3; page++) {
                    JsonArray rows = get(http, key, BASE + "/auction/transactions/" + page, false);
                    if (rows == null) { unauthorized = lastUnauthorized; break; }
                    collect(rows, prices, false);
                }
            }
            if (unauthorized) { status = "key rejected · /api in game"; nextTry = System.currentTimeMillis() + 300_000L; return; }
            if (prices.isEmpty()) { status = "API returned no prices"; nextTry = System.currentTimeMillis() + 120_000L; return; }

            JsonObject out = new JsonObject();
            for (var e : prices.entrySet()) {
                double v = e.getValue();
                if (Double.isFinite(v) && v > 0 && v < 1e12) out.addProperty(e.getKey(), Math.round(v * 100.0) / 100.0);
            }
            Files.createDirectories(OUT.getParent());
            Path tmp = OUT.resolveSibling("refract-prices-live.json.tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(out));
            try {
                Files.move(tmp, OUT, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, OUT, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            entries = out.size();
            updated = LocalTime.now().format(TIME);
            status = entries + " live prices · " + updated;
        } catch (Exception ex) {
            status = "market failed (" + ex.getClass().getSimpleName() + ")";
            nextTry = System.currentTimeMillis() + 90_000L;
        }
    }

    private static volatile boolean lastUnauthorized;

    private static JsonArray get(HttpClient http, String key, String url, boolean withBody) {
        lastUnauthorized = false;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(12));
            if (withBody) {
                b.header("Content-Type", "application/json")
                        .method("GET", HttpRequest.BodyPublishers.ofString("{\"sort\":\"lowest_price\"}", StandardCharsets.UTF_8));
            } else {
                b.GET();
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) { lastUnauthorized = true; return null; }
            if (res.statusCode() != 200) { status = "API HTTP " + res.statusCode(); return null; }
            JsonElement root = JsonParser.parseString(res.body());
            if (!root.isJsonObject()) return null;
            JsonElement result = root.getAsJsonObject().get("result");
            if (result == null) return null;
            if (result.isJsonArray()) return result.getAsJsonArray();
            // Some deployments wrap the list in result.items / result.auctions.
            if (result.isJsonObject()) {
                for (String k : new String[]{"items", "auctions", "listings", "transactions"}) {
                    JsonElement a = result.getAsJsonObject().get(k);
                    if (a != null && a.isJsonArray()) return a.getAsJsonArray();
                }
            }
            return null;
        } catch (Exception e) {
            status = "market " + e.getClass().getSimpleName();
            return null;
        }
    }

    /** Adds the cheapest per-unit price from each listing; sold-only mode does not overwrite a live ask. */
    private static void collect(JsonArray rows, Map<String, Double> prices, boolean asks) {
        for (JsonElement el : rows) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("price") || !o.get("price").isJsonPrimitive()) continue;
            double price;
            try { price = o.get("price").getAsDouble(); } catch (Exception e) { continue; }
            JsonObject item = o.has("item") && o.get("item").isJsonObject() ? o.getAsJsonObject("item") : o;
            int count = item.has("count") && item.get("count").isJsonPrimitive() ? Math.max(1, item.get("count").getAsInt()) : 1;
            double unit = price / count;
            if (!Double.isFinite(unit) || unit <= 0) continue;
            String name = name(item);
            if (name.isEmpty()) continue;
            if (asks || !prices.containsKey(name)) prices.merge(name, unit, Math::min);
        }
    }

    private static String name(JsonObject item) {
        String display = str(item, "display_name");
        if (display.isEmpty()) display = str(item, "displayName");
        if (display.isEmpty()) display = str(item, "name");
        if (!display.isEmpty()) return clean(display);
        String id = str(item, "id");
        if (id.isEmpty()) return "";
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);
        String[] parts = id.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Removes Minecraft colour codes and collapses whitespace. */
    private static String clean(String s) {
        String out = s.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
        return out.length() > 64 ? out.substring(0, 64) : out;
    }
}
