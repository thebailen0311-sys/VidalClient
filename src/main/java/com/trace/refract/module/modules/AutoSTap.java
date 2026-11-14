package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Combat;

/**
 * Taps S for a moment whenever a hit lands, and keeps forward movement off for that window. The input is
 * replaced inside {@code KeyboardInputMixin} for the tick instead of editing key bindings, so the moment the
 * window ends your real keys apply again: hold W the whole time and you keep walking without a re-press.
 *
 * <p>Hits from your own click and from the Trigger Bot both start a tap, so the two work as one feature.
 */
public class AutoSTap extends Module {
    public final NumberSetting hold = add(new NumberSetting("Hold Time", 150, 30, 600, 10, "ms"));
    public final NumberSetting delay = add(new NumberSetting("Delay", 0, 0, 300, 10, "ms"));
    public final NumberSetting cooldown = add(new NumberSetting("Cooldown", 60, 0, 500, 10, "ms"));
    public final BoolSetting blockForward = add(new BoolSetting("Block Forward", true));
    public final BoolSetting weaponOnly = add(new BoolSetting("Weapon Only", false));

    private long startAt, endAt, nextAt;
    private int hits;

    public AutoSTap() {
        super("Auto S Tap", "Taps S for a set time after every hit and keeps you from walking into the trade.", Category.COMBAT);
    }

    @Override public void onEnable() { startAt = endAt = nextAt = 0; hits = 0; }

    /** Hits seen since the module was switched on (used by the in-game check). */
    public int hits() { return hits; }

    @Override public void onDisable() { startAt = endAt = 0; }

    /** A hit was landed on a living entity: queue the next tap. */
    public void onHit() {
        hits++;
        long now = System.currentTimeMillis();
        if (now < nextAt) return;
        if (weaponOnly.on() && !Combat.holdingWeapon()) return;
        startAt = now + delay.asInt();
        endAt = startAt + hold.asInt();
        nextAt = endAt + cooldown.asInt();
    }

    /** True while the tap window is open. */
    public boolean tapping() {
        long now = System.currentTimeMillis();
        return isEnabled() && endAt > 0 && now >= startAt && now <= endAt;
    }

    /** True when forward input must be ignored (the tap always ignores it; the movement is forced back). */
    public boolean blocking() { return tapping() && blockForward.on(); }

    @Override public String suffix() {
        if (!isEnabled()) return null;
        return tapping() ? "S" : null;
    }
}
