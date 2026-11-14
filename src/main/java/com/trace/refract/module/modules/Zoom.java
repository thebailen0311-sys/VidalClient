package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Time;

/** Smooth optical zoom. Bind a key; with "Hold" on it only zooms while the key is held. */
public class Zoom extends Module {
    public final NumberSetting factor = add(new NumberSetting("Factor", 4.0, 1.5, 12.0, 0.5, "x"));
    public final BoolSetting hold = add(new BoolSetting("Hold", true));
    private final Anim amount = new Anim(1f, 10f);

    public Zoom() { super("Zoom", "Smooth spyglass-style zoom.", Category.RENDER); }

    /** Divisor applied to the FOV; animated every frame. */
    public float divisor() {
        amount.to(isEnabled() ? factor.asFloat() : 1f);
        return Math.max(1f, amount.update(Time.dt()));
    }

    public String suffix() { return factor.display(); }
}
