package com.trace.refract.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trace.refract.Refract;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Self updater. Two sources, tried in order:
 * <ol>
 *   <li>the newest GitHub <em>release</em> of {@link #REPO} (jar attached as an asset);</li>
 *   <li>the repository's {@code update.json} on {@code main} — {@code {"version","notes","jar"}}, where {@code jar}
 *       is a path inside the repo or a full URL. This means a plain push publishes an update, no release needed.</li>
 * </ol>
 *
 * <p>Nothing is replaced while the game runs: a loaded jar cannot be deleted on Windows. The new jar is written next
 * to the old one and {@link #pruneOlder()} removes superseded versions on the next launch. So: alert now, restart
 * once, and the new build is the only one loaded.
 *
 * <p>Disabled with {@code -Drefract.noupdate}; failures become a status string, never an exception in game.
 */
public final class Updater {
    public static final String REPO = "thebailen0311-sys/VidalClient";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RAW = "https://raw.githubusercontent.com/" + REPO + "/main/";
    private static final String MANIFEST = RAW + "update.json";
    private static final String USER_AGENT = "Vanta-Updater";

    private static volatile String status = "not checked";
    private static volatile String latestVersion = "";
    private static volatile String source = "";

    private Updater() {}

    public static String status() { return status; }

    public static String latestVersion() { return latestVersion; }

    public static String source() { return source; }

    /** The version this process is running. */
    public static String currentVersion() {
        for (String id : new String[]{"refract", "vanta"}) {
            Optional<ModContainer> c = FabricLoader.getInstance().getModContainer(id);
            if (c.isPresent()) return c.get().getMetadata().getVersion().getFriendlyString();
        }
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            String n = c.getMetadata().getName().toLowerCase(Locale.ROOT);
            if (n.equals("vanta") || n.equals("refract")) return c.getMetadata().getVersion().getFriendlyString();
        }
        return "0.0.0";
    }

    /** Called once on client start. Silent and cheap when disabled. */
    public static void init() {
        if (System.getProperty("refract.noupdate") != null) { status = "disabled"; return; }
        pruneOlder();
        Thread t = new Thread(() -> {
            try { check(); } catch (Throwable e) { status = "check failed: " + e; Refract.LOG.debug("update check failed", e); }
        }, "vanta-updater");
        t.setDaemon(true);
        t.start();
    }

    /** One update record, from either source. */
    private record Release(String version, String title, String notes, String jarUrl, String source) {}

    /** Blocking check; call from a worker thread. */
    public static void check() throws Exception {
        String current = currentVersion();
        Release rel = fetchRelease();
        if (rel == null) rel = fetchManifest();
        if (rel == null) { status = "no update source reachable"; return; }
        source = rel.source;
        latestVersion = rel.version;

        if (!isNewer(rel.version, current)) { status = "up to date (" + current + ") via " + rel.source; return; }
        if (rel.jarUrl == null) { status = "newer version " + rel.version + " has no jar"; announce(rel, false); return; }

        Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
        Files.createDirectories(mods);
        String fileName = "vanta-" + sanitize(rel.version) + ".jar";
        Path target = mods.resolve(fileName);
        HttpURLConnection d = (HttpURLConnection) new URL(rel.jarUrl).openConnection();
        d.setRequestProperty("User-Agent", USER_AGENT);
        d.setConnectTimeout(10000);
        d.setReadTimeout(30000);
        d.setInstanceFollowRedirects(true);
        if (d.getResponseCode() != 200) { status = "download failed: HTTP " + d.getResponseCode(); return; }
        Path tmp = mods.resolve(fileName + ".part");
        try (InputStream in = d.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        status = "downloaded " + rel.version + " -> mods/" + fileName + " (restart to apply)";
        announce(rel, true);
    }

    /** GitHub release, or null when the repo has none / is unreachable. */
    private static Release fetchRelease() {
        try {
            JsonObject o = getJson(API);
            if (o == null) return null;
            String tag = o.has("tag_name") ? o.get("tag_name").getAsString() : "";
            if (tag.isEmpty()) return null;
            String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : tag;
            String body = o.has("body") && !o.get("body").isJsonNull() ? o.get("body").getAsString() : "";
            return new Release(tag, name, body, pickJar(o), "release " + tag);
        } catch (Exception e) {
            return null;
        }
    }

    /** Repository manifest: a push is enough, no release required. */
    private static Release fetchManifest() {
        try {
            JsonObject o = getJson(MANIFEST);
            if (o == null || !o.has("version")) return null;
            String v = o.get("version").getAsString();
            String notes = o.has("notes") && !o.get("notes").isJsonNull() ? o.get("notes").getAsString() : "";
            String jar = o.has("jar") && !o.get("jar").isJsonNull() ? o.get("jar").getAsString() : "";
            String url = jar.isEmpty() ? null : (jar.startsWith("http") ? jar : RAW + jar);
            return new Release(v, v, notes, url, "update.json");
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject getJson(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Chat summary of the release: version arrow, title, then the notes lines. */
    private static void announce(Release rel, boolean downloaded) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        String headline = (downloaded ? "§bUpdate ready§7 " : "§bUpdate available§7 ")
                + currentVersion() + " §8-> §f" + rel.version
                + (rel.title == null || rel.title.isBlank() || rel.title.equals(rel.version) ? "" : " §7· " + rel.title);
        String notes = summarize(rel.notes);
        mc.execute(() -> {
            Refract.chat(headline);
            if (!notes.isEmpty()) Refract.chat("§7" + notes);
            if (downloaded) Refract.chat("§7Restart the game to finish updating.");
        });
    }

    /** First few meaningful lines of the notes, flattened and clipped. */
    public static String summarize(String body) {
        if (body == null || body.isBlank()) return "";
        List<String> lines = new ArrayList<>();
        for (String raw : body.split("\\R")) {
            String l = raw.replaceAll("^[#>*\\-\\s]+", "").trim();
            if (l.isEmpty()) continue;
            lines.add(l);
            if (lines.size() >= 3) break;
        }
        String s = String.join(" · ", lines);
        return s.length() > 220 ? s.substring(0, 217) + "..." : s;
    }

    private static String pickJar(JsonObject release) {
        if (!release.has("assets")) return null;
        JsonArray assets = release.getAsJsonArray("assets");
        String fallback = null;
        for (var el : assets) {
            JsonObject a = el.getAsJsonObject();
            String n = a.has("name") ? a.get("name").getAsString() : "";
            String u = a.has("browser_download_url") ? a.get("browser_download_url").getAsString() : null;
            if (u == null || !n.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            String lower = n.toLowerCase(Locale.ROOT);
            if (lower.contains("vanta") || lower.contains("refract")) return u;
            if (fallback == null) fallback = u;
        }
        return fallback;
    }

    /** Dotted numeric compare; non-numeric tags compare as "different means newer". */
    public static boolean isNewer(String remote, String local) {
        int[] r = parse(remote), l = parse(local);
        if (r == null || l == null) return !remote.equalsIgnoreCase(local);
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int a = i < r.length ? r[i] : 0, b = i < l.length ? l[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] parse(String v) {
        String s = v.replaceAll("^[vV]", "").replaceAll("[^0-9.].*$", "");
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) { return null; }
        return out;
    }

    private static String sanitize(String tag) { return tag.replaceAll("[^A-Za-z0-9._-]", "_"); }

    /**
     * Deletes superseded Vanta jars in {@code mods/} so only one version loads. The jar in use stays (Windows locks
     * it); the launch after this one removes it.
     */
    public static void pruneOlder() {
        Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(mods)) return;
        String current = currentVersion();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(name.startsWith("vanta-") || name.startsWith("refract-"))) continue;
                if (name.contains(current.toLowerCase(Locale.ROOT))) continue;
                try {
                    Files.deleteIfExists(p);
                    Refract.LOG.info("Updater removed old jar {}", p.getFileName());
                } catch (Exception ignored) {
                    // in use by this process; the next launch removes it
                }
            }
        } catch (Exception e) {
            Refract.LOG.debug("Updater prune failed", e);
        }
    }
}
