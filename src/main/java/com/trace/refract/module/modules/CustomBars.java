package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import net.minecraft.item.ItemStack;

/**
 * Replaces the bottom of the HUD with our own: a frosted panel carrying the hotbar, health, food and experience bars.
 *
 * <p>This module is rendered in the late HUD element, i.e. after vanilla has drawn its own bars, so the panel covers
 * them instead of leaving two copies on screen. No mixin is involved, which keeps it stable across HUD changes.
 */
public class CustomBars extends Module {
    public final ModeSetting style = add(new ModeSetting("Style", "Glass", "Glass", "Segmented", "Minimal"));
    public final BoolSetting hotbar = add(new BoolSetting("Custom Hotbar", true));
    public final BoolSetting health = add(new BoolSetting("Health Bar", true));
    public final BoolSetting food = add(new BoolSetting("Food Bar", true));
    public final BoolSetting xp = add(new BoolSetting("XP Bar", true));
    public final BoolSetting numbers = add(new BoolSetting("Show Numbers", false));
    public final BoolSetting hideVanilla = add(new BoolSetting("Cover Vanilla", true));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.7, 1.6, 0.05, "x"));
    public final NumberSetting offsetY = add(new NumberSetting("Offset Y", 0, -40, 60, 1, "px"));

    public CustomBars() {
        super("Custom Bars", "Custom hotbar with health, food and XP bars in the theme style.", Category.RENDER);
    }

    /**
     * Laid out on the vanilla hotbar's own geometry: 182×22 centred on the bottom edge, health and hunger directly
     * above it, XP as a thin line between them. Everything is drawn in a local frame anchored to bottom-centre, so
     * the panel and its contents can never drift off centre, and the backing plate is opaque so the vanilla bars
     * underneath are genuinely covered rather than showing through a translucent panel.
     */
    @Override public void onHud(Theme t, float sw, float sh) {
        var p = mc.player;
        if (p == null || mc.options.hudHidden) return;
        float k = scale.asFloat();
        float cy = sh + offsetY.asFloat();
        // Local origin (0,0) is mapped to the bottom centre of the screen: that is what makes the panel
        // bottom-anchored. The previous call mapped a point to itself, so every local coordinate drew up near the
        // top of the screen and nothing was visible.
        Gfx.pushPlace(0, 0, sw / 2, cy, k);
        try {
            // Local frame: x -110..110, y -62..-2. Bottom edge of the screen is y = 0.
            float panelH = 48;
            if (hideVanilla.on()) {
                Gfx.round(-112, -panelH, 224, panelH, 10, 0xF20A0C12);
                Gfx.round(-112, -panelH, 224, panelH, 10, 0x33FFFFFF);
                Gfx.roundGradientH(-110, -panelH + 1, 220, 1.2f, 0.6f, Colors.withAlpha(t.accent(), 0xE0), Colors.withAlpha(t.accent2(), 0x00));
            }

            // Level badge on the left, then the experience bar running the width of the panel.
            int lvl = p.experienceLevel;
            if (xp.on()) {
                if (lvl > 0) {
                    Gfx.circle(-100, -37.5f, 5.5f, 0x33FFFFFF);
                    Gfx.circle(-100, -37.5f, 4.5f, Colors.withAlpha(t.accent(), 0x55));
                    String lv = Integer.toString(lvl);
                    Gfx.text(lv, -100 - Gfx.width(lv, 6, Font.Weight.BOLD) / 2, -40.5f, 6, Font.Weight.BOLD, 0xFFFFFFFF);
                }
                float frac = Math.min(1f, Math.max(0f, p.experienceProgress));
                float bx = -90, bw = 180;
                Gfx.round(bx, -38, bw, 3.5f, 1.75f, 0x2AFFFFFF);
                if (frac > 0.001f) {
                    Gfx.roundGradientH(bx, -38, Math.max(4, bw * frac), 3.5f, 1.75f, t.accent(), t.accent2());
                    Gfx.glow(bx + bw * frac, -36, 8, Colors.withAlpha(t.accent2(), 0x55));
                }
            }

            // Health and hunger as segmented pills: ten pips each, so damage reads at a glance.
            if (health.on()) {
                float hp = p.getHealth() + p.getAbsorptionAmount();
                float max = Math.max(1f, p.getMaxHealth());
                float frac = Math.min(1f, hp / max);
                pips(-96, -30, 92, 4.5f, frac, 0xFFF2555A, 0xFFFF8A5C, t);
                if (numbers.on()) {
                    String s = String.format(java.util.Locale.ROOT, "%.0f", hp);
                    Gfx.text(s, -96, -40, 6, Font.Weight.BOLD, Colors.lerp(0xFFFF8A8A, 0xFFFFFFFF, 0.4f));
                }
            }
            if (food.on()) {
                int fd = p.getHungerManager().getFoodLevel();
                pips(4, -30, 92, 4.5f, Math.min(1f, fd / 20f), 0xFFC98A3C, 0xFFFFD27A, t);
                if (numbers.on()) {
                    String s = Integer.toString(fd);
                    Gfx.text(s, 96 - Gfx.width(s, 6, Font.Weight.BOLD), -40, 6, Font.Weight.BOLD, 0xFFFFE3B0);
                }
            }

            if (hotbar.on()) {
                int sel = p.getInventory().getSelectedSlot();
                float cell = 18, gap = 2, total = 9 * cell + 8 * gap;
                float hx = -total / 2, hy = -21;
                // Body of the bar behind the cells.
                Gfx.round(hx - 4, hy - 3, total + 8, cell + 6, 6, 0x1CFFFFFF);
                Gfx.round(hx - 4, hy - 3, total + 8, cell + 6, 6, 0x14FFFFFF);
                for (int i = 0; i < 9; i++) {
                    float cx = hx + i * (cell + gap);
                    boolean on = i == sel;
                    if (on) {
                        Gfx.glow(cx + cell / 2, hy + cell / 2, 16, Colors.withAlpha(t.accent(), 0x66));
                        Gfx.round(cx - 2, hy - 4, cell + 4, cell + 6, 5, 0xB0FFFFFF);
                        Gfx.round(cx - 1.5f, hy - 3.5f, cell + 3, cell + 5, 4.5f, Colors.withAlpha(t.accent(), 0xF0));
                        Gfx.round(cx, hy - 2, cell, cell + 2, 4, 0xF0101319);
                    } else {
                        Gfx.round(cx, hy, cell, cell, 3.5f, 0x24FFFFFF);
                    }
                    ItemStack st = p.getInventory().getStack(i);
                    if (!st.isEmpty()) {
                        Gfx.item(st, cx + 1.5f, hy + 1.5f, cell - 3);
                        int count = st.getCount();
                        if (count > 1) {
                            String c = Integer.toString(count);
                            Gfx.text(c, cx + cell - 1.5f - Gfx.width(c, 5, Font.Weight.BOLD), hy + cell - 5.5f, 5, Font.Weight.BOLD, 0xFFFFFFFF);
                        }
                        if (st.isDamageable() && st.getDamage() > 0) {
                            float f = 1f - (float) st.getDamage() / Math.max(1, st.getMaxDamage());
                            Gfx.round(cx + 1, hy + cell - 2.5f, cell - 2, 1.6f, 0.8f, 0x55000000);
                            Gfx.roundGradientH(cx + 1, hy + cell - 2.5f, (cell - 2) * f, 1.6f, 0.8f,
                                    Colors.lerp(0xFFF2555A, 0xFF34D399, f), Colors.lerp(0xFFFFC24D, 0xFF34D399, f));
                        }
                    }
                }
                // Armour (left of the bar) and off hand (right of it).
                float ax = hx - 26;
                for (var slot : new net.minecraft.entity.EquipmentSlot[]{net.minecraft.entity.EquipmentSlot.HEAD,
                        net.minecraft.entity.EquipmentSlot.CHEST, net.minecraft.entity.EquipmentSlot.LEGS,
                        net.minecraft.entity.EquipmentSlot.FEET}) {
                    ItemStack st = p.getEquippedStack(slot);
                    if (st.isEmpty()) { ax += 13; continue; }
                    Gfx.round(ax, hy + 2, 12, 12, 2.5f, 0x22FFFFFF);
                    Gfx.item(st, ax + 0.5f, hy + 2.5f, 11);
                    ax += 13;
                }
                ItemStack off = p.getOffHandStack();
                if (!off.isEmpty()) {
                    float ox = hx + total + 8;
                    Gfx.round(ox, hy + 2, 12, 12, 2.5f, 0x22FFFFFF);
                    Gfx.item(off, ox + 0.5f, hy + 2.5f, 11);
                }
                // Little indicator above the selected slot.
                float ix = hx + sel * (cell + gap) + cell / 2;
                Gfx.round(ix - 2, hy - 7, 4, 2, 1, Colors.withAlpha(t.accent(), 0xFF));
            }
        } finally {
            Gfx.popPlace();
        }
    }

    /** A bar made of ten pips with thin gaps, gradient-filled up to {@code frac}. */
    private void pips(float x, float y, float w, float h, float frac, int from, int to, Theme t) {
        int n = 10;
        float gap = 1.5f;
        float pw = (w - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            float px = x + i * (pw + gap);
            boolean full = frac * n > i;
            Gfx.round(px, y, pw, h, 1f, 0x2AFFFFFF);
            if (full) {
                float f = Math.min(1f, frac * n - i); // partial last pip
                Gfx.roundGradientH(px, y, Math.max(1f, pw * f), h, 1f,
                        Colors.lerp(from, to, (float) i / n), Colors.lerp(from, to, (float) (i + 1) / n));
            }
        }
        if (style.is("Minimal")) Gfx.round(x, y + h + 1, w * frac, 0.8f, 0.4f, Colors.withAlpha(t.accent(), 0xC0));
    }
}
