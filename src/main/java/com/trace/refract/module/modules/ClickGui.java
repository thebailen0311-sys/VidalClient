package com.trace.refract.module.modules;

import com.trace.refract.gui.ClickGuiScreen;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.ModeSetting;
import com.trace.refract.theme.Themes;

/** Holds the GUI's own options. Toggling it opens the click GUI. */
public class ClickGui extends Module {
    public final ModeSetting theme = add(new ModeSetting("Theme", "Violet", Themes.names()).onChange(Themes::set));
    public final ModeSetting glass = add(new ModeSetting("Glass Style", "Liquid Glass", "Liquid Glass", "Frosted"));
    public final BoolSetting tilt = add(new BoolSetting("3D Tilt", true));
    public final BoolSetting blur = add(new BoolSetting("Frosted Blur", true));
    public final BoolSetting dim = add(new BoolSetting("Dim Background", true));
    public final BoolSetting animations = add(new BoolSetting("Animations", true));
    public final BoolSetting sounds = add(new BoolSetting("UI Sounds", true));
    public final com.trace.refract.setting.NumberSetting volume = add(new com.trace.refract.setting.NumberSetting("Sound Volume", 0.8, 0.1, 1.5, 0.1, "x"));

    public ClickGui() { super("Click GUI", "The settings window. Default key: Right Shift.", Category.CLIENT); }

    @Override public boolean showsInHud() { return false; }

    @Override public void onEnable() {
        setEnabled(false, false);
        if (!(mc.currentScreen instanceof ClickGuiScreen)) mc.setScreen(new ClickGuiScreen());
    }
}
