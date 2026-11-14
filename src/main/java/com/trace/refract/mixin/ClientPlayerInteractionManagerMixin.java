package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import com.trace.refract.module.modules.SoundLocator;
import com.trace.refract.module.modules.Stealth;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells the Sound Locator what you clicked, attacked or broke, so your own noise is never reported. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    private static void refract$self(BlockPos pos, boolean redstone) {
        if (ModuleManager.soundLocator != null) ModuleManager.soundLocator.onSelfAction(pos, redstone);
    }

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void refract$interact(ClientPlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
        var world = MinecraftClient.getInstance().world;
        BlockPos pos = hit.getBlockPos();
        // A module's own interactions are exempt: cancelling them made place/charge macros return PASS forever,
        // so the anchor and crystal cycles never actually placed anything and retried every tick.
        if (!com.trace.refract.util.Combat.internalAction() && Stealth.shouldCancel(pos)) {
            cir.setReturnValue(ActionResult.PASS);
            return;
        }
        boolean redstone = world != null && SoundLocator.redstoneSource(world.getBlockState(pos));
        refract$self(pos, redstone);
        refract$self(pos.offset(hit.getSide()), false); // a block you place
    }

    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void refract$attack(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (Stealth.shouldCancel(pos)) { cir.setReturnValue(false); return; }
        var world = MinecraftClient.getInstance().world;
        refract$self(pos, world != null && SoundLocator.redstoneSource(world.getBlockState(pos)));
    }

    /** Every hit the player lands, from their own click or from a module, feeds the S-tap module. */
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void refract$hit(net.minecraft.entity.player.PlayerEntity player, net.minecraft.entity.Entity target, CallbackInfo ci) {
        ModuleManager.onAttack(target);
    }

    @Inject(method = "breakBlock", at = @At("HEAD"))
    private void refract$break(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        refract$self(pos, false);
    }
}
