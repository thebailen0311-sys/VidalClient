package com.trace.refract;

import com.trace.refract.config.Config;
import com.trace.refract.gui.ClickGuiScreen;
import com.trace.refract.hud.RefractHud;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.theme.Themes;
import net.fabricmc.api.ClientModInitializer;
import com.trace.refract.scan.Indicators;
import com.trace.refract.scan.WorldScanner;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Refract implements ClientModInitializer {
    public static final String ID = "refract";
    public static final String NAME = "Vanta";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    public static KeyBinding guiKey;

    @Override
    public void onInitializeClient() {
        Indicators.init();
        ModuleManager.init();
        Config.load();
        Themes.set(ModuleManager.clickGui.theme.get());

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(ID, "main"));
        guiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.refract.clickgui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (guiKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new ClickGuiScreen());
            }
            LiveTest.tick(mc);
            ModuleManager.tick(mc);
            com.trace.refract.util.Rotations.endTick();
            long ts = System.nanoTime();
            WorldScanner.tick(mc);
            com.trace.refract.util.Prof.add("t:scanner", System.nanoTime() - ts);
        });
        ClientChunkEvents.CHUNK_LOAD.register(WorldScanner::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(WorldScanner::onChunkUnload);

        ClientReceiveMessageEvents.GAME.register((text, overlay) -> {
            if (!overlay) ModuleManager.antiVanish.onGameMessage(text);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ModuleManager.antiVanish.reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            if (ModuleManager.freecam.isEnabled()) mc.execute(() -> ModuleManager.freecam.setEnabled(false, false));
            mc.execute(() -> {
                ModuleManager.soundLocator.pings.clear();
                WorldScanner.clearAll();
            });
        });

        com.trace.refract.render.HoloRenderer.register();
        // The colour grade is a full-screen pass, so it has to be drawn BEFORE the vanilla HUD. Registering it in
        // the same late element as our panels painted over the hotbar, health, hunger, XP bar and action bar.
        HudElementRegistry.addFirst(Identifier.of(ID, "grade"), (ctx, tick) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (ModuleManager.ambience.isEnabled() && mc.currentScreen == null) {
                ModuleManager.ambience.drawGrade(ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
            }
        });
        HudElementRegistry.addLast(Identifier.of(ID, "hud"), RefractHud::render);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Config.save());
        com.trace.refract.update.Updater.init();
        LOG.info("Refract loaded - press Right Shift for the GUI");
    }

    /** Gradient "Refract" prefix for chat lines. */
    public static MutableText prefix() {
        var t = Themes.current();
        MutableText out = Text.empty();
        String s = NAME;
        for (int i = 0; i < s.length(); i++) {
            int col = t.gradient(i * 0.08f) & 0xFFFFFF;
            out.append(Text.literal(String.valueOf(s.charAt(i))).styled(st -> st.withColor(col).withBold(true)));
        }
        return out.append(Text.literal(" » ").styled(st -> st.withColor(0x808898)));
    }

    public static void chat(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(prefix().append(Text.literal(msg)));
    }
}
