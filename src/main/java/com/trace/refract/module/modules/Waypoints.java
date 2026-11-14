package com.trace.refract.module.modules;

import com.google.gson.*;
import com.trace.refract.Refract;
import com.trace.refract.hud.WorldLabels;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Fmt;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Waypoints with a light beam and distance label, saved per server and dimension in
 * config/refract-waypoints.json. Bases are only added when "Auto-Add Bases" is on (off by default), and
 * "Clear Saved" forgets every spot saved for the current server and dimension.
 */
public class Waypoints extends Module {
    public final BoolSetting autoBases = add(new BoolSetting("Auto-Add Bases", false));
    /** Acts as a button: switching it on deletes every saved waypoint on this server and dimension. */
    public final BoolSetting clearSaved = add(new BoolSetting("Clear Saved (this world)", false));
    public final BoolSetting beams = add(new BoolSetting("Beams", true));
    public final BoolSetting removeOnArrival = add(new BoolSetting("Remove On Arrival", false));

    public record Point(String server, String dim, String name, int x, int y, int z, int color) {
        public BlockPos pos() { return new BlockPos(x, y, z); }
    }

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("refract-waypoints.json");
    private final List<Point> points = new ArrayList<>();
    private boolean loaded;

    public Waypoints() {
        super("Waypoints", "Beams and distance labels on saved spots. Turn on Auto-Add Bases to save finds.", Category.RENDER);
        setEnabled(true, false);
    }

    private static String server() {
        var e = mc.getCurrentServerEntry();
        return e != null ? e.address : "singleplayer";
    }

    private static String dim() { return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString(); }

    public List<Point> here() {
        ensureLoaded();
        String s = server(), d = dim();
        return points.stream().filter(p -> p.server.equals(s) && p.dim.equals(d)).toList();
    }

    /** Adds a waypoint unless one already exists within 16 blocks. */
    public void add(String name, BlockPos pos, int color) {
        ensureLoaded();
        for (Point p : here()) if (p.pos().getSquaredDistance(pos) < 256) return;
        points.add(new Point(server(), dim(), name, pos.getX(), pos.getY(), pos.getZ(), color));
        save();
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            for (JsonElement e : JsonParser.parseString(Files.readString(FILE)).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                points.add(new Point(o.get("server").getAsString(), o.get("dim").getAsString(), o.get("name").getAsString(),
                        o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("color").getAsInt()));
            }
        } catch (Exception ex) {
            Refract.LOG.warn("Could not read waypoints", ex);
        }
    }

    private void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Point p : points) {
                JsonObject o = new JsonObject();
                o.addProperty("server", p.server); o.addProperty("dim", p.dim); o.addProperty("name", p.name);
                o.addProperty("x", p.x); o.addProperty("y", p.y); o.addProperty("z", p.z); o.addProperty("color", p.color);
                arr.add(o);
            }
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(arr));
        } catch (Exception ex) {
            Refract.LOG.warn("Could not save waypoints", ex);
        }
    }

    @Override public String suffix() { return mc.world == null ? null : String.valueOf(here().size()); }

    @Override public void onAlwaysTick() {
        if (!clearSaved.on()) return;
        clearSaved.set(false);
        if (mc.world == null) return;
        ensureLoaded();
        List<Point> mine = here();
        points.removeAll(mine);
        save();
        com.trace.refract.hud.Notifications.push("Waypoints", "Cleared " + mine.size() + " saved waypoints", 0xFF34D399, 3000);
    }

    /** Removes every saved waypoint (all servers). */
    public void clearAll() { ensureLoaded(); points.clear(); save(); }

    @Override public void onTick() {
        if (!removeOnArrival.on() || mc.player == null) return;
        BlockPos me = mc.player.getBlockPos();
        List<Point> hit = here().stream().filter(p -> p.pos().getSquaredDistance(me) < 16).toList();
        if (!hit.isEmpty()) { points.removeAll(hit); save(); }
    }

    @Override public void onWorldRender(float tickDelta) {
        if (!beams.on() || mc.world == null) return;
        int bottom = mc.world.getBottomY(), top = bottom + mc.world.getHeight();
        for (Point p : here()) {
            Esp.box(new Box(p.x + 0.4, bottom, p.z + 0.4, p.x + 0.6, top, p.z + 0.6), Colors.withAlpha(p.color, 0x40), Colors.withAlpha(p.color, 0x90), 1f, true);
            Esp.block(p.pos(), 0, Colors.withAlpha(p.color, 0x30), Colors.withAlpha(p.color, 0xF0), 2f, true);
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (mc.player == null) return;
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        for (Point p : here()) {
            Vec3d c = p.pos().toCenterPos();
            WorldLabels.draw(t, c.add(0, 1.2, 0), p.name, Fmt.xyz(p.pos()) + "  ·  " + Fmt.dist(eye.distanceTo(c)), p.color, 1f, sw, sh, true);
        }
    }
}
