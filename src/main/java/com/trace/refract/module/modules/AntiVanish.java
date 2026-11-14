package com.trace.refract.module.modules;

import com.trace.refract.Refract;
import com.trace.refract.hud.Notifications;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.*;

/**
 * Detects players who vanish. Vanish plugins hide a player by removing them from the tab list without the
 * usual "left the game" message, so a tab-list removal with no matching leave message is reported.
 * Players visible in the world but missing from the tab list are reported as well.
 */
public class AntiVanish extends Module {
    public final NumberSetting grace = add(new NumberSetting("Leave Grace", 3, 1, 10, 1, "s"));
    public final BoolSetting nearby = add(new BoolSetting("Detect Nearby", true));
    public final BoolSetting chat = add(new BoolSetting("Chat Alerts", true));

    private Map<UUID, String> lastTab = new HashMap<>();
    private final Map<String, Integer> leaveMessages = new HashMap<>();
    private final Map<String, Integer> joinMessages = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> reportedNearby = new HashSet<>();
    private int warmup = 100;
    private int tickCount;

    private record Pending(String name, int tick) {}

    public AntiVanish() { super("Anti-Vanish", "Alerts you when a player vanishes or hides nearby.", Category.MISC); }

    @Override public void onEnable() { reset(); }

    /** Clears state; also called when joining a server. */
    public void reset() {
        lastTab = snapshot();
        pending.clear(); vanished.clear(); reportedNearby.clear();
        leaveMessages.clear(); joinMessages.clear();
        warmup = 100;
    }

    @Override public String suffix() { return vanished.isEmpty() ? null : String.valueOf(vanished.size()); }

    private Map<UUID, String> snapshot() {
        Map<UUID, String> map = new HashMap<>();
        if (mc.getNetworkHandler() == null) return map;
        for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
            if (e.getProfile() != null && e.getProfile().name() != null) map.put(e.getProfile().id(), e.getProfile().name());
        }
        return map;
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    /** Hooked from ClientReceiveMessageEvents.GAME: remembers vanilla join/leave messages. */
    public void onGameMessage(Text text) {
        if (!(text.getContent() instanceof TranslatableTextContent tr)) return;
        Object[] args = tr.getArgs();
        if (args.length == 0) return;
        String name = args[0] instanceof Text t ? t.getString() : String.valueOf(args[0]);
        if (tr.getKey().equals("multiplayer.player.left")) leaveMessages.put(key(name), tickCount);
        else if (tr.getKey().startsWith("multiplayer.player.joined")) joinMessages.put(key(name), tickCount);
    }

    @Override public void onTick() {
        if (mc.getNetworkHandler() == null || mc.world == null) { lastTab.clear(); warmup = 100; return; }
        tickCount++;
        Map<UUID, String> now = snapshot();
        if (warmup > 0) { warmup--; lastTab = now; return; }

        int removedCount = 0;
        for (UUID id : lastTab.keySet()) if (!now.containsKey(id)) removedCount++;
        // A proxy server switch wipes most of the list at once: that is not a vanish.
        boolean mass = removedCount > 4 && removedCount > lastTab.size() / 2;

        if (!mass) {
            for (Map.Entry<UUID, String> e : lastTab.entrySet()) {
                if (!now.containsKey(e.getKey()) && !vanished.contains(e.getKey())) {
                    pending.put(e.getKey(), new Pending(e.getValue(), tickCount));
                }
            }
        }
        int graceTicks = (int) (grace.get() * 20);
        for (Map.Entry<UUID, String> e : now.entrySet()) {
            UUID id = e.getKey();
            pending.remove(id);
            if (!lastTab.containsKey(id) && vanished.remove(id)) {
                Integer joined = joinMessages.get(key(e.getValue()));
                boolean silent = joined == null || tickCount - joined > graceTicks;
                alert(e.getValue() + (silent ? " reappeared (unvanished)" : " rejoined"), 0xFF7CFF9A);
            }
        }

        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Pending p = e.getValue();
            if (tickCount - p.tick() < graceTicks) continue;
            it.remove();
            Integer left = leaveMessages.get(key(p.name()));
            boolean legit = left != null && Math.abs(left - p.tick()) <= graceTicks;
            if (!legit) {
                vanished.add(e.getKey());
                alert(p.name() + " vanished!", 0xFFFF5C8A);
            }
        }

        if (nearby.on() && mc.player != null) {
            for (PlayerEntity pl : mc.world.getPlayers()) {
                if (pl == mc.player) continue;
                UUID id = pl.getUuid();
                // Version 4 UUIDs are real accounts; NPC plugins use other versions and are skipped.
                if (id.version() != 4 || now.containsKey(id) || !reportedNearby.add(id)) continue;
                alert(pl.getName().getString() + " is near you but hidden from tab (" + Math.round(pl.distanceTo(mc.player)) + "m)", 0xFFFFB347);
            }
        }
        lastTab = now;
        if (tickCount % 1200 == 0) { leaveMessages.clear(); joinMessages.clear(); }
    }

    private void alert(String msg, int color) {
        Notifications.push("Anti-Vanish", msg, color, 5000);
        if (chat.on()) Refract.chat("§d" + msg);
    }
}
