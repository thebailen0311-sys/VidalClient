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
    private static final String USER_AGENT = "Vidal-Updater";

    private static volatile String status = "not checked";
    private static volatile String latestVersion = "";
    private static volatile String source = "";
    private static volatile long lastAttempt = System.currentTimeMillis();

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
        // A dev client runs from build/classes; writing a jar into run/mods would collide with the classpath copy.
        if (runningJar() == null) { status = "dev build - updates off"; return; }
        pruneOlder();
        lastAttempt = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try { check(); } catch (Throwable e) { status = "check failed: " + e; Refract.LOG.debug("update check failed", e); }
        }, "vidal-updater");
        t.setDaemon(true);
        t.start();
    }

    /** Retries a failed or missed check every half hour, in case the network was down at launch. Cheap to call. */
    public static void maybeCheck() {
        if (System.getProperty("refract.noupdate") != null || runningJar() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 1_800_000L) return;
        lastAttempt = now;
        Thread t = new Thread(() -> {
            try { check(); } catch (Throwable e) { status = "check failed: " + e; }
        }, "vidal-updater-retry");
        t.setDaemon(true);
        t.start();
    }

    /** One update record, from either source. */
    private record Release(String version, String title, String notes, String jarUrl, String sha256, String source) {}

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
        // raw.githubusercontent.com caches a file for five minutes and the jar changes with every build, so the
        // manifest checksum could describe bytes the CDN is not serving yet. Fetch repo jars through the contents API.
        String jarUrl = rel.jarUrl;
        boolean apiRaw = rel.source.startsWith("update.json") && jarUrl.startsWith(RAW);
        if (apiRaw) {
            String path = jarUrl.substring(RAW.length());
            jarUrl = "https://api.github.com/repos/" + REPO + "/contents/" + path + "?ref=main";
        }
        HttpURLConnection d = (HttpURLConnection) new URL(jarUrl).openConnection();
        d.setRequestProperty("User-Agent", USER_AGENT);
        if (apiRaw) d.setRequestProperty("Accept", "application/vnd.github.raw");
        d.setConnectTimeout(10000);
        d.setReadTimeout(30000);
        d.setInstanceFollowRedirects(true);
        if (d.getResponseCode() != 200) { status = "download failed: HTTP " + d.getResponseCode(); return; }
        Path tmp = mods.resolve(fileName + ".part");
        try (InputStream in = d.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        }
        if (rel.sha256 != null && !rel.sha256.isBlank()) {
            String actual = sha256(tmp);
            if (!actual.equalsIgnoreCase(rel.sha256.trim())) {
                Files.deleteIfExists(tmp);
                status = "download failed: sha256 mismatch";
                Refract.LOG.warn("Update jar checksum mismatch: expected {}, got {}", rel.sha256, actual);
                return;
            }
        }

        // Fabric loads every jar in mods/, and two jars with the same mod id abort the launch, while Windows will not
        // let a running process rename or delete its own jar. So stage the jar, start a helper that does not load the
        // mod jar, and let it swap the files the moment the game exits.
        Path running = runningJar();
        if (running == null || !Files.isRegularFile(running)) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            status = "downloaded " + rel.version + " -> mods/" + fileName + " (restart to apply)";
            announce(rel, true);
            return;
        }

        Path dir = mods.resolve(".vanta-swap");
        try {
            Files.createDirectories(dir);
            Path helper = dir.resolve("Swap.class");
            try (InputStream in = Updater.class.getResourceAsStream("/com/trace/refract/update/Swap.class")) {
                if (in == null) throw new IllegalStateException("Swap.class missing from jar");
                Files.copy(in, helper, StandardCopyOption.REPLACE_EXISTING);
            }
            Path staged = dir.resolve(fileName);
            Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING);
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(java, "-cp", dir.toAbsolutePath().toString(),
                    "com.trace.refract.update.Swap",
                    running.toAbsolutePath().toString(), staged.toAbsolutePath().toString(),
                    String.valueOf(ProcessHandle.current().pid()), target.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(dir.resolve("swap.log").toFile());
            Process p = pb.start();
            status = "downloaded " + rel.version + "; it is installed when you close the game (swap pid " + p.pid() + ")";
            announce(rel, true);
        } catch (Exception ex) {
            Refract.LOG.warn("Update handover failed: {}", ex.toString());
            try { Files.move(tmp, mods.resolve(fileName + ".pending"), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) { }
            status = "downloaded, but the automatic swap failed: replace the old jar in mods/ by hand";
            announce(rel, false);
        }
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
            return new Release(tag, name, body, pickJar(o), null, "release " + tag);
        } catch (Exception e) {
            return null;
        }
    }

    /** Repository manifest: a push is enough, no release required. */
    private static Release fetchManifest() {
        try {
            // raw.githubusercontent.com is fronted by a CDN that caches for five minutes and ignores query strings,
            // so a brand-new push could stay invisible. The contents API is always the current commit; raw is the
            // fallback for when the API rate limit (60/hour per IP) is exhausted.
            JsonObject o = fetchManifestApi();
            if (o == null || !o.has("version")) o = getJson(MANIFEST + "?t=" + System.currentTimeMillis());
            if (o == null || !o.has("version")) return null;
            String v = o.get("version").getAsString();
            String notes = o.has("notes") && !o.get("notes").isJsonNull() ? o.get("notes").getAsString() : "";
            String jar = o.has("jar") && !o.get("jar").isJsonNull() ? o.get("jar").getAsString() : "";
            String sha = o.has("sha256") && !o.get("sha256").isJsonNull() ? o.get("sha256").getAsString() : null;
            String url = jar.isEmpty() ? null : (jar.startsWith("http") ? jar : RAW + jar);
            return new Release(v, v, notes, url, sha, "update.json");
        } catch (Exception e) {
            return null;
        }
    }

    /** update.json as committed on main, read through the GitHub contents API (never CDN-cached). */
    private static JsonObject fetchManifestApi() {
        try {
            JsonObject root = getJson("https://api.github.com/repos/" + REPO + "/contents/update.json?ref=main");
            if (root == null || !root.has("content")) return null;
            String b64 = root.get("content").getAsString().replaceAll("\\s", "");
            String text = new String(java.util.Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
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

    /** The jar this process was loaded from, or null when running from a classes directory (dev). */
    private static Path runningJar() {
        for (String id : new String[]{"refract", "vanta"}) {
            Optional<ModContainer> c = FabricLoader.getInstance().getModContainer(id);
            if (c.isEmpty()) continue;
            try {
                for (Path p : c.get().getRootPaths()) {
                    if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) return p;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static String sha256(Path p) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Deletes superseded Vidal jars in {@code mods/} so only one version loads. The jar in use stays (Windows locks
     * it); the launch after this one removes it.
     */
    public static void pruneOlder() {
        Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(mods)) return;
        String current = currentVersion();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jar.old")) { Files.deleteIfExists(p); continue; }
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
