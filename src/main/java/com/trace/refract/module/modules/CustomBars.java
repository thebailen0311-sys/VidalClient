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
    public final BoolSetting numbers = add(new BoolSetting("Show Numbers", true));
    public final BoolSetting hideVanilla = add(new BoolSetting("Cover Vanilla", true));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.7, 1.6, 0.05, "x"));
    public final NumberSetting offsetY = add(new NumberSetting("Offset Y", 0, -40, 60, 1, "px"));

    public CustomBars() {
        super("Custom Bars", "Custom hotbar with health, food and XP bars in the theme style.", Category.RENDER);
    }

    @Override public void onHud(Theme t, float sw, float sh) {
        var p = mc.player;
        if (p == null || mc.options.hudHidden) return;
        float s = scale.asFloat();
        float w = 196 * s, h = 46 * s;
        float x = sw / 2 - w / 2, y = sh - h - 6 * s + offsetY.asFloat();

        if (hideVanilla.on()) Gfx.frost(t, x - 4 * s, y - 4 * s, w + 8 * s, h + 8 * s, 10 * s, 0.82f);

        float barW = (w - 12 * s) / 2;
        if (health.on()) {
            float hp = p.getHealth() + p.getAbsorptionAmount(), max = Math.max(1f, p.getMaxHealth() + p.getAbsorptionAmount());
            float frac = Math.min(1f, hp / max);
            bar(x + 6 * s, y + 4 * s, barW, 7 * s, frac, 0xFFF2555A, 0xFFFF8A5C, t);
            if (numbers.on()) {
                String label = String.format(java.util.Locale.ROOT, "%.0f", hp);
                Gfx.text(label, x + 9 * s, y + 5 * s, Gfx.SMALL * s, Font.Weight.BOLD, 0xFFFFFFFF);
            }
        }
        if (food.on()) {
            int fd = p.getHungerManager().getFoodLevel();
            float frac = Math.min(1f, fd / 20f);
            bar(x + 6 * s + barW + 4 * s, y + 4 * s, barW - 4 * s, 7 * s, frac, 0xFFC98A3C, 0xFFFFD27A, t);
            if (numbers.on()) {
                String label = Integer.toString(fd);
                float lw = Gfx.width(label, Gfx.SMALL * s, Font.Weight.BOLD);
                Gfx.text(label, x + 6 * s + barW * 2 - 10 * s - lw, y + 5 * s, Gfx.SMALL * s, Font.Weight.BOLD, 0xFFFFFFFF);
            }
        }

        if (xp.on()) {
            float frac = Math.min(1f, Math.max(0f, p.experienceProgress));
            Gfx.round(x + 6 * s, y + 14 * s, w - 12 * s, 3 * s, 1.5f * s, 0x2AFFFFFF);
            if (frac > 0.001f)
                Gfx.roundGradientH(x + 6 * s, y + 14 * s, (w - 12 * s) * frac, 3 * s, 1.5f * s, t.accent(), t.accent2());
            if (numbers.on() && p.experienceLevel > 0) {
                String lv = Integer.toString(p.experienceLevel);
                Gfx.text(lv, x + w / 2 - Gfx.width(lv, Gfx.SMALL * s, Font.Weight.BOLD) / 2, y + 1 * s,
                        Gfx.SMALL * s, Font.Weight.BOLD, t.accent2());
            }
        }

        if (hotbar.on()) {
            int sel = p.getInventory().getSelectedSlot();
            float cell = 18 * s, gap = 2 * s;
            float total = 9 * cell + 8 * gap;
            float hx = sw / 2 - total / 2, hy = y + 21 * s;
            for (int i = 0; i < 9; i++) {
                float cx = hx + i * (cell + gap);
                boolean on = i == sel;
                if (on) Gfx.round(cx - 1 * s, hy - 1 * s, cell + 2 * s, cell + 2 * s, 4 * s, Colors.withAlpha(t.accent(), 0xD0));
                Gfx.round(cx, hy, cell, cell, 3 * s, on ? 0x33FFFFFF : 0x1AFFFFFF);
                ItemStack st = p.getInventory().getStack(i);
                if (!st.isEmpty()) {
                    Gfx.item(st, cx + 1.5f * s, hy + 1.5f * s, cell - 3 * s);
                    if (style.is("Segmented")) {
                        int count = st.getCount();
                        if (count > 1) {
                            String c = Integer.toString(count);
                            Gfx.text(c, cx + cell - 2 * s - Gfx.width(c, Gfx.SMALL * s * 0.8f, Font.Weight.BOLD),
                                    hy + cell - 6 * s, Gfx.SMALL * s * 0.8f, Font.Weight.BOLD, 0xFFFFFFFF);
                        }
                    }
                }
            }
        }
    }

    /** One bar: track, gradient fill, a soft highlight along the top and a thin outline. */
    private void bar(float x, float y, float w, float h, float frac, int from, int to, Theme t) {
        Gfx.round(x, y, w, h, h / 2, 0x2EFFFFFF);
        float fill = Math.max(h, w * Math.max(0f, Math.min(1f, frac)));
        Gfx.roundGradientH(x, y, fill, h, h / 2, from, to);
        Gfx.round(x, y, fill, h * 0.4f, h * 0.2f, 0x30FFFFFF);
        if (style.is("Minimal")) Gfx.round(x, y + h - 1, fill, 1, 0.5f, Colors.withAlpha(t.accent(), 0xC0));
    }
}
