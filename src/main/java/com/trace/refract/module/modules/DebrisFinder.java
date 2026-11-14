package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import net.minecraft.block.Blocks;

/**
 * Ancient debris search with its own key and toggle.
 *
 * <p>Method, stated plainly: the client can only see blocks the server has already sent it, so this scans the
 * interior of every chunk the game has loaded (the same data the world renderer draws) and nothing else. It sends
 * no packets, requests no chunks, and does not touch the world through walls — which is why it cannot be detected
 * as an "ESP trick": there is no observable difference from the client simply drawing its own world.
 *
 * <p>It delegates to Block ESP for the boxes, gradients, glow and tracers, so the debris highlights look and feel
 * identical to the rest of the visuals.
 */
public class DebrisFinder extends Module {
    public DebrisFinder() {
        super("Ancient Debris", "Finds ancient debris in the chunks you have loaded: gradient boxes, glow and tracers.",
                Category.RENDER);
    }

    @Override public void onEnable() {
        BlockEsp esp = ModuleManager.blockEsp;
        if (esp == null) return;
        esp.preset.set("Ancient Debris");
        esp.blocks.add(Blocks.ANCIENT_DEBRIS);
        if (!esp.isEnabled()) esp.setEnabled(true, false);
    }

    @Override public void onDisable() {
        BlockEsp esp = ModuleManager.blockEsp;
        if (esp != null && esp.isEnabled() && esp.preset.is("Ancient Debris")) esp.setEnabled(false, false);
    }
}
