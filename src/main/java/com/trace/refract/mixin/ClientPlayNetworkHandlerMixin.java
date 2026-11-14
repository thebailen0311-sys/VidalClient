package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import com.trace.refract.scan.WorldScanner;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the Sound Locator and the world scanner. Packet handlers first bounce themselves from the network
 * thread to the client thread, so HEAD injections check {@code isOnThread()} and only run once, on the client
 * thread, while the world still holds the old block state.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    private static boolean refract$ready() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.isOnThread() && mc.world != null && ModuleManager.soundLocator != null;
    }

    private static void refract$change(BlockPos pos, BlockState neu) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockState old = mc.world.getBlockState(pos);
        if (old == neu) return;
        WorldScanner.onBlockChange(pos, old, neu);
        ModuleManager.soundLocator.onBlockChange(pos, old, neu);
    }

    @Inject(method = "onBlockUpdate", at = @At("HEAD"))
    private void refract$blockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        if (refract$ready()) refract$change(packet.getPos(), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("HEAD"))
    private void refract$deltaUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        if (refract$ready()) packet.visitUpdates((pos, state) -> refract$change(pos.toImmutable(), state));
    }

    @Inject(method = "onBlockEvent", at = @At("TAIL"))
    private void refract$blockEvent(BlockEventS2CPacket packet, CallbackInfo ci) {
        if (ModuleManager.soundLocator == null) return;
        ModuleManager.soundLocator.onBlockEvent(packet.getPos(), packet.getBlock(), packet.getType(), packet.getData());
    }

    @Inject(method = "onPlaySound", at = @At("TAIL"))
    private void refract$sound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        if (ModuleManager.soundLocator == null) return;
        ModuleManager.soundLocator.onSound(packet.getSound().value().id(), packet.getX(), packet.getY(), packet.getZ(), null);
    }

    @Inject(method = "onWorldEvent", at = @At("TAIL"))
    private void refract$worldEvent(WorldEventS2CPacket packet, CallbackInfo ci) {
        if (ModuleManager.soundLocator == null || MinecraftClient.getInstance().world == null) return;
        ModuleManager.soundLocator.onWorldEvent(packet.getEventId(), packet.getPos(), packet.getData(), packet.isGlobal());
    }

    @Inject(method = "onPlaySoundFromEntity", at = @At("TAIL"))
    private void refract$entitySound(PlaySoundFromEntityS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (ModuleManager.soundLocator == null || mc.world == null) return;
        Entity e = mc.world.getEntityById(packet.getEntityId());
        if (e == null) return;
        ModuleManager.soundLocator.onSound(packet.getSound().value().id(), e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), e);
    }
}
