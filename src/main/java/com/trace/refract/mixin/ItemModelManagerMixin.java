package com.trace.refract.mixin;

import com.trace.refract.module.ModuleManager;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Cosmetics "Knife": every vanilla sword is drawn as the chosen 3D knife (karambit, butterfly, tactical, bowie)
 * in hand, on the ground and in the inventory. Client-side only; the item itself is unchanged.
 */
@Mixin(ItemModelManager.class)
public abstract class ItemModelManagerMixin {
    @ModifyVariable(method = "update", at = @At("STORE"), ordinal = 0)
    private Identifier refract$swordSkin(Identifier model) {
        var c = ModuleManager.cosmetics;
        if (model == null || c == null || !c.knifeOn()) return model;
        if (!model.getNamespace().equals("minecraft") || !model.getPath().endsWith("_sword")) return model;
        return c.knifeModel();
    }
}
