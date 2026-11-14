package com.trace.refract.gui;

import com.trace.refract.config.Config;
import com.trace.refract.hud.HudLayout;
import com.trace.refract.hud.Notifications;
import com.trace.refract.hud.RefractHud;
import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.theme.Theme;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Sfx;
import com.trace.refract.util.Time;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * HUD editor: every Refract HUD element is drawn live with an outline. Drag to move (snaps to the screen edges
 * and centre lines), scroll over one to scale it, right-click to reset it. Esc or Done saves.
 */
public class HudEditorScreen extends Screen {
    private final Screen parent;
    private HudLayout.Elem dragging, hovered;
    private float dragDX, dragDY, open;
    private boolean snapX, snapY;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("HUD Editor"));
        this.parent = parent;
    }

    @Override protected void init() {
        RefractHud.editing = true;
        Notifications.push("HUD Editor", "Drag to move, scroll to scale", Themes.current().accent(), 60_000);
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { }

    private HudLayout.Elem at(double mx, double my) {
        HudLayout.Elem hit = null;
        for (HudLayout.Elem e : HudLayout.ELEMS.values()) {
            if (e.drawn && mx >= e.x - 2 && mx < e.x + e.w + 2 && my >= e.y - 2 && my < e.y + e.h + 2) hit = e;
        }
        return hit;
    }

    private float[] doneButton() { return new float[]{width / 2f + 4, height - 30, 70, 18}; }

    private float[] resetButton() { return new float[]{width / 2f - 74, height - 30, 70, 18}; }

    private static boolean in(float[] r, double x, double y) { return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Theme t = Themes.current();
        open = Math.min(1f, open + Time.dt() / 0.25f);
        float e = Anim.easeOutCubic(open);
        if (dragging != null) {
            float x = mouseX - dragDX, y = mouseY - dragDY;
            // Snap to a 6px edge margin and to the screen's centre lines.
            snapX = snapY = false;
            if (Math.abs(x - 6) < 5) { x = 6; snapX = true; }
            if (Math.abs(x + dragging.w - (width - 6)) < 5) { x = width - 6 - dragging.w; snapX = true; }
            if (Math.abs(x + dragging.w / 2 - width / 2f) < 5) { x = width / 2f - dragging.w / 2; snapX = true; }
            if (Math.abs(y - 6) < 5) { y = 6; snapY = true; }
            if (Math.abs(y + dragging.h - (height - 6)) < 5) { y = height - 6 - dragging.h; snapY = true; }
            if (Math.abs(y + dragging.h / 2 - height / 2f) < 5) { y = height / 2f - dragging.h / 2; snapY = true; }
            HudLayout.moveTo(dragging, x, y, width, height);
        }
        hovered = dragging != null ? dragging : at(mouseX, mouseY);

        Gfx.begin(ctx);
        Gfx.alpha = e;
        Gfx.rect(0, 0, width, height, 0x30000000);
        if (dragging != null && snapX) Gfx.round(width / 2f - 0.4f, 0, 0.8f, height, 0.4f, Colors.withAlpha(t.accent(), 0x50));
        if (dragging != null && snapY) Gfx.round(0, height / 2f - 0.4f, width, 0.8f, 0.4f, Colors.withAlpha(t.accent(), 0x50));
        for (HudLayout.Elem el : HudLayout.ELEMS.values()) {
            if (!el.drawn) continue;
            boolean hot = el == hovered;
            int col = hot ? t.accent() : 0x60FFFFFF;
            if (hot) Gfx.round(el.x - 3, el.y - 3, el.w + 6, el.h + 6, 9, Colors.withAlpha(t.accent(), 0x18));
            Gfx.outline(el.x - 3, el.y - 3, el.w + 6, el.h + 6, 9, 1, col, hot ? Colors.withAlpha(t.accent2(), 0xC0) : 0x30FFFFFF);
            if (!hot) continue; // only the element under the cursor gets its name tag, so tags never cover other elements
            String tag = el.label + "  " + Math.round(el.scale * 100) + "%";
            float tw = Gfx.width(tag, Gfx.SMALL - 0.5f, Font.Weight.BOLD) + 10;
            float ty = el.y - 17 < 2 ? el.y + el.h + 5 : el.y - 17;
            float tx = Math.max(2, Math.min(width - tw - 2, el.x - 3));
            Gfx.round(tx, ty, tw, 12, 6, hot ? Colors.withAlpha(t.accent(), 0xE0) : 0xC0101218);
            Gfx.text(tag, tx + 5, ty + 2.3f, Gfx.SMALL - 0.5f, Font.Weight.BOLD, 0xFFFFFFFF);
        }
        // Help card and buttons.
        String help = "Drag to move  ·  Scroll to scale  ·  Right-click to reset";
        float hw = Gfx.width(help, Gfx.SMALL, Font.Weight.SEMIBOLD) + 24;
        Gfx.frost(t, width / 2f - hw / 2, height - 56, hw, 18, 9, 0.6f);
        Gfx.textCentered(help, width / 2f, height - 51.5f, Gfx.SMALL, Font.Weight.SEMIBOLD, t.text());
        button(t, resetButton(), "Reset All", false, mouseX, mouseY);
        button(t, doneButton(), "Done", true, mouseX, mouseY);
        Gfx.end();
    }

    private void button(Theme t, float[] r, String label, boolean primary, int mx, int my) {
        boolean hov = in(r, mx, my);
        if (primary) {
            Gfx.shadow(r[0], r[1], r[2], r[3], 9, hov ? 9 : 6, Colors.withAlpha(t.accent(), hov ? 0x70 : 0x40));
            Gfx.roundGradientH(r[0], r[1], r[2], r[3], 9, t.accent(), t.accent2());
        } else {
            Gfx.frost(t, r[0], r[1], r[2], r[3], 9, 0.5f);
            if (hov) Gfx.round(r[0], r[1], r[2], r[3], 9, 0x18FFFFFF);
        }
        Gfx.textCentered(label, r[0] + r[2] / 2, r[1] + 4.5f, Gfx.SMALL + 0.5f, Font.Weight.BOLD, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double x = click.x(), y = click.y();
        if (in(doneButton(), x, y)) { Sfx.click(); close(); return true; }
        if (in(resetButton(), x, y)) { Sfx.click(); HudLayout.ELEMS.values().forEach(HudLayout::reset); return true; }
        HudLayout.Elem e = at(x, y);
        if (e == null) return true;
        if (click.button() == 1) { Sfx.click(); HudLayout.reset(e); return true; }
        dragging = e;
        dragDX = (float) x - e.x;
        dragDY = (float) y - e.y;
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        HudLayout.Elem e = at(mouseX, mouseY);
        if (e != null) e.scale = Math.max(0.5f, Math.min(2f, Math.round((e.scale + (float) vertical * 0.05f) * 20) / 20f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        Config.save();
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void removed() {
        RefractHud.editing = false;
        Notifications.dismiss("HUD Editor");
        Config.save();
        super.removed();
    }
}
