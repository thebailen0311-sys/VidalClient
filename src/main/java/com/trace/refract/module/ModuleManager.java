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
import java.util.Set;
import java.util.HashSet;

public final class ModuleManager {
    public static final List<Module> MODULES = new ArrayList<>();

    public static BaseFinder baseFinder;
    public static SusChunks susChunks;
    public static GrowthEsp growthEsp;
    public static SurfaceFilter surfaceFilter;
    public static SoundLocator soundLocator;
    public static SpawnerNotifier spawnerNotifier;
    public static Relog relog;
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
    public static CustomBars customBars;
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
    /** Consecutive failures per module, so one broken module can be isolated and switched off. */
    private static final Map<Module, Integer> failures = new HashMap<>();
    private static final Map<Module, Long> failureLogAt = new HashMap<>();
    /** Modules that failed repeatedly and stay parked until the user switches them on again. */
    private static final Set<Module> quarantined = new HashSet<>();

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
        spawnerNotifier = add(new SpawnerNotifier());
        relog = add(new Relog());
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
        customBars = add(new CustomBars());
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
        // Sprinting with an inventory open is a server-side speed violation; vanilla stops it, modules must too.
        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen && mc.player != null
                && mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }
        com.trace.refract.util.Prof.tick();
        for (Module m : MODULES) {
            if (quarantined.contains(m)) {
                if (!m.isEnabled()) continue;
                quarantined.remove(m); // an explicit re-enable is also a retry
            }
            long t0 = System.nanoTime();
            safe(m, "tick", () -> {
                m.onAlwaysTick();
                if (m.isEnabled()) {
                    com.trace.refract.util.NetLog.setModule(m.name);
                    try { m.onTick(); } finally { com.trace.refract.util.NetLog.setModule(null); }
                }
            });
            com.trace.refract.util.Prof.add("t:" + m.name, System.nanoTime() - t0);
        }
    }

    /**
     * Runs one module phase without letting its exception reach the client tick or the renderer. A single bad state
     * (a null entity, a server packet arriving at a strange time) used to take down every module and sometimes the
     * client with it. It is now logged, rate limited, and after a few consecutive failures that module is switched off
     * with a chat note instead of spamming the log every tick.
     */
    private static void safe(Module m, String phase, Runnable body) {
        if (quarantined.contains(m)) return;
        try {
            body.run();
            failures.remove(m);
        } catch (Throwable t) {
            int n = failures.merge(m, 1, Integer::sum);
            long now = System.currentTimeMillis();
            if (now - failureLogAt.getOrDefault(m, 0L) > 5000L) {
                failureLogAt.put(m, now);
                com.trace.refract.Refract.LOG.error("{} failed in {} ({} in a row)", m.name, phase, n, t);
            }
            if (n >= 3) {
                failures.remove(m);
                quarantined.add(m);
                com.trace.refract.Refract.LOG.error("Disabling {} after repeated {} failures", m.name, phase);
                try { m.setEnabled(false, false); } catch (Throwable ignored) { }
                try { com.trace.refract.Refract.chat("§c" + m.name + " hit an error and was turned off."); } catch (Throwable ignored) { }
            }
        }
    }

    public static void renderWorld(float tickDelta) {
        if (MinecraftClient.getInstance().player == null) return;
        com.trace.refract.util.Prof.frame();
        for (Module m : MODULES) {
            if (!m.isEnabled()) continue;
            long t0 = System.nanoTime();
            safe(m, "world render", () -> m.onWorldRender(tickDelta));
            com.trace.refract.util.Prof.add("esp:" + m.name, System.nanoTime() - t0);
        }
    }

    public static void renderHud(Theme t, float w, float h) {
        for (Module m : MODULES) {
            if (!m.isEnabled()) continue;
            long t0 = System.nanoTime();
            safe(m, "hud", () -> m.onHud(t, w, h));
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
