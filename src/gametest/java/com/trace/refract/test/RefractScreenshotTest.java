package com.trace.refract.test;

import com.trace.refract.Refract;
import com.trace.refract.gui.ClickGuiScreen;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.scan.ChunkReport;
import com.trace.refract.scan.Confidence;
import com.trace.refract.scan.WorldScanner;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.math.BlockPos;

/**
 * Builds a test scene and checks every detector, logging PASS/FAIL lines:
 * <ul>
 *   <li>a stash and hideout built on the surface must NOT be flagged while the Surface Filter is on;</li>
 *   <li>the same kind of stash buried under 16 blocks of stone MUST be found, with exact coordinates;</li>
 *   <li>overgrown kelp (age 25) and a matured geode must show in Growth ESP;</li>
 *   <li>a lever 80 blocks away and 40 blocks up must be heard; one you "clicked" yourself must not;</li>
 *   <li>a dispenser firing must be reported through world events.</li>
 * </ul>
 * Then screenshots the world, HUD and GUI and logs FPS with everything on and off.
 */
public class RefractScreenshotTest implements FabricClientGameTest {
    private static int failures;

    // Camera-stillness tracking while Auto Hunter digs (sampled every client tick).
    private static volatile boolean tracking;
    private static Float digYaw, digPitch;
    private static float maxDrift;
    private static int digTicks, silentTicks;
    private static float maxHeadOffset;

    private static void log(String fmt, Object... args) { Refract.LOG.info("[refract-test] " + fmt, args); }

