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

    public Trajectories() { super("Trajectories", "Arc and landing spot for pearls, potions, bows, tridents, plus where thrown pearls land.", Category.RENDER); }

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

    private void draw(Path path, int c1, int c2, int skip) {
        List<Vec3d> pts = path.points();
        int n = pts.size();
        for (int i = Math.max(1, skip); i < n; i++) {
            float f = (float) i / Math.max(1, n - 1);
            int col = Colors.lerp(c1, c2, f);
            Esp.line(pts.get(i - 1), pts.get(i), Colors.withAlpha(col, 0x40), 5f, false);
            Esp.line(pts.get(i - 1), pts.get(i), Colors.withAlpha(col, 0xF0), 2f, false);
        }
        Vec3d end = pts.get(n - 1);
        if (path.hitEntity() != null) {
            Esp.box(path.hitEntity().getBoundingBox(), Colors.withAlpha(c2, 0x30), Colors.withAlpha(c2, 0xF0), 2f, true);
        } else if (path.hit() instanceof BlockHitResult bh) {
            // A glowing ring flat on the face it lands on.
            Direction f = bh.getSide();
            Vec3d nrm = new Vec3d(f.getOffsetX(), f.getOffsetY(), f.getOffsetZ());
            Vec3d u = Math.abs(nrm.y) > 0.5 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            Vec3d v = nrm.crossProduct(u);
            Vec3d c = end.add(nrm.multiply(0.02));
            for (int i = 0; i < 24; i++) {
                double a0 = i * Math.PI * 2 / 24, a1 = (i + 1) * Math.PI * 2 / 24;
                Vec3d p0 = c.add(u.multiply(Math.cos(a0) * 0.45)).add(v.multiply(Math.sin(a0) * 0.45));
                Vec3d p1 = c.add(u.multiply(Math.cos(a1) * 0.45)).add(v.multiply(Math.sin(a1) * 0.45));
                Esp.line(p0, p1, Colors.withAlpha(c2, 0xF0), 2.5f, true);
            }
            Esp.block(BlockPos.ofFloored(end.subtract(nrm.multiply(0.01))), 0, Colors.withAlpha(c1, 0x22), Colors.withAlpha(c1, 0x90), 1.25f, true);
        }
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        for (Object[] l : pearlLabels) {
            Vec3d at = (Vec3d) l[0];
            WorldLabels.draw(t, at.add(0, 0.8, 0), (String) l[1] + "'s pearl", Fmt.xyz(BlockPos.ofFloored(at)), (int) l[2], 1f, sw, sh, true);
        }
    }
}
