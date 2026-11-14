package com.trace.refract.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Diagnostic trail of everything this client sends to the server, tagged with the module that sent it, the tick,
 * whether the look direction moved in that tick and how far away the target was. Written to
 * {@code <game dir>/refract-net.log} so it can be lined up with the server's own anticheat output by timestamp.
 *
 * <p>Off unless {@code -Drefract.netlog} is set, so normal play pays nothing.
 */
public final class NetLog {
    private static final boolean ON = System.getProperty("refract.netlog") != null;
    private static BufferedWriter out;
    private static boolean failed;
    private static String module = "-";
    private static float lastYaw = Float.NaN, lastPitch = Float.NaN;

    private NetLog() {}

    public static void setModule(String name) { module = name == null ? "-" : name; }

    /** Magic-mouse style timestamp: milliseconds since start, so it lines up with a server log tail. */
    public static void record(String action, String detail) {
        if (!ON) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) return;
            float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
            float dy = Float.isNaN(lastYaw) ? 0 : MathHelper.wrapDegrees(yaw - lastYaw);
            float dp = Float.isNaN(lastPitch) ? 0 : pitch - lastPitch;
            lastYaw = yaw;
            lastPitch = pitch;
            if (out == null) {
                Path p = FabricLoader.getInstance().getGameDir().resolve("refract-net.log");
                out = Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write(String.format(java.util.Locale.ROOT,
                    "%d tick=%d module=%s action=%s yaw=%.1f pitch=%.1f dyaw=%.1f dpitch=%.1f %s%n",
                    System.currentTimeMillis(), mc.player.age, module, action, yaw, pitch, dy, dp, detail));
            out.flush();
        } catch (Throwable t) {
            if (!failed) { failed = true; com.trace.refract.Refract.LOG.warn("net log disabled: {}", t.toString()); }
        }
    }
}
