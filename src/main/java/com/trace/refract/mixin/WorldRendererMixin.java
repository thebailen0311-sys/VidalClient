package com.trace.refract.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.trace.refract.Refract;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.render.Esp;
import com.trace.refract.util.WorldToScreen;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;
import net.minecraft.world.debug.gizmo.GizmoCollectorImpl;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Each frame: capture camera matrices (for HUD labels) and emit ESP shapes into the world renderer's gizmo
 * collector before it is drawn, inside the renderer's own gizmo scope.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Shadow @Final private GizmoCollectorImpl gizmoCollector;

    @Shadow public abstract GizmoDrawing.CollectorScope startDrawingGizmos();

    @Inject(method = "render", at = @At("HEAD"))
    private void refract$frame(ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera,
                               Matrix4f a, Matrix4f b, Matrix4f c, GpuBufferSlice fog, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        WorldToScreen.capture(camera.getCameraPos(), a, b, c);
        Esp.setTickDelta(tickCounter.getTickProgress(true));
        Esp.beginFrame(gizmoCollector);
        try (GizmoDrawing.CollectorScope scope = startDrawingGizmos()) {
            ModuleManager.renderWorld(tickCounter.getTickProgress(true));
        } catch (Throwable t) {
            Refract.LOG.error("ESP render failed", t);
        }
    }
}
