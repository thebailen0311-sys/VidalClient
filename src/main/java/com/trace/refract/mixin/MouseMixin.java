package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: the mouse wheel adjusts flying speed instead of switching hotbar slots. */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void refract$freecamScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (ModuleManager.freecam != null && ModuleManager.freecam.onScroll(vertical)) ci.cancel();
    }
}
