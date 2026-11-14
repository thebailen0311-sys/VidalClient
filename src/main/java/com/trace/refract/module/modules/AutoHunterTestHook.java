package com.trace.refract.module.modules;

/** Exposes Auto Hunter's auction price parser to the client game test. */
public final class AutoHunterTestHook {
    private AutoHunterTestHook() {}

    public static double price(net.minecraft.item.ItemStack s) { return AutoHunter.price(s); }
}