    private static void check(boolean ok, String what) {
        if (!ok) failures++;
        log("{} {}", ok ? "PASS" : "FAIL", what);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            var s = world.getServer();
            s.runCommand("time set noon");
            s.runCommand("weather clear");
            s.runCommand("gamerule doDaylightCycle false");
            s.runCommand("gamerule randomTickSpeed 0");
            s.runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 25");
            ctx.waitTicks(20);
            BlockPos me = ctx.computeOnClient(c -> c.player.getBlockPos());
            ctx.runOnClient(c -> c.options.getViewDistance().setValue(16));
            s.runCommand("execute as @p at @s run tp @s ~ ~ ~");

            ctx.runOnClient(c -> {
                // Start from a clean slate: no saved waypoints, Auto-Add at its default (off).
                ModuleManager.waypoints.autoBases.set(false);
                ModuleManager.waypoints.clearAll();
                ModuleManager.surfaceFilter.setEnabled(true, false);
                ModuleManager.baseFinder.minLevel.set("Low");
                ModuleManager.susChunks.minLevel.set("Low");
                ModuleManager.soundLocator.duration.set(60.0);
                ModuleManager.baseFinder.setEnabled(true);
                ModuleManager.susChunks.setEnabled(true);
                ModuleManager.growthEsp.setEnabled(true);
                ModuleManager.storageEsp.setEnabled(true);
                ModuleManager.soundLocator.setEnabled(true);
                ModuleManager.entityFinder.setEnabled(true);
                ModuleManager.playerLog.setEnabled(true);
            });

            String at = "execute as @p at @s run ";
            // Overgrown kelp: a sealed glass tank with 12 capped (age 25) columns of 12 blocks.
            s.runCommand(at + "fill ~-5 ~ ~9 ~6 ~15 ~20 minecraft:glass hollow");
            s.runCommand(at + "fill ~-4 ~1 ~10 ~5 ~1 ~19 minecraft:sand");
            s.runCommand(at + "fill ~-4 ~2 ~10 ~5 ~14 ~19 minecraft:water");
            for (int i = 0; i < 12; i++) {
                int x = -4 + (i % 4) * 3, z = 10 + (i / 4) * 3;
                s.runCommand(at + "fill ~" + x + " ~2 ~" + z + " ~" + x + " ~12 ~" + z + " minecraft:kelp_plant");
                s.runCommand(at + "setblock ~" + x + " ~13 ~" + z + " minecraft:kelp[age=25]");
            }
            // Natural-looking kelp next to it (age 22, 6 tall) must NOT be reported.
            s.runCommand(at + "fill ~3 ~2 ~18 ~3 ~6 ~18 minecraft:kelp_plant");
            s.runCommand(at + "setblock ~3 ~7 ~18 minecraft:kelp[age=22]");
            // Matured geode: 14 clusters and 2 buds on budding amethyst (natural share would be ~25-30%).
            s.runCommand(at + "fill ~8 ~ ~12 ~15 ~ ~13 minecraft:budding_amethyst");
            s.runCommand(at + "fill ~8 ~1 ~12 ~15 ~1 ~12 minecraft:amethyst_cluster[facing=up]");
            s.runCommand(at + "fill ~8 ~1 ~13 ~13 ~1 ~13 minecraft:amethyst_cluster[facing=up]");
            s.runCommand(at + "fill ~14 ~1 ~13 ~15 ~1 ~13 minecraft:small_amethyst_bud[facing=up]");
            // SURFACE stash and hideout (griefed-land noise): must be ignored with the Surface Filter on.
            s.runCommand(at + "fill ~-14 ~ ~38 ~-8 ~ ~38 minecraft:shulker_box");
            s.runCommand(at + "fill ~-14 ~ ~40 ~-11 ~ ~40 minecraft:ender_chest");
            s.runCommand(at + "setblock ~-9 ~ ~42 minecraft:beacon");
            s.runCommand(at + "fill ~-14 ~ ~44 ~-10 ~ ~44 minecraft:chest");
            s.runCommand(at + "setblock ~-8 ~ ~44 minecraft:oak_sign");
            s.runCommand(at + "fill ~-24 ~ ~18 ~-18 ~5 ~34 minecraft:stone");
            s.runCommand(at + "fill ~-21 ~1 ~19 ~-21 ~2 ~33 minecraft:air");
            s.runCommand(at + "fill ~-24 ~3 ~18 ~-24 ~5 ~20 minecraft:deepslate[axis=x]");
            // BURIED stash: 16 blocks of stone on top, shulkers and ender chests in a dug-out room.
            s.runCommand(at + "fill ~18 ~ ~-34 ~34 ~16 ~-18 minecraft:stone");
            s.runCommand(at + "fill ~22 ~1 ~-30 ~30 ~2 ~-22 minecraft:air");
            s.runCommand(at + "fill ~22 ~1 ~-26 ~28 ~1 ~-26 minecraft:shulker_box");
            s.runCommand(at + "fill ~22 ~1 ~-24 ~25 ~1 ~-24 minecraft:ender_chest");
            s.runCommand(at + "setblock ~29 ~1 ~-28 minecraft:hopper");
            s.runCommand(at + "summon minecraft:villager ~26 ~1 ~-29 {NoAI:1b}");
            s.runCommand(at + "summon minecraft:chest_minecart ~24 ~1 ~-29");
            s.runCommand(at + "summon minecraft:villager ~-12 ~ ~36 {NoAI:1b}");
            // GRIEFED base: a dug-out room under a stone hill, roof ripped open to the sky, storage gone; only planks,
            // glass, a door and lanterns left behind. Must still be found.
            s.runCommand(at + "fill ~-40 ~ ~-40 ~-22 ~18 ~-22 minecraft:stone");
            s.runCommand(at + "fill ~-38 ~1 ~-38 ~-26 ~3 ~-26 minecraft:air");
            s.runCommand(at + "fill ~-36 ~4 ~-36 ~-28 ~18 ~-28 minecraft:air");
            s.runCommand(at + "fill ~-38 ~1 ~-38 ~-38 ~3 ~-26 minecraft:oak_planks");
            s.runCommand(at + "fill ~-37 ~3 ~-30 ~-34 ~3 ~-30 minecraft:glass");
            s.runCommand(at + "setblock ~-33 ~1 ~-38 minecraft:oak_door[half=lower]");
            s.runCommand(at + "setblock ~-33 ~2 ~-38 minecraft:oak_door[half=upper]");
            s.runCommand(at + "setblock ~-27 ~1 ~-27 minecraft:lantern");
            s.runCommand(at + "setblock ~-29 ~1 ~-35 minecraft:lantern");
            ctx.waitTicks(80);

            // Far geode 105 blocks out (inside render distance): must reach Growth ESP.
            s.runCommand("forceload add " + (me.getX() + 85) + " " + (me.getZ() - 20) + " " + (me.getX() + 100) + " " + (me.getZ() + 20));
            s.runCommand(at + "fill ~90 ~ ~ ~97 ~ ~1 minecraft:budding_amethyst");
            s.runCommand(at + "fill ~90 ~1 ~ ~97 ~1 ~1 minecraft:amethyst_cluster[facing=up]");
            s.runCommand(at + "setblock ~97 ~1 ~1 minecraft:small_amethyst_bud[facing=up]");
            ctx.waitTicks(120);
            // Sound: a lever 80 blocks away and 40 blocks up, flipped by the server (render-distance detection).
            s.runCommand(at + "setblock ~6 ~40 ~80 minecraft:stone");
            s.runCommand(at + "setblock ~6 ~41 ~80 minecraft:lever[face=floor,powered=false]");
            // A lever "you" clicked: registered as your own action first, so flipping it must not be reported.
            s.runCommand(at + "setblock ~-6 ~ ~-8 minecraft:lever[face=floor,powered=false]");
            ctx.waitTicks(10);
            ctx.runOnClient(c -> ModuleManager.soundLocator.onSelfAction(me.add(-6, 0, -8), true));
            s.runCommand(at + "setblock ~-6 ~ ~-8 minecraft:lever[face=floor,powered=true]");
            s.runCommand(at + "setblock ~6 ~41 ~80 minecraft:lever[face=floor,powered=true]");
            // World event: an empty dispenser powered by a redstone block clicks.
            s.runCommand(at + "setblock ~24 ~ ~6 minecraft:dispenser[facing=up]");
            s.runCommand(at + "setblock ~25 ~ ~6 minecraft:redstone_block");
            // Block events / sounds nearby: a real piston and a chest sound.
            s.runCommand(at + "setblock ~3 ~ ~6 minecraft:piston[facing=up]");
            s.runCommand(at + "setblock ~3 ~ ~5 minecraft:redstone_block");
            s.runCommand(at + "playsound minecraft:block.chest.open block @a ~-3 ~ ~5");
            ctx.waitTicks(60);

            ctx.runOnClient(c -> {
                log("scanned chunks: {} (queue {})", WorldScanner.reports().size(), WorldScanner.queued());
                for (ChunkReport r : WorldScanner.reports()) {
                    if (r.kelpColumns > 0 || r.clusters > 0 || r.activity > 0 || r.tunnelLength > 0 || r.rotated > 0 || r.surfaceSkipped > 0)
                        log("chunk {} tunnel {} rot {} kelp {}/{} | amethyst {}c/{}b z={} mature {} | activity {} strong {} [{}] | skipped {} | sus {} ({})",
                                r.pos, r.tunnelLength, r.rotated, r.kelpGrown, r.kelpColumns,
                                r.clusters, r.buds, String.format("%.2f", r.amethystZ), r.geodeMature, String.format("%.1f", r.activity),
                                String.format("%.1f", r.strong), r.summary(4), r.surfaceSkipped, String.format("%.2f", r.susConfidence),
                                Confidence.level(r.susConfidence).label);
                }
                var cands = ModuleManager.baseFinder.candidates;
                for (var cand : cands) log("BASE {} {}% at {} bounds {} : {}", cand.level.label, Math.round(cand.confidence * 100), cand.anchor, cand.bounds, cand.summary(5));
                boolean buried = cands.stream().anyMatch(c2 -> c2.anchor.getX() >= me.getX() + 20 && c2.anchor.getZ() <= me.getZ() - 20);
                boolean surface = cands.stream().anyMatch(c2 -> c2.anchor.getZ() >= me.getZ() + 15);
                check(buried, "buried stash found by Base Finder");
                cands.stream().filter(c2 -> c2.anchor.getX() >= me.getX() + 20).findFirst().ifPresent(c2 -> {
                    log("BASE TYPE {} / {} ({})", c2.type, c2.status, c2.statusReason);
                    check(c2.type.equals("Stash"), "buried stash classified as Stash (got " + c2.type + ")");
                    check(c2.status.equals("Intact"), "buried stash reported Intact (got " + c2.status + ")");
                    var est = com.trace.refract.scan.BaseValue.of(c2.found);
                    log("BASE VALUE {} : {}", com.trace.refract.scan.BaseValue.money(est.total()), est.lines());
                    check(est.total() >= 7 * 30_000, "buried stash valued from its blocks (got " + com.trace.refract.scan.BaseValue.money(est.total()) + ")");
                });
                check(!surface, "surface stash/hideout ignored by Base Finder (Surface Filter)");
                var griefed = cands.stream().filter(c2 -> c2.anchor.getX() <= me.getX() - 22 && c2.anchor.getZ() <= me.getZ() - 22).findFirst().orElse(null);
                if (griefed != null) log("GRIEFED {} / {} at {} : {}", griefed.type, griefed.status, griefed.anchor, griefed.summary(5));
                check(griefed != null && griefed.type.equals("Griefed Base"), "griefed base (roof ripped open, storage gone) found and called Griefed Base");
                boolean surfaceActivity = ModuleManager.susChunks.hits().stream().anyMatch(h -> h.report().rotated > 0 || h.report().tunnelLength > 0);
                check(!surfaceActivity, "surface tunnel / rotated deepslate ignored by Activity Finder");
                for (var h : ModuleManager.susChunks.hits()) log("ACTIVITY {} at {} : {}", h.level().label, h.focus(), h.reasons());
                for (var f : ModuleManager.entityFinder.found) log("ENTITY {} at {}", f.kind(), f.entity().getBlockPos());
                check(ModuleManager.entityFinder.found.size() == 2, "Entity Finder: buried villager + chest minecart found, surface villager ignored (got " + ModuleManager.entityFinder.found.size() + ")");
                check(ModuleManager.growthEsp.kelpCount() == 12, "Growth ESP shows exactly the 12 grown kelp (got " + ModuleManager.growthEsp.kelpCount() + ")");
                check(ModuleManager.growthEsp.clusterCount() >= 14, "Growth ESP shows the matured geodes' clusters (got " + ModuleManager.growthEsp.clusterCount() + ")");
                var pings = ModuleManager.soundLocator.pings;
                for (var p : pings) log("PING {} '{}' at {} via {}", p.kind, p.label, p.pos, p.source);
                check(pings.stream().anyMatch(p -> p.pos.equals(me.add(6, 41, 80))), "far lever (80m away, 41 up) heard");
                log("reach readout: {}", ModuleManager.soundLocator.reachText());
                log("scanner sees {} m; client has {} chunks loaded, scanner knows {}", Math.round(WorldScanner.farthest()), c.world.getChunkManager().getLoadedChunkCount(), WorldScanner.reports().size());
                boolean farGeode = WorldScanner.reports().stream().anyMatch(r -> r.geodeMature && r.clusterEvidence.stream().anyMatch(q -> q.getX() >= me.getX() + 90));
                check(farGeode, "mature geode 90 blocks away detected (render distance " + c.options.getViewDistance().getValue() + ", clamped " + c.options.getClampedViewDistance() + ")");
                check(pings.stream().noneMatch(p -> p.pos.equals(me.add(-6, 0, -8))), "own lever not reported");
                check(pings.stream().anyMatch(p -> p.pos.equals(me.add(24, 0, 6)) && p.kind == com.trace.refract.module.modules.SoundLocator.Kind.REDSTONE), "dispenser reported");
            });

            // Turning the filter off must bring the surface stash back (proves the filter is what hides it).
            ctx.runOnClient(c -> ModuleManager.surfaceFilter.setEnabled(false, false));
            ctx.waitTicks(80);
            ctx.runOnClient(c -> {
                boolean surface = ModuleManager.baseFinder.candidates.stream().anyMatch(c2 -> c2.evidence.stream().anyMatch(q -> q.getZ() >= me.getZ() + 38));
                check(surface, "surface stash found once the Surface Filter is off");
                ModuleManager.surfaceFilter.setEnabled(true, false);
            });
            ctx.waitTicks(60);
            ctx.takeScreenshot("refract_world_esp");

            // Look straight at the buried base: the inspector card must open with the breakdown.
            ctx.runOnClient(c -> {
                var cand = ModuleManager.baseFinder.candidates.get(0);
                var d = cand.center.subtract(c.player.getEyePos());
                c.player.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                c.player.setPitch((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
            });
            ctx.waitTicks(30);
            ctx.takeScreenshot("refract_inspector");
            ctx.runOnClient(c -> {
                Long hk = ModuleManager.baseFinder.hologramKey();
                log("HOLOGRAM key {} voxels {}", hk, ModuleManager.baseFinder.hologramVoxels());
                check(hk != null && hk == ModuleManager.baseFinder.candidates.get(0).key && ModuleManager.baseFinder.hologramVoxels() > 20,
                        "looking at a base shows its hologram (" + ModuleManager.baseFinder.hologramVoxels() + " voxels)");
            });
            // The same view with the HUD hidden, so the hologram itself is visible in the screenshot.
            ctx.runOnClient(c -> c.options.hudHidden = true);
            ctx.waitTicks(10);
            ctx.takeScreenshot("refract_hologram");
            ctx.runOnClient(c -> c.player.setPitch(c.player.getPitch() - 22));
            ctx.waitTicks(15);
            ctx.takeScreenshot("refract_hologram_up");
            ctx.runOnClient(c -> c.options.hudHidden = false);
            // Toggle toasts: a green tick for on, a red cross for off.
            ctx.runOnClient(c -> { ModuleManager.entityFinder.setEnabled(false, true); ModuleManager.playerLog.setEnabled(false, true); ModuleManager.playerLog.setEnabled(true, true); });
            ctx.waitTicks(8);
            ctx.takeScreenshot("refract_toasts");
            ctx.runOnClient(c -> ModuleManager.entityFinder.setEnabled(true, false));
            ctx.runOnClient(c -> { c.player.setYaw(0); c.player.setPitch(25); });

            // /ah price reader.
            ctx.runOnClient(c -> {
                var st = new net.minecraft.item.ItemStack(net.minecraft.item.Items.FIREWORK_ROCKET, 64);
                st.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(
                        java.util.List.of(net.minecraft.text.Text.literal("Seller: Bob"), net.minecraft.text.Text.literal("Price: $1.5k"))));
                double pr = com.trace.refract.module.modules.AutoHunterTestHook.price(st);
                check(pr == 1500, "auction price '$1.5k' read as 1500 (got " + pr + ")");
            });

            // Auto Hunter end to end: elytra takeoff, rockets, find the 100% buried stash, land, dig down to it.
            s.runCommand("gamemode survival @p");
            s.runCommand("effect give @p minecraft:resistance 300 4 true");
            s.runCommand("item replace entity @p armor.chest with minecraft:elytra");
            s.runCommand("give @p minecraft:firework_rocket[minecraft:fireworks={flight_duration:2}] 128");
            s.runCommand("give @p minecraft:diamond_pickaxe");
            ctx.waitTicks(10);
            // The full flight-and-dig run takes minutes; it only runs with -Drefract.test.hunter=true.
            if (Boolean.getBoolean("refract.test.hunter")) {
            ctx.runOnClient(c -> {
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                    if (!tracking || mc.player == null || !ModuleManager.autoHunter.state().name().startsWith("DIG")) return;
                    float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
                    if (digYaw == null) { digYaw = yaw; digPitch = pitch; }
                    maxDrift = Math.max(maxDrift, Math.max(Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(yaw - digYaw)), Math.abs(pitch - digPitch)));
                    digTicks++;
                    if (com.trace.refract.util.Rotations.active()) {
                        float off = Math.max(Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(com.trace.refract.util.Rotations.yaw() - yaw)),
                                Math.abs(com.trace.refract.util.Rotations.pitch() - pitch));
                        maxHeadOffset = Math.max(maxHeadOffset, off);
                        if (off > 2f) silentTicks++;
                    }
                });
                tracking = true;
                ModuleManager.autoHunter.cruiseY.set(120.0);
                ModuleManager.autoHunter.setEnabled(true, false);
            });
            boolean dugShot = false;
            String last = "";
            for (int i = 0; i < 160; i++) {
                ctx.waitTicks(20);
                String st = ctx.computeOnClient(c -> ModuleManager.autoHunter.state() + " | " + ModuleManager.autoHunter.status()
                        + " | at " + c.player.getBlockPos().toShortString() + " | rockets used? gliding=" + c.player.isGliding());
                if (!st.equals(last)) log("HUNTER {}", st);
                last = st;
                if (!ctx.computeOnClient(c -> ModuleManager.autoHunter.isEnabled())) break;
                if (i == 12) ctx.takeScreenshot("refract_autohunter_flight");
                if (!dugShot && st.startsWith("DIG_CLEAR")) { dugShot = true; ctx.takeScreenshot("refract_autohunter_digging"); }
            }
            ctx.runOnClient(c -> {
                var cand = ModuleManager.baseFinder.candidates.stream().filter(c2 -> c2.anchor.getX() >= me.getX() + 20).findFirst().orElse(null);
                var pos = c.player.getBlockPos();
                log("HUNTER END state {} at {} target {}", ModuleManager.autoHunter.state(), pos, cand == null ? null : cand.anchor);
                boolean arrived = cand != null && Math.abs(pos.getX() - cand.anchor.getX()) + Math.abs(pos.getZ() - cand.anchor.getZ()) <= 5
                        && Math.abs(pos.getY() - cand.anchor.getY()) <= 3;
                check(arrived, "Auto Hunter flew, landed and dug down to the buried stash");
                check(ModuleManager.waypoints.here().isEmpty(), "no waypoints saved while Auto-Add Bases is off (got " + ModuleManager.waypoints.here().size() + ")");
                tracking = false;
                log("DIG camera: {} dig ticks, camera drift {} deg, server head off-camera on {} ticks (max {} deg)",
                        digTicks, String.format("%.3f", maxDrift), silentTicks, String.format("%.1f", maxHeadOffset));
                check(digTicks > 20 && maxDrift < 0.01f, "camera stayed still while Auto Hunter mined (drift " + String.format("%.3f", maxDrift) + " deg over " + digTicks + " ticks)");
                check(silentTicks > 0, "server-side head turned onto the blocks while mining (silent rotation, max " + String.format("%.1f", maxHeadOffset) + " deg off camera)");
                ModuleManager.autoHunter.setEnabled(false, false);
            });
            ctx.takeScreenshot("refract_autohunter_arrived");
            } else {
                log("Auto Hunter flight/dig run skipped (set -Drefract.test.hunter=true to include it)");
            }
            failures += CombatChecks.run(ctx, s::runCommand, me);

            // Honest cost check: same view, uncapped, Refract off vs everything visual on, plus a per-module profile.
            ctx.runOnClient(c -> {
                c.options.getEnableVsync().setValue(false);
                c.options.getMaxFps().setValue(260);
                c.options.getInactivityFpsLimit().setValue(net.minecraft.client.option.InactivityFpsLimit.MINIMIZED);
            });
            ctx.runOnClient(c -> ModuleManager.MODULES.forEach(mod -> { if (mod != ModuleManager.surfaceFilter) mod.setEnabled(false, false); }));
            ctx.waitTicks(70);
            int fpsOff = ctx.computeOnClient(c -> c.getCurrentFps());
            ctx.runOnClient(c -> ModuleManager.hud.setEnabled(true, false));
            ctx.waitTicks(60);
            int fpsHud = ctx.computeOnClient(c -> c.getCurrentFps());
            ctx.runOnClient(c -> ModuleManager.hud.setEnabled(false, false));
            ctx.runOnClient(c -> {
                ModuleManager.baseFinder.setEnabled(true, false);
                ModuleManager.susChunks.setEnabled(true, false);
                ModuleManager.growthEsp.setEnabled(true, false);
                ModuleManager.storageEsp.setEnabled(true, false);
                ModuleManager.soundLocator.setEnabled(true, false);
            });
            ctx.waitTicks(60);
            int fpsEsp = ctx.computeOnClient(c -> c.getCurrentFps());
            log("FPS split: HUD only {} / hunting ESP + labels only {}", fpsHud, fpsEsp);
            ctx.runOnClient(c -> {
                ModuleManager.hud.setEnabled(true, false);
                ModuleManager.baseFinder.setEnabled(true, false);
                ModuleManager.susChunks.setEnabled(true, false);
                ModuleManager.growthEsp.setEnabled(true, false);
                ModuleManager.storageEsp.setEnabled(true, false);
                ModuleManager.soundLocator.setEnabled(true, false);
            });
            ctx.waitTicks(70);
            int fpsOn = ctx.computeOnClient(c -> c.getCurrentFps());
            log("FPS same view: Refract off {} / all hunting modules + HUD on {}", fpsOff, fpsOn);
            ctx.runOnClient(c -> { ModuleManager.cosmetics.setEnabled(true, false); ModuleManager.ambience.setEnabled(true, false); com.trace.refract.util.Prof.reset(); });
            ctx.waitTicks(80);
            int fpsAll = ctx.computeOnClient(c -> c.getCurrentFps());
            log("FPS everything on (hunting + HUD + cosmetics + ambience): {}", fpsAll);
            String profile = ctx.computeOnClient(c -> com.trace.refract.util.Prof.report(14));
            log("PROFILE {}", profile);
            ctx.runOnClient(c -> { ModuleManager.cosmetics.setEnabled(false, false); ModuleManager.ambience.setEnabled(false, false); });

            ctx.runOnClient(c -> c.options.hudHidden = false);
            ctx.runOnClient(c -> ClickGuiScreen.focusGrid(com.trace.refract.module.Category.HUNTING));
            ctx.setScreen(ClickGuiScreen::new);
            ctx.waitTicks(25);
            ctx.takeScreenshot("refract_gui_grid");
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);
            ctx.runOnClient(c -> ClickGuiScreen.focusGrid(com.trace.refract.module.Category.COMBAT));
            ctx.setScreen(ClickGuiScreen::new);
            ctx.waitTicks(25);
            ctx.takeScreenshot("refract_gui_combat");
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);
            ctx.runOnClient(c -> ClickGuiScreen.focus(ModuleManager.soundLocator));
            ctx.setScreen(ClickGuiScreen::new);
            ctx.waitTicks(25);
            ctx.takeScreenshot("refract_gui_hunting");
            ctx.runOnClient(c -> {
                ClickGuiScreen.focus(ModuleManager.growthEsp);
                ModuleManager.clickGui.theme.set("Mint");
            });
            ctx.setScreen(ClickGuiScreen::new);
            ctx.waitTicks(30);
            ctx.takeScreenshot("refract_gui_growth");
            ctx.runOnClient(c -> {
                ClickGuiScreen.focus(ModuleManager.surfaceFilter);
                ModuleManager.clickGui.theme.set("Ocean");
            });
            ctx.setScreen(ClickGuiScreen::new);
            ctx.waitTicks(30);
            ctx.takeScreenshot("refract_gui_filter");
            ctx.runOnClient(c -> ClickGuiScreen.focusTheme());
            ctx.setScreen(ClickGuiScreen::new);
            ctx.waitTicks(20);
            ctx.takeScreenshot("refract_gui_theme");
            // Acrylic style, same view.
            ctx.runOnClient(c -> { ModuleManager.clickGui.glass.set("Frosted"); ClickGuiScreen.focus(ModuleManager.baseFinder); });
            ctx.setScreen(ClickGuiScreen::new);
            ctx.waitTicks(20);
            ctx.takeScreenshot("refract_gui_acrylic");
            ctx.runOnClient(c -> ModuleManager.clickGui.glass.set("Liquid Glass"));
            // Block ESP: pick a block from the search, check it is found, and show the search with live previews.
            ctx.runOnClient(c -> {
                ModuleManager.blockEsp.blocks.get().clear();
                ModuleManager.blockEsp.blocks.add(net.minecraft.block.Blocks.CHEST);
                ModuleManager.blockEsp.blocks.add(net.minecraft.block.Blocks.SHULKER_BOX);
                ModuleManager.blockEsp.setEnabled(true, false);
            });
            ctx.waitTicks(60);
            String besp = ctx.computeOnClient(c -> String.valueOf(ModuleManager.blockEsp.suffix()));
            ctx.runOnClient(c -> check(ModuleManager.blockEsp.suffix() != null, "Block ESP finds the chosen blocks (found " + besp + ")"));
            ctx.runOnClient(c -> { ClickGuiScreen.focus(ModuleManager.blockEsp); ClickGuiScreen.pendingBlockQuery = "diamond"; ClickGuiScreen.pendingBlockList = ModuleManager.blockEsp.blocks; });
            ctx.setScreen(ClickGuiScreen::new);
            ctx.waitTicks(20);
            ctx.takeScreenshot("refract_block_search");
            ctx.setScreen(() -> null);
            ctx.runOnClient(c -> ModuleManager.blockEsp.setEnabled(false, false));
            // HUD editor with every element outlined.
            ctx.runOnClient(c -> { ModuleManager.hud.setEnabled(true, false); ModuleManager.hud.coords.set(true); });
            ctx.setScreen(() -> new com.trace.refract.gui.HudEditorScreen(null));
            ctx.waitTicks(20);
            ctx.takeScreenshot("refract_hud_editor");
            ctx.runOnClient(c -> check(com.trace.refract.hud.HudLayout.WATERMARK.drawn && com.trace.refract.hud.HudLayout.FINDINGS.drawn
                    && com.trace.refract.hud.HudLayout.FINDINGS.w > 50, "HUD editor measures every element"));
            ctx.runOnClient(c -> com.trace.refract.hud.HudLayout.FINDINGS.scale = 1.3f);
            ctx.waitTicks(3);
            ctx.runOnClient(c -> com.trace.refract.hud.HudLayout.moveTo(com.trace.refract.hud.HudLayout.FINDINGS, 120, 40, c.getWindow().getScaledWidth(), c.getWindow().getScaledHeight()));
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                var e = com.trace.refract.hud.HudLayout.FINDINGS;
                check(Math.abs(e.x - 120) < 2 && Math.abs(e.y - 40) < 2, "HUD element moved to where it was dropped (at " + Math.round(e.x) + ", " + Math.round(e.y) + ")");
                com.trace.refract.hud.HudLayout.ELEMS.values().forEach(com.trace.refract.hud.HudLayout::reset);
            });
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);
            // Visuals: theme sky, particles, trail + halo in third person, and the karambit in hand.
            s.runCommand("time set 18000");
            s.runCommand("give @p minecraft:netherite_sword");
            ctx.runOnClient(c -> {
                ModuleManager.ambience.setEnabled(true, false);
                ModuleManager.ambience.preset.set("Deep Space");
                ModuleManager.cosmetics.setEnabled(true, false);
                var inv = c.player.getInventory();
                for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(net.minecraft.item.Items.NETHERITE_SWORD)) inv.setSelectedSlot(i);
                c.player.setPitch(-10);
            });
            ctx.getInput().holdKey(o -> o.forwardKey);
            ctx.waitTicks(25);
            ctx.getInput().releaseKey(o -> o.forwardKey);
            ctx.waitTicks(2);
            ctx.waitTicks(12);
            ctx.takeScreenshot("refract_visuals_firstperson");
            s.runCommand("time set noon");
            ctx.waitTicks(4);
            ctx.takeScreenshot("refract_knife_day");
            s.runCommand("time set 18000");
            int arms = ctx.computeOnClient(c -> com.trace.refract.render.knife.KnifeRenderer.armRenders);
            log("ARM renders so far: {}", arms);
            ctx.runOnClient(c -> com.trace.refract.render.knife.KnifeRenderer.inspectStart = com.trace.refract.util.Time.seconds());
            ctx.waitTicks(9);
            ctx.takeScreenshot("refract_knife_inspect");
            ctx.runOnClient(c -> { ModuleManager.cosmetics.knife.set("Butterfly"); ModuleManager.cosmetics.knifeWrap.set("Theme"); });
            ctx.waitTicks(16);
            ctx.takeScreenshot("refract_knife_butterfly");
            s.runCommand("give @p minecraft:ender_pearl 16");
            ctx.runOnClient(c -> {
                ModuleManager.trajectories.setEnabled(true, false);
                var inv = c.player.getInventory();
                for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(net.minecraft.item.Items.ENDER_PEARL)) inv.setSelectedSlot(i);
                c.player.setYaw(90);
                c.player.setPitch(-12);
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("refract_trajectory");
            ctx.runOnClient(c -> ModuleManager.trajectories.setEnabled(false, false));
            ctx.runOnClient(c -> c.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK));
            ctx.getInput().holdKey(o -> o.forwardKey);
            ctx.waitTicks(20);
            ctx.getInput().releaseKey(o -> o.forwardKey);
            ctx.waitTicks(2);
            ctx.runOnClient(c -> c.options.hudHidden = true);
            ctx.waitTicks(2);
            ctx.takeScreenshot("refract_visuals_thirdperson");
            ctx.runOnClient(c -> c.options.hudHidden = false);
            ctx.runOnClient(c -> { c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON); ModuleManager.hud.setEnabled(false, false); c.player.setYaw(-139); c.player.setPitch(-40); });
            ctx.waitTicks(10);
            ctx.takeScreenshot("refract_space_sky");
            ctx.runOnClient(c -> { c.player.setYaw(35); c.player.setPitch(-35); });
            ctx.waitTicks(40);
            ctx.takeScreenshot("refract_space_blackhole");
            ctx.runOnClient(c -> { ModuleManager.ambience.preset.set("Rainy Night"); c.player.setYaw(90); c.player.setPitch(10); });
            ctx.waitTicks(30);
            ctx.takeScreenshot("refract_rain");
            ctx.runOnClient(c -> ModuleManager.ambience.preset.set("Deep Space"));
            int skyFps = ctx.computeOnClient(c -> c.getCurrentFps());
            log("FPS night, space sky + aurora + post-processing + glass, sky in view: {}", skyFps);
            ctx.runOnClient(c -> ModuleManager.hud.setEnabled(true, false));
            ctx.runOnClient(c -> {
                c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
                ModuleManager.ambience.setEnabled(false, false);
                ModuleManager.cosmetics.setEnabled(false, false);
            });
            s.runCommand("time set noon");
            ctx.runOnClient(c -> ClickGuiScreen.focusGrid(com.trace.refract.module.Category.HUNTING));
            ctx.setScreen(() -> null);
            ctx.waitTicks(10);

            ctx.runOnClient(c -> ModuleManager.freecam.setEnabled(true));
            ctx.getInput().holdKey(o -> o.backKey);
            ctx.getInput().holdKey(o -> o.jumpKey);
            ctx.waitTicks(16);
            ctx.getInput().releaseKey(o -> o.backKey);
            ctx.getInput().releaseKey(o -> o.jumpKey);
            ctx.waitTicks(10);
            ctx.takeScreenshot("refract_freecam_overview");
            int fps = ctx.computeOnClient(c -> c.getCurrentFps());
            log("fps with all hunting modules + freecam: {}, ESP shapes per frame: {}", fps, ctx.computeOnClient(c -> com.trace.refract.render.Esp.emitted()));
            ctx.runOnClient(c -> ModuleManager.freecam.setEnabled(false));
            log("RESULT: {} check(s) failed", failures);
        }
    }
}
