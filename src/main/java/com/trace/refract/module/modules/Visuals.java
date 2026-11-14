package com.trace.refract.module.modules;

import com.trace.refract.Refract;
import com.trace.refract.hud.Notifications;
import com.trace.refract.hud.WorldLabels;
import com.trace.refract.module.Category;
import com.trace.refract.module.Module;
import com.trace.refract.render.Esp;
import com.trace.refract.setting.BoolSetting;
import com.trace.refract.setting.NumberSetting;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Fmt;
import com.trace.refract.util.Sfx;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/** Small visual and awareness modules, grouped in one file. */
public final class Visuals {
    private Visuals() {}

    /** Rain, thunder and clear-sky changes, plus every lightning strike with its coordinates. */
    public static class WeatherNotifier extends Module {
        public final BoolSetting lightning = add(new BoolSetting("Lightning Strikes", true));
        public final BoolSetting chat = add(new BoolSetting("Chat", false));
        private int state = -1; // 0 clear, 1 rain, 2 thunder
        private final Set<Integer> strikes = new HashSet<>();

        public WeatherNotifier() { super("Weather Notifier", "Toasts when rain or thunder starts or stops, and where lightning hits.", Category.MISC); }

        @Override public void onEnable() { state = -1; }

        @Override public String suffix() { return state == 2 ? "Thunder" : state == 1 ? "Rain" : state == 0 ? "Clear" : null; }

        @Override public void onTick() {
            if (mc.world == null || mc.player == null) return;
            int now = mc.world.isThundering() ? 2 : mc.world.isRaining() ? 1 : 0;
            if (state >= 0 && now != state) {
                String[] text = {"Skies cleared", "Rain started", "Thunderstorm started"};
                int[] col = {0xFFFFD84D, 0xFF4DA3FF, 0xFFB06BFF};
                Notifications.push("Weather", text[now] + (now == 2 ? " (mobs spawn on the surface)" : ""), col[now], 5000);
                if (chat.on()) Refract.chat("§f" + text[now]);
                Sfx.notifySoft();
            }
            state = now;
            if (!lightning.on()) return;
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof LightningEntity) || !strikes.add(e.getId())) continue;
                Notifications.push("Lightning", "Strike at " + Fmt.xyz(e.getBlockPos()) + "  ·  " + Fmt.dist(mc.player.distanceTo(e)), 0xFFE0E7FF, 4000);
            }
            if (strikes.size() > 256) strikes.clear();
        }
    }

    /** Your recent path as a fading line, so you never search the same ground twice. */
    public static class Breadcrumbs extends Module {
        public final NumberSetting length = add(new NumberSetting("Length", 600, 50, 3000, 50, " pts"));
        private final ArrayDeque<Vec3d> trail = new ArrayDeque<>();

        public Breadcrumbs() { super("Breadcrumbs", "Draws the path you travelled so you do not search the same area twice.", Category.RENDER); }

        @Override public void onEnable() { trail.clear(); }

        @Override public void onTick() {
            if (mc.player == null) return;
            Vec3d p = mc.player.getEntityPos().add(0, 0.1, 0);
            if (trail.isEmpty() || trail.peekLast().squaredDistanceTo(p) > 4) trail.addLast(p);
            while (trail.size() > length.asInt()) trail.pollFirst();
        }

        @Override public void onWorldRender(float tickDelta) {
            Vec3d prev = null;
            int i = 0, n = trail.size();
            int col = com.trace.refract.theme.Themes.current().accent();
            for (Vec3d p : trail) {
                if (prev != null) Esp.line(prev, p, Colors.withAlpha(col, 40 + 200 * i / Math.max(1, n)), 1.5f, false);
                prev = p;
                i++;
            }
        }
    }

    /** Dropped items and XP orbs: fresh drops mean someone was just here (or died here). */
    public static class ItemEsp extends Module {
        public final BoolSetting xp = add(new BoolSetting("XP Orbs", true));
        public final BoolSetting labels = add(new BoolSetting("Labels", true));
        public final NumberSetting range = add(new NumberSetting("Range", 128, 16, 512, 16, "m"));
        private final List<Entity> found = new ArrayList<>();

        public ItemEsp() { super("Item ESP", "Dropped items and XP orbs with names and counts: signs someone was just here.", Category.RENDER); }

        @Override public String suffix() { return found.isEmpty() ? null : String.valueOf(found.size()); }

        @Override public void onTick() {
            found.clear();
            if (mc.world == null || mc.player == null) return;
            double r2 = range.get() * range.get();
            for (Entity e : mc.world.getEntities()) {
                if ((e instanceof ItemEntity || (xp.on() && e instanceof ExperienceOrbEntity)) && e.squaredDistanceTo(mc.player) <= r2) found.add(e);
            }
        }

        @Override public void onWorldRender(float tickDelta) {
            for (Entity e : found) {
                int col = e instanceof ItemEntity ? 0xFFFFD84D : 0xFF7CFF9A;
                var b = Esp.box(e).expand(0.08);
                Esp.pretty(b, col, true);
            }
        }

        @Override public void onHud(Theme t, float sw, float sh) {
            if (!labels.on()) return;
            int n = 0;
            for (Entity e : found) {
                if (!(e instanceof ItemEntity it) || n++ >= 20) continue;
                var s = it.getStack();
                WorldLabels.draw(t, Esp.pos(e).add(0, 0.6, 0), s.getCount() + " × " + s.getName().getString(), null, 0xFFFFD84D, 1f, sw, sh, false);
            }
        }
    }

    /** Full brightness via a client-side night vision effect (nothing is sent to the server). */
    public static class Fullbright extends Module {
        public Fullbright() { super("Fullbright", "See in dark caves and bases without torches.", Category.RENDER); }

        @Override public void onTick() {
            if (mc.player == null) return;
            var cur = mc.player.getStatusEffect(StatusEffects.NIGHT_VISION);
            if (cur == null || cur.getDuration() < 400) {
                mc.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 1200, 0, false, false, false));
            }
        }

        @Override public void onDisable() {
            if (mc.player == null) return;
            var cur = mc.player.getStatusEffect(StatusEffects.NIGHT_VISION);
            if (cur != null && !cur.shouldShowIcon()) mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION); // only ours, never a real potion
        }
    }
}
