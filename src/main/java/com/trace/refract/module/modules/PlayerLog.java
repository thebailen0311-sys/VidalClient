package com.trace.refract.module.modules;

import com.trace.refract.Refract;
import com.trace.refract.hud.Notifications;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.util.FindingsLog;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Sfx;
import net.minecraft.client.network.AbstractClientPlayerEntity;

import java.util.*;

/**
 * Tells you when another player comes into or leaves your render distance, with their exact coordinates.
 * While hunting, a player showing up near a spot usually means it is their base.
 */
public class PlayerLog extends Module {
    public final BoolSetting leaves = add(new BoolSetting("Announce Leaving", true));
    public final BoolSetting chat = add(new BoolSetting("Chat", true));
    public final BoolSetting sound = add(new BoolSetting("Sound", true));

    private final Map<UUID, String> present = new HashMap<>();
    private final Map<UUID, net.minecraft.util.math.BlockPos> lastPos = new HashMap<>();
    private boolean primed;

    public PlayerLog() { super("Player Log", "Alerts with exact coordinates when a player enters or leaves your render distance.", Category.HUNTING); }

    @Override public void onEnable() { present.clear(); lastPos.clear(); primed = false; }

    @Override public String suffix() { return present.isEmpty() ? null : String.valueOf(present.size()); }

    @Override public void onTick() {
        if (mc.world == null || mc.player == null) return;
        Set<UUID> now = new HashSet<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            UUID id = p.getUuid();
            now.add(id);
            lastPos.put(id, p.getBlockPos());
            if (present.putIfAbsent(id, p.getName().getString()) == null && primed) {
                String msg = p.getName().getString() + " entered at " + Fmt.xyz(p.getBlockPos()) + " (" + Fmt.dist(mc.player.distanceTo(p)) + ")";
                Notifications.push("Player Log", msg, 0xFFFF9A3D, 5000);
                if (chat.on()) Refract.chat("§f" + msg);
                if (sound.on()) Sfx.alert();
                FindingsLog.write("player", Fmt.xyz(p.getBlockPos()), p.getName().getString() + " entered");
            }
        }
        Iterator<Map.Entry<UUID, String>> it = present.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now.contains(e.getKey())) continue;
            it.remove();
            var at = lastPos.remove(e.getKey());
            if (leaves.on() && at != null) {
                String msg = e.getValue() + " left near " + Fmt.xyz(at);
                Notifications.push("Player Log", msg, 0xFF8A93A6, 3500);
                if (chat.on()) Refract.chat("§7" + msg);
            }
        }
        primed = true; // players already around when you join or enable are not announced
    }
}
