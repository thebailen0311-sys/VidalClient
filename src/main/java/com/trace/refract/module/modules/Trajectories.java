package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.hud.WorldLabels;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Fmt;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Projectile paths: hold a pearl, snowball, egg, potion, XP bottle, trident, or draw a bow/charged crossbow and the
 * exact arc is drawn with a landing marker (and a box on any player or mob it would hit). Ender pearls already in
 * the air (yours or anyone's) show where they will land, with the thrower's name.
 */
public class Trajectories extends Module {
    public final BoolSetting own = add(new BoolSetting("Held Item", true));
    public final BoolSetting pearls = add(new BoolSetting("Thrown Pearls", true));

    private record Path(List<Vec3d> points, HitResult hit, Entity hitEntity) {}

    private final List<Object[]> pearlLabels = new ArrayList<>();

    public Trajectories() {
        super("Trajectories", "Glowing arc and landing ring for pearls, potions, bows, tridents, plus where thrown pearls land.", Category.RENDER);
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(this::drawRibbons);
    }

    /** Vanilla projectile physics: move, then drag, then gravity, checking blocks and entities along each step. */
    private Path simulate(Vec3d pos, Vec3d vel, double gravity, double drag, Entity owner) {
        List<Vec3d> pts = new ArrayList<>();
        pts.add(pos);
        for (int i = 0; i < 300; i++) {
            Vec3d next = pos.add(vel);
            BlockHitResult bh = mc.world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
            Vec3d end = bh.getType() == HitResult.Type.MISS ? next : bh.getPos();
            Entity best = null;
            Vec3d bestAt = null;
            for (Entity e : mc.world.getOtherEntities(owner, new Box(pos, end).expand(1), e -> e.isAttackable() && !e.isSpectator() && e.canHit())) {
                var opt = e.getBoundingBox().expand(0.3).raycast(pos, end);
                if (opt.isPresent() && (bestAt == null || pos.squaredDistanceTo(opt.get()) < pos.squaredDistanceTo(bestAt))) { best = e; bestAt = opt.get(); }
            }
            if (best != null) { pts.add(bestAt); return new Path(pts, null, best); }
            if (bh.getType() != HitResult.Type.MISS) { pts.add(end); return new Path(pts, bh, null); }
            pts.add(next);
            pos = next;
            vel = vel.multiply(drag).add(0, -gravity, 0);
            if (pos.y < mc.world.getBottomY() - 8) break;
        }
        return new Path(pts, null, null);
    }

    /** Speed, gravity, drag and pitch offset for what is in hand, or null when it is not a projectile (or not drawn). */
    private double[] params(ItemStack s) {
        if (s.isOf(Items.ENDER_PEARL) || s.isOf(Items.SNOWBALL) || s.isOf(Items.EGG)) return new double[]{1.5, 0.03, 0.99, 0};
        if (s.isOf(Items.SPLASH_POTION) || s.isOf(Items.LINGERING_POTION)) return new double[]{0.5, 0.05, 0.99, -20};
        if (s.isOf(Items.EXPERIENCE_BOTTLE)) return new double[]{0.7, 0.07, 0.99, -20};
        if (s.isOf(Items.TRIDENT) && mc.player.isUsingItem()) return new double[]{2.5, 0.05, 0.99, 0};
        if (s.isOf(Items.BOW) && mc.player.isUsingItem()) {
            float pull = BowItem.getPullProgress(mc.player.getItemUseTime());
            return pull < 0.1f ? null : new double[]{pull * 3.0, 0.05, 0.99, 0};
        }
        if (s.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(s)) return new double[]{3.15, 0.05, 0.99, 0};
        return null;
    }

    @Override public void onWorldRender(float tickDelta) {
        if (mc.player == null || mc.world == null) return;
        var t = Themes.current();
        pearlLabels.clear();
        if (own.on()) {
            ItemStack s = mc.player.getMainHandStack();
            double[] p = params(s);
            if (p == null) { s = mc.player.getOffHandStack(); p = params(s); }
            if (p != null) {
                float yaw = (float) Math.toRadians(mc.player.getYaw(tickDelta)), pitch = (float) Math.toRadians(mc.player.getPitch(tickDelta)), roll = (float) Math.toRadians(p[3]);
                Vec3d dir = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch + roll), Math.cos(yaw) * Math.cos(pitch)).normalize().multiply(p[0]);
                Vec3d pv = mc.player.getVelocity();
                Vec3d vel = dir.add(pv.x, mc.player.isOnGround() ? 0 : pv.y, pv.z);
                Vec3d start = Esp.pos(mc.player).add(0, mc.player.getStandingEyeHeight() - 0.1, 0);
                draw(simulate(start, vel, p[1], p[2], mc.player), t.accent(), t.accent2(), 2);
            }
        }
        if (pearls.on()) {
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof EnderPearlEntity pearl)) continue;
                Path path = simulate(Esp.pos(pearl), pearl.getVelocity(), 0.03, 0.99, pearl);
                int col = pearl.getOwner() == mc.player ? t.accent() : 0xFFFF6FD8;
                draw(path, col, Colors.lerp(col, 0xFFFFFFFF, 0.4f), 0);
                Vec3d end = path.points().get(path.points().size() - 1);
                String who = pearl.getOwner() != null ? pearl.getOwner().getName().getString() : "Pearl";
                pearlLabels.add(new Object[]{end, who, col});
            }
        }
    }

    /** Paths to draw this frame as light ribbons: {points, c1, c2, hitPos or null, face normal or null, skip}. */
    private final List<Object[]> ribbons = new ArrayList<>();

    private void draw(Path path, int c1, int c2, int skip) {
        Vec3d hit = null, nrm = null;
        if (path.hitEntity() != null) {
            Esp.box(path.hitEntity().getBoundingBox(), Colors.withAlpha(c2, 0x30), Colors.withAlpha(c2, 0xF0), 2f, true);
        } else if (path.hit() instanceof BlockHitResult bh) {
            Direction f = bh.getSide();
            hit = path.points().get(path.points().size() - 1);
            nrm = new Vec3d(f.getOffsetX(), f.getOffsetY(), f.getOffsetZ());
        }
        ribbons.add(new Object[]{path.points(), c1, c2, hit, nrm, skip});
    }

    /**
     * Light ribbons (additive glow): a soft wide glow under a bright core, always facing the camera, with energy pulses
     * running along the arc toward where it lands, and a pulsing ring of light flat on the landing face.
     */
    private void drawRibbons(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext ctx) {
        if (!isEnabled() || ribbons.isEmpty() || ctx.matrices() == null || ctx.consumers() == null) { ribbons.clear(); return; }
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        var layer = net.minecraft.client.render.RenderLayers.lightning();
        var vc = ctx.consumers().getBuffer(layer);
        var e = ctx.matrices().peek();
        float time = com.trace.refract.util.Time.seconds();
        for (Object[] rb : ribbons) {
            @SuppressWarnings("unchecked") List<Vec3d> pts = (List<Vec3d>) rb[0];
            int c1 = (int) rb[1], c2 = (int) rb[2], skip = (int) rb[5];
            double along = 0;
            for (int i = Math.max(1, skip); i < pts.size(); i++) {
                Vec3d a = pts.get(i - 1).subtract(cam), b = pts.get(i).subtract(cam);
                double segLen = b.distanceTo(a);
                Vec3d dir = b.subtract(a);
                Vec3d side = dir.crossProduct(a.add(b).multiply(0.5)).normalize();
                float f = (float) i / Math.max(1, pts.size() - 1);
                int col = Colors.lerp(c1, c2, f);
                float pulse = (float) Math.max(0, 1 - Math.abs(((along - time * 7) % 3.0 + 3.0) % 3.0 - 1.5) / 0.35);
                for (int pass = 0; pass < 2; pass++) {
                    double w = pass == 0 ? 0.16 : 0.035 + 0.02 * pulse;
                    int alpha = pass == 0 ? (int) (60 + 70 * pulse) : (int) (200 + 55 * pulse);
                    int cc = Colors.withAlpha(pass == 0 ? col : Colors.lerp(col, 0xFFFFFFFF, 0.45f + 0.4f * pulse), Math.min(255, alpha));
                    int edge = Colors.withAlpha(col, 0);
                    Vec3d s0 = side.multiply(w);
                    quad(vc, e, a.add(s0), a, b, b.add(s0), edge, cc, cc, edge);
                    quad(vc, e, a, a.subtract(s0), b.subtract(s0), b, cc, edge, edge, cc);
                }
                along += segLen;
            }
            if (rb[3] instanceof Vec3d hit && rb[4] instanceof Vec3d nrm) {
                Vec3d u = Math.abs(nrm.y) > 0.5 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
                Vec3d v = nrm.crossProduct(u);
                Vec3d c = hit.add(nrm.multiply(0.03)).subtract(cam);
                for (int ring = 0; ring < 2; ring++) {
                    double ph = ((time * 0.9 + ring * 0.5) % 1.0);
                    double r0 = 0.15 + ph * 0.6, r1 = r0 + 0.08;
                    int ca = Colors.withAlpha(c2, (int) (230 * (1 - ph)));
                    for (int k = 0; k < 32; k++) {
                        double a0 = k * Math.PI * 2 / 32, a1 = (k + 1) * Math.PI * 2 / 32;
                        Vec3d d0 = u.multiply(Math.cos(a0)).add(v.multiply(Math.sin(a0))), d1 = u.multiply(Math.cos(a1)).add(v.multiply(Math.sin(a1)));
                        quad(vc, e, c.add(d0.multiply(r0)), c.add(d0.multiply(r1)), c.add(d1.multiply(r1)), c.add(d1.multiply(r0)), ca, Colors.withAlpha(c2, 0), Colors.withAlpha(c2, 0), ca);
                    }
                }
                // Steady bright centre dot.
                int core = Colors.withAlpha(Colors.lerp(c2, 0xFFFFFFFF, 0.5f), 0xF0);
                for (int k = 0; k < 16; k++) {
                    double a0 = k * Math.PI * 2 / 16, a1 = (k + 1) * Math.PI * 2 / 16;
                    Vec3d d0 = u.multiply(Math.cos(a0)).add(v.multiply(Math.sin(a0))), d1 = u.multiply(Math.cos(a1)).add(v.multiply(Math.sin(a1)));
                    quad(vc, e, c, c.add(d0.multiply(0.12)), c.add(d1.multiply(0.12)), c, core, Colors.withAlpha(c2, 0), Colors.withAlpha(c2, 0), core);
                }
            }
        }
        ribbons.clear();
        if (ctx.consumers() instanceof net.minecraft.client.render.VertexConsumerProvider.Immediate imm) imm.draw(layer);
    }

    private static void quad(net.minecraft.client.render.VertexConsumer vc, net.minecraft.client.util.math.MatrixStack.Entry e,
                             Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, int c0, int c1, int c2, int c3) {
        Vec3d[] p = {p0, p1, p2, p3};
        int[] c = {c0, c1, c2, c3};
        for (int i = 0; i < 4; i++) vc.vertex(e, (float) p[i].x, (float) p[i].y, (float) p[i].z).color(c[i]);
        for (int i = 3; i >= 0; i--) vc.vertex(e, (float) p[i].x, (float) p[i].y, (float) p[i].z).color(c[i]);
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        for (Object[] l : pearlLabels) {
            Vec3d at = (Vec3d) l[0];
            WorldLabels.draw(t, at.add(0, 0.8, 0), (String) l[1] + "'s pearl", Fmt.xyz(BlockPos.ofFloored(at)), (int) l[2], 1f, sw, sh, true);
        }
    }
}
