package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import com.trace.refract.render.CapeArt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cosmetics: your own player wears the Vanta cape and elytra (client-side only; nobody else sees them). */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void refract$cape(CallbackInfoReturnable<SkinTextures> cir) {
        var c = ModuleManager.cosmetics;
        if (c == null || c.preview || !c.isEnabled() || (Object) this != MinecraftClient.getInstance().player) return;
        if (!c.cape.on() && !c.elytra.on()) return;
        SkinTextures s = cir.getReturnValue();
        if (s == null) return;
        CapeArt.ensure();
        cir.setReturnValue(new SkinTextures(s.body(), c.cape.on() ? CapeArt.CAPE_ASSET : s.cape(), c.elytra.on() ? CapeArt.ELYTRA_ASSET : s.elytra(), s.model(), s.secure()));
    }
}
