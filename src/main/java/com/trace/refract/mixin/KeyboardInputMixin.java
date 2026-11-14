package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freecam: the body gets no movement input while the camera is detached.
 * Auto Hunter: between dig steps, walks toward its target direction relative to where the camera faces, so the
 * camera never has to turn.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void refract$freezeBody(CallbackInfo ci) {
        if (ModuleManager.freecam != null && ModuleManager.freecam.isEnabled()) {
            this.playerInput = PlayerInput.DEFAULT;
            this.movementVector = Vec2f.ZERO;
            return;
        }
        // Auto S Tap: the body walks straight back and ignores forward input until the tap window ends. Because
        // this replaces the tick's input rather than editing the key bindings, the real keys take over again on
        // the very next tick, so holding W resumes instantly.
        if (ModuleManager.autoSTap != null && ModuleManager.autoSTap.tapping()) {
            this.playerInput = new PlayerInput(false, true, false, false, false, false, false);
            this.movementVector = new Vec2f(0f, -1f);
            return;
        }
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        Float walk = ModuleManager.autoHunter == null ? null : ModuleManager.autoHunter.walkYaw();
        if (walk == null || mc.player == null) return;
        // Express the world direction in the player's own frame: x is sideways (left positive), y is forward.
        double a = Math.toRadians(net.minecraft.util.math.MathHelper.wrapDegrees(walk - mc.player.getYaw()));
        float side = (float) -Math.sin(a), fwd = (float) Math.cos(a);
        this.movementVector = new Vec2f(side, fwd);
        PlayerInput in = this.playerInput;
        this.playerInput = new PlayerInput(fwd > 0.3f, fwd < -0.3f, side > 0.3f, side < -0.3f, in.jump(), in.sneak(), false);
    }
}
