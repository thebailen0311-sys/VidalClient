package com.trace.refract.test;

import com.trace.refract.Refract;
import com.trace.refract.module.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.function.Consumer;

/**
 * In-game checks for the combat modules. Each one drives a real scene on the server and then reads back what the
 * client actually did, so a module that compiles but does nothing is caught here.
 */
public final class CombatChecks {
    private static int failures;

    private CombatChecks() {}

    private static void log(String fmt, Object... args) { Refract.LOG.info("[refract-test] " + fmt, args); }

    private static void check(boolean ok, String what) {
        if (!ok) failures++;
        log("{} {}", ok ? "PASS" : "FAIL", what);
    }

    /** Runs every combat check and returns how many failed. {@code cmd} runs a server command. */
    public static int run(ClientGameTestContext ctx, Consumer<String> cmd, BlockPos me) {
        failures = 0;
        cmd.accept("gamemode survival @p");
        cmd.accept("effect give @p minecraft:resistance 600 4 true");
        // Clear the area so every placement has a free space, then a floor to stand on and a wall two blocks
        // east to aim at.
        // These commands run at the world origin unless they are given the player's position explicitly.
        cmd.accept("execute as @p at @s run fill ~-6 ~ ~-6 ~6 ~4 ~6 minecraft:air");
        cmd.accept("execute as @p at @s run fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 minecraft:stone");
        cmd.accept("execute as @p at @s run fill ~3 ~-1 ~-6 ~3 ~3 ~6 minecraft:stone");
        cmd.accept("item replace entity @p hotbar.0 with minecraft:iron_sword");
        cmd.accept("item replace entity @p weapon.offhand with minecraft:air");
        ctx.runOnClient(c -> c.player.getInventory().setSelectedSlot(0));
        // Stealth guard: a click that is out of reach must be dropped, not thrown. This is the regression check for
        // the crash where that guard called setReturnValue on a mixin that was not declared cancellable.
        boolean guardOk = ctx.computeOnClient(c -> {
            BlockPos far = c.player.getBlockPos().add(10, 0, 0);
            boolean wouldCancel = com.trace.refract.module.modules.Stealth.shouldCancel(far);
            var res = c.interactionManager.interactBlock(c.player, net.minecraft.util.Hand.MAIN_HAND,
                    new net.minecraft.util.hit.BlockHitResult(far.toCenterPos(), net.minecraft.util.math.Direction.UP, far, false));
            return wouldCancel && res == net.minecraft.util.ActionResult.PASS;
        });
        check(guardOk, "Stealth guard drops an out-of-reach click without crashing");

        // One zombie, used by both combat checks: aim first (it must survive), then the Trigger Bot swings at
        // the same target. A second summon later in the scene does not reliably appear, so nothing re-summons.
        cmd.accept("execute as @p at @s run summon minecraft:zombie ~ ~ ~1.9 {NoAI:1b}");
        // The spawn packet can take a moment to reach the client, so wait for the entity instead of assuming.
        int nearby = 0;
        for (int i = 0; i < 60 && nearby == 0; i++) {
            ctx.waitTicks(1);
            nearby = ctx.computeOnClient(c -> c.world.getEntitiesByClass(ZombieEntity.class, c.player.getBoundingBox().expand(8), e -> true).size());
        }

        // Aim Assist: look 70 degrees away from the target; the camera must be pulled back onto it.
        ctx.runOnClient(c -> { c.player.setYaw(70f); c.player.setPitch(0f); });
        ctx.waitTicks(2);
        ctx.runOnClient(c -> {
            ModuleManager.aimAssist.holdOnly.set(false);
            ModuleManager.aimAssist.weaponOnly.set(true);
            ModuleManager.aimAssist.silent.set(false);
            ModuleManager.aimAssist.fov.set(360.0); // the target is 70 degrees off camera on purpose
            ModuleManager.aimAssist.setEnabled(true, false);
        });
        // Visible aim eases inside Rotations.frame, which the renderer normally drives. A headless test window
        // may not be drawing frames, so step the same driver by hand here; it is the identical call path.
        float start = ctx.computeOnClient(c -> c.player.getYaw());
        float yaw = start;
        for (int i = 0; i < 10; i++) {
            ctx.runOnClient(c -> { for (int f = 0; f < 6; f++) com.trace.refract.util.Rotations.frame(0.02f); });
            ctx.waitTicks(2);
            yaw = ctx.computeOnClient(c -> c.player.getYaw());
            if (Math.abs(MathHelper.wrapDegrees(start)) - Math.abs(MathHelper.wrapDegrees(yaw)) > 20f) break;
        }
        float turned = Math.abs(MathHelper.wrapDegrees(start)) - Math.abs(MathHelper.wrapDegrees(yaw));
        check(nearby > 0 && turned > 20f,
                "Aim Assist turned the camera onto the target (" + Math.round(turned) + " deg of " + Math.round(Math.abs(MathHelper.wrapDegrees(start))) + ", targets " + nearby + ")");
        ctx.runOnClient(c -> ModuleManager.aimAssist.setEnabled(false, false));

        // Trigger Bot: swing until the zombie in front loses health, with Auto S Tap listening for its hits.
        ctx.runOnClient(c -> { c.player.setYaw(0f); c.player.setPitch(0f); });
        ctx.runOnClient(c -> {
            ModuleManager.triggerbot.holdOnly.set(false);
            ModuleManager.triggerbot.weaponOnly.set(true);
            ModuleManager.triggerbot.crits.set("Off");
            ModuleManager.autoSTap.setEnabled(true, false);
            ModuleManager.triggerbot.setEnabled(true, false);
        });
        boolean tapped = false;
        int seen = 0;
        for (int i = 0; i < 40 && !tapped; i++) {
            ctx.waitTicks(1);
            tapped = ctx.computeOnClient(c -> ModuleManager.autoSTap.tapping());
            seen = ctx.computeOnClient(c -> ModuleManager.autoSTap.hits());
        }
        ctx.waitTicks(10); // damage is applied on the server's next tick
        int hp = ctx.computeOnClient(c -> {
            var list = c.world.getEntitiesByClass(ZombieEntity.class, c.player.getBoundingBox().expand(8), e -> true);
            return list.isEmpty() ? -1 : Math.round(list.get(0).getHealth());
        });
        check(hp >= 0 && hp < 20, "Trigger Bot damaged the target (health " + (hp < 0 ? "no target" : hp) + ")");
        check(tapped, "Auto S Tap opened a tap window after a Trigger Bot hit (hits seen " + seen + ")");
        ctx.runOnClient(c -> ModuleManager.autoSTap.setEnabled(false, false));
        ctx.runOnClient(c -> ModuleManager.triggerbot.setEnabled(false, false));

        // Auto Totem: a totem in the inventory must end up in the off hand. The item has to come from the server,
        // otherwise the next inventory sync wipes the client-side stack again.
        cmd.accept("item replace entity @p hotbar.5 with minecraft:totem_of_undying");
        ctx.waitTicks(5);
        ctx.runOnClient(c -> ModuleManager.autoTotem.setEnabled(true, false));
        ctx.waitTicks(20);
        boolean offhand = ctx.computeOnClient(c -> c.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING));
        check(offhand, "Auto Totem filled the off hand");
        ctx.runOnClient(c -> ModuleManager.autoTotem.setEnabled(false, false));

