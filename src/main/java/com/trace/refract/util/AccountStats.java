package com.trace.refract.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trace.refract.Refract;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Player statistics for the Account panel, from the official DonutSMP API.
 *
 * <p>Key in config/refract-market.json ({@code {"apiKey":"..."}}), made in game with {@code /api}. The API is the
 * one behind api.donutsmp.net: {@code GET /v1/stats/{user}} with {@code Authorization: Bearer <key>}. Results are
 * cached for five minutes, fetched off the game thread, and every failure (missing key, rejected key, offline)
 * becomes a status line in the panel instead of an exception.
 */
public final class AccountStats {
    private static final Path KEY = FabricLoader.getInstance().getConfigDir().resolve("refract-market.json");
    private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("refract-account-cache.json");
    private static final String BASE = "https://api.donutsmp.net/v1";
    private static final long TTL = 300_000L;

    public static volatile String name = "";
    public static volatile String status = "not loaded";
    public static volatile String lastUpdate = "";
    public static volatile long fetchedAt;
    public static volatile boolean loading;
    public static volatile String token = "";

    public static volatile String money = "", playtime = "", shards = "", kills = "", deaths = "";
    public static volatile long killsRaw, deathsRaw;
    public static volatile String mobs = "", placed = "", broken = "", sell = "";

    private AccountStats() {}

    /** Cached last known stats, so the Account page is not blank for the first seconds after launch (or offline). */
    static {
        try {
            if (Files.exists(CACHE)) {
                JsonObject c = JsonParser.parseString(Files.readString(CACHE)).getAsJsonObject();
                name = c.has("name") ? c.get("name").getAsString() : "";
                money = s(c, "money"); playtime = s(c, "playtime"); shards = s(c, "shards");
                kills = s(c, "kills"); deaths = s(c, "deaths"); mobs = s(c, "mobs");
                placed = s(c, "placed"); broken = s(c, "broken");
                killsRaw = c.has("killsRaw") ? c.get("killsRaw").getAsLong() : 0;
                deathsRaw = c.has("deathsRaw") ? c.get("deathsRaw").getAsLong() : 0;
                lastUpdate = c.has("saved") ? c.get("saved").getAsString() : "";
                fetchedAt = c.has("at") ? c.get("at").getAsLong() : 0;
                if (!name.isBlank()) status = "cached · " + lastUpdate;
            }
        } catch (Exception ignored) { }
    }

    private static String s(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "—";
    }

