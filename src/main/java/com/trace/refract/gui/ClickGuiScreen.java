package com.trace.refract.gui;

import com.google.gson.JsonObject;
import com.trace.refract.Refract;
import com.trace.refract.config.Config;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.setting.Setting;
import com.trace.refract.theme.Theme;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Sfx;
import com.trace.refract.util.Time;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Refract's main window.
 * <ul>
 *   <li>Left sidebar: logo mark and version, MODULES (categories with icons and an active-count badge), GENERAL
 *       (Settings, Theme) and a profile card with your player name and skin.</li>
 *   <li>Top bar: "Hello, name", a live clock and the search box.</li>
 *   <li>Main area: a two-column grid of module cards (name, info icon with the description on hover, keybind,
 *       switch). Enabled cards get an accent gradient border and a breathing glow.</li>
 *   <li>Clicking a card slides a settings drawer in from the right. Right-click toggles, middle-click binds.</li>
 * </ul>
 * Every animation is time based (real seconds, eased curves), so it looks the same at 30 and 240 fps. Layout is
 * rebuilt each frame and recorded as hit regions, so input always matches what is drawn.
 */
public class ClickGuiScreen extends Screen {
    private static final String VERSION = "v3.0.0";
    private static final float R = 12, PAD = 6, CARD_GAP = 6, ROW = 24, ROW_NUM = 32;
    private static final float OPEN_S = 0.26f, CLOSE_S = 0.17f, DRAWER_S = 0.30f, STAGGER_S = 0.035f, ENTER_S = 0.32f;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private enum Panel { NONE, MODULE, SETTINGS, THEME, ACCOUNT }

    // Persistent between openings (saved with the config)
    private static Category category = Category.HUNTING;
    private static Module selected;
    private static Panel panel = Panel.NONE;
    private static float offX, offY;

    public static JsonObject saveState() {
        JsonObject o = new JsonObject();
        o.addProperty("category", category.name());
        o.addProperty("selected", selected == null ? "" : selected.name);
        o.addProperty("panel", panel.name());
        o.addProperty("x", offX);
        o.addProperty("y", offY);
        return o;
    }

    public static void loadState(JsonObject o) {
        try {
            if (o.has("category")) category = Category.valueOf(o.get("category").getAsString());
        } catch (IllegalArgumentException ignored) { }
        if (o.has("selected")) selected = ModuleManager.byName(o.get("selected").getAsString());
        try {
            panel = o.has("panel") ? Panel.valueOf(o.get("panel").getAsString()) : Panel.NONE;
        } catch (IllegalArgumentException e) { panel = Panel.NONE; }
        if (panel == Panel.MODULE && selected == null) panel = Panel.NONE;
        if (o.has("x")) offX = o.get("x").getAsFloat();
        if (o.has("y")) offY = o.get("y").getAsFloat();
    }

    /** Test hook: show a module's category with its settings drawer open. */
    public static void focus(Module mod) { category = mod.category; selected = mod; panel = Panel.MODULE; }

    /** Test hook: show a category's grid with the drawer closed. */
    public static void focusGrid(Category c) { category = c; panel = Panel.NONE; }

    /** Test hook: show the theme drawer. */
    public static void focusTheme() { panel = Panel.THEME; }

    /** Test hook: open the account page. */
    public static void focusAccount() { panel = Panel.ACCOUNT; }

    // ------------------------------------------------------------------ hit regions

    private enum HitType {
        CATEGORY, GENERAL, CARD, SWITCH, INFO, SEARCH, DRAG, GRID_AREA, DRAWER_AREA, DIM, CLOSE, MOD_TOGGLE,
        BOOL, NUMBER, MODE_PREV, MODE_NEXT, MODE_SEG, BIND, BIND_HOLD, BIND_PLACE, THEME, BLOCK_SEARCH, BLOCK_ADD, BLOCK_COLOR, BLOCK_REMOVE
    }

    private record Hit(HitType type, float x, float y, float w, float h, Object a, Object b) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Hit> hits = new ArrayList<>();

    // ------------------------------------------------------------------ state

    private boolean opened, closing;
    private float openT, drawerT, scale = 1f, mx, my;
    private Panel shownPanel = Panel.NONE;
    private Module shownModule;
    private float panelSwitchAt;
    private boolean dragging;
    private float dragDX, dragDY;
    private NumberSetting slider;
    private float sliderX, sliderW;
    private Module listening;
    private boolean listeningPlace;
    private String search = "";
    private boolean searchFocus;
    private Module hoverCard;
    /** Click ripples in window space: x, y, start millis. */
    private final List<float[]> ripples = new ArrayList<>();
    private final Anim catPill = new Anim(category.ordinal(), 18f);
    private final Anim generalPill = new Anim(0, 18f);
    private final Anim searchGlow = new Anim(0, 14f);
    private final Anim gridScroll = new Anim(0, 16f), drawerScroll = new Anim(0, 16f);
    private float gridTarget, drawerTarget, gridMax, drawerMax;
    private float winX, winY, winW, winH;
    /** Card positions inside the grid (unscrolled), eased so cards glide when the list changes. */
    private final Map<Module, float[]> cardPos = new HashMap<>();
    /** When each card started its entrance (seconds), for the staggered fade and slide. */
    private final Map<Module, Float> cardIn = new HashMap<>();
    private boolean staggerNext = true;
    private Module infoHover;
    private float infoSince;

    public ClickGuiScreen() {
        super(Text.literal(Refract.NAME));
        if (pendingBlockQuery != null) { blockQuery = pendingBlockQuery; blockFocus = pendingBlockList; pendingBlockQuery = null; pendingBlockList = null; }
        if (panel != Panel.NONE) {
            shownPanel = panel;
            shownModule = selected;
            drawerT = 1f; // reopened with the drawer it was closed with: it arrives with the window, no extra slide
        }
    }

    @Override public boolean shouldPause() { return false; }

    private static boolean animated() { return ModuleManager.clickGui.animations.on(); }

    /** The window transform (open scale + 3D tilt), kept so mouse input maps back through exactly the same matrix. */
    private final org.joml.Matrix3x2f view = new org.joml.Matrix3x2f(), viewInv = new org.joml.Matrix3x2f();
    private final Anim tiltX = new Anim(0, 6f), tiltY = new Anim(0, 6f);

    /** Maps a screen point through the inverse window transform. */
    private float[] toLocal(double x, double y) {
        return new float[]{viewInv.m00 * (float) x + viewInv.m10 * (float) y + viewInv.m20, viewInv.m01 * (float) x + viewInv.m11 * (float) y + viewInv.m21};
    }

