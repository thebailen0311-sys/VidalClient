package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.scan.WorldScanner;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;

import java.util.Objects;

/**
 * Shared scan filter for every hunting module. On (default): in the Overworld, player blocks, deepslate,
 * tunnels and cave vines only count when they are at or below Max Y and at least Min Depth blocks under the
 * highest solid block of their column, so a griefed, built-up surface is never flagged. Changing anything here
 * rescans the loaded chunks.
 */
public class SurfaceFilter extends Module {
    public final NumberSetting maxY = add(new NumberSetting("Max Y", 40, -64, 320, 1, ""));
    public final NumberSetting depth = add(new NumberSetting("Min Depth", 12, 0, 64, 1, " blocks"));
    public final BoolSetting antiXray = add(new BoolSetting("Anti-Xray Filter", true));

    private Object lastKey;
    private Object pendingKey;
    private int stableTicks;

    public SurfaceFilter() {
        super("Surface Filter", "Ignores everything near the surface so griefed land is never flagged. Applies to all hunting modules.", Category.HUNTING);
        setEnabled(true, false);
    }

    @Override public boolean showsInHud() { return false; }

    @Override public String suffix() { return "≤ y" + maxY.asInt(); }

    private Object key() { return java.util.List.of(isEnabled(), maxY.asInt(), depth.asInt(), antiXray.on()); }

    @Override public void onAlwaysTick() {
        Object k = key();
        if (lastKey == null) { lastKey = k; pendingKey = k; return; }
        if (!Objects.equals(k, pendingKey)) { pendingKey = k; stableTicks = 0; return; }
        // Wait until a slider has stopped moving for half a second before rescanning.
        if (!Objects.equals(k, lastKey) && ++stableTicks >= 10) {
            lastKey = k;
            if (mc.world != null) WorldScanner.rescanAll();
        }
    }
}
