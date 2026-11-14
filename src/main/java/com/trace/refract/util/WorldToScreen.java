package com.trace.refract.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Projects world positions onto the scaled GUI. The view and projection matrices are captured from the
 * world renderer every frame by WorldRendererMixin.
 */
public final class WorldToScreen {
    private static final Matrix4f view = new Matrix4f();
    private static final Matrix4f proj = new Matrix4f();
    private static Vec3d camPos = Vec3d.ZERO;
    private static boolean ready;

    /** Result of a projection: screen position, whether it is in front of the camera and on screen. */
    public record Point(float x, float y, boolean onScreen, float angle) {}

    private WorldToScreen() {}

    public static void capture(Vec3d cam, Matrix4f a, Matrix4f b, Matrix4f c) {
        Matrix4f v = null, p = null;
        for (Matrix4f m : new Matrix4f[]{a, b, c}) {
            if (m == null) continue;
            boolean isView = Math.abs(m.m33() - 1f) < 1e-3f && Math.abs(m.m23()) < 1e-3f && Math.abs(m.m30()) < 1e-3f;
            boolean isProj = Math.abs(m.m23() + 1f) < 0.25f && Math.abs(m.m33()) < 0.5f;
            if (isView && v == null) v = m;
            else if (isProj && p == null) p = m;
        }
        if (v == null || p == null) return;
        view.set(v);
        proj.set(p);
        camPos = cam;
        ready = true;
    }

    public static boolean ready() { return ready; }

    /** World-space view direction through a point on the scaled screen (sx, sy), from the captured camera matrices. */
    public static org.joml.Vector3f ray(float sx, float sy, float sw, float sh) {
        Matrix4f inv = new Matrix4f(proj).mul(view).invert();
        float nx = sx / sw * 2f - 1f, ny = 1f - sy / sh * 2f;
        Vector4f a = new Vector4f(nx, ny, -1f, 1f), b = new Vector4f(nx, ny, 1f, 1f);
        inv.transform(a);
        inv.transform(b);
        org.joml.Vector3f d = new org.joml.Vector3f(b.x / b.w - a.x / a.w, b.y / b.w - a.y / a.w, b.z / b.w - a.z / a.w);
        return d.normalize();
    }

    public static Point project(Vec3d world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        Vector4f v = new Vector4f((float) (world.x - camPos.x), (float) (world.y - camPos.y), (float) (world.z - camPos.z), 1f);
        view.transform(v);
        float vx = v.x, vy = v.y;
        boolean behind = v.z > 0; // camera looks down -Z in view space
        proj.transform(v);

        if (!behind && v.w > 1e-4f) {
            float nx = v.x / v.w, ny = v.y / v.w;
            float sx = (nx * 0.5f + 0.5f) * sw;
            float sy = (1f - (ny * 0.5f + 0.5f)) * sh;
            boolean on = nx >= -1f && nx <= 1f && ny >= -1f && ny <= 1f;
            float ang = (float) Math.atan2(sy - sh / 2f, sx - sw / 2f);
            return new Point(sx, sy, on, ang);
        }
        // Behind the camera: point the arrow the way you need to turn.
        float ang = (float) Math.atan2(-vy, vx);
        if (Math.abs(vx) < 1e-3 && Math.abs(vy) < 1e-3) ang = (float) (Math.PI / 2);
        return new Point(-1, -1, false, ang);
    }
}
