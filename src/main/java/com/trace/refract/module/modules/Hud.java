package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;

public class Hud extends Module {
    public final BoolSetting watermark = add(new BoolSetting("Watermark", true));
    public final BoolSetting arrayList = add(new BoolSetting("Module List", true));
    public final BoolSetting notifications = add(new BoolSetting("Notifications", true));
    public final BoolSetting hunting = add(new BoolSetting("Findings Panel", true));
    public final BoolSetting soundLog = add(new BoolSetting("Sound Log", true));
    public final BoolSetting coords = add(new BoolSetting("Coordinates", false));
    public final com.trace.refract.setting.NumberSetting scale = add(new com.trace.refract.setting.NumberSetting("HUD Scale", 1.0, 0.5, 2.0, 0.05, "x"));
    public final com.trace.refract.setting.NumberSetting labelScale = add(new com.trace.refract.setting.NumberSetting("Label Scale", 1.0, 0.5, 2.0, 0.05, "x"));
    public final com.trace.refract.setting.NumberSetting cardScale = add(new com.trace.refract.setting.NumberSetting("Detail Card Scale", 1.0, 0.5, 2.0, 0.05, "x"));
    /** Acts as a button: switching it on opens the HUD editor. */
    public final BoolSetting edit = add(new BoolSetting("Open HUD Editor", false));

    public Hud() {
        super("HUD", "Watermark, module list, toasts, findings panel with coordinates, and sound log.", Category.CLIENT);
        setEnabled(true, false);
    }

    @Override public boolean showsInHud() { return false; }

    @Override public void onAlwaysTick() {
        if (!edit.on()) return;
        edit.set(false);
        mc.setScreen(new com.trace.refract.gui.HudEditorScreen(mc.currentScreen));
    }
}
