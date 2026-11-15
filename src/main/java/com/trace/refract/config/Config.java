package com.trace.refract.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trace.refract.Refract;
import com.trace.refract.gui.ClickGuiScreen;
import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Saves modules, settings, binds and panel layout to config/refract.json. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("refract.json");
    private static final Path BACKUP = FabricLoader.getInstance().getConfigDir().resolve("refract.json.bak");

    private Config() {}

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject mods = new JsonObject();
            for (Module m : ModuleManager.MODULES) {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", m.isEnabled());
                o.addProperty("key", m.key);
                o.addProperty("hold", m.hold);
                JsonObject s = new JsonObject();
                for (Setting<?> set : m.settings) s.add(set.name, set.save());
                o.add("settings", s);
                mods.add(m.name, o);
            }
            root.add("modules", mods);
            root.add("gui", ClickGuiScreen.saveState());
            root.add("hud", com.trace.refract.hud.HudLayout.save());
            Files.createDirectories(FILE.getParent());
            String json = GSON.toJson(root);
            // Write beside the real file, keep the last good copy, then swap atomically. A crash or a closed laptop
            // mid-save can no longer leave a half-written refract.json behind.
            Path tmp = FILE.resolveSibling("refract.json.tmp");
            Files.writeString(tmp, json);
            if (Files.exists(FILE)) Files.copy(FILE, BACKUP, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Refract.LOG.error("Could not save Vidal config", e);
        }
    }

    public static void load() {
        if (!Files.exists(FILE) && !Files.exists(BACKUP)) return;
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
        } catch (Exception primary) {
            Refract.LOG.warn("refract.json could not be read ({}), trying the backup", primary.toString());
            try {
                root = JsonParser.parseString(Files.readString(BACKUP)).getAsJsonObject();
                Refract.chat("§eConfig was damaged; the last backup was loaded.");
            } catch (Exception backup) {
                Refract.LOG.error("Could not load Vidal config or its backup", backup);
                return;
            }
        }
        try {
            if (root.has("modules")) {
                JsonObject mods = root.getAsJsonObject("modules");
                for (Module m : ModuleManager.MODULES) {
                    if (!mods.has(m.name)) continue;
                    JsonObject o = mods.getAsJsonObject(m.name);
                    if (o.has("key")) m.key = o.get("key").getAsInt();
                    if (o.has("hold")) m.hold = o.get("hold").getAsBoolean();
                    if (o.has("settings")) {
                        JsonObject s = o.getAsJsonObject("settings");
                        for (Setting<?> set : m.settings) {
                            JsonElement e = s.get(set.name);
                            if (e == null) continue;
                            // One setting written by an older build must not abort the rest of the config.
                            try { set.load(e); } catch (Exception bad) { Refract.LOG.warn("Skipping bad setting {}.{}: {}", m.name, set.name, bad.toString()); }
                        }
                    }
                    // Freecam and Zoom always start off; the GUI module is never "on".
                    boolean persist = m != ModuleManager.freecam && m != ModuleManager.zoom && m != ModuleManager.clickGui;
                    if (persist && o.has("enabled")) m.setEnabled(o.get("enabled").getAsBoolean(), false);
                }
            }
            if (root.has("gui")) ClickGuiScreen.loadState(root.getAsJsonObject("gui"));
            if (root.has("hud")) com.trace.refract.hud.HudLayout.load(root.getAsJsonObject("hud"));
        } catch (Exception e) {
            Refract.LOG.error("Could not load Refract config", e);
        }
    }
}
