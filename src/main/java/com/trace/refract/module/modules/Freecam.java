package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Detaches the camera. The body stays still and sends no movement or rotation (input is zeroed in
 * KeyboardInputMixin, mouse look is redirected in EntityMixin, the camera moves in CameraMixin), so the
 * server only ever sees a player standing still. Chunk occlusion culling is turned off while active so caves
 * and underground bases render when the camera is inside terrain. The camera can only show chunks the server
 * has already sent you.
 *
 * <p>The camera position is interpolated per frame between ticks, so it glides instead of stepping 20 times a
 * second. Look is direct: the camera turns exactly as far as the mouse moved, with no easing in between. The
 * camera is clamped to the world's build height, so it can never end up somewhere the client cannot resolve a
 * position for.
 */
public class Freecam extends Module {
    public final NumberSetting speed = add(new NumberSetting("Speed", 1.2, 0.1, 12.0, 0.1, ""));
    public final BoolSetting scrollSpeed = add(new BoolSetting("Scroll Adjusts Speed", true));
    public final BoolSetting smooth = add(new BoolSetting("Glide", true));
    public final NumberSetting glide = add(new NumberSetting("Glide Amount", 0.35, 0.05, 1.0, 0.05, ""));
    public final NumberSetting verticalSpeed = add(new NumberSetting("Vertical Speed", 1.0, 0.2, 3.0, 0.1, "x"));
    public final BoolSetting avoidBlocks = add(new BoolSetting("Solid Camera", false));
    public final BoolSetting hideHand = add(new BoolSetting("Hide Hand", true));
    public final BoolSetting noCulling = add(new BoolSetting("See Through Caves", true));
    public final BoolSetting strict = add(new BoolSetting("Strict (No Reach)", true));

    private double x, y, z, prevX, prevY, prevZ;
    private double vx, vy, vz;
    public float yaw, pitch;
    private boolean prevCulling = true;
    private ClientWorld world;

    public Freecam() { super("Freecam", "Fly the camera freely. Your body stays still and sends no movement or rotation packets.", Category.RENDER); }

    @Override public void onEnable() {
        if (mc.player == null) { setEnabled(false, false); return; }
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        x = prevX = eye.x; y = prevY = eye.y; z = prevZ = eye.z;
        vx = vy = vz = 0;
        yaw = mc.player.getYaw();
        pitch = mc.player.getPitch();
        world = mc.world;
        prevCulling = mc.chunkCullingEnabled;
        if (noCulling.on()) mc.chunkCullingEnabled = false;
        mc.worldRenderer.scheduleTerrainUpdate();
    }

    @Override public void onDisable() {
        mc.chunkCullingEnabled = prevCulling;
        vx = vy = vz = 0;
        if (mc.worldRenderer != null) mc.worldRenderer.scheduleTerrainUpdate();
    }

    /** True when detached cameras must not touch the world (keeps the server's reach checks clean). */
    public boolean reachLocked() { return strict.on(); }

    @Override public void onTick() {
        // Turn off on death or when the world changes (dimension switch, server switch).
        if (mc.player == null || mc.world != world || mc.player.isDead()) { setEnabled(false); return; }
        if (noCulling.on() && mc.chunkCullingEnabled) mc.chunkCullingEnabled = false;
        prevX = x; prevY = y; prevZ = z;
        var o = mc.options;
        double fwd = (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0);
        double strafe = (o.leftKey.isPressed() ? 1 : 0) - (o.rightKey.isPressed() ? 1 : 0);
        double up = (o.jumpKey.isPressed() ? 1 : 0) - (o.sneakKey.isPressed() ? 1 : 0);
        double s = speed.get() * (o.sprintKey.isPressed() ? 2.5 : 1.0);

        float rad = yaw * MathHelper.RADIANS_PER_DEGREE;
        double sin = MathHelper.sin(rad), cos = MathHelper.cos(rad);
        double tx = (-sin * fwd + cos * strafe), tz = (cos * fwd + sin * strafe);
        double len = Math.sqrt(tx * tx + tz * tz);
        if (len > 1e-4) { tx = tx / len * s; tz = tz / len * s; }
        double ty = up * s * verticalSpeed.get();

        double k = smooth.on() ? glide.get() : 1.0;
        vx += (tx - vx) * k; vy += (ty - vy) * k; vz += (tz - vz) * k;
        double nx = x + vx, ny = y + vy, nz = z + vz;
        // Solid camera: refuse the steps that would put the eye inside a block, axis by axis, so sliding along a
        // wall still works instead of stopping dead.
        if (avoidBlocks.on()) {
            if (solid(nx, y, z)) { nx = x; vx = 0; }
            if (solid(nx, ny, z)) { ny = y; vy = 0; }
            if (solid(nx, ny, nz)) { nz = z; vz = 0; }
        }
        x = nx; y = ny; z = nz;

        // Stay inside the world: a camera outside the build height has no light or block data to show.
        int low = mc.world.getBottomY() + 2, high = mc.world.getBottomY() + mc.world.getHeight() - 2;
        if (y < low) { y = low; vy = 0; }
        if (y > high) { y = high; vy = 0; }
    }

    /** Mouse wheel while flying: change speed instead of scrolling the hotbar. Returns true if consumed. */
    public boolean onScroll(double amount) {
        if (!isEnabled() || !scrollSpeed.on() || mc.currentScreen != null || amount == 0) return false;
        double factor = amount > 0 ? 1.15 : 1 / 1.15;
        speed.set(speed.get() * factor);
        return true;
    }

    /** Direct look: the camera turns by exactly what the mouse moved, so tracking is 1:1. */
    public void look(double dx, double dy) {
        yaw += (float) dx * 0.15f;
        pitch = MathHelper.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
    }

    private boolean solid(double px, double py, double pz) {
        if (mc.world == null) return false;
        var state = mc.world.getBlockState(net.minecraft.util.math.BlockPos.ofFloored(px, py, pz));
        return !state.getCollisionShape(mc.world, net.minecraft.util.math.BlockPos.ofFloored(px, py, pz)).isEmpty();
    }

    public double lerpX(float t) { return MathHelper.lerp(t, prevX, x); }
    public double lerpY(float t) { return MathHelper.lerp(t, prevY, y); }
    public double lerpZ(float t) { return MathHelper.lerp(t, prevZ, z); }

    @Override public String suffix() { return speed.display(); }
}
