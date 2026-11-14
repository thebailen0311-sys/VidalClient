package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nametags: hides the vanilla player name tag while Refract draws its own. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true)
    private void refract$hideLabel(Entity entity, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof PlayerEntity && entity != MinecraftClient.getInstance().player
                && ModuleManager.nametags != null && ModuleManager.nametags.isEnabled()) cir.setReturnValue(false);
    }
}
