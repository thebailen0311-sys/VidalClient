package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import com.trace.refract.module.modules.Freecam;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: after vanilla positions the camera, move it to the free camera and render the player body. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean thirdPerson;

    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Shadow protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("TAIL"))
    private void refract$freecam(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        Freecam f = ModuleManager.freecam;
        if (f == null || !f.isEnabled()) return;
        this.setRotation(f.yaw, f.pitch);
        this.setPos(f.lerpX(tickProgress), f.lerpY(tickProgress), f.lerpZ(tickProgress));
        this.thirdPerson = true;
    }
}
