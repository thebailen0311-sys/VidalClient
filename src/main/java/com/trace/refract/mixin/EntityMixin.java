package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: mouse movement turns the free camera instead of the player. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void refract$freecamLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity && ModuleManager.freecam != null && ModuleManager.freecam.isEnabled()) {
            ModuleManager.freecam.look(cursorDeltaX, cursorDeltaY);
            ci.cancel();
        }
    }
}
