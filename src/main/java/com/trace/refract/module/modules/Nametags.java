package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import com.trace.refract.util.WorldToScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Clean frosted name tags over players (replacing the vanilla ones): name, health in colour with a bar, distance,
 * and their armour plus both hands as item icons with durability bars. Nearest players are drawn on top.
 */
public class Nametags extends Module {
    public final BoolSetting armor = add(new BoolSetting("Armour & Items", true));
    public final BoolSetting health = add(new BoolSetting("Health", true));
    public final BoolSetting distance = add(new BoolSetting("Distance", true));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.5, 2.0, 0.05, "x"));
    public final NumberSetting range = add(new NumberSetting("Range", 128, 16, 512, 16, "m"));

    public Nametags() { super("Nametags", "Frosted name tags with health, distance, armour and held items with durability.", Category.RENDER); }

    @Override public void onHud(Theme t, float sw, float sh) {
        if (mc.world == null || mc.player == null) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double r2 = range.get() * range.get();
        List<PlayerEntity> list = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) if (p != mc.player && !p.isSpectator() && p.squaredDistanceTo(cam) <= r2) list.add(p);
        list.sort(Comparator.comparingDouble((PlayerEntity p) -> p.squaredDistanceTo(cam)).reversed());
        for (PlayerEntity p : list) {
            Vec3d at = Esp.pos(p).add(0, p.getHeight() + 0.45, 0);
            WorldToScreen.Point sp = WorldToScreen.project(at);
            if (!sp.onScreen()) continue;
            double d = cam.distanceTo(at);
            float k = (float) Math.max(0.6, Math.min(1.1, 1.15 - d / 90)) * scale.asFloat();
            float x = sp.x(), y = sp.y();
            Gfx.pushPlace(x, y, x, y, k);
            String name = p.getName().getString();
            float hp = p.getHealth() + p.getAbsorptionAmount();
            int hpCol = Colors.lerp(0xFFF2555A, 0xFF34D399, Math.min(1f, hp / 20f));
            String hpText = String.valueOf(Math.round(hp));
            String dist = Math.round(d) + "m";
            float nw = Gfx.width(name, Gfx.BODY, Font.Weight.BOLD);
            float hw = health.on() ? Gfx.width(hpText, Gfx.BODY, Font.Weight.BOLD) + 7 : 0;
            float dw = distance.on() ? Gfx.width(dist, Gfx.SMALL, Font.Weight.SEMIBOLD) + 7 : 0;
            float w = nw + hw + dw + 16, h = 16, x0 = x - w / 2, y0 = y - h;
            Gfx.frost(t, x0, y0, w, h, 8, 0.3f);
            int teamCol = p.getTeamColorValue();
            int nameCol = teamCol != 0xFFFFFF && teamCol != 0 ? 0xFF000000 | teamCol : t.text();
            float tx = x0 + 8;
            tx += Gfx.text(name, tx, y0 + 3.3f, Gfx.BODY, Font.Weight.BOLD, nameCol) + 7;
            if (health.on()) tx += Gfx.text(hpText, tx, y0 + 3.3f, Gfx.BODY, Font.Weight.BOLD, hpCol) + 7;
            if (distance.on()) Gfx.text(dist, tx, y0 + 4.6f, Gfx.SMALL, Font.Weight.SEMIBOLD, t.textDim());
            if (health.on()) Gfx.round(x0 + 7, y0 + h - 2.6f, Math.max(1.4f, (w - 14) * Math.min(1f, hp / 20f)), 1.4f, 0.7f, hpCol);
            if (armor.on()) {
                List<ItemStack> items = new ArrayList<>();
                for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
                    ItemStack st = p.getEquippedStack(s);
                    if (!st.isEmpty()) items.add(st);
                }
                if (!items.isEmpty()) {
                    float size = 12, gap = 2, total = items.size() * (size + gap) - gap + 8;
                    float ix = x - total / 2, iy = y0 - size - 9;
                    Gfx.frost(t, ix, iy - 2, total, size + 7, 6, 0.2f);
                    float cx = ix + 4;
                    for (ItemStack st : items) {
                        Gfx.item(st, cx, iy + 0.5f, size);
                        if (st.isDamageable() && st.getDamage() > 0) {
                            float f = 1f - (float) st.getDamage() / st.getMaxDamage();
                            Gfx.round(cx + 1, iy + size + 1.6f, (size - 2) * f, 1.2f, 0.6f, Colors.lerp(0xFFF2555A, 0xFF34D399, f));
                        }
                        cx += size + gap;
                    }
                }
            }
            Gfx.popPlace();
        }
    }
}
