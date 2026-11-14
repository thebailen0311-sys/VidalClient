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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Optional live prices for the base value estimate.
 *
 * <p>The public DonutSMP market API (api.donutsmp.design) needs a free key, made in game with {@code /api key create}.
 * Put it in config/refract-market.json as {@code {"apiKey":"..."}}. With a key the client refreshes
 * config/refract-prices-live.json every ten minutes from the real market, and Base Finder prices every block it can
 * name from that file. Without a key nothing network happens and the hand-edited refract-prices.json is used, which
 * is what you want if you disagree with the live numbers anyway.
 */
public final class MarketPrices {
    private static final Path KEY = FabricLoader.getInstance().getConfigDir().resolve("refract-market.json");
    private static final Path OUT = FabricLoader.getInstance().getConfigDir().resolve("refract-prices-live.json");
    private static final String ENDPOINT = "https://api.donutsmp.design/v1/market/items?limit=200";
    private static volatile long last;
    private static volatile String status = "not checked";

    private MarketPrices() {}

    public static String status() { return status; }

    /** Kicks one background refresh if the last one is older than ten minutes. Never blocks the game thread. */
    public static void maybeSync() {
        long now = System.currentTimeMillis();
        if (now - last < 600_000L) return;
        last = now;
        Thread t = new Thread(MarketPrices::sync, "Vanta market prices");
        t.setDaemon(true);
        t.start();
    }

    private static void sync() {
        try {
            if (!Files.exists(KEY)) {
                JsonObject o = new JsonObject();
                o.addProperty("apiKey", "");
                o.addProperty("note", "Free key from /api key create in game on DonutSMP. Leave empty to use config/refract-prices.json only.");
                Files.writeString(KEY, new GsonBuilder().setPrettyPrinting().create().toJson(o));
                status = "no key";
                return;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(KEY));
            String key = parsed.isJsonObject() && parsed.getAsJsonObject().has("apiKey")
                    ? parsed.getAsJsonObject().get("apiKey").getAsString().trim() : "";
            if (key.isEmpty()) { status = "no key"; return; }

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .header("X-API-Key", key)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) { status = "http " + res.statusCode(); return; }

            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : new JsonArray();
            JsonObject out = new JsonObject();
            for (JsonElement el : data) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("name") || !o.has("price") || !o.get("price").isJsonPrimitive()) continue;
                try { out.addProperty(o.get("name").getAsString(), o.get("price").getAsDouble()); } catch (Exception ignored) { }
            }
            Files.writeString(OUT, new GsonBuilder().setPrettyPrinting().create().toJson(out));
            status = out.size() + " live prices";
        } catch (Exception ex) {
            status = "failed: " + ex.getClass().getSimpleName();
        }
    }
}