        // Anchor Macro in Place Only: an anchor must appear in the free block against the wall.
        cmd.accept("item replace entity @p hotbar.1 with minecraft:respawn_anchor");
        cmd.accept("item replace entity @p hotbar.2 with minecraft:glowstone");
        ctx.runOnClient(c -> { c.player.setYaw(-90); c.player.setPitch(0); });
        ctx.waitTicks(5);
        ctx.runOnClient(c -> {
            ModuleManager.anchorMacro.mode.set("Place Only");
            ModuleManager.anchorMacro.safe.set(false);
            ModuleManager.anchorMacro.setEnabled(true, false);
        });
        ctx.waitTicks(10);
        ctx.waitTicks(40);
        boolean anchor = ctx.computeOnClient(c -> {
            BlockPos p = c.player.getBlockPos();
            for (BlockPos q : BlockPos.iterate(p.add(-5, -4, -5), p.add(5, 3, 5))) {
                if (c.world.getBlockState(q).getBlock() == Blocks.RESPAWN_ANCHOR) return true;
            }
            return false;
        });
        check(anchor, "Anchor Macro placed a respawn anchor");
        ctx.runOnClient(c -> ModuleManager.anchorMacro.setEnabled(false, false));

        // Auto Crystal, two blocks further along the wall so the anchor above is out of the way.
        cmd.accept("item replace entity @p hotbar.3 with minecraft:obsidian");
        cmd.accept("item replace entity @p hotbar.4 with minecraft:end_crystal");
        // Three blocks along the wall, so the ray no longer passes through the anchor placed above.
        cmd.accept("execute as @p run tp @s ~ ~ ~3");
        // Look down at the floor instead of across at the wall: the floor is always there, so the spot the
        // module should build on is unambiguous.
        ctx.runOnClient(c -> { c.player.setYaw(-90); c.player.setPitch(40); });
        ctx.waitTicks(5);
        ctx.runOnClient(c -> ModuleManager.autoCrystal.setEnabled(true, false));
        ctx.waitTicks(40);
        boolean crystal = ctx.computeOnClient(c -> {
            BlockPos p = c.player.getBlockPos();
            for (BlockPos q : BlockPos.iterate(p.add(-5, -4, -5), p.add(5, 3, 5))) {
                if (c.world.getBlockState(q).getBlock() == Blocks.OBSIDIAN) return true;
            }
            return !c.world.getEntitiesByClass(EndCrystalEntity.class, c.player.getBoundingBox().expand(8), e -> true).isEmpty();
        });
        check(crystal, "Auto Crystal placed obsidian or a crystal");
        ctx.runOnClient(c -> ModuleManager.autoCrystal.setEnabled(false, false));

        cmd.accept("gamemode creative @p");
        cmd.accept("effect clear @p");
        cmd.accept("execute as @p run tp @s " + me.getX() + " " + me.getY() + " " + me.getZ() + " 0 25");
        ctx.waitTicks(20);
        log("combat checks done, {} failed", failures);
        return failures;
    }
}
