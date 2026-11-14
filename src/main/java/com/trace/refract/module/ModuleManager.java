package com.trace.refract.module;

import com.trace.refract.module.modules.*;
import com.trace.refract.theme.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ModuleManager {
    public static final List<Module> MODULES = new ArrayList<>();

    public static BaseFinder baseFinder;
    public static SusChunks susChunks;
    public static GrowthEsp growthEsp;
    public static SurfaceFilter surfaceFilter;
    public static SoundLocator soundLocator;
    public static EntityFinder entityFinder;
    public static AutoHunter autoHunter;
    public static Triggerbot triggerbot;
    public static AutoSTap autoSTap;
    public static AimAssist aimAssist;
    public static AnchorMacro anchorMacro;
    public static AutoTotem autoTotem;
    public static AutoCrystal autoCrystal;
    public static Waypoints waypoints;
    public static Visuals.WeatherNotifier weather;
    public static Visuals.Breadcrumbs breadcrumbs;
    public static Visuals.ItemEsp itemEsp;
    public static Visuals.Fullbright fullbright;
    public static Ambience ambience;
    public static Cosmetics cosmetics;
    public static Nametags nametags;
    public static Trajectories trajectories;
    public static PlayerLog playerLog;
    public static FindingsLogModule findingsLog;
    public static StorageEsp storageEsp;
    public static BlockEsp blockEsp;
    public static DebrisFinder debrisFinder;
    public static PlayerEsp playerEsp;
    public static Freecam freecam;
    public static Zoom zoom;
    public static AntiVanish antiVanish;
    public static Stealth stealth;
    public static ClickGui clickGui;
    public static Hud hud;

    private static final Map<Module, Boolean> keyDown = new HashMap<>();
    private static final Map<Module, Boolean> placeDown = new HashMap<>();

    private ModuleManager() {}

    public static void init() {
        triggerbot = add(new Triggerbot());
        autoSTap = add(new AutoSTap());
        aimAssist = add(new AimAssist());
        anchorMacro = add(new AnchorMacro());
        autoTotem = add(new AutoTotem());
        autoCrystal = add(new AutoCrystal());
        baseFinder = add(new BaseFinder());
        susChunks = add(new SusChunks());
        growthEsp = add(new GrowthEsp());
        soundLocator = add(new SoundLocator());
        storageEsp = add(new StorageEsp());
        blockEsp = add(new BlockEsp());
        debrisFinder = add(new DebrisFinder());
        entityFinder = add(new EntityFinder());
        autoHunter = add(new AutoHunter());
        waypoints = add(new Waypoints());
        itemEsp = add(new Visuals.ItemEsp());
        breadcrumbs = add(new Visuals.Breadcrumbs());
        fullbright = add(new Visuals.Fullbright());
        ambience = add(new Ambience());
        cosmetics = add(new Cosmetics());
        nametags = add(new Nametags());
        trajectories = add(new Trajectories());
        weather = add(new Visuals.WeatherNotifier());
        playerLog = add(new PlayerLog());
        surfaceFilter = add(new SurfaceFilter());
        findingsLog = add(new FindingsLogModule());
        playerEsp = add(new PlayerEsp());
        freecam = add(new Freecam());
        zoom = add(new Zoom());
        antiVanish = add(new AntiVanish());
        stealth = add(new Stealth());
        clickGui = add(new ClickGui());
        hud = add(new Hud());
    }

    private static <M extends Module> M add(M m) { MODULES.add(m); return m; }

    public static List<Module> byCategory(Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : MODULES) if (m.category == c) out.add(m);
        return out;
    }

    public static Module byName(String name) {
        for (Module m : MODULES) if (m.name.equalsIgnoreCase(name)) return m;
        return null;
    }

    public static void tick(MinecraftClient mc) {
        pollKeys(mc);
        com.trace.refract.util.Prof.tick();
        for (Module m : MODULES) {
            long t0 = System.nanoTime();
            m.onAlwaysTick();
            if (m.isEnabled()) m.onTick();
            com.trace.refract.util.Prof.add("t:" + m.name, System.nanoTime() - t0);
        }
    }

    public static void renderWorld(float tickDelta) {
        if (MinecraftClient.getInstance().player == null) return;
        com.trace.refract.util.Prof.frame();
        for (Module m : MODULES) {
            if (!m.isEnabled()) continue;
            long t0 = System.nanoTime();
            m.onWorldRender(tickDelta);
            com.trace.refract.util.Prof.add("esp:" + m.name, System.nanoTime() - t0);
        }
    }

    public static void renderHud(Theme t, float w, float h) {
        for (Module m : MODULES) {
            if (!m.isEnabled()) continue;
            long t0 = System.nanoTime();
            m.onHud(t, w, h);
            com.trace.refract.util.Prof.add("hud:" + m.name, System.nanoTime() - t0);
        }
    }

    private static void pollKeys(MinecraftClient mc) {
        boolean inGame = mc.currentScreen == null && mc.getWindow() != null;
        for (Module m : MODULES) {
            if (m.key == -1) continue;
            boolean down = inGame && isDown(mc, m.key);
            boolean was = keyDown.getOrDefault(m, false);
            keyDown.put(m, down);
            // Hold binds act only while held; everything else toggles on press.
            if (m.hold || (m == zoom && zoom.hold.on())) {
                if (down && !was) m.setEnabled(true, false);
                else if (!down && m.isEnabled() && !m.keepWhileRunning()) m.setEnabled(false, false);
                continue;
            }
            if (down && !was) m.toggle();
        }
        // The anchor macro's separate "place" bind fires a single run without needing the module switched on.
        if (anchorMacro != null && anchorMacro.placeKey != -1) {
            boolean down = inGame && isDown(mc, anchorMacro.placeKey);
            boolean was = placeDown.getOrDefault(anchorMacro, false);
            placeDown.put(anchorMacro, down);
            if (down && !was) anchorMacro.triggerOnce();
        }
    }

    /** Every landed hit, so Auto S Tap can react to your own clicks and the Trigger Bot alike. */
    public static void onAttack(Entity target) {
        if (autoSTap == null || !(target instanceof LivingEntity)) return;
        autoSTap.onHit();
    }

    private static boolean isDown(MinecraftClient mc, int key) {
        if (Module.isMouseKey(key)) {
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), Module.mouseButton(key)) == GLFW.GLFW_PRESS;
        }
        return key >= 0 && InputUtil.isKeyPressed(mc.getWindow(), key);
    }
}
