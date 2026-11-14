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
        Gfx.pushPlace(sw / 2, cy, sw / 2, cy, k);
        try {
            // Local frame: x in -105..105, y in -56..-2 (0 is the bottom edge of the screen).
            if (hideVanilla.on()) {
                Gfx.round(-107, -58, 214, 56, 9, 0xF00A0C11);          // opaque cover
                Gfx.round(-107, -58, 214, 56, 9, 0x30FFFFFF);          // glass sheen
                Gfx.round(-103, -55, 206, 22, 7, 0x14FFFFFF);          // subtle inner lift
            }

            if (xp.on()) {
                float frac = Math.min(1f, Math.max(0f, p.experienceProgress));
                Gfx.round(-96, -36, 192, 3, 1.5f, 0x2AFFFFFF);
                if (frac > 0.001f) Gfx.roundGradientH(-96, -36, 192 * frac, 3, 1.5f, t.accent(), t.accent2());
                if (numbers.on() && p.experienceLevel > 0) {
                    String lv = Integer.toString(p.experienceLevel);
                    Gfx.text(lv, -Gfx.width(lv, 6, Font.Weight.BOLD) / 2, -48, 6, Font.Weight.BOLD, t.accent2());
                }
            }

            if (health.on()) {
                float hp = p.getHealth() + p.getAbsorptionAmount();
                float max = Math.max(1f, p.getMaxHealth() + p.getAbsorptionAmount());
                bar(-96, -31, 92, 6, Math.min(1f, hp / max), 0xFFF2555A, 0xFFFF8A5C, t);
                if (numbers.on()) {
                    Gfx.text(String.format(java.util.Locale.ROOT, "%.0f", hp), -92, -30.5f, 6, Font.Weight.BOLD, 0xFFFFFFFF);
                }
            }
            if (food.on()) {
                int fd = p.getHungerManager().getFoodLevel();
                bar(4, -31, 92, 6, Math.min(1f, fd / 20f), 0xFFC98A3C, 0xFFFFD27A, t);
                if (numbers.on()) {
                    String label = Integer.toString(fd);
                    Gfx.text(label, 96 - Gfx.width(label, 6, Font.Weight.BOLD), -30.5f, 6, Font.Weight.BOLD, 0xFFFFFFFF);
                }
            }

            if (hotbar.on()) {
                int sel = p.getInventory().getSelectedSlot();
                float cell = 18, gap = 2, total = 9 * cell + 8 * gap;
                float hx = -total / 2, hy = -24;
                for (int i = 0; i < 9; i++) {
                    float cx = hx + i * (cell + gap);
                    boolean on = i == sel;
                    if (on) Gfx.round(cx - 1.5f, hy - 1.5f, cell + 3, cell + 3, 4, Colors.withAlpha(t.accent(), 0xD0));
                    Gfx.round(cx, hy, cell, cell, 3, on ? 0x33FFFFFF : 0x1AFFFFFF);
                    ItemStack st = p.getInventory().getStack(i);
                    if (!st.isEmpty()) {
                        Gfx.item(st, cx + 1.5f, hy + 1.5f, cell - 3);
                        int count = st.getCount();
                        if (count > 1) {
                            String c = Integer.toString(count);
                            Gfx.text(c, cx + cell - 1.5f - Gfx.width(c, 5, Font.Weight.BOLD), hy + cell - 6.5f, 5, Font.Weight.BOLD, 0xFFFFFFFF);
                        }
                    }
                }
            }
        } finally {
            Gfx.popPlace();
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
