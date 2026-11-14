package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Combat;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Keeps a totem in the off hand with the ordinary off-hand swap (click-slot button 40).
 *
 * <p><b>Hover Totem</b> swaps the exact stack under your cursor while the inventory is open, so the choice of
 * which totem moves is yours. With the inventory closed it falls back to the first totem it can find.
 *
 * <p>It stays out of the way on purpose: no swap while you are swinging, eating, blocking, on attack cooldown,
 * or while another module is mid-interaction. Overlapping a swap with another action is exactly what makes a
 * totem module look wrong to a server.
 */
public class AutoTotem extends Module {
    public final BoolSetting hoverTotem = add(new BoolSetting("Hover Totem", true));
    public final NumberSetting delay = add(new NumberSetting("Delay", 4, 0, 20, 1, "t"));
    public final NumberSetting health = add(new NumberSetting("Below Health", 0, 0, 20, 1, "hp"));
    public final BoolSetting doubleHand = add(new BoolSetting("Double Hand", false));
    public final BoolSetting openInventory = add(new BoolSetting("Auto Inv Open", false));
    public final NumberSetting closeDelay = add(new NumberSetting("Close Delay", 6, 1, 40, 1, "t"));

    private int timer, closeTimer;
    private boolean opened;

    public AutoTotem() {
        super("Auto Totem", "Swaps a totem into your off hand: hover one in your inventory and that is the one it moves.", Category.COMBAT);
    }

    @Override public void onDisable() {
        timer = 0;
        closeTimer = 0;
        if (opened && mc.player != null && mc.currentScreen instanceof InventoryScreen) mc.player.closeHandledScreen();
        opened = false;
    }

    @Override public String suffix() {
        if (mc.player == null) return null;
        return String.valueOf(Combat.count(Items.TOTEM_OF_UNDYING));
    }

    /** True while something else is happening that a swap must not be stacked on top of. */
    private boolean occupied() {
        if (mc.player == null) return true;
        if (Combat.internalAction()) return true;
        if (mc.player.isUsingItem() || mc.player.handSwinging) return true;
        if (mc.player.getAttackCooldownProgress(0f) < 0.75f) return true;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) return true;
        return false;
    }

    /** The totem under the cursor in an open inventory, or null. */
    private Slot hoveredTotem() {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return null;
        Slot s = ((com.trace.refract.mixin.HandledScreenAccessor) hs).refract$focusedSlot();
        if (s == null || s.getStack().isEmpty() || !s.getStack().isOf(Items.TOTEM_OF_UNDYING)) return null;
        if (s.inventory == mc.player.getInventory() && s.getIndex() == 40) return null; // already the off hand
        return s;
    }

    @Override public void onTick() {
        if (mc.player == null || mc.interactionManager == null) return;
        if (timer > 0) timer--;
        if (occupied()) return;

        boolean offhandOk = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        boolean low = health.get() <= 0 || mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get();

        if (!offhandOk && low && timer == 0) {
            if (hoverTotem.on() && hoveredTotem() != null) {
                Slot s = hoveredTotem();
                // One inventory action per tick; opening the screen or a second swap in the same tick is what the
                // server reports as multiple actions.
                if (!Combat.inventoryAllowed()) { timer = 1; return; }
                mc.interactionManager.clickSlot(((HandledScreen<?>) mc.currentScreen).getScreenHandler().syncId,
                        s.id, 40, SlotActionType.SWAP, mc.player);
                timer = delay.asInt();
            } else {
                int slot = Combat.find(Items.TOTEM_OF_UNDYING);
                if (slot >= 0) {
                    Combat.toOffhand(slot);
                    timer = delay.asInt();
                    if (openInventory.on() && mc.currentScreen == null && !Combat.holdingWeapon()) {
                        mc.setScreen(new InventoryScreen(mc.player));
                        opened = true;
                        closeTimer = 0;
                    }
                }
            }
        }

        // Double hand only takes the main hand when it is free: never yank a weapon out of your hand mid-fight.
        if (doubleHand.on() && timer == 0 && !Combat.holdingWeapon()
                && !mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            int slot = Combat.find(Items.TOTEM_OF_UNDYING);
            if (slot >= 0) {
                if (slot < 9) mc.player.getInventory().setSelectedSlot(slot);
                else Combat.holdItem(Items.TOTEM_OF_UNDYING);
                timer = delay.asInt();
            }
        }

        if (opened && mc.currentScreen instanceof InventoryScreen && ++closeTimer > closeDelay.asInt()) {
            mc.player.closeHandledScreen();
            opened = false;
            closeTimer = 0;
        }
    }
}
