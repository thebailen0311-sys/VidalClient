package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import com.trace.refract.theme.Themes;
import com.trace.refract.util.Time;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** Drives the shared animation clock once per frame. */
    @Inject(method = "render", at = @At("HEAD"))
    private void refract$frame(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        Time.frame();
        Themes.tick();
        com.trace.refract.util.Rotations.frame(Time.dt());
    }

    /** Zoom: divide the world FOV. */
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void refract$zoom(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (!changingFov || ModuleManager.zoom == null) return;
        float div = ModuleManager.zoom.divisor();
        if (div > 1.001f) cir.setReturnValue(cir.getReturnValueF() / div);
    }

    /** Freecam: hide the floating first-person hand. */
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void refract$hideHand(float tickProgress, boolean sleeping, Matrix4f positionMatrix, CallbackInfo ci) {
        if (ModuleManager.freecam != null && ModuleManager.freecam.isEnabled() && ModuleManager.freecam.hideHand.on()) ci.cancel();
    }
}