    private boolean over(float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    /** Moves {@code v} toward {@code target} at a constant rate so a full change takes {@code seconds}. */
    private static float approach(float v, float target, float dt, float seconds) {
        if (!animated()) return target;
        float step = dt / seconds;
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    /** One curve both ways, so closing part-way through opening never jumps. */
    private float openEase() { return Anim.easeOutCubic(openT); }

    // ------------------------------------------------------------------ background

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Theme t = Themes.current();
        if (ModuleManager.clickGui.blur.on() && !Gfx.liquid()) ctx.applyBlur();
        if (ModuleManager.clickGui.dim.on()) {
            Gfx.begin(ctx);
            Gfx.alpha = openEase();
            Gfx.gradientV(0, 0, width, height, Colors.mulAlpha(t.vignette(), 0.6f), t.vignette());
            Gfx.end();
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        float dt = Time.dt();
        if (!opened) { opened = true; Sfx.open(); }
        openT = approach(openT, closing ? 0f : 1f, dt, closing ? CLOSE_S : OPEN_S);
        if (closing && openT <= 0f) { finishClose(); return; }
        Theme t = Themes.current();
        float e = openEase();
        // Fixed virtual layout, scaled to fit the window. Without this the window shrank with the screen while its
        // contents kept their size, which overlapped the dock rows and clipped the account card.
        float fit = Math.min(1f, Math.min((width - 16f) / 600f, (height - 16f) / 360f));
        scale = fit * (0.965f + 0.035f * e);
        // 3D tilt: the window leans toward the mouse (a gentle shear that eases), like a pane of glass turning to face you.
        boolean tiltOn = ModuleManager.clickGui.tilt.on() && animated();
        tiltX.to(tiltOn ? MathHelper.clamp((mouseX - width / 2f) / (width / 2f), -1, 1) : 0).update(dt);
        tiltY.to(tiltOn ? MathHelper.clamp((mouseY - height / 2f) / (height / 2f), -1, 1) : 0).update(dt);
        view.identity().translate(width / 2f, height / 2f).scale(scale, scale)
                .mul(new org.joml.Matrix3x2f(1f, tiltX.value * -0.035f, tiltY.value * -0.03f, 1f, 0f, 0f))
                .translate(-width / 2f, -height / 2f);
        view.invert(viewInv);
        float[] lm = toLocal(mouseX, mouseY);
        mx = lm[0];
        my = lm[1];

        winW = 600;
        winH = 360;
        float roomX = Math.max(0, (width / fit - winW) / 2f), roomY = Math.max(0, (height / fit - winH) / 2f);
        if (dragging) { offX = mx - dragDX - (width - winW) / 2f; offY = my - dragDY - (height - winH) / 2f; }
        offX = MathHelper.clamp(offX, -roomX, roomX);
        offY = MathHelper.clamp(offY, -roomY, roomY);
        // The view scales around the middle of the screen, so the window is centred in un-scaled coordinates too.
        winX = Math.round((width - winW) / 2f + offX);
        winY = (height - winH) / 2f + offY + (1 - e) * 14;
        if (slider != null) slider.setFrac((mx - sliderX) / sliderW);

        // Drawer: slide in when a panel is chosen, keep drawing the old one while it slides out.
        if (panel != Panel.NONE && (panel != shownPanel || (panel == Panel.MODULE && selected != shownModule))) {
            boolean wasOpen = shownPanel != Panel.NONE && drawerT > 0.05f;
            shownPanel = panel;
            shownModule = selected;
            drawerTarget = 0;
            drawerScroll.snap(0);
            panelSwitchAt = wasOpen ? Time.seconds() : -10f;
        }
        drawerT = approach(drawerT, panel != Panel.NONE ? 1f : 0f, dt, DRAWER_S);
        if (drawerT <= 0f && panel == Panel.NONE) shownPanel = Panel.NONE;

        hits.clear();
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.mul(view);
        Gfx.begin(ctx);
        Gfx.alpha = e;

        dockTip = null;
        drawAmbient(t);
        float dockW = 50, gap = 8, headH = 31;
        drawDock(t, dt, winX, winY, dockW, winH);
        float cx = winX + dockW + gap, cw = winX + winW - cx;
        drawHeader(t, dt, cx, winY, cw, headH);
        drawContent(t, dt, cx, winY + headH + gap, cw, winH - headH - gap);
        drawDockTip(t);
        drawRipples();
        Gfx.end();
        m.popMatrix();
        drawAccountAvatar(ctx);
    }

    /** Four additive glows drifting behind the window: cheap, always moving, and different every second. */
    private void drawAmbient(Theme t) {
        if (!animated()) return;
        double now = Time.seconds();
        float cx = winX + winW / 2f, cy = winY + winH / 2f;
        int[] cols = {t.accent(), t.accent2(), t.accent3()};
        for (int i = 0; i < 4; i++) {
            double ph = now * (0.13 + i * 0.035) + i * 1.7;
            float x = cx + (float) Math.cos(ph) * (winW * 0.42f);
            float y = cy + (float) Math.sin(ph * 1.3) * (winH * 0.36f);
            Gfx.light(x, y, 130 + i * 26, Colors.withAlpha(cols[i % 3], 0x18));
        }
    }

    /** Expanding rings where the mouse last clicked. */
    private void drawRipples() {
        long now = System.currentTimeMillis();
        ripples.removeIf(r -> now - (long) r[2] > 620);
        for (float[] r : ripples) {
            float p = Anim.clamp01((now - r[2]) / 620f);
            float rad = 5 + 92 * Anim.easeOutCubic(p);
            Gfx.ring(r[0], r[1], rad, 2, Colors.withAlpha(Themes.current().accent(), (int) (0xB4 * (1 - p))));
            Gfx.ring(r[0], r[1], rad * 0.6f, 1, Colors.withAlpha(0xFFFFFFFF, (int) (0x5A * (1 - p))));
        }
    }

    // ------------------------------------------------------------------ account

    private float avatarX, avatarY, avatarW, avatarH;
    private boolean avatarReady;

    /** Stats card: a live 3D player on a showcase plate on the left, DonutSMP numbers on the right. */
    private float accountBody(Theme t, float x, float y, float w, float ly, float lh) {
        float cw = Math.min(224, Math.max(194, w * 0.35f));
        float ch = Math.max(190, lh - 10);
        Gfx.shadow(x, y, cw, ch, 18, 14, 0x48000000);
        Gfx.glassBody(t, x, y, cw, ch, 18, 0.35f);
        // Player identity.
        Gfx.text("PLAYER", x + 14, y + 12, Gfx.SMALL - 1, Font.Weight.BOLD, Colors.withAlpha(t.accent(), 0xC0));
        Gfx.text(Gfx.ellipsize(playerName(), cw - 28, Gfx.BODY + 5, Font.Weight.BOLD), x + 14, y + 21, Gfx.BODY + 5, Font.Weight.BOLD, t.text());
        Gfx.text("DonutSMP", x + 14, y + 39, Gfx.SMALL - 0.5f, Font.Weight.SEMIBOLD, t.textDim());
        float pillH = 20, pillY = y + ch - pillH - 10;
        String status = com.trace.refract.util.AccountStats.loading ? "loading…" : com.trace.refract.util.AccountStats.status;
        Gfx.round(x + 10, pillY, cw - 20, pillH, pillH / 2, 0x1AFFFFFF);
        Gfx.outline(x + 10, pillY, cw - 20, pillH, pillH / 2, 1, 0x18FFFFFF, 0x08FFFFFF);
        boolean live = com.trace.refract.util.AccountStats.status.startsWith("live");
        Gfx.circle(x + 19, pillY + pillH / 2, 2.2f, live ? 0xFF5CFFB0 : Colors.withAlpha(t.accent(), 0xE0));
        Gfx.text(Gfx.ellipsize(status, cw - 46, Gfx.SMALL - 0.5f, Font.Weight.SEMIBOLD), x + 25, pillY + 6, Gfx.SMALL - 0.5f, Font.Weight.SEMIBOLD, live ? 0xFF5CFFB0 : t.textDim());
        // Showcase plate behind the character, with room above the head and below the feet.
        float plateX = x + 10, plateY = y + 54, plateW = cw - 20, plateH = pillY - plateY - 9;
        Gfx.roundGradient(plateX, plateY, plateW, plateH, 16, 0x1EFFFFFF, 0x08FFFFFF);
        Gfx.outline(plateX, plateY, plateW, plateH, 16, 1, Colors.withAlpha(t.accent(), 0x38), 0x10FFFFFF);
        Gfx.glow(plateX + plateW / 2, plateY + plateH * 0.6f, plateW * 0.55f, Colors.withAlpha(t.accent(), 0x24));
        Gfx.hline(plateX + 12, plateY + plateH - 14, plateW - 24, 1, Colors.withAlpha(t.accent(), 0x20));
        avatarX = plateX + 4;
        avatarY = plateY + 8;
        avatarW = plateW - 8;
        avatarH = plateH - 20;
        avatarReady = true;

        // Stats grid.
        Gfx.text("DONUTSMP STATS", x + cw + 14, y + 6, Gfx.SMALL - 1, Font.Weight.BOLD, Colors.withAlpha(t.textDim(), 0xB0));
        String[][] tiles = {
                {"Money", com.trace.refract.util.AccountStats.money},
                {"Playtime", com.trace.refract.util.AccountStats.playtime},
                {"Shards", com.trace.refract.util.AccountStats.shards},
                {"Kills", com.trace.refract.util.AccountStats.kills},
                {"Deaths", com.trace.refract.util.AccountStats.deaths},
                {"Mob kills", com.trace.refract.util.AccountStats.mobs},
                {"Blocks placed", com.trace.refract.util.AccountStats.placed},
                {"Blocks broken", com.trace.refract.util.AccountStats.broken},
        };
        float tx = x + cw + 12, tw = w - cw - 12, gap = 8, gridY = y + 18;
        float cellW = (tw - gap) / 2f, cellH = Math.max(36, (ch - 18 - 3 * gap) / 4f);
        int[] dots = {t.accent(), t.accent2(), t.accent3(), Colors.lerp(t.accent(), 0xFFFFFFFF, 0.3f)};
        for (int i = 0; i < tiles.length; i++) {
            float cx = tx + (i % 2) * (cellW + gap), cy = gridY + (i / 2) * (cellH + gap);
            boolean hov = over(cx, cy, cellW, cellH);
            Gfx.round(cx, cy, cellW, cellH, 12, hov ? 0x1EFFFFFF : 0x14FFFFFF);
            Gfx.outline(cx, cy, cellW, cellH, 12, 1, 0x18FFFFFF, 0x08FFFFFF);
            Gfx.round(cx + 1.5f, cy + cellH * 0.3f, 2.5f, cellH * 0.4f, 1.25f, dots[i % dots.length]);
            Gfx.text(Gfx.ellipsize(tiles[i][0], cellW - 24, Gfx.SMALL - 0.5f, Font.Weight.SEMIBOLD), cx + 10, cy + 8, Gfx.SMALL - 0.5f, Font.Weight.SEMIBOLD, t.textDim());
            String v = tiles[i][1] == null || tiles[i][1].isBlank() ? "—" : tiles[i][1];
            Gfx.text(Gfx.ellipsize(v, cellW - 22, Gfx.BODY + 3, Font.Weight.BOLD), cx + 10, cy + cellH - 22, Gfx.BODY + 3, Font.Weight.BOLD, t.text());
        }
        return y + ch + 10;
    }

    /** The spinning player, drawn after the custom GUI pass so the vanilla entity renderer keeps its own state. */
    private void drawAccountAvatar(DrawContext ctx) {
        var player = net.minecraft.client.MinecraftClient.getInstance().player;
        if (!avatarReady || panel != Panel.ACCOUNT || player == null || openT < 0.45f) return;
        avatarReady = false;
        org.joml.Vector2f p = new org.joml.Vector2f(avatarX, avatarY);
        view.transformPosition(p);
        float ax = p.x, ay = p.y, aw = avatarW * scale, ah = avatarH * scale;
        float spin = (float) Math.sin(Time.seconds() * 0.9) * 170f;
        try {
            // A taller clip box than the character needs: drawEntity scissors to this box, so any spill is what cut
            // the top of the head off before. Size stays under the box height for the same reason.
            net.minecraft.client.gui.screen.ingame.InventoryScreen.drawEntity(ctx,
                    (int) (ax - 6), (int) (ay - 18), (int) (ax + aw + 6), (int) (ay + ah + 4),
                    (int) Math.min(ah * 0.54f, aw * 0.92f), 0f,
                    ax + aw / 2 + spin, ay + ah * 0.55f, player);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ dock

    private String dockTip;
    private float listTop;
    private int scrollToSel = 4;
    private float dockTipX, dockTipY;
    /** Settings body height per module, measured as it is drawn, so expansion animates to the right size. */
    private final Map<Module, Float> bodyH = new HashMap<>();

    /** A floating glass panel: soft shadow plus the glass body in the selected style. */
    private static void floating(Theme t, float x, float y, float w, float h, float r, float density) {
        Gfx.shadow(x, y + 2, w, h, r, 14, 0x48000000);
        Gfx.glassBody(t, x, y, w, h, r, density);
    }

    /**
     * The dock: a floating glass capsule with the logo, one icon per category (selected one sits in a glowing glass
     * bubble, hover shows its name), and Settings, Theme and the HUD editor at the bottom.
     */
    private void drawDock(Theme t, float dt, float x, float y, float w, float h) {
        floating(t, x, y, w, h, Math.min(w / 2, 24), 0.2f);
        Gfx.logo(t, x + w / 2 - 10, y + 8, 20);
        Gfx.textCentered("VIDAL", x + w / 2, y + 32, Gfx.SMALL - 2, Font.Weight.BOLD, Colors.withAlpha(t.text(), 0x70));
        hits.add(new Hit(HitType.DRAG, x, y, w, 48, null, null));
        Category[] cats = Category.values();
        int bottomItems = 4;
        float bub = MathHelper.clamp((h - 76 - 28) / (cats.length + bottomItems) - 5, 17, 26);
        float step = bub + 6;
        float cy = y + 48;
        boolean inCategories = (panel == Panel.NONE || panel == Panel.MODULE) && search.isEmpty();
        for (Category c : cats) {
            boolean sel = inCategories && c == category;
            long on = ModuleManager.byCategory(c).stream().filter(Module::isEnabled).count();
            dockItem(t, x + w / 2, cy + bub / 2, bub, sel, c.label, on, () -> {}, c, null);
            cy += step;
        }
        float by = y + h - 12 - bottomItems * (bub + 6) + 4;
        Gfx.roundGradient(x + 14, by - 8, w - 28, 1, 0.5f, Colors.withAlpha(t.accent(), 0x30), Colors.withAlpha(t.accent2(), 0x30));
        Object[][] general = {{Panel.ACCOUNT, "Account"}, {Panel.SETTINGS, "Settings"}, {Panel.THEME, "Theme"}, {null, "HUD Editor"}};
        for (Object[] g : general) {
            dockItem(t, x + w / 2, by + bub / 2, bub, g[0] != null && panel == g[0], (String) g[1], 0, null, null, g);
            by += bub + 6;
        }
    }

    private void dockItem(Theme t, float cx, float cy, float size, boolean sel, String label, long count, Runnable unused, Category cat, Object[] general) {
        float x = cx - size / 2, y = cy - size / 2;
        boolean hov = over(x, y, size, size);
        if (sel) {
            float pulse = animated() ? 1f + 0.05f * (float) Math.sin(Time.seconds() * 3.4) : 1f;
            Gfx.pushPlace(cx, cy, cx, cy, pulse);
            Gfx.shadow(x, y, size, size, size / 2, 9, Colors.withAlpha(t.accent3(), 0x60));
            Gfx.shadow(x, y, size, size, size / 2, 5, Colors.withAlpha(t.accent(), 0x70));
            Gfx.roundGradientH(x, y, size, size, size / 2, Colors.withAlpha(t.accent(), 0xE0), Colors.withAlpha(t.accent2(), 0xE0));
            Gfx.roundGradient(x, y, size, size * 0.5f, size / 2, 0x40FFFFFF, 0x00FFFFFF);
            Gfx.outline(x, y, size, size, size / 2, 1, 0x90FFFFFF, 0x20FFFFFF);
            Gfx.popPlace();
        } else if (hov) {
            Gfx.round(x, y, size, size, size / 2, 0x1CFFFFFF);
        }
        int col = sel ? 0xFFFFFFFF : Colors.lerp(t.textDim(), t.text(), hov ? 0.7f : 0f);
        if (cat != null) icon(cat, cx, cy, col, t);
        else if ("Account".equals(label)) personIcon(cx, cy, col);
        else if ("Settings".equals(label)) gearIcon(cx, cy, col);
        else if ("Theme".equals(label)) paletteIcon(t, cx, cy, col);
        else hudIcon(cx, cy, col);
        if (count > 0) {
            String n = String.valueOf(count);
            float bw = Math.max(9, Gfx.width(n, Gfx.SMALL - 1, Font.Weight.BOLD) + 5);
            Gfx.round(x + size - bw + 2, y - 1, bw, 9, 4.5f, sel ? 0xFFFFFFFF : t.accent());
            Gfx.textCentered(n, x + size - bw / 2 + 2, y - 0.2f, Gfx.SMALL - 1, Font.Weight.BOLD, sel ? t.accent() : 0xFFFFFFFF);
        }
        if (hov) { dockTip = label; dockTipX = x + size + 10; dockTipY = cy; }
        if (cat != null) hits.add(new Hit(HitType.CATEGORY, x, y, size, size, cat, null));
        else hits.add(new Hit(HitType.GENERAL, x, y, size, size, general[0], general[1]));
    }

    /** Head-and-shoulders avatar glyph in a thin ring, with an online pip. */
    private static void personIcon(float cx, float cy, int col) {
        Gfx.ring(cx, cy, 6.8f, 1, Colors.withAlpha(col, 0x48));
        Gfx.circle(cx, cy - 2.5f, 2.45f, col);
        Gfx.roundGradient(cx - 5.3f, cy + 1.5f, 10.6f, 5.6f, 2.8f, col, Colors.withAlpha(col, 0x70));
        Gfx.circle(cx + 5.1f, cy - 5.1f, 1.35f, 0xFF5CFFB0);
        Gfx.ring(cx + 5.1f, cy - 5.1f, 1.35f, 1, 0x40000000);
    }

    /** Two stacked HUD cards with a live accent bar, instead of a single generic window. */
    private static void hudIcon(float cx, float cy, int col) {
        Gfx.outline(cx - 6.4f, cy - 4.4f, 9.6f, 8.2f, 2.4f, 1, Colors.withAlpha(col, 0xB0), Colors.withAlpha(col, 0xB0));
        Gfx.round(cx - 4.9f, cy - 2.9f, 3.4f, 1.3f, 0.65f, col);
        Gfx.round(cx - 4.9f, cy - 0.7f, 5.2f, 1.1f, 0.55f, Colors.withAlpha(col, 0x90));
        Gfx.outline(cx - 1.4f, cy - 0.8f, 8.2f, 6.4f, 2.2f, 1, col, col);
        Gfx.round(cx + 0.1f, cy + 0.6f, 2.9f, 1.2f, 0.6f, col);
        Gfx.round(cx + 0.1f, cy + 2.6f, 4.6f, 1.1f, 0.55f, Colors.withAlpha(col, 0x90));
    }

    private void drawDockTip(Theme t) {
        if (dockTip == null) return;
        Gfx.newLayer();
        float w = Gfx.width(dockTip, Gfx.SMALL + 0.5f, Font.Weight.BOLD) + 16;
        Gfx.frost(t, dockTipX, dockTipY - 8, w, 16, 8, 0.5f);
        Gfx.text(dockTip, dockTipX + 8, dockTipY - 4.6f, Gfx.SMALL + 0.5f, Font.Weight.BOLD, t.text());
    }

    // ------------------------------------------------------------------ header

    /** Floating capsule: greeting with your name in the accent, a live clock, and the search field. */
    private void drawHeader(Theme t, float dt, float x, float y, float w, float h) {
        floating(t, x, y, w, h, h / 2, 0.2f);
        hits.add(new Hit(HitType.DRAG, x, y, w, h, null, null));
        float ty = y + h / 2 - 6f, gx = x + 16;
        // Wordmark on a soft plate so it reads over bright scenery, then the build tag.
        Gfx.round(x + 11, y + 5, 103, h - 10, (h - 10) / 2, 0x24000000);
        String brand = "VIDAL";
        for (int i = 0; i < brand.length(); i++) {
            String ch = String.valueOf(brand.charAt(i));
            gx += Gfx.text(ch, gx, ty - 1, Gfx.BODY + 4, Font.Weight.BOLD, t.gradient(i * 0.2f));
            gx += 0.8f;
        }
        float vw = Gfx.width(VERSION, Gfx.SMALL - 1, Font.Weight.BOLD) + 9;
        Gfx.round(gx + 3, y + h / 2 - 6.5f, vw, 13, 6.5f, Colors.withAlpha(t.accent(), 0x26));
        Gfx.textCentered(VERSION, gx + 3 + vw / 2, y + h / 2 - 4.1f, Gfx.SMALL - 1, Font.Weight.BOLD, t.accent());
        gx += vw + 12;
        float sh = h - 10, sw = MathHelper.clamp(w * 0.32f, 104, 178), sx = x + w - 5 - sw, sy = y + 5;
        float avail = sx - 12 - gx;
        if (avail > 96) {
            String greet = playerName();
            float gw = Gfx.width(greet, Gfx.SMALL + 0.5f, Font.Weight.SEMIBOLD);
            float pillW = Math.min(avail, gw + 20);
            Gfx.round(gx, y + h / 2 - 7, pillW, 14, 7, 0x1CFFFFFF);
            Gfx.circle(gx + 8, y + h / 2, 2f, 0xFF5CFFB0);
            Gfx.text(Gfx.ellipsize(greet, pillW - 18, Gfx.SMALL + 0.5f, Font.Weight.SEMIBOLD), gx + 13, ty + 1.5f, Gfx.SMALL + 0.5f, Font.Weight.SEMIBOLD, Colors.withAlpha(t.textDim(), 0xE0));
        }
        searchGlow.to(searchFocus ? 1 : 0).update(animated() ? dt : 1f);
        float sg = searchGlow.value;
        Gfx.round(sx, sy, sw, sh, sh / 2, Colors.withAlpha(0x000000, (int) (0x2C + 0x14 * sg)));
        Gfx.outline(sx, sy, sw, sh, sh / 2, 1, Colors.lerp(0x24FFFFFF, Colors.withAlpha(t.accent(), 0xE0), sg), Colors.lerp(0x08FFFFFF, Colors.withAlpha(t.accent2(), 0x90), sg));
        Gfx.ring(sx + 11, sy + sh / 2 - 0.5f, 3.4f, 1, t.textDim());
        Gfx.round(sx + 13.1f, sy + sh / 2 + 2.2f, 3.2f, 1.3f, 0.6f, t.textDim());
        String shown = search.isEmpty() ? "Search" : search;
        Gfx.scissor(sx + 20, sy, sw - 28, sh);
        float tw = Gfx.text(shown, sx + 20, sy + sh / 2 - 5f, Gfx.BODY, search.isEmpty() ? Colors.withAlpha(t.textDim(), 0xA8) : t.text());
        if (searchFocus && (Time.ms() / 530) % 2 == 0) Gfx.round(sx + 20 + (search.isEmpty() ? 0 : tw) + 0.5f, sy + sh / 2 - 5, 0.8f, 10, 0.4f, t.accent());
        Gfx.unscissor();
        hits.add(new Hit(HitType.SEARCH, sx, sy, sw, sh, null, null));
    }

    // ------------------------------------------------------------------ content

    /**
     * The content panel: a title row, then either the module list (rows that expand in place to show their settings),
     * the client settings, or the theme picker. Everything scrolls together and nothing ever overlaps.
     */
    private void drawContent(Theme t, float dt, float x, float y, float w, float h) {
        floating(t, x, y, w, h, 18, 0.5f);
        boolean searching = !search.isEmpty();
        List<Module> mods = visibleModules();
        if (panel == Panel.ACCOUNT) com.trace.refract.util.AccountStats.refreshIfStale();
        String title = searching ? "Search" : panel == Panel.ACCOUNT ? "Account" : panel == Panel.SETTINGS ? "Settings" : panel == Panel.THEME ? "Theme" : category.label;
        String sub = searching ? mods.size() + (mods.size() == 1 ? " result" : " results")
                : panel == Panel.ACCOUNT ? com.trace.refract.util.AccountStats.status
                : panel == Panel.SETTINGS ? "Window, glass and sound" : panel == Panel.THEME ? "Accent colours for the whole client"
                : mods.stream().filter(Module::isEnabled).count() + " of " + mods.size() + " active  ·  " + category.subtitle;
        // Title glyph in a tinted bubble, then the page title and its one-line summary.
        float ibx = x + 15, iby = y + 10, ibs = 22;
        Gfx.round(ibx, iby, ibs, ibs, 8, Colors.withAlpha(t.accent(), 0x22));
        Gfx.outline(ibx, iby, ibs, ibs, 8, 1, Colors.withAlpha(t.accent(), 0x40), 0x08FFFFFF);
        float icx = ibx + ibs / 2, icy = iby + ibs / 2;
        if (panel == Panel.ACCOUNT) personIcon(icx, icy, t.text());
        else if (panel == Panel.SETTINGS) gearIcon(icx, icy, t.text());
        else if (panel == Panel.THEME) paletteIcon(t, icx, icy, t.text());
        else if (searching) { Gfx.ring(icx, icy, 4.2f, 1, t.text()); Gfx.round(icx + 3.2f, icy + 3.2f, 3.6f, 1.4f, 0.7f, t.text()); }
        else icon(category, icx, icy, t.text(), t);
        Gfx.text(title, x + 46, y + 11, Gfx.LARGE, Font.Weight.BOLD, t.text());
        Gfx.text(Gfx.ellipsize(sub, w - 64, Gfx.SMALL, Font.Weight.REGULAR), x + 46, y + 27, Gfx.SMALL, t.textDim());
        float ly = y + 46, lh = h - 52, lx = x + 10, lw = w - 20;
        listTop = ly + 4;
        Gfx.round(x + 16, ly - 3, w - 32, 1, 0.5f, 0x16FFFFFF);
        hits.add(new Hit(HitType.GRID_AREA, x, ly, w, lh, null, null));
        gridScroll.to(gridTarget).update(animated() ? dt : 1f);
        Gfx.scissor(x + 2, ly, w - 4, lh);
        float yy = ly + 4 - gridScroll.value, top = yy;
        if (!searching && panel == Panel.ACCOUNT) {
            float px = lx + 8, pw = lw - 16;
            yy = accountBody(t, px, yy, pw, ly, lh);
        } else if (!searching && (panel == Panel.SETTINGS || panel == Panel.THEME)) {
            float px = lx + 8, pw = lw - 16;
            if (panel == Panel.THEME) yy = themeBody(t, px, yy, pw, ly, lh);
            else {
                for (Setting<?> s : ModuleManager.clickGui.settings) {
                    if (s == ModuleManager.clickGui.theme || !s.visible()) continue;
                    yy = drawSetting(t, s, px, yy, pw, ly, lh, dt);
                }
                yy = paragraph(t, "Open this window with Right Shift (change it in Controls). Click a module to see its settings, right-click to toggle, middle-click to bind.", px, yy + 6, pw);
            }
        } else if (mods.isEmpty()) {
            Gfx.textCentered("No modules match", x + w / 2, ly + 30, Gfx.BODY, Font.Weight.BOLD, t.textDim());
        } else {
            float now = Time.seconds();
            cardIn.keySet().retainAll(mods);
            for (int i = 0; i < mods.size(); i++) if (!cardIn.containsKey(mods.get(i))) cardIn.put(mods.get(i), now + (staggerNext ? i * STAGGER_S : 0f));
            staggerNext = false;
            // Floating cards grid: modules are glass tiles; the open one spans the full width with its settings.
            int cols = lw > 560 ? 3 : 2;
            float gap = 8, tw = (lw - gap * (cols - 1)) / cols, th = 60;
            int col = 0;
            for (int i = 0; i < mods.size(); i++) {
                Module mod = mods.get(i);
                float in = animated() ? Anim.easeOutCubic(Anim.clamp01((now - cardIn.get(mod)) / ENTER_S)) : 1f;
                float prev = Gfx.alpha;
                Gfx.alpha = prev * in;
                boolean open = panel == Panel.MODULE && selected == mod;
                if (open) {
                    if (col > 0) { yy += th + gap; col = 0; }
                    yy = drawRow(t, mod, lx, yy + (1 - in) * 8, lw, dt, ly, lh) - (1 - in) * 8 + gap - 4;
                } else {
                    drawTile(t, mod, lx + col * (tw + gap), yy + (1 - in) * 10, tw, th, i, dt, ly, lh);
                    if (++col == cols) { col = 0; yy += th + gap; }
                }
                Gfx.alpha = prev;
            }
            if (col > 0) yy += th + gap;
        }
        Gfx.unscissor();
        gridMax = Math.max(0, (yy - top) + 10 - lh);
        gridTarget = MathHelper.clamp(gridTarget, 0, gridMax);
        if (gridMax > 0) scrollbar(x + w - 7, ly + 4, lh - 8, gridScroll.value, gridMax);
    }

    /**
     * One module as a floating glass card: category icon in a coloured bubble, name, description, key chip and switch.
     * Hover lifts and slightly enlarges it; enabled cards carry a glowing accent-gradient rim.
     */
    private void drawTile(Theme t, Module mod, float x, float y, float w, float h, int idx, float dt, float clipY, float clipH) {
        float adt = animated() ? dt : 1f;
        boolean inClip = my >= clipY && my < clipY + clipH;
        boolean hov = inClip && slider == null && !dragging && over(x, y, w, h);
        mod.hover.to(hov ? 1 : 0).update(adt);
        mod.toggleAnim.to(mod.isEnabled() ? 1 : 0).update(adt);
        if (hov && hoverCard != mod) { hoverCard = mod; Sfx.hover(); }
        if (!hov && hoverCard == mod) hoverCard = null;
        float hv = Anim.easeOutCubic(mod.hover.value), on = mod.toggleAnim.value;
        float cy = y - hv * 2.5f, cx = x + w / 2, mid = cy + h / 2;
        Gfx.pushPlace(cx, mid, cx, mid, 1f + 0.025f * hv);
        float r = 14;
        if (on > 0.01f) {
            float pulse = animated() ? 0.5f + 0.5f * MathHelper.sin(Time.seconds() * 2.2f + idx * 0.8f) : 0.6f;
            Gfx.shadow(x, cy, w, h, r, 10 + 3 * pulse, Colors.withAlpha(Colors.lerp(t.accent(), t.accent3(), 0.4f), (int) ((0x30 + 0x24 * pulse) * on)));
        }
        Gfx.shadow(x, cy + 2 + hv * 2, w, h, r, 8 + hv * 6, Colors.withAlpha(0x000000, (int) (0x40 + 0x28 * hv)));
        Gfx.glassBody(t, x, cy, w, h, r, 0.32f + 0.1f * hv);
        if (hv > 0.01f) {
            // A soft light that follows the cursor inside the card, so the surface feels alive under the mouse.
            Gfx.glow(mx, my, 48, Colors.withAlpha(t.accent(), (int) (0x26 * hv)));
            Gfx.glow(mx, my, 18, Colors.withAlpha(0xFFFFFFFF, (int) (0x12 * hv)));
        }
        if (on > 0.01f) {
            Gfx.roundGradientH(x, cy, w, h, r, Colors.withAlpha(t.accent(), (int) (0x22 * on)), Colors.withAlpha(t.accent2(), (int) (0x08 * on)));
            Gfx.outline(x, cy, w, h, r, 1, Colors.withAlpha(t.accent(), (int) (0xE0 * on)), Colors.withAlpha(t.accent3(), (int) (0xB0 * on)));
        }
        // Icon bubble.
        float bx = x + 10, by = cy + 10, bs = 20;
        if (on > 0.01f) Gfx.roundGradientH(bx, by, bs, bs, 7, Colors.withAlpha(t.accent(), (int) (0xE0 * on)), Colors.withAlpha(t.accent2(), (int) (0xE0 * on)));
        if (on < 0.99f) Gfx.round(bx, by, bs, bs, 7, Colors.withAlpha(0xFFFFFF, (int) (0x1A * (1 - on))));
        icon(mod.category, bx + bs / 2, by + bs / 2, on > 0.5f ? 0xFFFFFFFF : Colors.lerp(t.textDim(), t.text(), hv), t);
        // Switch top-right, text below.
        float swW = 24, swH = 13, swX = x + w - swW - 10, swY = cy + 13;
        Gfx.toggle(t, swX, swY, swW, swH, on);
        float tx = x + 10, maxW = w - 20;
        Gfx.text(Gfx.ellipsize(mod.name, swX - 6 - (bx + bs + 7), Gfx.BODY + 0.5f, Font.Weight.BOLD), bx + bs + 7, by + 4.5f, Gfx.BODY + 0.5f, Font.Weight.BOLD,
                Colors.lerp(t.textDim(), t.text(), 0.6f + 0.4f * Math.max(on, hv)));
        String sub = listening == mod ? "Press a key…" : mod.description;
        int subCol = listening == mod ? t.accent() : Colors.withAlpha(t.textDim(), 0xD0);
        float kw = 0;
        if (mod.key >= 0 && listening != mod) {
            String k = keyName(mod.key);
            kw = Gfx.width(k, Gfx.SMALL - 0.5f, Font.Weight.BOLD) + 10;
            Gfx.glassBody(t, x + w - 10 - kw, cy + h - 18, kw, 12, 6, 0.35f);
            Gfx.textCentered(k, x + w - 10 - kw / 2, cy + h - 16.2f, Gfx.SMALL - 0.5f, Font.Weight.BOLD, t.textDim());
            kw += 6;
        }
        Gfx.text(Gfx.ellipsize(sub, maxW - kw, Gfx.SMALL, Font.Weight.REGULAR), tx, cy + h - 17, Gfx.SMALL, subCol);
        Gfx.popPlace();
        if (inClip) {
            hits.add(new Hit(HitType.CARD, x, cy, w, h, mod, null));
            hits.add(new Hit(HitType.SWITCH, swX - 4, swY - 4, swW + 8, swH + 8, mod, null));
        }
    }

    /** One module row (status light, name, description, key, switch, chevron) and, when open, its settings under it. */
    private float drawRow(Theme t, Module mod, float x, float y, float w, float dt, float clipY, float clipH) {
        float h = 34, adt = animated() ? dt : 1f;
        boolean expanded = panel == Panel.MODULE && selected == mod;
        boolean inClip = my >= clipY && my < clipY + clipH;
        boolean hov = inClip && slider == null && !dragging && over(x, y, w, h);
        mod.hover.to(hov ? 1 : 0).update(adt);
        mod.toggleAnim.to(mod.isEnabled() ? 1 : 0).update(adt);
        mod.select.to(expanded ? 1 : 0).update(adt);
        if (hov && hoverCard != mod) { hoverCard = mod; Sfx.hover(); }
        if (!hov && hoverCard == mod) hoverCard = null;
        float hv = mod.hover.value, on = mod.toggleAnim.value, ex = Anim.easeInOutCubic(mod.select.value);
        float body = bodyH.getOrDefault(mod, 0f) * ex;
        float total = h + body;
        // Row surface: invisible at rest, a soft glass lift on hover, an inset glass card when open.
        if (ex > 0.01f) {
            Gfx.round(x, y, w, total, 12, Colors.withAlpha(0xFFFFFF, (int) (0x10 * ex)));
            Gfx.outline(x, y, w, total, 12, 1, Colors.withAlpha(0xFFFFFF, (int) (0x30 * ex)), Colors.withAlpha(0xFFFFFF, (int) (0x0A * ex)));
        }
        if (hv > 0.01f) Gfx.round(x, y, w, h, 12, Colors.withAlpha(0xFFFFFF, (int) (0x0E * hv)));
        // Status light: a glowing accent dot when on, a hollow ring when off.
        float lx = x + 14, lyc = y + h / 2;
        if (on > 0.01f) Gfx.glow(lx, lyc, 9, Colors.withAlpha(t.accent(), (int) (0x70 * on)));
        Gfx.ring(lx, lyc, 4f, 1, Colors.withAlpha(Colors.lerp(t.textDim(), t.accent(), on), 0xD0));
        if (on > 0.01f) Gfx.circle(lx, lyc, 2.6f * on, Colors.lerp(t.accent(), 0xFFFFFFFF, 0.25f));
        float tx = x + 26, swW = 24, swH = 13, swX = x + w - swW - 12, swY = y + (h - swH) / 2;
        float right = swX - 8;
        // Chevron (rotates open) and key chip.
        Gfx.arrow(right - 4, y + h / 2, 7, (float) (Math.PI / 2 * ex), Colors.withAlpha(t.textDim(), 0xC0));
        right -= 14;
        if (mod.key != -1 && listening != mod) {
            String k = keyName(mod.key) + (mod.hold ? " ·  hold" : "");
            float kw = Gfx.width(k, Gfx.SMALL - 0.5f, Font.Weight.BOLD) + 10;
            Gfx.round(right - kw, y + h / 2 - 6, kw, 12, 6, 0x1EFFFFFF);
            Gfx.textCentered(k, right - kw / 2, y + h / 2 - 4.2f, Gfx.SMALL - 0.5f, Font.Weight.BOLD, t.textDim());
            right -= kw + 6;
        }
        float maxText = right - tx - 4;
        Gfx.text(Gfx.ellipsize(mod.name, maxText, Gfx.BODY + 0.5f, Font.Weight.BOLD), tx, y + 6, Gfx.BODY + 0.5f, Font.Weight.BOLD, Colors.lerp(t.textDim(), t.text(), 0.55f + 0.45f * Math.max(on, hv)));
        if (listening == mod) {
            float pulse = 0.5f + 0.5f * MathHelper.sin(Time.seconds() * 8);
            Gfx.text("Press a key or mouse button…", tx, y + 19, Gfx.SMALL, Font.Weight.BOLD, Colors.lerp(t.accent(), 0xFFFFFFFF, pulse * 0.5f));
        } else {
            Gfx.text(Gfx.ellipsize(mod.description, maxText, Gfx.SMALL, Font.Weight.REGULAR), tx, y + 19, Gfx.SMALL, Colors.withAlpha(t.textDim(), 0xD0));
        }
        Gfx.toggle(t, swX, swY, swW, swH, on);
        if (inClip) {
            hits.add(new Hit(HitType.CARD, x, y, w, h, mod, null));
            hits.add(new Hit(HitType.SWITCH, swX - 4, swY - 4, swW + 8, swH + 8, mod, null));
        }
        if (expanded && scrollToSel > 0) { // bring a freshly opened module into view
            scrollToSel--;
            gridTarget = Math.max(0, y + gridScroll.value - listTop - 4);
            if (!animated() || openT < 0.5f) gridScroll.snap(gridTarget);
        }
        // Settings, revealed by the expansion (clipped to the animated height).
        if (ex > 0.01f) {
            float by = y + h;
            Gfx.round(x + 14, by - 1, w - 28, 1, 0.5f, Colors.withAlpha(0xFFFFFF, (int) (0x14 * ex)));
            float clipTop = Math.max(clipY, by), clipBot = Math.min(clipY + clipH, by + body);
            if (clipBot > clipTop) {
                Gfx.scissor(x, clipTop, w, clipBot - clipTop);
                float prev = Gfx.alpha;
                Gfx.alpha = prev * Anim.clamp01(ex * 1.4f);
                float end = moduleBody(t, mod, x + 26, by + 8, w - 26 - 16, clipTop, clipBot - clipTop, dt);
                Gfx.alpha = prev;
                Gfx.unscissor();
                bodyH.put(mod, end - (by + 8) + 14);
            } else if (!bodyH.containsKey(mod)) bodyH.put(mod, 120f);
        }
        return y + total + 4;
    }

    /** Selected-row highlight: accent gradient wash with a gradient bar on the left. */
    private static void pill(Theme t, float x, float y, float w, float h) {
        Gfx.roundGradientH(x, y, w, h, 6, Colors.withAlpha(t.accent(), 0x42), Colors.withAlpha(t.accent2(), 0x10));
        Gfx.outline(x, y, w, h, 6, 1, Colors.withAlpha(t.accent(), 0x40), Colors.withAlpha(t.accent2(), 0x18));
        Gfx.roundGradient(x + 1.5f, y + 4, 2.5f, h - 8, 1.25f, t.accent(), t.accent2());
    }

    private static void badge(Theme t, String n, float right, float cy, boolean sel) {
        float bw = Math.max(13, Gfx.width(n, Gfx.SMALL, Font.Weight.BOLD) + 7), bh = 11;
        float bx = right - bw, by = cy - bh / 2;
        if (sel) Gfx.roundGradientH(bx, by, bw, bh, bh / 2, t.accent(), t.accent2());
        else Gfx.round(bx, by, bw, bh, bh / 2, Colors.withAlpha(t.accent(), 0x30));
        Gfx.textCentered(n, bx + bw / 2, by + 1.6f, Gfx.SMALL, Font.Weight.BOLD, sel ? 0xFFFFFFFF : t.accent());
    }

    private String playerName() {
        if (client == null) return "Player";
        if (client.player != null) return client.player.getName().getString();
        try { return client.getSession().getUsername(); } catch (Throwable ignored) { return "Player"; }
    }

    /** Small vector icons drawn from primitives (no textures). */
    /**
     * Category icons: clean anti-aliased line glyphs with one or two features in the theme's accent colours, so each
     * icon carries colour, not just a single tone. Selected icons sit on the gradient bubble in white with a
     * third-accent highlight.
     */
    private void icon(Category c, float cx, float cy, int col, Theme t) {
        boolean sel = col == 0xFFFFFFFF;
        int line = col;
        int a1 = sel ? Colors.lerp(t.accent3(), 0xFFFFFFFF, 0.35f) : t.accent();
        int a2 = sel ? 0xFFFFFFFF : t.accent2();
        int a3 = sel ? Colors.lerp(t.accent3(), 0xFFFFFFFF, 0.6f) : t.accent3();
        switch (c) {
            case COMBAT -> {
                // Crossed blades with guards and pommels; one arm in the theme accent, the other in line colour.
                Gfx.stroke(cx - 5f, cy + 5.4f, cx + 5.4f, cy - 5.4f, 1.9f, line);
                Gfx.stroke(cx + 5f, cy + 5.4f, cx - 5.4f, cy - 5.4f, 1.9f, a1);
                Gfx.stroke(cx - 6.6f, cy + 2.3f, cx - 2.3f, cy + 6.6f, 1.5f, a3);
                Gfx.stroke(cx + 6.6f, cy + 2.3f, cx + 2.3f, cy + 6.6f, 1.5f, a3);
                Gfx.circle(cx - 5.9f, cy + 5.9f, 1.25f, a1);
                Gfx.circle(cx + 5.9f, cy + 5.9f, 1.25f, a2);
                Gfx.circle(cx, cy - 6.3f, 0.8f, 0xFFFFFFFF);
            }
            case HUNTING -> {
                // Radar: two rings, crosshair ticks, sweep hand and two targets.
                Gfx.ring(cx, cy, 6.1f, 1, line);
                Gfx.ring(cx, cy, 2.9f, 1, Colors.mulAlpha(line, 0.55f));
                Gfx.stroke(cx, cy - 6.9f, cx, cy - 5.2f, 1f, a3);
                Gfx.stroke(cx, cy + 5.2f, cx, cy + 6.9f, 1f, a3);
                Gfx.stroke(cx - 6.9f, cy, cx - 5.2f, cy, 1f, a3);
                Gfx.stroke(cx + 5.2f, cy, cx + 6.9f, cy, 1f, a3);
                double a = Time.seconds() * (sel ? 2.1 : 0.0) - 0.8;
                Gfx.stroke(cx, cy, cx + (float) Math.cos(a) * 5.4f, cy + (float) Math.sin(a) * 5.4f, 1.3f, a1);
                Gfx.circle(cx + 3.1f, cy - 2.9f, 1.7f, a2);
                Gfx.circle(cx - 2.5f, cy + 3.2f, 1.15f, a3);
                Gfx.circle(cx, cy, 1.2f, 0xFFFFFFFF);
            }
            case RENDER -> {
                // Eye with a gradient iris, upper lid and two light glints.
                Gfx.stroke(cx - 6.8f, cy, cx - 2.4f, cy - 4f, 1.35f, line);
                Gfx.stroke(cx - 2.4f, cy - 4f, cx + 2.4f, cy - 4f, 1.35f, line);
                Gfx.stroke(cx + 2.4f, cy - 4f, cx + 6.8f, cy, 1.35f, line);
                Gfx.stroke(cx - 6.8f, cy, cx - 2.4f, cy + 4f, 1.35f, line);
                Gfx.stroke(cx - 2.4f, cy + 4f, cx + 2.4f, cy + 4f, 1.35f, line);
                Gfx.stroke(cx + 2.4f, cy + 4f, cx + 6.8f, cy, 1.35f, line);
                Gfx.circle(cx, cy, 3f, a1);
                Gfx.circle(cx, cy, 1.55f, a2);
                Gfx.circle(cx - 0.9f, cy - 1f, 0.7f, 0xFFFFFFFF);
                Gfx.circle(cx + 6.9f, cy - 5.6f, 0.65f, a3);
            }
            case MISC -> {
                // Compass rose: ring, four ticks and a two-tone needle.
                Gfx.ring(cx, cy, 6f, 1, line);
                Gfx.stroke(cx, cy - 6.9f, cx, cy - 5.1f, 1f, Colors.mulAlpha(line, 0.5f));
                Gfx.stroke(cx, cy + 5.1f, cx, cy + 6.9f, 1f, Colors.mulAlpha(line, 0.5f));
                Gfx.stroke(cx - 6.9f, cy, cx - 5.1f, cy, 1f, Colors.mulAlpha(line, 0.5f));
                Gfx.stroke(cx + 5.1f, cy, cx + 6.9f, cy, 1f, Colors.mulAlpha(line, 0.5f));
                Gfx.stroke(cx, cy + 4.4f, cx, cy - 4.4f, 2.1f, a1);
                Gfx.stroke(cx - 2.5f, cy, cx + 2.5f, cy, 1.4f, a3);
                Gfx.circle(cx, cy, 1.25f, 0xFFFFFFFF);
            }
            case CLIENT -> {
                // Brand shield with the Vidal V cut into it.
                Gfx.stroke(cx - 5.6f, cy - 4.2f, cx, cy - 6.4f, 1.4f, line);
                Gfx.stroke(cx + 5.6f, cy - 4.2f, cx, cy - 6.4f, 1.4f, line);
                Gfx.stroke(cx - 5.6f, cy - 4.2f, cx - 5.6f, cy + 1.4f, 1.4f, line);
                Gfx.stroke(cx + 5.6f, cy - 4.2f, cx + 5.6f, cy + 1.4f, 1.4f, line);
                Gfx.stroke(cx - 5.6f, cy + 1.4f, cx, cy + 6.6f, 1.4f, line);
                Gfx.stroke(cx + 5.6f, cy + 1.4f, cx, cy + 6.6f, 1.4f, line);
                Gfx.stroke(cx - 2.5f, cy - 2.1f, cx, cy + 2.2f, 2f, a1);
                Gfx.stroke(cx + 2.5f, cy - 2.1f, cx, cy + 2.2f, 2f, a2);
                Gfx.circle(cx, cy - 2.6f, 0.65f, 0xFFFFFFFF);
            }
        }
    }

    /** Settings gear: eight beveled teeth, a thick ring and a clean hole with an accent rim. */
    private static void gearIcon(float cx, float cy, int col) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float x1 = cx + (float) Math.cos(a) * 3.7f, y1 = cy + (float) Math.sin(a) * 3.7f;
            float x2 = cx + (float) Math.cos(a) * 6.1f, y2 = cy + (float) Math.sin(a) * 6.1f;
            Gfx.stroke(x1, y1, x2, y2, 2.2f, col);
        }
        Gfx.circle(cx, cy, 4.5f, col);
        Gfx.circle(cx, cy, 2.3f, Colors.shade(col, -0.55f));
        Gfx.circle(cx, cy, 1.35f, Colors.withAlpha(col, 0x90));
        Gfx.circle(cx - 0.7f, cy - 0.8f, 0.45f, 0xFFFFFFFF);
    }

    /** Painter's palette: rounded body, thumb hole, three theme swatches and a brush stroke. */
    private static void paletteIcon(Theme t, float cx, float cy, int col) {
        Gfx.round(cx - 6f, cy - 5.4f, 12f, 10.8f, 5.2f, Colors.withAlpha(col, 0x26));
        Gfx.outline(cx - 6f, cy - 5.4f, 12f, 10.8f, 5.2f, 1, col, Colors.withAlpha(col, 0xB0));
        Gfx.circle(cx + 1.3f, cy + 1.6f, 1.8f, Colors.withAlpha(0x000000, 0x50));
        Gfx.circle(cx - 2.6f, cy - 1.9f, 1.35f, t.accent());
        Gfx.circle(cx + 0.7f, cy - 2.8f, 1.35f, t.accent2());
        Gfx.circle(cx + 3.4f, cy - 0.9f, 1.35f, t.accent3());
        Gfx.stroke(cx - 4.6f, cy + 4.2f, cx - 1.8f, cy + 1.4f, 1.2f, 0xFFFFFFFF);
    }


    private List<Module> visibleModules() {
        List<Module> out = new ArrayList<>();
        String q = search.trim().toLowerCase(Locale.ROOT);
        for (Module mod : ModuleManager.MODULES) {
            if (q.isEmpty() ? mod.category == category
                    : mod.name.toLowerCase(Locale.ROOT).contains(q) || mod.description.toLowerCase(Locale.ROOT).contains(q)) out.add(mod);
        }
        return out;
    }

    private float paragraph(Theme t, String text, float x, float y, float w) {
        for (String line : wrap(text, w - 2, Gfx.SMALL)) {
            Gfx.text(line, x, y, Gfx.SMALL, t.textDim());
            y += 10.5f;
        }
        return y + 6;
    }

    private float moduleBody(Theme t, Module mod, float px, float yy, float pw, float clipY, float clipH, float dt) {
        boolean any = false;
        for (Setting<?> s : mod.settings) {
            if (!s.visible()) continue;
            yy = drawSetting(t, s, px, yy, pw, clipY, clipH, dt);
            any = true;
        }
        if (!any) { Gfx.text("No settings", px, yy + 4, Gfx.SMALL, Font.Weight.SEMIBOLD, Colors.withAlpha(t.textDim(), 0xA0)); yy += 18; }

        // Keybind row.
        boolean rowVisible = yy + ROW > clipY && yy < clipY + clipH;
        Gfx.text("Keybind", px, yy + 7, Gfx.BODY, t.text());
        String kv = listening == mod ? "Press a key…" : keyName(mod.key);
        float kw = Math.max(44, Gfx.width(kv, Gfx.SMALL, Font.Weight.BOLD) + 16);
        boolean khov = over(px + pw - kw, yy + 4, kw, 17);
        if (listening == mod) Gfx.roundGradientH(px + pw - kw, yy + 4, kw, 17, 8.5f, Colors.withAlpha(t.accent(), 0xB0), Colors.withAlpha(t.accent2(), 0xB0));
        else Gfx.glassBody(Themes.current(), px + pw - kw, yy + 4, kw, 17, 8.5f, khov ? 0.2f : 0.35f);
        Gfx.textCentered(kv, px + pw - kw / 2, yy + 8, Gfx.SMALL, Font.Weight.BOLD, t.text());
        if (rowVisible) hits.add(new Hit(HitType.BIND, px + pw - kw, yy + 4, kw, 17, mod, null));
        yy += ROW;

        // Bind mode: toggle on press, or only while held (what macros and crystals want).
        boolean holdVisible = yy + ROW > clipY && yy < clipY + clipH;
        Gfx.text("Hold", px, yy + 7, Gfx.BODY, t.text());
        Gfx.text(mod.hold ? "while pressed" : "press to toggle", px + Gfx.width("Hold", Gfx.BODY) + 8, yy + 8.5f, Gfx.SMALL,
                Colors.withAlpha(t.textDim(), 0xC0));
        Gfx.toggle(t, px + pw - 22, yy + 6, 22, 12, mod.hold ? 1f : 0f);
        if (holdVisible) hits.add(new Hit(HitType.BIND_HOLD, px, yy, pw, ROW, mod, null));
        yy += ROW;

        // Anchor Macro: a second bind that fires one whole run without switching the module on.
        if (mod instanceof com.trace.refract.module.modules.AnchorMacro am) {
            boolean placeVisible = yy + ROW > clipY && yy < clipY + clipH;
            Gfx.text("Place Bind", px, yy + 7, Gfx.BODY, t.text());
            String pv = listeningPlace ? "Press a key…" : keyName(am.placeKey);
            float pkw = Math.max(44, Gfx.width(pv, Gfx.SMALL, Font.Weight.BOLD) + 16);
            boolean phov = over(px + pw - pkw, yy + 4, pkw, 17);
            if (listeningPlace) Gfx.roundGradientH(px + pw - pkw, yy + 4, pkw, 17, 8.5f, Colors.withAlpha(t.accent(), 0xB0), Colors.withAlpha(t.accent2(), 0xB0));
            else Gfx.glassBody(Themes.current(), px + pw - pkw, yy + 4, pkw, 17, 8.5f, phov ? 0.2f : 0.35f);
            Gfx.textCentered(pv, px + pw - pkw / 2, yy + 8, Gfx.SMALL, Font.Weight.BOLD, t.text());
            if (placeVisible) hits.add(new Hit(HitType.BIND_PLACE, px + pw - pkw, yy + 4, pkw, 17, mod, null));
            yy += ROW;
        }

        yy = paragraph(t, "Click to bind a key or mouse button, right-click clears. Esc or Backspace removes the bind while listening.", px, yy + 2, pw);
        return yy;
    }

    private float themeBody(Theme t, float px, float yy, float pw, float clipY, float clipH) {
        yy = paragraph(t, "Accent colours for the whole client. Changes fade in smoothly.", px, yy, pw);
        float gap = 6, cw = (pw - gap) / 2f, ch = 36;
        int i = 0;
        for (Theme th : Themes.ALL) {
            float cx = px + (i % 2) * (cw + gap), cy = yy + (i / 2) * (ch + gap);
            boolean sel = th == Themes.target(), hov = over(cx, cy, cw, ch);
            Gfx.round(cx, cy, cw, ch, 8, hov ? 0x22FFFFFF : 0x14FFFFFF);
            Gfx.roundGradientH(cx + 5, cy + 5, cw - 10, 12, 4, th.accent(), th.accent2());
            Gfx.text(th.name(), cx + 6, cy + 21, Gfx.SMALL, Font.Weight.SEMIBOLD, sel ? t.text() : t.textDim());
            if (sel) {
                Gfx.outline(cx, cy, cw, ch, 8, 1, th.accent(), th.accent2());
                Gfx.circle(cx + cw - 9, cy + 25, 3.5f, th.accent());
                Gfx.text("✓", cx + cw - 11.6f, cy + 21.2f, Gfx.SMALL - 1, Font.Weight.BOLD, 0xFFFFFFFF);
            } else {
                Gfx.outline(cx, cy, cw, ch, 8, 1, 0x14FFFFFF, 0x08FFFFFF);
            }
            if (cy + ch > clipY && cy < clipY + clipH) hits.add(new Hit(HitType.THEME, cx, cy, cw, ch, th.name(), null));
            i++;
        }
        return yy + ((Themes.ALL.size() + 1) / 2) * (ch + gap);
    }

    private com.trace.refract.setting.BlockListSetting blockFocus;
    private String blockQuery = "";

    /** Test hook: open the next screen with this text already typed in the module's block search. */
    public static String pendingBlockQuery;
    public static com.trace.refract.setting.BlockListSetting pendingBlockList;

    /**
     * Block list editor: a search box over every block in the game with a live 3D preview of each result (click or
     * Enter adds it), then the chosen blocks, each with its preview, name, a colour swatch (click to change) and a
     * remove button.
     */
    private float drawBlockList(Theme t, com.trace.refract.setting.BlockListSetting bl, float x, float y, float w, float clipY, float clipH) {
        boolean focus = blockFocus == bl;
        float sh = 18;
        Gfx.round(x, y + 2, w, sh, sh / 2, 0x38000000);
        int border = focus ? Colors.withAlpha(t.accent(), 0xD0) : 0x24FFFFFF;
        Gfx.outline(x, y + 2, w, sh, sh / 2, 1, border, focus ? Colors.withAlpha(t.accent2(), 0x80) : 0x0CFFFFFF);
        Gfx.ring(x + 10, y + 2 + sh / 2 - 0.5f, 3.3f, 1, t.textDim());
        Gfx.round(x + 12, y + 2 + sh / 2 + 2.1f, 3.1f, 1.2f, 0.6f, t.textDim());
        String shown = blockQuery.isEmpty() ? "Search any block..." : blockQuery;
        float tw = Gfx.text(shown, x + 19, y + 2 + sh / 2 - 5f, Gfx.SMALL + 0.5f, blockQuery.isEmpty() ? Colors.withAlpha(t.textDim(), 0xA8) : t.text());
        if (focus && (Time.ms() / 530) % 2 == 0) Gfx.round(x + 19 + (blockQuery.isEmpty() ? 0 : tw) + 0.5f, y + sh / 2 - 2, 0.8f, 9, 0.4f, t.accent());
        boolean vis = y + sh > clipY && y < clipY + clipH;
        if (vis) hits.add(new Hit(HitType.BLOCK_SEARCH, x, y + 2, w, sh, bl, null));
        float yy = y + sh + 6;
        // Results.
        for (net.minecraft.block.Block b : bl.search(blockQuery, 7)) {
            boolean hov = over(x, yy, w, 18);
            Gfx.round(x, yy, w, 18, 6, hov ? Colors.withAlpha(t.accent(), 0x30) : 0x12FFFFFF);
            blockIcon(b, x + 3, yy + 1.5f, 15, 0xFF8A93A6);
            Gfx.text(Gfx.ellipsize(b.getName().getString(), w - 40, Gfx.SMALL + 0.5f, Font.Weight.SEMIBOLD), x + 22, yy + 4.5f, Gfx.SMALL + 0.5f, Font.Weight.SEMIBOLD, t.text());
            Gfx.text("+", x + w - 11, yy + 3, Gfx.BODY + 1, Font.Weight.BOLD, t.accent());
            if (yy + 18 > clipY && yy < clipY + clipH) hits.add(new Hit(HitType.BLOCK_ADD, x, yy, w, 18, bl, b));
            yy += 20;
        }
        if (!blockQuery.isEmpty()) yy += 4;
        // Chosen blocks.
        if (bl.get().isEmpty()) {
            Gfx.text("No blocks yet. Search above and click one to add it.", x, yy + 2, Gfx.SMALL, t.textDim());
            yy += 14;
        }
        for (var e : new java.util.ArrayList<>(bl.get().entrySet())) {
            net.minecraft.block.Block b = e.getKey();
            int col = e.getValue() | 0xFF000000;
            Gfx.round(x, yy, w, 20, 7, 0x16FFFFFF);
            Gfx.round(x + 1.5f, yy + 4, 2.5f, 12, 1.25f, col);
            blockIcon(b, x + 7, yy + 2.5f, 15, col);
            Gfx.text(Gfx.ellipsize(b.getName().getString(), w - 64, Gfx.SMALL + 0.5f, Font.Weight.SEMIBOLD), x + 26, yy + 5.5f, Gfx.SMALL + 0.5f, Font.Weight.SEMIBOLD, t.text());
            float swx = x + w - 34, swy = yy + 4;
            Gfx.shadow(swx, swy, 12, 12, 6, 3, Colors.withAlpha(col, 0x60));
            Gfx.circle(swx + 6, swy + 6, 6, col);
            Gfx.ring(swx + 6, swy + 6, 6, 1, 0x60FFFFFF);
            boolean xh = over(x + w - 16, yy + 3, 14, 14);
            Gfx.circle(x + w - 9, yy + 10, 6, xh ? 0x50F2555A : 0x18FFFFFF);
            Gfx.textCentered("×", x + w - 9, yy + 4.6f, Gfx.BODY, Font.Weight.BOLD, xh ? 0xFFFFFFFF : t.textDim());
            if (yy + 20 > clipY && yy < clipY + clipH) {
                hits.add(new Hit(HitType.BLOCK_COLOR, swx - 2, swy - 2, 16, 16, bl, b));
                hits.add(new Hit(HitType.BLOCK_REMOVE, x + w - 16, yy + 3, 14, 14, bl, b));
            }
            yy += 22;
        }
        return yy + 4;
    }

    /** A block's 3D item preview, or a coloured gem when the block has no item (portals, fire...). */
    private static void blockIcon(net.minecraft.block.Block b, float x, float y, float size, int fallback) {
        var item = b.asItem();
        if (item != net.minecraft.item.Items.AIR) Gfx.item(new net.minecraft.item.ItemStack(item), x, y, size);
        else Gfx.diamond(x + size / 2, y + size / 2, size * 0.4f, fallback, Colors.lerp(fallback, 0xFFFFFFFF, 0.4f));
    }

    private float drawSetting(Theme t, Setting<?> s, float x, float y, float w, float clipY, float clipH, float dt) {
        boolean visible = y + ROW > clipY && y < clipY + clipH;
        float adt = animated() ? dt : 1f;
        if (s instanceof BoolSetting b) {
            b.knob.to(b.on() ? 1 : 0).update(adt);
            boolean hov = over(x, y, w, ROW);
            if (hov) Gfx.round(x - 4, y + 1, w + 8, ROW - 2, 6, 0x0AFFFFFF);
            Gfx.text(Gfx.ellipsize(b.name, w - 32, Gfx.BODY, Font.Weight.REGULAR), x, y + 7, Gfx.BODY, t.text());
            Gfx.toggle(t, x + w - 22, y + 6, 22, 12, b.knob.value);
            if (visible) hits.add(new Hit(HitType.BOOL, x, y, w, ROW, b, null));
            return y + ROW;
        }
        if (s instanceof NumberSetting n) {
            n.fill.to((float) n.frac()).update(adt);
            String v = n == ModuleManager.soundLocator.range ? ModuleManager.soundLocator.rangeText() : n.display();
            float vw = Gfx.width(v, Gfx.SMALL, Font.Weight.BOLD) + 10;
            Gfx.text(Gfx.ellipsize(n.name, w - vw - 6, Gfx.BODY, Font.Weight.REGULAR), x, y + 4, Gfx.BODY, t.text());
            Gfx.glassBody(Themes.current(), x + w - vw, y + 3, vw, 13, 6.5f, 0.35f);
            Gfx.textCentered(v, x + w - vw / 2, y + 5, Gfx.SMALL, Font.Weight.BOLD, t.text());
            float ty = y + 23, th = 4;
            Gfx.round(x, ty, w, th, 2, 0x22FFFFFF);
            float fw = w * Anim.clamp01(n.fill.value);
            if (fw > 1) Gfx.roundGradientH(x, ty, Math.max(th, fw), th, 2, t.accent(), t.accent2());
            boolean active = slider == n, hov = over(x - 2, y + 14, w + 4, 18);
            if (active || hov) Gfx.glow(x + fw, ty + th / 2, 9, Colors.withAlpha(t.accent(), 0x70));
            Gfx.circle(x + fw, ty + th / 2, active || hov ? 4.5f : 4, 0xFFFFFFFF);
            if (visible) hits.add(new Hit(HitType.NUMBER, x - 4, y + 12, w + 8, 20, n, null));
            return y + ROW_NUM;
        }
        if (s instanceof com.trace.refract.setting.BlockListSetting bl) return drawBlockList(t, bl, x, y, w, clipY, clipH);
        if (s instanceof ModeSetting md) {
            float labelW = Gfx.width(md.name, Gfx.BODY) + 10;
            float total = 0;
            for (String o : md.modes) total += Gfx.width(o, Gfx.SMALL, Font.Weight.BOLD) + 12;
            boolean segmented = md.modes.length <= 4 && total + 4 <= w;
            float controlW = segmented ? total : Math.min(w, Math.max(80, Gfx.width(md.get(), Gfx.SMALL, Font.Weight.BOLD) + 34));
            boolean below = controlW > w - labelW;
            if (below && !segmented) controlW = w;
            Gfx.text(Gfx.ellipsize(md.name, w, Gfx.BODY, Font.Weight.REGULAR), x, y + 7, Gfx.BODY, t.text());
            float sh = 17, sy = below ? y + 23 : y + 4;
            float sx = below ? x : x + w - controlW;
            if (segmented) {
                Gfx.glassBody(Themes.current(), sx - 2, sy - 2, total + 4, sh + 4, (sh + 4) / 2, 0.35f);
                float ox = sx;
                for (String o : md.modes) {
                    float ow = Gfx.width(o, Gfx.SMALL, Font.Weight.BOLD) + 12;
                    boolean sel = md.is(o);
                    if (sel) Gfx.roundGradientH(ox, sy, ow, sh, sh / 2, t.accent(), t.accent2());
                    else if (over(ox, sy, ow, sh)) Gfx.round(ox, sy, ow, sh, sh / 2, 0x14FFFFFF);
                    Gfx.textCentered(o, ox + ow / 2, sy + 4.5f, Gfx.SMALL, Font.Weight.BOLD, sel ? 0xFFFFFFFF : t.textDim());
                    if (visible) hits.add(new Hit(HitType.MODE_SEG, ox, sy, ow, sh, md, o));
                    ox += ow;
                }
            } else {
                Gfx.glassBody(Themes.current(), sx, sy, controlW, sh, sh / 2, 0.35f);
                Gfx.textCentered(md.get(), sx + controlW / 2, sy + 4.5f, Gfx.SMALL, Font.Weight.BOLD, t.text());
                Gfx.arrow(sx + 9, sy + sh / 2, 7, (float) Math.PI, t.textDim());
                Gfx.arrow(sx + controlW - 9, sy + sh / 2, 7, 0, t.textDim());
                if (visible) {
                    hits.add(new Hit(HitType.MODE_PREV, sx, sy, controlW / 2, sh, md, null));
                    hits.add(new Hit(HitType.MODE_NEXT, sx + controlW / 2, sy, controlW / 2, sh, md, null));
                }
            }
            return y + (below ? ROW + 20 : ROW);
        }
        return y;
    }

    private void scrollbar(float x, float y, float h, float value, float max) {
        float thumb = Math.max(20, h * h / (h + max));
        float ty = y + (h - thumb) * (value / max);
        Gfx.round(x, ty, 3, thumb, 1.5f, 0x50FFFFFF);
    }

    private static List<String> wrap(String text, float maxW, float size) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (Gfx.width(test, size) > maxW && line.length() > 0) { lines.add(line.toString()); line = new StringBuilder(word); }
            else line = new StringBuilder(test);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static String keyName(int key) { return Module.keyLabel(key); }

    // ------------------------------------------------------------------ input

    private Hit hitAt(float x, float y, HitType... types) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(x, y)) continue;
            for (HitType ty : types) if (h.type() == ty) return h;
        }
        return null;
    }

    private void switchCategory(Category c) {
        if (panel == Panel.SETTINGS || panel == Panel.THEME || panel == Panel.ACCOUNT) { panel = Panel.NONE; if (c == category) return; }
        if (c == category && search.isEmpty()) return;
        category = c;
        search = "";
        gridTarget = 0;
        gridScroll.snap(0);
        cardPos.clear();
        cardIn.clear();
        staggerNext = true;
    }

    private void openPanel(Panel p, Module mod) {
        if (p == Panel.MODULE) scrollToSel = 4;
        if (p == Panel.MODULE) selected = mod;
        panel = p;
    }

    private void closePanel() { panel = Panel.NONE; }

    private static void toggleWithSound(Module mod) {
        mod.toggle();
        if (!mod.showsInHud()) Sfx.toggle(mod.isEnabled());
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (closing) return true;
        float[] lc = toLocal(click.x(), click.y());
        float x = lc[0], y = lc[1];
        int button = click.button();
        if (animated()) ripples.add(new float[]{x, y, System.currentTimeMillis()});
        // Resolve the click first: while a bind is armed the next click is normally the new key, but a click that
        // lands on another control (the hold switch, the place bind, close) must act on that control instead of
        // being eaten as a mouse bind.
        boolean armed = listening != null || listeningPlace;
        Hit hit = hitAt(x, y, HitType.CATEGORY, HitType.GENERAL, HitType.SWITCH, HitType.CARD, HitType.SEARCH, HitType.DIM, HitType.CLOSE,
                HitType.MOD_TOGGLE, HitType.BOOL, HitType.NUMBER, HitType.MODE_PREV, HitType.MODE_NEXT, HitType.MODE_SEG, HitType.BIND,
                HitType.BIND_HOLD, HitType.BIND_PLACE,
                HitType.THEME, HitType.BLOCK_SEARCH, HitType.BLOCK_ADD, HitType.BLOCK_COLOR, HitType.BLOCK_REMOVE, HitType.DRAWER_AREA, HitType.DRAG);
        if (armed) {
            boolean control = hit != null && (hit.type() == HitType.BIND || hit.type() == HitType.BIND_HOLD
                    || hit.type() == HitType.BIND_PLACE || hit.type() == HitType.CLOSE || hit.type() == HitType.MOD_TOGGLE);
            if (!control) {
                if (listening != null) listening.key = Module.mouseKey(button);
                else if (ModuleManager.anchorMacro != null) ModuleManager.anchorMacro.placeKey = Module.mouseKey(button);
                listening = null;
                listeningPlace = false;
                Sfx.click();
                return true;
            }
            listening = null;
            listeningPlace = false;
        }
        searchFocus = hit != null && hit.type() == HitType.SEARCH;
        if (hit == null || (hit.type() != HitType.BLOCK_SEARCH && hit.type() != HitType.BLOCK_ADD)) blockFocus = null;
        if (hit != null && hit.type() == HitType.BLOCK_SEARCH) blockFocus = (com.trace.refract.setting.BlockListSetting) hit.a();
        if (hit == null) return true;
        switch (hit.type()) {
            case CATEGORY -> { Sfx.click(); switchCategory((Category) hit.a()); }
            case GENERAL -> {
                Sfx.click();
                if (hit.a() == null) { if (client != null) client.setScreen(new HudEditorScreen(this)); return true; }
                Panel p = (Panel) hit.a();
                if (panel == p) closePanel(); else { openPanel(p, null); gridTarget = 0; gridScroll.snap(0); }
            }
            case SWITCH -> toggleWithSound((Module) hit.a());
            case CARD -> {
                Module mod = (Module) hit.a();
                if (button == 2) { Sfx.click(); listening = mod; listeningPlace = false; }
                else if (button == 1) toggleWithSound(mod);
                else { Sfx.click(); if (panel == Panel.MODULE && selected == mod) closePanel(); else openPanel(Panel.MODULE, mod); }
            }
            case DIM, CLOSE -> { Sfx.click(); closePanel(); }
            case MOD_TOGGLE -> toggleWithSound((Module) hit.a());
            case THEME -> { Sfx.click(); ModuleManager.clickGui.theme.set((String) hit.a()); }
            case BOOL -> { BoolSetting b = (BoolSetting) hit.a(); b.toggle(); Sfx.toggle(b.on()); }
            case BIND_HOLD -> { Module mod = (Module) hit.a(); mod.hold = !mod.hold; Sfx.toggle(mod.hold); }
            case NUMBER -> {
                slider = (NumberSetting) hit.a();
                sliderX = hit.x() + 4;
                sliderW = hit.w() - 8;
                slider.setFrac((x - sliderX) / sliderW);
            }
            case MODE_PREV -> { Sfx.click(); ((ModeSetting) hit.a()).cycle(-1); }
            case MODE_NEXT -> { Sfx.click(); ((ModeSetting) hit.a()).cycle(1); }
            case MODE_SEG -> { Sfx.click(); ((ModeSetting) hit.a()).set((String) hit.b()); }
            case BLOCK_ADD -> { Sfx.toggle(true); ((com.trace.refract.setting.BlockListSetting) hit.a()).add((net.minecraft.block.Block) hit.b()); blockQuery = ""; }
            case BLOCK_COLOR -> { Sfx.click(); ((com.trace.refract.setting.BlockListSetting) hit.a()).cycleColor((net.minecraft.block.Block) hit.b()); }
            case BLOCK_REMOVE -> { Sfx.toggle(false); ((com.trace.refract.setting.BlockListSetting) hit.a()).remove((net.minecraft.block.Block) hit.b()); }
            case BIND -> {
                Module mod = (Module) hit.a();
                listeningPlace = false;
                if (button == 1) mod.key = -1; else listening = mod;
            }
            case BIND_PLACE -> {
                if (button == 1) { if (ModuleManager.anchorMacro != null) ModuleManager.anchorMacro.placeKey = -1; }
                else { listening = null; listeningPlace = true; Sfx.click(); }
            }
            case DRAG -> {
                dragging = true;
                dragDX = x - winX;
                dragDY = y - winY;
            }
            default -> { }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        slider = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        float[] lc = toLocal(mouseX, mouseY);
        float x = lc[0], y = lc[1];
        Hit area = hitAt(x, y, HitType.GRID_AREA, HitType.DRAWER_AREA, HitType.DIM);
        if (area == null) return true;
        if (area.type() == HitType.GRID_AREA) gridTarget = MathHelper.clamp(gridTarget - (float) vertical * 30, 0, gridMax);
        else if (area.type() == HitType.DRAWER_AREA) drawerTarget = MathHelper.clamp(drawerTarget - (float) vertical * 30, 0, drawerMax);
        return true;
    }

    private void searchChanged() {
        gridTarget = 0;
        // Cards that stay glide to their new slots; new ones fade in.
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (listening != null || closing) return true;
        if (!input.isValidChar()) return false;
        String s = input.asString();
        if (blockFocus != null) { if (blockQuery.length() < 32) blockQuery += s; return true; }
        if (!searchFocus && s.isBlank()) return true;
        searchFocus = true;
        if (search.length() < 32) {
            search += s;
            searchChanged();
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (listening != null || listeningPlace) {
            int bound = (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) ? -1 : key;
            if (listening != null) listening.key = bound;
            else if (ModuleManager.anchorMacro != null) ModuleManager.anchorMacro.placeKey = bound;
            listening = null;
            listeningPlace = false;
            return true;
        }
        boolean ctrl = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (blockFocus != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { blockFocus = null; blockQuery = ""; return true; }
            if (key == GLFW.GLFW_KEY_BACKSPACE && !blockQuery.isEmpty()) blockQuery = ctrl ? "" : blockQuery.substring(0, blockQuery.length() - 1);
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !blockQuery.isEmpty()) {
                var r = blockFocus.search(blockQuery, 1);
                if (!r.isEmpty()) { blockFocus.add(r.get(0)); blockQuery = ""; Sfx.toggle(true); }
            }
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_F) { searchFocus = true; return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!search.isEmpty() || searchFocus) { search = ""; searchFocus = false; searchChanged(); return true; }
            if (panel != Panel.NONE) { closePanel(); return true; }
            close();
            return true;
        }
        if (searchFocus) {
            if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = ctrl ? "" : search.substring(0, search.length() - 1);
                searchChanged();
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                List<Module> mods = visibleModules();
                if (!mods.isEmpty()) toggleWithSound(mods.get(0));
            }
            return true;
        }
        if (Refract.guiKey.matchesKey(input)) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        if (!closing) {
            Sfx.close();
            closing = true;
            dragging = false;
            slider = null;
            listening = null;
            listeningPlace = false;
        }
    }

    private void finishClose() {
        Config.save();
        if (client != null) client.setScreen(null);
    }

    @Override
    public void removed() {
        Config.save();
        super.removed();
    }
}
