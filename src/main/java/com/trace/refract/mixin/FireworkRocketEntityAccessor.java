package com.trace.refract.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Auto Hunter see whose elytra boost a rocket is (so a new one is only used when yours runs out). */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccessor {
    @Accessor("shooter")
    LivingEntity refract$getShooter();
}
