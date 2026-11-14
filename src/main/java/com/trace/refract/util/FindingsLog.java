package com.trace.refract.util;

import com.trace.refract.Refract;
import com.trace.refract.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Appends every new finding to config/refract-findings.log (time, server, dimension, type, coordinates). */
public final class FindingsLog {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("refract-findings.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FindingsLog() {}

    public static void write(String type, String coords, String details) {
        if (ModuleManager.findingsLog == null || !ModuleManager.findingsLog.isEnabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
        String dim = mc.world != null ? mc.world.getRegistryKey().getValue().getPath() : "?";
        String line = String.format("%s  %-20s %-10s %-16s %-24s %s%n", LocalDateTime.now().format(TIME), server, dim, type, coords, details);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            Refract.LOG.warn("Could not write findings log", e);
        }
    }
}
