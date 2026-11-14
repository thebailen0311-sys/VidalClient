package com.trace.refract.mixin;

import com.trace.refract.util.Rotations;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silent rotations. While a module aims silently, the rotation the game puts in its own movement packet is
 * swapped for the aimed one and swapped straight back, so the server sees the head turn while the camera
 * never moves. No packet is hand-built: this is the vanilla packet with a different look direction, the
 * same one it would carry if you had turned by hand.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @Unique private float refract$yaw, refract$pitch;
    @Unique private boolean refract$swapped;

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void refract$silentBefore(CallbackInfo ci) {
        if (!Rotations.active()) return;
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        refract$yaw = self.getYaw();
        refract$pitch = self.getPitch();
        // The driver tracks yaw continuously (it can pass 180 while turning the short way round). The packet must
        // carry it wrapped into -180..180 or the server's aim check reports AimModulo360 on every single aim.
        self.setYaw(net.minecraft.util.math.MathHelper.wrapDegrees(Rotations.yaw()));
        self.setPitch(net.minecraft.util.math.MathHelper.clamp(Rotations.pitch(), -90f, 90f));
        refract$swapped = true;
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void refract$silentAfter(CallbackInfo ci) {
        if (!refract$swapped) return;
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        self.setYaw(refract$yaw);
        self.setPitch(refract$pitch);
        refract$swapped = false;
    }
}
