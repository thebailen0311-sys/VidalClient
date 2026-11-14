package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import com.trace.refract.render.knife.KnifeRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cosmetics knife, held for real: in first person your arm is drawn and the knife is placed in the hand bone of
 * that arm (the same in-hand placement the game uses in third person), so it sits in your fist and follows every
 * swing and equip motion instead of floating beside the hand.
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Unique private final ItemRenderState refract$state = new ItemRenderState();
    @Unique private boolean refract$pushed;

    @Shadow
    private void renderArmHoldingItem(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, float equipProgress, float swingProgress, Arm arm) {}

    /** The exact transform vanilla applies before drawing the first-person arm (see renderArmHoldingItem). */
    @Unique
    private static void refract$armPose(MatrixStack m, Arm arm, float equip, float swing) {
        float f = arm != Arm.LEFT ? 1f : -1f;
        float g = MathHelper.sqrt(swing);
        float h = -0.3f * MathHelper.sin(g * (float) Math.PI);
        float i = 0.4f * MathHelper.sin(g * (float) (Math.PI * 2));
        float j = -0.4f * MathHelper.sin(swing * (float) Math.PI);
        m.translate(f * (h + 0.64000005f), i - 0.6f + equip * -0.6f, j - 0.71999997f);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(f * 45f));
        float k = MathHelper.sin(swing * swing * (float) Math.PI);
        float l = MathHelper.sin(g * (float) Math.PI);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(f * l * 70f));
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(f * k * -20f));
        m.translate(f * -1f, 3.6f, 3.5f);
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(f * 120f));
        m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(200f));
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(f * -135f));
        m.translate(f * 5.6f, 0f, 0f);
    }

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void refract$knifeHand(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item,
                                   float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        var c = ModuleManager.cosmetics;
        refract$pushed = false;
        boolean knife = c != null && c.knifeOn() && hand == Hand.MAIN_HAND && !player.isInvisible() && Registries.ITEM.getId(item.getItem()).getPath().endsWith("_sword");
        if (!knife) {
            // Hand animations for every other item (and the empty hand): applied on top of vanilla, undone at RETURN.
            if (c != null && c.isEnabled() && !c.handAnim.is("Off")) {
                matrices.push();
                refract$pushed = true;
                float f = (hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite()) != Arm.LEFT ? 1f : -1f;
                float t = com.trace.refract.util.Time.seconds();
                float sw = MathHelper.sin(swingProgress * (float) Math.PI);
                // Idle breathing sway for all modes.
                matrices.translate(f * 0.006f * MathHelper.sin(t * 1.3f), 0.008f * MathHelper.sin(t * 2.1f), 0);
                switch (c.handAnim.get()) {
                    case "Spin" -> {
                        matrices.translate(f * 0.56f, -0.52f, -0.72f);
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(f * -360f * com.trace.refract.util.Anim.easeInOutCubic(swingProgress)));
                        matrices.translate(f * -0.56f, 0.52f, 0.72f);
                    }
                    case "Punch" -> matrices.translate(f * -0.04f * sw, 0.05f * sw, -0.22f * sw);
                    default -> { // Smooth: a softer arc and a little settle
                        matrices.translate(f * 0.02f * sw, -0.03f * sw, -0.05f * sw);
                        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(f * 6f * sw));
                    }
                }
            }
            return;
        }
        Arm arm = player.getMainArm();
        float f = arm != Arm.LEFT ? 1f : -1f;
        KnifeRenderer.armRenders++;
        matrices.push();
        renderArmHoldingItem(matrices, queue, light, equipProgress, swingProgress, arm);
        matrices.pop();
        // Same pose again, then into the arm bone (pivot) and the held-item placement used for third person.
        matrices.push();
        refract$armPose(matrices, arm, equipProgress, swingProgress);
        matrices.translate(f * -5f / 16f, 2f / 16f, 0f);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f));
        matrices.translate(f / 16f, 0.125f, -0.625f);
        MinecraftClient.getInstance().getItemModelManager().clearAndUpdate(refract$state, item,
                f > 0 ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND, player.getEntityWorld(), player, 0);
        KnifeRenderer.inHand = true;
        KnifeRenderer.swing = swingProgress;
        try {
            refract$state.render(matrices, queue, light, OverlayTexture.DEFAULT_UV, 0);
        } finally {
            KnifeRenderer.inHand = false;
        }
        matrices.pop();
        ci.cancel();
    }

    @Inject(method = "renderFirstPersonItem", at = @At("RETURN"))
    private void refract$handAnimEnd(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item,
                                     float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        if (refract$pushed) { matrices.pop(); refract$pushed = false; }
    }
}