    private static void saveCache() {
        try {
            JsonObject c = new JsonObject();
            c.addProperty("name", name); c.addProperty("money", money); c.addProperty("playtime", playtime);
            c.addProperty("shards", shards); c.addProperty("kills", kills); c.addProperty("deaths", deaths);
            c.addProperty("mobs", mobs); c.addProperty("placed", placed); c.addProperty("broken", broken);
            c.addProperty("killsRaw", killsRaw); c.addProperty("deathsRaw", deathsRaw);
            c.addProperty("saved", lastUpdate); c.addProperty("at", fetchedAt);
            Files.createDirectories(CACHE.getParent());
            Path tmp = CACHE.resolveSibling("refract-account-cache.json.tmp");
            Files.writeString(tmp, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(c));
            Files.move(tmp, CACHE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { Refract.LOG.debug("account cache write failed", e); }
    }

    /** Forces the next {@link #refreshIfStale()} to fetch, even if the cache is still warm. */
    public static void refreshNow() {
        fetchedAt = 0;
        name = "";
        status = "refreshing…";
        refreshIfStale();
    }

    /**
     * Takes an API key from the clipboard and writes it to config/refract-market.json without ever logging it. Keys are
     * made in game with {@code /api}; the clipboard route avoids asking anyone to paste a secret into chat.
     */
    public static String useClipboardKey() {
        try {
            String key = MinecraftClient.getInstance().keyboard.getClipboard();
            key = key == null ? "" : key.trim();
            if (key.length() < 8 || key.length() > 200 || key.chars().anyMatch(Character::isWhitespace)) {
                return "Clipboard does not contain an API key. Copy the key from /api first.";
            }
            JsonObject o = new JsonObject();
            if (Files.exists(KEY)) {
                try {
                    JsonElement old = JsonParser.parseString(Files.readString(KEY));
                    if (old.isJsonObject()) o = old.getAsJsonObject();
                } catch (Exception ignored) { }
            }
            o.addProperty("apiKey", key);
            o.addProperty("note", "Key from /api in game. Kept local; never send it to anyone.");
            Files.createDirectories(KEY.getParent());
            Files.writeString(KEY, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
            refreshNow();
            return "API key saved locally. Refreshing stats…";
        } catch (Exception e) {
            return "Could not save the key (" + e.getClass().getSimpleName() + ").";
        }
    }

    public static void refreshIfStale() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String user = mc.getSession() == null ? "" : mc.getSession().getUsername();
        long now = System.currentTimeMillis();
        if (loading || user.isBlank()) return;
        if (name.equals(user) && now - fetchedAt < TTL) return;
        name = user;
        loading = true;
        Thread t = new Thread(() -> fetch(user), "Vidal account stats");
        t.setDaemon(true);
        t.start();
    }

    private static void fetch(String user) {
        try {
            String key = key();
            if (key == null || key.isBlank()) { status = "No API key (config/refract-market.json)"; return; }
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/stats/" + java.net.URLEncoder.encode(user, java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) { status = "Key rejected · run /api in game"; return; }
            if (res.statusCode() != 200) { status = "API HTTP " + res.statusCode(); return; }
            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonObject r = root.has("result") && root.get("result").isJsonObject() ? root.getAsJsonObject("result") : root;
            money = str(r, "money");
            playtime = str(r, "playtime");
            shards = str(r, "shards");
            kills = str(r, "kills");
            deaths = str(r, "deaths");
            killsRaw = raw(r, "kills");
            deathsRaw = raw(r, "deaths");
            mobs = str(r, "mobs_killed");
            placed = str(r, "placed_blocks");
            broken = str(r, "broken_blocks");
            sell = str(r, "money_made_from_sell");
            lastUpdate = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            status = "live · updated " + lastUpdate;
            fetchedAt = System.currentTimeMillis();
            saveCache();
        } catch (Exception ex) {
            status = "API offline (" + ex.getClass().getSimpleName() + ")";
            Refract.LOG.debug("account stats failed", ex);
        } finally {
            loading = false;
            if (fetchedAt == 0) fetchedAt = System.currentTimeMillis() - TTL + 30_000L; // retry in half a minute
        }
    }

    private static String key() {
        try {
            if (!Files.exists(KEY)) return "";
            JsonElement e = JsonParser.parseString(Files.readString(KEY));
            return e.isJsonObject() && e.getAsJsonObject().has("apiKey") ? e.getAsJsonObject().get("apiKey").getAsString().trim() : "";
        } catch (Exception e) { return ""; }
    }

    /** Kills per death, two decimals; 0 deaths reads as the raw kill count. */
    public static String kd() {
        if (killsRaw <= 0 && deathsRaw <= 0) return "—";
        if (deathsRaw <= 0) return killsRaw + ".00";
        return String.format(Locale.ROOT, "%.2f", killsRaw / (double) deathsRaw);
    }

    private static long raw(JsonObject o, String k) {
        try {
            if (!o.has(k) || o.get(k).isJsonNull()) return 0;
            String v = o.get(k).getAsString().replace(",", "").trim();
            return (long) Double.parseDouble(v);
        } catch (Exception e) { return 0; }
    }

    private static String str(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return "—";
        String v = o.get(k).getAsString();
        try { return compact(Long.parseLong(v.replace(",", "").trim())); } catch (Exception e) { return v; }
    }

    /** 1.2T / 34.5B / 900M / 23.5K / 143d 19h style. */
    public static String compact(long v) {
        if (v >= 1_000_000_000_000L) return String.format(Locale.ROOT, "%.1fT", v / 1e12);
        if (v >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", v / 1e9);
        if (v >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", v / 1e6);
        if (v >= 1_000L) return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        return Long.toString(v);
    }
}
