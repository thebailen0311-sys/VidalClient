package com.trace.refract.module.modules;

import com.trace.refract.hud.Notifications;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.setting.NumberSetting;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

/**
 * Drops the connection and joins the same server again after a short delay.
 *
 * <p>Useful when the tab list, scoreboard or chunk cache goes stale, when a server's queue forgets you, or after a
 * plugin leaves your client in a half-broken state. It is a plain disconnect plus a normal join: there is no packet
 * trick, so a queue or a cooldown applies exactly as it would if you relogged by hand.
 */
public class Relog extends Module {
    public final NumberSetting delay = add(new NumberSetting("Delay", 3, 0, 20, 0.5, "s"));

    private ServerInfo target;
    private ServerAddress address;
    private int ticks;
    private boolean armed;

    public Relog() {
        super("Relog", "Disconnects and rejoins the current server after a delay.", Category.MISC);
    }

    @Override public void onEnable() {
        ServerInfo info = mc.getCurrentServerEntry();
        if (mc.getNetworkHandler() == null || info == null) {
            Notifications.push("Relog", "Join a multiplayer server first", 0xFFFF9A3D, 2400);
            setEnabled(false, false);
            return;
        }
        try {
            address = ServerAddress.parse(info.address);
        } catch (Exception ex) {
            Notifications.push("Relog", "Cannot resolve " + info.address, 0xFFF2555A, 2600);
            setEnabled(false, false);
            return;
        }
        // A copy, because the live entry is dropped together with the connection.
        target = new ServerInfo(info.name, info.address, ServerInfo.ServerType.OTHER);
        ticks = 0;
        armed = true;
        mc.getNetworkHandler().getConnection().disconnect(Text.literal("Relog"));
    }

    @Override public void onTick() {
        if (!armed) return;
        if (++ticks < Math.round(delay.get() * 20)) return;
        armed = false;
        setEnabled(false, false);
        ConnectScreen.connect(new TitleScreen(), mc, address, target, false, null);
    }

    @Override public void onDisable() {
        armed = false;
    }
}
