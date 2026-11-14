package com.trace.refract;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.module.modules.Triggerbot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Live-server self test, enabled with {@code -Drefract.livetest=<spec>}.
 * <p>
 * The game test harness runs against an integrated server, which never starts a server-side anticheat. This
 * driver instead runs in a real client, joins a real server (the one with the anticheat installed) and plays a
 * scripted action so the anticheat's own output can be read from that server's console. It is inert unless the
 * system property is set, so it never affects normal play.
 */
public final class LiveTest {
    private static final String SPEC = System.getProperty("refract.livetest", "").trim().toLowerCase();
    private static boolean started, finished;
    private static int ticks;
    private static final List<Module> on = new ArrayList<>();
    private static Entity target;

    private LiveTest() {}

    private static void log(String msg) { Refract.LOG.info("[livetest] " + msg); }

    private static void cmd(MinecraftClient mc, String command) {
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatCommand(command);
    }

    private static Module find(String name) {
        for (Category c : Category.values()) {
            for (Module m : ModuleManager.byCategory(c)) {
                if (m.name.toLowerCase().contains(name)) return m;
            }
        }
        return null;
    }

    private static void enable(String name, boolean on0) {
        Module m = find(name);
        if (m == null) { log("missing module: " + name); return; }
        m.setEnabled(on0, false);
        if (on0) on.add(m);
    }

