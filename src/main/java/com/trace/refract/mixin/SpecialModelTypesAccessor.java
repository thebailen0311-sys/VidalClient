package com.trace.refract.mixin;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.render.item.model.special.SpecialModelRenderer;
import net.minecraft.client.render.item.model.special.SpecialModelTypes;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.Codecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Refract register its own special item model type ("refract:knife"). */
@Mixin(SpecialModelTypes.class)
public interface SpecialModelTypesAccessor {
    @Accessor("ID_MAPPER")
    static Codecs.IdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked>> refract$idMapper() { throw new AssertionError(); }
}
