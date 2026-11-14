package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ambience: tints the open-air fog colour to match the recoloured sky (never under water, lava or snow). */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Inject(method = "getFogColor", at = @At("RETURN"))
    private void refract$fog(Camera camera, float tickProgress, ClientWorld world, int viewDistance, float skyDarkness, CallbackInfoReturnable<Vector4f> cir) {
        if (ModuleManager.ambience == null || !ModuleManager.ambience.isEnabled()) return;
        if (camera.getSubmersionType() != CameraSubmersionType.NONE || cir.getReturnValue() == null) return;
        ModuleManager.ambience.applyFog(cir.getReturnValue());
    }
}
