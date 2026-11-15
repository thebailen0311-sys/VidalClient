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
    private static final String BASE = "https://api.donutsmp.net/v1";
    private static final long TTL = 300_000L;

    public static volatile String name = "";
    public static volatile String status = "not loaded";
    public static volatile long fetchedAt;
    public static volatile boolean loading;
    public static volatile String token = "";

    public static volatile String money = "", playtime = "", shards = "", kills = "", deaths = "";
    public static volatile String mobs = "", placed = "", broken = "", sell = "";

    private AccountStats() {}

    public static void refreshIfStale() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String user = mc.getSession() == null ? "" : mc.getSession().getUsername();
        long now = System.currentTimeMillis();
        if (loading || user.isBlank()) return;
        if (name.equals(user) && now - fetchedAt < TTL) return;
        name = user;
        loading = true;
        Thread t = new Thread(() -> fetch(user), "Vanta account stats");
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
            mobs = str(r, "mobs_killed");
            placed = str(r, "placed_blocks");
            broken = str(r, "broken_blocks");
            sell = str(r, "money_made_from_sell");
            status = "live · " + user;
            fetchedAt = System.currentTimeMillis();
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