    private static LivingEntity nearest(MinecraftClient mc) {
        LivingEntity best = null;
        double bd = 8 * 8;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e == mc.player) continue;
            double d = le.squaredDistanceTo(mc.player);
            if (d < bd) { bd = d; best = le; }
        }
        return best;
    }

    /** Saves a frame of the running game, so visuals can be checked rather than assumed. */
    private static void shoot(MinecraftClient mc, String name) {
        try {
            for (var m : net.minecraft.client.util.ScreenshotRecorder.class.getMethods()) {
                if (!m.getName().equals("saveScreenshot")) continue;
                Class<?>[] p = m.getParameterTypes();
                Object[] args = new Object[p.length];
                for (int i = 0; i < p.length; i++) {
                    if (p[i] == java.io.File.class) args[i] = mc.runDirectory;
                    else if (p[i] == String.class) args[i] = name;
                    else if (p[i].getName().contains("Framebuffer")) args[i] = mc.getFramebuffer();
                    else if (p[i] == int.class) args[i] = 1;
                    else if (java.util.function.Consumer.class.isAssignableFrom(p[i])) {
                        args[i] = (java.util.function.Consumer<net.minecraft.text.Text>) t -> log("shot: " + t.getString());
                    } else args[i] = null;
                }
                m.invoke(null, args);
                log("screenshot requested via " + m.getName() + "/" + p.length + " args");
                return;
            }
            log("screenshot: no saveScreenshot overload found");
        } catch (Throwable t) {
            log("screenshot failed: " + t);
        }
    }

    private static int countBlock(MinecraftClient mc, net.minecraft.block.Block block) {
        int n = 0;
        BlockPos origin = mc.player.getBlockPos();
        for (BlockPos p : BlockPos.iterate(origin.add(-6, -6, -6), origin.add(6, 6, 6))) {
            if (mc.world.getBlockState(p).getBlock() == block) n++;
        }
        return n;
    }

    private static int countCrystals(MinecraftClient mc) {
        int n = 0;
        for (Entity e : mc.world.getEntities()) if (e instanceof net.minecraft.entity.decoration.EndCrystalEntity) n++;
        return n;
    }

    private static void look(MinecraftClient mc, Entity e) {
        if (e == null) return;
        double dx = e.getX() - mc.player.getX();
        double dz = e.getZ() - mc.player.getZ();
        double dy = (e.getY() + e.getHeight() * 0.5) - mc.player.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, flat)));
    }

    /** Called every client tick, before modules tick. */
    public static void tick(MinecraftClient mc) {
        if (SPEC.isEmpty() || finished) return;
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (!started) { started = true; log("joined, spec=" + SPEC); }
        ticks++;

        // Structure specs: if a previous run left the player dead, respawn instead of idling on the death screen.
        if (mc.player.isDead() && (SPEC.equals("base") || SPEC.equals("vault") || SPEC.equals("debris") || SPEC.equals("esp"))) {
            mc.player.requestRespawn();
        }

        if (ticks == 15) {
            cmd(mc, "time set noon");
            cmd(mc, "gamerule doDaylightCycle false");
            // Clean arena: solid floor under the player, air above, so nothing spawns inside terrain.
            // Clear leftover dummies from earlier runs first, then build a clean arena.
            cmd(mc, "kill @e[type=minecraft:zombie]");
            cmd(mc, "kill @e[type=minecraft:end_crystal]");
            // Small arena: a huge fill in one tick makes the server fall behind, which shows up as Timer/TimerLimit
            // and desyncs the very placement we are trying to measure.
            cmd(mc, "execute at @s run fill ~-4 ~-1 ~-4 ~4 ~-1 ~4 minecraft:stone");
            cmd(mc, "execute at @s run fill ~-4 ~ ~-4 ~4 ~4 ~4 minecraft:air");
            cmd(mc, "clear @s");
            // Structure specs only need the scanner alive: falling to death mid-run blanked the results.
            if (SPEC.equals("base") || SPEC.equals("vault") || SPEC.equals("debris") || SPEC.equals("esp")) {
                cmd(mc, "gamemode creative");
                // Start from a known safe slab so the scanner is never interrupted by a death screen.
                cmd(mc, "tp @s 0 120 0");
                cmd(mc, "setblock 0 118 0 minecraft:stone");
            }
            cmd(mc, "give @s diamond_sword 1");
            cmd(mc, "give @s obsidian 64");
            cmd(mc, "give @s end_crystal 64");
            cmd(mc, "give @s glowstone 64");
            cmd(mc, "give @s respawn_anchor 8");
            cmd(mc, "give @s totem_of_undying 1");
            // Summoned in front of and ABOVE the player, so it lands on the surface instead of inside terrain.
            // Only the combat specs get a dummy: one parked in front of the player blocks the crosshair and is
            // exactly where the placement modules need to work, which ruined those runs.
            if (SPEC.equals("tbot") || SPEC.equals("stap")) {
                cmd(mc, "execute at @s anchored eyes run summon minecraft:zombie ^ ^ ^2 {NoAI:1b,NoGravity:1b,Silent:1b,PersistenceRequired:1b}");
            }
            log("setup commands sent");
        }

        switch (SPEC) {
            case "tbot" -> {
                if (ticks == 55) {
                    StringBuilder bar = new StringBuilder();
                    for (int i = 0; i < 9; i++) {
                        bar.append(i).append('=').append(mc.player.getInventory().getStack(i).getItem()).append(' ');
                    }
                    log("hotbar: " + bar);
                }
                // A weapon-only trigger bot will not swing with building blocks in hand, so keep the sword out.
                if (ticks > 50 && mc.player.getMainHandStack().getItem() != net.minecraft.item.Items.DIAMOND_SWORD) {
                    for (int i = 0; i < 9; i++) {
                        if (mc.player.getInventory().getStack(i).getItem() == net.minecraft.item.Items.DIAMOND_SWORD) {
                            mc.player.getInventory().setSelectedSlot(i);
                            break;
                        }
                    }
                }
                if (ticks == 60) {
                    Module tb = find("trigger bot");
                    if (tb != null && find("trigger bot") instanceof Triggerbot t) t.range.set(2.9);
                    enable("aim assist", true);
                    enable("trigger bot", true);
                    mc.options.attackKey.setPressed(true);
                    log("attack held, range widened");
                }
                // The dummy is stationary, so aim once and leave the view alone: rotating every tick makes the
                // first swings carry the previous tick's angle, which is a real (and avoidable) mismatch.
                if (ticks == 70) look(mc, target = nearest(mc));
                if (ticks > 72) mc.options.attackKey.setPressed(true);
                if (ticks > 60 && ticks % 20 == 0) {
                    double d = target == null ? -1 : Math.sqrt(target.squaredDistanceTo(mc.player));
                    log("t=" + ticks + " held=" + mc.player.getInventory().getStack(mc.player.getInventory().getSelectedSlot()).getItem()
                            + " atkKey=" + mc.options.attackKey.isPressed()
                            + " cross=" + mc.crosshairTarget.getType()
                            + " targetDist=" + String.format(java.util.Locale.ROOT, "%.2f", d)
                            + " cooldown=" + String.format(java.util.Locale.ROOT, "%.2f", mc.player.getAttackCooldownProgress(0f))
                            + " hp=" + (target instanceof LivingEntity le ? String.format(java.util.Locale.ROOT, "%.1f", le.getHealth()) : "-")
                            + " tbot=" + (find("trigger bot") != null && find("trigger bot").isEnabled())
                            + " zY=" + (target == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", target.getY()))
                            + " playerY=" + String.format(java.util.Locale.ROOT, "%.1f", mc.player.getY())
                            + " eye=" + (target instanceof LivingEntity le2 ? String.format(java.util.Locale.ROOT, "%.2f", com.trace.refract.util.Combat.eyeDistance(le2)) : "-")
                            + " gates[holdAtk=" + com.trace.refract.util.Combat.holdingAttack()
                            + " holdWeapon=" + com.trace.refract.util.Combat.holdingWeapon()
                            + " critReady=" + com.trace.refract.util.Combat.critReady()
                            + " best=" + (com.trace.refract.util.Combat.bestTarget(3.2, 120, true) != null)
                            + "]");
                }
                if (ticks == 200) mc.options.attackKey.setPressed(false);
            }
            case "crystal" -> {
                if (ticks == 110) {
                    for (int i = 0; i < 9; i++) {
                        if (mc.player.getInventory().getStack(i).getItem() == net.minecraft.item.Items.OBSIDIAN) {
                            mc.player.getInventory().setSelectedSlot(i);
                            log("selected obsidian in slot " + i);
                            break;
                        }
                    }
                }
                if (ticks == 60) {
                    enable("auto crystal", true);
                    // Look at the floor ~2 blocks ahead, not straight down into the player's own feet block.
                    mc.player.setYaw(0f);
                    mc.player.setPitch(35f);
                    log("before: obsidian=" + countBlock(mc, net.minecraft.block.Blocks.OBSIDIAN)
                            + " crystals=" + countCrystals(mc));

                    log("auto crystal on, looking down");
                }
                if (ticks > 60 && ticks % 20 == 0) {
                    log("t=" + ticks + " obsidian=" + countBlock(mc, net.minecraft.block.Blocks.OBSIDIAN)
                            + " crystals=" + countCrystals(mc)
                            + " held=" + mc.player.getMainHandStack().getItem());
                }
                if (ticks == 200) enable("auto crystal", false);
            }
            case "anchor" -> {
                if (ticks == 60) {
                    enable("anchor macro", true);
                    cmd(mc, "execute as @s at @s run setblock ^ ^ ^2 minecraft:stone");
                    mc.player.setPitch(50f);
                    ModuleManager.anchorMacro.triggerOnce();
                    log("anchor macro triggered");
                }
                if (ticks == 200) enable("anchor macro", false);
            }
            case "totem" -> {
                if (ticks == 50) { enable("auto totem", true); log("auto totem on"); }
                if (ticks == 150) cmd(mc, "damage @s 60");
                if (ticks == 260) enable("auto totem", false);
            }
            case "base" -> {
                if (ticks == 20) {
                    cmd(mc, "execute at @s run fill ~5 ~-1 ~5 ~13 ~6 ~13 minecraft:stone");
                    cmd(mc, "execute at @s run fill ~6 ~ ~6 ~12 ~5 ~12 minecraft:air");
                    cmd(mc, "execute at @s run fill ~7 ~ ~7 ~11 ~ ~7 minecraft:hopper");
                    cmd(mc, "execute at @s run fill ~7 ~ ~9 ~10 ~ ~9 minecraft:furnace");
                    cmd(mc, "execute at @s run fill ~7 ~1 ~7 ~10 ~1 ~7 minecraft:chest");
                    cmd(mc, "execute at @s run fill ~11 ~ ~11 ~11 ~ ~11 minecraft:barrel");
                    // Trap Zone: TNT behind pistons, observers and repeaters, almost no storage.
                    cmd(mc, "execute at @s run fill ~44 ~-1 ~0 ~54 ~6 ~10 minecraft:stone");
                    cmd(mc, "execute at @s run fill ~45 ~ ~1 ~53 ~5 ~9 minecraft:air");
                    cmd(mc, "execute at @s run fill ~46 ~ ~2 ~48 ~ ~2 minecraft:tnt");
                    cmd(mc, "execute at @s run fill ~46 ~1 ~4 ~50 ~1 ~4 minecraft:sticky_piston");
                    cmd(mc, "execute at @s run fill ~46 ~2 ~6 ~50 ~2 ~6 minecraft:observer");
                    cmd(mc, "execute at @s run fill ~46 ~3 ~8 ~48 ~3 ~8 minecraft:repeater");
                    // Vault: a sealed room of shulker boxes and ender chests.
                    cmd(mc, "execute at @s run fill ~-54 ~-1 ~0 ~-44 ~6 ~10 minecraft:stone");
                    cmd(mc, "execute at @s run fill ~-53 ~ ~1 ~-45 ~5 ~9 minecraft:air");
                    cmd(mc, "execute at @s run fill ~-52 ~ ~2 ~-50 ~ ~2 minecraft:shulker_box");
                    cmd(mc, "execute at @s run fill ~-49 ~ ~2 ~-48 ~ ~2 minecraft:ender_chest");
                    log("farm stash, trap zone and vault built");
                }
                if (ticks == 60) {
                    enable("base finder", true);
                    log("base finder on");
                }
                if (ticks > 100 && ticks % 40 == 0 && ModuleManager.baseFinder != null) {
                    StringBuilder sb = new StringBuilder();
                    int i = 0;
                    for (var cd : ModuleManager.baseFinder.candidates) {
                        if (i++ > 4) break;
                        sb.append(cd.type).append('@').append(cd.anchor)
                          .append('(').append(Math.round(cd.confidence * 100)).append("%) ");
                    }
                    log("base t=" + ticks + " n=" + ModuleManager.baseFinder.candidates.size() + " [" + sb + "]");
                }
            }
            case "update" -> {
                if (ticks == 100) log("updater status: " + com.trace.refract.update.Updater.status()
                        + " current=" + com.trace.refract.update.Updater.currentVersion()
                        + " latest=" + com.trace.refract.update.Updater.latestVersion());
                if (ticks == 140) log("updater status: " + com.trace.refract.update.Updater.status());
                if (ticks == 160) {
                    log("isNewer(1.1.0,1.0.0)=" + com.trace.refract.update.Updater.isNewer("1.1.0", "1.0.0")
                            + " isNewer(1.0.0,1.0.0)=" + com.trace.refract.update.Updater.isNewer("1.0.0", "1.0.0")
                            + " summary='" + com.trace.refract.update.Updater.summarize("# Changelog\n- Fixed reach\n- Better ESP") + "'");
                }
            }
            case "chest" -> {
                if (ticks == 18) cmd(mc, "tp @s 3000 90 3000");
                if (ticks == 20) {
                    cmd(mc, "execute at @s run fill ~5 ~-1 ~5 ~11 ~4 ~11 minecraft:stone");
                    cmd(mc, "execute at @s run fill ~6 ~ ~6 ~10 ~3 ~10 minecraft:air");
                    cmd(mc, "execute at @s run setblock ~8 ~ ~8 minecraft:chest");
                    log("single chest placed");
                }
                if (ticks == 50) enable("base finder", true);
                if (ticks > 90 && ticks % 40 == 0 && ModuleManager.baseFinder != null) {
                    StringBuilder sb = new StringBuilder();
                    for (var cd : ModuleManager.baseFinder.candidates) sb.append(cd.type).append(' ');
                    log("chest t=" + ticks + " n=" + ModuleManager.baseFinder.candidates.size() + " [" + sb + "]");
                }
            }
            case "vault" -> {
                if (ticks == 18) {
                    cmd(mc, "tp @s 4000 -20 4000");
                    log("moved underground for vault test");
                }
                if (ticks == 22) {
                    // Underground on purpose: surface builds are filtered out as ordinary terrain.
                    cmd(mc, "execute at @s run fill ~-5 ~-1 ~-5 ~5 ~4 ~5 minecraft:stone");
                    cmd(mc, "execute at @s run fill ~-4 ~ ~-4 ~4 ~3 ~4 minecraft:air");
                    cmd(mc, "execute at @s run fill ~-3 ~ ~-3 ~-1 ~ ~-3 minecraft:shulker_box");
                    cmd(mc, "execute at @s run fill ~0 ~ ~-3 ~1 ~ ~-3 minecraft:ender_chest");
                    cmd(mc, "execute at @s run fill ~-3 ~ ~-1 ~ ~ ~-1 minecraft:chest");
                    log("vault structure built underground");
                }
                if (ticks == 50) enable("base finder", true);
                if (ticks > 90 && ticks % 40 == 0 && ModuleManager.baseFinder != null) {
                    StringBuilder sb = new StringBuilder();
                    for (var cd : ModuleManager.baseFinder.candidates) {
                        sb.append(cd.type).append('@').append(cd.anchor)
                          .append('(').append(Math.round(cd.confidence * 100)).append("%) ");
                    }
                    log("vault t=" + ticks + " n=" + ModuleManager.baseFinder.candidates.size() + " [" + sb + "]");
                }
            }
            case "debris" -> {
                // Build a sealed room in the Nether with a known debris vein, then fly the player into it.
                if (ticks == 20) {
                    // Teleport first: blocks cannot be placed in chunks the server has not loaded yet.
                    cmd(mc, "execute in minecraft:the_nether run tp @s 5 71 8");
                    log("teleported to nether");
                }
                if (ticks == 40) {
                    cmd(mc, "execute in minecraft:the_nether run kill @e[type=!minecraft:player,distance=..24]");
                    cmd(mc, "execute in minecraft:the_nether run fill 0 69 0 10 69 10 minecraft:netherrack");
                    cmd(mc, "execute in minecraft:the_nether run fill 1 70 1 9 76 9 minecraft:air");
                    cmd(mc, "execute in minecraft:the_nether run fill 3 70 3 6 70 6 minecraft:ancient_debris");
                    cmd(mc, "execute in minecraft:the_nether run setblock 4 71 4 minecraft:ancient_debris");
                    cmd(mc, "execute in minecraft:the_nether run tp @s 5 71 8");
                    log("nether chamber built after load");
                }
                if (ticks == 55) {
                    mc.setScreen(null); // make sure no screen is covering the world
                }
                if (ticks == 60) {
                    // Look down at the vein so the boxes and tracers are in frame for the shot.
                    mc.player.setYaw(166f);
                    mc.player.setPitch(51f);
                    log("aimed at vein");
                }
                if (ticks == 190) {
                    mc.setScreen(new com.trace.refract.gui.ClickGuiScreen());
                    log("opened click gui for icon shot");
                }
                if (ticks == 210) shoot(mc, "icons.png");
                if (ticks == 80) {
                    enable("ancient debris", true);
                    log("debris on: dim=" + (mc.world == null ? "-" : mc.world.getRegistryKey().getValue())
                            + " preset=" + ModuleManager.blockEsp.preset.get()
                            + " blocks=" + ModuleManager.blockEsp.blocks.get().size());
                }
                if (ticks > 80 && ticks % 40 == 0) {
                    log("debris t=" + ticks + " found=" + ModuleManager.blockEsp.suffix()
                            + " dim=" + (mc.world == null ? "-" : mc.world.getRegistryKey().getValue()));
                }
                if (ticks == 160) shoot(mc, "debris-finder.png");
            }
            case "esp" -> {
                if (ticks == 60) {
                    enable("ancient debris", true);
                    log("debris finder on, preset=" + ModuleManager.blockEsp.preset.get()
                            + " blocks=" + ModuleManager.blockEsp.blocks.get().size());
                }
                if (ticks > 60 && ticks % 40 == 0) {
                    log("esp t=" + ticks + " found=" + ModuleManager.blockEsp.suffix()
                            + " blockEsp=" + ModuleManager.blockEsp.isEnabled());
                }
            }
            case "vanilla" -> {
                // Control: a plain right-click, exactly as the game would do it, with no module involved.
                if (ticks == 110) {
                    mc.player.setYaw(0f);
                    mc.player.setPitch(35f);
                    log("control placed at 110");
                }
                if (ticks == 115) {
                    if (mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult b) {
                        var r = mc.interactionManager.interactBlock(mc.player, net.minecraft.util.Hand.MAIN_HAND, b);
                        log("control result=" + r + " pos=" + b.getBlockPos() + " side=" + b.getSide());
                    } else {
                        log("control: crosshair not a block (" + mc.crosshairTarget.getType() + ")");
                    }
                }
                if (ticks == 140) log("control after: obsidian=" + countBlock(mc, net.minecraft.block.Blocks.OBSIDIAN));
            }
            case "stap" -> {
                if (ticks == 50) { enable("auto s tap", true); mc.options.attackKey.setPressed(true); }
                if (ticks > 50) look(mc, target = nearest(mc));
                if (ticks == 180) mc.options.attackKey.setPressed(false);
            }
            default -> log("unknown spec: " + SPEC);
        }

        if (ticks == 240) {
            for (Module m : on) m.setEnabled(false, false);
            mc.options.attackKey.setPressed(false);
            finished = true;
            log("RESULT spec=" + SPEC + " finished, modules exercised=" + on.size()
                    + " health=" + (mc.player == null ? "-" : mc.player.getHealth())
                    + " onGround=" + (mc.player != null && mc.player.isOnGround()));
            new Thread(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                mc.scheduleStop();
            }, "livetest-stop").start();
        }
    }
}
