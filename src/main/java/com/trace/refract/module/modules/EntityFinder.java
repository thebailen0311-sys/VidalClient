package com.trace.refract.module.modules;

import com.trace.refract.hud.Notifications;
import com.trace.refract.hud.WorldLabels;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.render.Esp;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import com.trace.refract.util.FindingsLog;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Sfx;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.entity.vehicle.HopperMinecartEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.*;

/**
 * Entities players leave at their bases: villagers (trading halls), chest and hopper minecarts, item frames,
 * armor stands, tamed pets, chested donkeys/llamas and name-tagged mobs. Uses the Surface Filter, so animals
 * and frames on griefed land are ignored. Each is boxed with its exact coordinates.
 */
public class EntityFinder extends Module {
    public enum Kind {
        VILLAGER("Villager", 0xFF7CFF9A), STORAGE_CART("Storage cart", 0xFFFFB648), FRAME("Item frame", 0xFFE0C38A),
        STAND("Armor stand", 0xFFB8C0CC), PET("Tamed pet", 0xFFFF6FD8), PACK("Chested mount", 0xFFC8935A), NAMED("Named mob", 0xFF4DE3FF);

        public final String label;
        public final int color;

        Kind(String label, int color) { this.label = label; this.color = color; }
    }

    public final BoolSetting villagers = add(new BoolSetting("Villagers", true));
    public final BoolSetting carts = add(new BoolSetting("Chest & Hopper Carts", true));
    public final BoolSetting frames = add(new BoolSetting("Item Frames", true));
    public final BoolSetting stands = add(new BoolSetting("Armor Stands", true));
    public final BoolSetting pets = add(new BoolSetting("Tamed Pets & Mounts", true));
    public final BoolSetting named = add(new BoolSetting("Named Mobs", true));
    public final BoolSetting labels = add(new BoolSetting("Labels", true));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", false));
    public final BoolSetting alerts = add(new BoolSetting("Toasts", true));

    public record Found(Entity entity, Kind kind) {}

    public final List<Found> found = new ArrayList<>();
    private final Set<UUID> announced = new HashSet<>();
    private int ticks;

    public EntityFinder() { super("Entity Finder", "Underground villagers, storage minecarts, item frames, armor stands, pets and named mobs.", Category.HUNTING); }

    @Override public void onDisable() { found.clear(); }

    @Override public String suffix() { return found.isEmpty() ? null : String.valueOf(found.size()); }

    private Kind classify(Entity e) {
        if (e instanceof PlayerEntity) return null;
        if (e instanceof VillagerEntity) return villagers.on() ? Kind.VILLAGER : null;
        if (e instanceof ChestMinecartEntity || e instanceof HopperMinecartEntity) return carts.on() ? Kind.STORAGE_CART : null;
        if (e instanceof ItemFrameEntity) return frames.on() ? Kind.FRAME : null;
        if (e instanceof ArmorStandEntity) return stands.on() ? Kind.STAND : null;
        if (e instanceof AbstractDonkeyEntity d && d.hasChest()) return pets.on() ? Kind.PACK : null;
        if (e instanceof TameableEntity t && t.isTamed()) return pets.on() ? Kind.PET : null;
        if (e instanceof MobEntity && e.hasCustomName()) return named.on() ? Kind.NAMED : null;
        return null;
    }

    /** Same rule as the chunk scanner: in the Overworld, at or below Max Y and Min Depth under the surface. */
    private boolean underground(Entity e) {
        SurfaceFilter f = ModuleManager.surfaceFilter;
        if (!f.isEnabled() || mc.world.getRegistryKey() != World.OVERWORLD) return true;
        BlockPos p = e.getBlockPos();
        int top = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return p.getY() <= f.maxY.asInt() && p.getY() <= top - 1 - f.depth.asInt();
    }

    @Override public void onTick() {
        if (++ticks % 10 != 0 || mc.world == null) return;
        found.clear();
        Map<Kind, Integer> fresh = new EnumMap<>(Kind.class);
        BlockPos firstFresh = null;
        for (Entity e : mc.world.getEntities()) {
            Kind k = classify(e);
            if (k == null || !underground(e)) continue;
            found.add(new Found(e, k));
            if (announced.add(e.getUuid())) {
                fresh.merge(k, 1, Integer::sum);
                if (firstFresh == null) firstFresh = e.getBlockPos();
            }
        }
        if (!fresh.isEmpty() && firstFresh != null) {
            StringBuilder sb = new StringBuilder();
            fresh.forEach((k, n) -> sb.append(sb.length() > 0 ? ", " : "").append(n).append(' ').append(k.label));
            if (alerts.on()) { Notifications.push("Entity Finder", sb + " at " + Fmt.xyz(firstFresh), 0xFF7CFF9A, 5000); Sfx.alert(); }
            FindingsLog.write("entities", Fmt.xyz(firstFresh), sb.toString());
        }
        if (announced.size() > 5000) announced.clear();
    }

    @Override public void onWorldRender(float tickDelta) {
        Vec3d origin = tracers.on() ? Esp.tracerOrigin() : null;
        for (Found f : found) {
            Entity e = f.entity;
            if (e.isRemoved()) continue;
            var box = Esp.box(e);
            Esp.gradientBox(box, Colors.withAlpha(f.kind.color, 0x3A), Colors.withAlpha(f.kind.color, 0x05), 1.5f, true, 3);
            if (e.squaredDistanceTo(mc.player) < 48 * 48) Esp.corners(box, Colors.withAlpha(f.kind.color, 0xE0), 1.25f, true);
            if (origin != null) Esp.line(origin, box.getCenter(), Colors.withAlpha(f.kind.color, 0x90), 1f, true);
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (!labels.on() || mc.player == null) return;
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        List<Found> near = new ArrayList<>(found);
        near.sort(Comparator.comparingDouble(f -> f.entity.squaredDistanceTo(eye)));
        int n = 0;
        for (Found f : near) {
            Entity e = f.entity;
            Vec3d at = Esp.pos(e);
            double d = eye.distanceTo(at);
            if (n++ >= 16 || d > com.trace.refract.hud.Markers.FAR) {
                com.trace.refract.hud.Markers.draw(at.add(0, e.getHeight() / 2, 0), f.kind.color, f.kind.label + "  " + Fmt.dist(d), 1f);
                continue;
            }
            String title = e.hasCustomName() ? f.kind.label + "  ·  " + e.getName().getString() : f.kind.label;
            WorldLabels.draw(t, at.add(0, e.getHeight() + 0.4, 0), title,
                    Fmt.xyz(e.getBlockPos()) + "  ·  " + Fmt.dist(d), f.kind.color, 1f, sw, sh, false);
        }
    }
}
