package com.trace.refract.module.modules;

import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.util.Combat;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Keeps a totem in the off hand.
 *
 * <p>Two ways in, and neither one opens a container:
 * <ul>
 *   <li><b>Hover</b> — if you already have a container open and the cursor is on a totem, that totem is swapped.</li>
 *   <li><b>Silent</b> — otherwise the totem is moved into the off hand with a single swap click on your own
 *       inventory, which needs no screen at all.</li>
 * </ul>
 *
 * <p>The rewrite removes the old behaviour of opening the inventory itself: that is a real container interaction the
 * server sees, it cancelled sprinting, and doing it in the same tick as a swap is precisely what produced
 * MultiActions. Now at most one inventory action happens per tick, and only when the hand is free.
 */
public class AutoTotem extends Module {
    public final NumberSetting health = add(new NumberSetting("Health", 8, 1, 20, 1, "hp"));
    public final NumberSetting delay = add(new NumberSetting("Delay", 2, 0, 20, 1, "t"));
    public final BoolSetting hoverTotem = add(new BoolSetting("Hover Totem", true));
    public final BoolSetting doubleHand = add(new BoolSetting("Double Hand", false));

    private int timer;

    public AutoTotem() {
        super("Auto Totem", "Swaps a totem into your off hand. Uses the slot you hover if a container is open, otherwise a silent swap.", Category.COMBAT);
    }

    @Override public void onDisable() { timer = 0; }

    private boolean occupied() {
        if (mc.player == null) return true;
        if (!mc.player.getOffHandStack().isEmpty() && mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return false;
        if (mc.player.isUsingItem() || mc.player.handSwinging) return true;
        if (mc.player.getAttackCooldownProgress(0f) < 0.75f) return true;
        return false;
    }

    private Slot hoveredTotem() {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return null;
        Slot s = ((com.trace.refract.mixin.HandledScreenAccessor) hs).refract$focusedSlot();
        if (s == null || s.getStack().isEmpty() || !s.getStack().isOf(Items.TOTEM_OF_UNDYING)) return null;
        if (s.inventory == mc.player.getInventory() && s.getIndex() == 40) return null; // already the off hand
        return s;
    }

    @Override public void onTick() {
        if (mc.player == null || mc.interactionManager == null) return;
        if (timer > 0) { timer--; return; }

        boolean haveTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        boolean low = mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get();
        if (!haveTotem && low) {
            // Hovered slot first, but only when the player has a container open themselves.
            Slot hovered = hoverTotem.on() ? hoveredTotem() : null;
            if (hovered != null) {
                if (!Combat.inventoryAllowed()) { timer = 1; return; }
                mc.interactionManager.clickSlot(((HandledScreen<?>) mc.currentScreen).getScreenHandler().syncId,
                        hovered.id, 40, SlotActionType.SWAP, mc.player);
                timer = delay.asInt();
                return;
            }
            int slot = Combat.find(Items.TOTEM_OF_UNDYING);
            if (slot >= 0 && Combat.toOffhand(slot)) {
                timer = delay.asInt();
                return;
            }
            timer = 2; // nothing to swap yet; check again shortly
        }

        // Double hand is a last-resort shield: only at low health, only with a free hand, and never over a weapon.
        if (doubleHand.on() && low && mc.currentScreen == null && !Combat.holdingWeapon()
                && !mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            int slot = Combat.find(Items.TOTEM_OF_UNDYING);
            if (slot >= 0) {
                if (slot < 9) mc.player.getInventory().setSelectedSlot(slot);
                else Combat.holdItem(Items.TOTEM_OF_UNDYING);
                timer = delay.asInt();
            }
        }
    }
}
