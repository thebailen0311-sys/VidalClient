package com.trace.refract.module;

import com.trace.refract.hud.Notifications;
import com.trace.refract.setting.Setting;
import com.trace.refract.util.Anim;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    public final String name;
    public final String description;
    public final Category category;
    public final List<Setting<?>> settings = new ArrayList<>();
    /**
     * GLFW key code, -1 when unbound, or {@code -100 - button} for a mouse button. Mouse codes are negative
     * enough that they can never collide with a keyboard key.
     */
    public int key = -1;
    /** Bind mode: false toggles on press, true is only on while the key or button is held. */
    public boolean hold;
    private boolean enabled;

    public static int mouseKey(int button) { return -100 - button; }

    public static boolean isMouseKey(int k) { return k <= -100; }

    public static int mouseButton(int k) { return -100 - k; }

    /** "None" / "LMB" / "R Shift" for bind chips. */
    public static String keyLabel(int k) {
        if (k == -1) return "None";
        if (isMouseKey(k)) {
            int b = mouseButton(k);
            return b == 0 ? "LMB" : b == 1 ? "RMB" : b == 2 ? "MMB" : "M" + (b + 1);
        }
        String s = InputUtil.Type.KEYSYM.createFromCode(k).getLocalizedText().getString();
        return s.length() > 14 ? s.substring(0, 14) : s;
    }

    // UI animation state
    public final Anim toggleAnim = new Anim(0f, 14f);
    public final Anim hudAnim = new Anim(0f, 12f);
    public final Anim hover = new Anim(0f, 18f);
    public final Anim select = new Anim(0f, 16f);

    protected Module(String name, String description, Category category) {
        this.name = name; this.description = description; this.category = category;
    }

    protected <S extends Setting<?>> S add(S s) { settings.add(s); return s; }

    public boolean isEnabled() { return enabled; }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean on) { setEnabled(on, true); }

    public void setEnabled(boolean on, boolean notify) {
        if (on == enabled) return;
        enabled = on;
        toggleAnim.to(on ? 1 : 0);
        if (on) onEnable(); else onDisable();
        if (notify && showsInHud()) Notifications.toggle(this);
    }

    /** Whether this module appears in the HUD array list and toggle notifications. */
    public boolean showsInHud() { return true; }

    /**
     * For hold binds: whether letting go of the key must leave the module running. A macro that is halfway
     * through its sequence says yes, so a quick tap of the bind still completes the whole action.
     */
    public boolean keepWhileRunning() { return false; }

    /** Extra text shown greyed after the name in the array list. */
    public String suffix() { return null; }

    public void onEnable() {}

    public void onDisable() {}

    /** Called every client tick while enabled. */
    public void onTick() {}

    /** Called every client tick regardless of state. */
    public void onAlwaysTick() {}

    /** Called every frame during world rendering while enabled: emit ESP shapes through {@code Esp}. */
    public void onWorldRender(float tickDelta) {}

    /** Called every frame inside a Gfx bracket while enabled: draw HUD overlays and world labels. */
    public void onHud(com.trace.refract.theme.Theme theme, float width, float height) {}
}
