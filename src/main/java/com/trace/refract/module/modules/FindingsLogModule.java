package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;

/** Saves every new base, activity spot, entity group and player sighting to config/refract-findings.log. */
public class FindingsLogModule extends Module {
    public FindingsLogModule() {
        super("Findings Log", "Saves every finding with time, server and exact coordinates to config/refract-findings.log.", Category.HUNTING);

    }

    @Override public boolean showsInHud() { return false; }
}
