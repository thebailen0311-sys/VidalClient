package com.trace.refract.hud;

import com.trace.refract.module.Module;
import com.trace.refract.module.ModuleManager;
import com.trace.refract.render.Font;
import com.trace.refract.render.Gfx;
import com.trace.refract.theme.Theme;
import com.trace.refract.util.Anim;
import com.trace.refract.util.Colors;
import com.trace.refract.util.Time;

import java.util.ArrayList;
import java.util.List;

/** Glass toasts that spring in from the right and stack above the hotbar. */
public final class Notifications {
    private static final List<Note> NOTES = new ArrayList<>();

    private static final class Note {
        final String title, body;
        final int color;
        final long start, duration;
        final Anim slide = new Anim(0f, 11f);
        final Anim y = new Anim(-1f, 12f);
        /** 0: colour bar, 1: green circled tick, 2: red circled cross. */
        int icon;

        Note(String title, String body, int color, long duration) {
            this.title = title; this.body = body; this.color = color; this.duration = duration; this.start = Time.ms();
        }

        private Note(Note o, long start, long duration) {
            this.title = o.title; this.body = o.body; this.color = o.color; this.start = start; this.duration = duration;
            this.icon = o.icon;
            this.slide.snap(o.slide.value);
            this.y.snap(o.y.value);
        }

        /** Same toast, already expired, so it slides out from where it is. */
        Note endedCopy(long now) { return new Note(this, now - 1, 0); }
    }

    private Notifications() {}

    public static void push(String title, String body, int color, long durationMs) {
        if (NOTES.size() > 6) NOTES.remove(0);
        if (!title.equals("Enabled") && !body.equals("Enabled") && !body.equals("Disabled")) com.trace.refract.util.Sfx.notifySoft();
        NOTES.add(new Note(title, body, color, durationMs));
    }

    /** Lets every toast with this title slide out now. */
    public static void dismiss(String title) {
        long now = Time.ms();
        for (int i = 0; i < NOTES.size(); i++) {
            Note n = NOTES.get(i);
            if (n.title.equals(title) && now - n.start < n.duration) NOTES.set(i, n.endedCopy(now));
        }
    }

    public static void toggle(Module m) {
        push(m.name, m.isEnabled() ? "Enabled" : "Disabled", m.isEnabled() ? 0xFF34D399 : 0xFFF2555A, 1600);
        NOTES.get(NOTES.size() - 1).icon = m.isEnabled() ? 1 : 2;
        com.trace.refract.util.Sfx.toggle(m.isEnabled());
    }

    public static void render(Theme t, float sw, float sh) {
        if (!ModuleManager.hud.notifications.on()) { NOTES.clear(); return; }
        long now = Time.ms();
        float dt = Time.dt();
        float baseY = sh - 54;
        int stack = 0;
        for (int i = NOTES.size() - 1; i >= 0; i--) {
            Note n = NOTES.get(i);
            boolean alive = now - n.start < n.duration;
            n.slide.to(alive ? 1f : 0f).update(dt);
            if (!alive && n.slide.value < 0.01f) { NOTES.remove(i); continue; }

            String body = Gfx.ellipsize(n.body, 230, Gfx.SMALL, Font.Weight.REGULAR);
            float w = Math.max(n.icon != 0 ? 118 : 130, Math.max(Gfx.width(n.title, Gfx.BODY, Font.Weight.BOLD), Gfx.width(body, Gfx.SMALL)) + (n.icon != 0 ? 44 : 40));
            float h = 32;
            float targetY = baseY - stack * (h + 6) - h;
            if (n.y.value < 0) n.y.snap(targetY);
            n.y.to(targetY).update(dt);
            stack++;

            float e = Anim.easeOutCubic(n.slide.value);
            float x = sw - 8 - w * e;
            float y = n.y.value;
            float prev = Gfx.alpha;
            Gfx.alpha = prev * Anim.clamp01(n.slide.value * 1.4f);
            Gfx.hudGlass(t, x, y, w, h, 10);
            float tx = x + 19;
            if (n.icon != 0) {
                // Icon pops in with a little overshoot as the toast arrives.
                float pop = Anim.easeOutBack(Anim.clamp01((now - n.start) / 260f));
                float r = 6.5f * Math.max(0.01f, pop);
                if (n.icon == 1) Gfx.tickCircle(x + 16, y + h / 2, r, n.color);
                else Gfx.crossCircle(x + 16, y + h / 2, r, n.color);
                tx = x + 29;
            } else {
                Gfx.round(x + 9, y + 8, 3, h - 16, 1.5f, n.color);
            }
            Gfx.bold(n.title, tx, y + 6, Gfx.BODY, t.text());
            Gfx.text(body, tx, y + 18, Gfx.SMALL, Font.Weight.SEMIBOLD, n.icon != 0 ? n.color : t.textDim());
            float life = Anim.clamp01((now - n.start) / (float) n.duration);
            float bw = (w - 20) * (1 - life);
            if (bw > 1.5f) Gfx.round(x + 10, y + h - 3.5f, bw, 1.5f, 0.75f, Colors.withAlpha(n.color, 0x90));
            Gfx.alpha = prev;
        }
    }
}
