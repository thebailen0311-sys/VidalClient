package com.trace.refract.render;

import com.trace.refract.util.Colors;
import com.trace.refract.util.Time;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoCollectorImpl;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.minecraft.world.debug.gizmo.VisibilityConfigurable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 3D ESP built on the game's own gizmo renderer: boxes and lines are batched and drawn on the GPU in the
 * world pass, optionally on top of terrain (through walls). Shapes are re-emitted every frame from
 * WorldRendererMixin, and the previous frame's entries are removed so nothing is ever drawn twice.
 *
 * <p>Everything here is vertical-gradient aware: {@link #gradientBox} stacks a few thin slabs so a base or a
 * player box fades from a bright top into a transparent floor, and {@link #corners} draws corner brackets
 * instead of a full wireframe, which reads far cleaner at a distance and costs far less to look at.
 */
public final class Esp {
    private static final Set<VisibilityConfigurable> LAST = Collections.newSetFromMap(new IdentityHashMap<>());
    private static int emitted;
    private static float tickDelta = 1f;

    private Esp() {}

    /** Partial tick of the frame being drawn (set by WorldRendererMixin before modules render). */
    public static void setTickDelta(float td) { tickDelta = td; }

    public static float tickDelta() { return tickDelta; }

    /** Where an entity is drawn this frame (between its last and current tick positions), so boxes glide. */
    public static Vec3d pos(net.minecraft.entity.Entity e) { return e.getLerpedPos(tickDelta); }

    /** An entity's bounding box at its interpolated position for this frame. */
    public static Box box(net.minecraft.entity.Entity e) { return e.getBoundingBox().offset(pos(e).subtract(e.getEntityPos())); }

    /** Called at the start of each world render with the world renderer's gizmo collector. */
    public static void beginFrame(GizmoCollectorImpl collector) {
        if (!LAST.isEmpty()) {
            List<GizmoCollectorImpl.Entry> list = collector.getGizmos();
            list.removeIf(LAST::contains);
            LAST.clear();
        }
        emitted = 0;
    }

    public static int emitted() { return emitted; }

    private static void track(VisibilityConfigurable v, boolean throughWalls) {
        if (throughWalls) v.ignoreOcclusion();
        LAST.add(v);
        emitted++;
    }

    public static void box(Box b, int fill, int stroke, float width, boolean throughWalls) {
        if (Colors.a(fill) == 0 && Colors.a(stroke) == 0) return;
        DrawStyle style;
        if (Colors.a(fill) == 0) style = DrawStyle.stroked(stroke, width);
        else if (Colors.a(stroke) == 0) style = DrawStyle.filled(fill);
        else style = DrawStyle.filledAndStroked(stroke, width, fill);
        track(GizmoDrawing.box(b, style), throughWalls);
    }

    public static void block(BlockPos pos, double inset, int fill, int stroke, float width, boolean throughWalls) {
        box(new Box(pos).contract(inset), fill, stroke, width, throughWalls);
    }

    public static void line(Vec3d a, Vec3d b, int color, float width, boolean throughWalls) {
        if (Colors.a(color) == 0) return;
        track(GizmoDrawing.line(a, b, color, width), throughWalls);
    }

    /**
     * A box whose fill fades from {@code top} at its ceiling to {@code bottom} at its floor, stroked in a
     * colour between the two. {@code slices} of 3-5 is plenty; every slice is one batched gizmo.
     */
    public static void gradientBox(Box b, int top, int bottom, float width, boolean throughWalls, int slices) {
        double h = b.maxY - b.minY;
        if (h <= 1e-4 || slices <= 1) {
            if (Colors.a(top) > 0) box(b, top, width > 0 ? bottom : 0, width, throughWalls);
            return;
        }
        int n = Math.max(2, slices);
        for (int i = 0; i < n; i++) {
            double y0 = b.minY + h * i / n;
            double y1 = b.minY + h * (i + 1) / n;
            int col = Colors.lerp(top, bottom, (i + 0.5f) / n);
            if (Colors.a(col) == 0) continue;
            box(new Box(b.minX, y0, b.minZ, b.maxX, y1, b.maxZ), col, 0, 0, throughWalls);
        }
        if (width > 0 && Colors.a(bottom) > 0) box(b, 0, Colors.lerp(top, bottom, 0.65f), width, throughWalls);
    }

    /**
     * A breathing halo around a box: a slightly larger translucent shell whose alpha eases in and out. One extra
     * gizmo per box, so it is only worth it on the few things that matter (the base you are flying at, a player
     * next to you, the box a sound came from).
     */
    public static void glow(Box b, int color, boolean throughWalls) {
        if (Colors.a(color) == 0) return;
        float pulse = 0.5f + 0.5f * (float) Math.sin(Time.seconds() * 2.8 + (b.minX + b.minZ) * 0.11);
        int a = (int) (0x0C + 0x1C * pulse);
        box(b.expand(0.08 + 0.07 * pulse), Colors.withAlpha(color, a), 0, 0, throughWalls);
    }

    /** Corner brackets (three short segments per corner) instead of a full wireframe. */
    public static void corners(Box b, int color, float width, boolean throughWalls) {
        if (Colors.a(color) == 0) return;
        double lx = Math.min(1.2, (b.maxX - b.minX) * 0.3);
        double ly = Math.min(1.2, (b.maxY - b.minY) * 0.3);
        double lz = Math.min(1.2, (b.maxZ - b.minZ) * 0.3);
        for (int cx = 0; cx < 2; cx++) for (int cy = 0; cy < 2; cy++) for (int cz = 0; cz < 2; cz++) {
            double x = cx == 0 ? b.minX : b.maxX;
            double y = cy == 0 ? b.minY : b.maxY;
            double z = cz == 0 ? b.minZ : b.maxZ;
            double dx = cx == 0 ? lx : -lx, dy = cy == 0 ? ly : -ly, dz = cz == 0 ? lz : -lz;
            line(new Vec3d(x, y, z), new Vec3d(x + dx, y, z), color, width, throughWalls);
            line(new Vec3d(x, y, z), new Vec3d(x, y + dy, z), color, width, throughWalls);
            line(new Vec3d(x, y, z), new Vec3d(x, y, z + dz), color, width, throughWalls);
        }
    }

    /**
     * A thin vertical light column at {@code at}, fading upward. Chunk squares are flat and vanish at a
     * distance; one of these per suspected chunk keeps the base readable from the air.
     */
    public static void beam(Vec3d at, double bottom, double top, int bottomColor, int topColor, boolean throughWalls) {
        if (top - bottom <= 0.05) return;
        gradientBox(new Box(at.x - 0.09, bottom, at.z - 0.09, at.x + 0.09, top, at.z + 0.09),
                topColor, bottomColor, 0, throughWalls, 5);
    }

    /** A tracer start point just in front of the camera, so lines appear to come from the crosshair. */
    public static Vec3d tracerOrigin() {
        var cam = MinecraftClient.getInstance().gameRenderer.getCamera();
        float yaw = (float) Math.toRadians(cam.getYaw()), pitch = (float) Math.toRadians(cam.getPitch());
        Vec3d look = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        return cam.getCameraPos().add(look.multiply(0.6));
    }
}
