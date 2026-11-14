package com.trace.refract.hud;

import com.google.gson.JsonObject;
import com.trace.refract.render.Gfx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where each HUD element sits and how big it is. Elements that were never moved keep their automatic spot
 * (the left column stacks, the module list hugs the top-right, toasts the bottom-right). A moved element is
 * pinned to the screen edges nearest to it, so it stays put when the window is resized or its width changes.
 */
public final class HudLayout {
    public static final class Elem {
        public final String id, label;
        public boolean placed, right, bottom;
        /** Distance from the anchored edges, as a fraction of the screen size. */
        public float fx, fy;
        public float scale = 1f;
        /** Last drawn bounds: local (before placement) and on screen. */
        public final float[] local = new float[4];
        public float x, y, w, h;
        public boolean drawn;

        Elem(String id, String label) { this.id = id; this.label = label; }
    }

    public static final Map<String, Elem> ELEMS = new LinkedHashMap<>();

    private static Elem add(String id, String label) {
        Elem e = new Elem(id, label);
        ELEMS.put(id, e);
        return e;
    }

    public static final Elem WATERMARK = add("watermark", "Watermark");
    public static final Elem COORDS = add("coords", "Coordinates");
    public static final Elem HUNTER = add("hunter", "Auto Hunter");
    public static final Elem FINDINGS = add("findings", "Findings");
    public static final Elem SOUNDS = add("sounds", "Sound Log");
    public static final Elem MODULES = add("modules", "Module List");
    public static final Elem TOASTS = add("toasts", "Notifications");

    private HudLayout() {}

    /**
     * Draws one element. Unmoved elements are scaled around their natural anchor ({@code ax, ay}); moved ones are
     * drawn so their bounds' top-left lands at the saved spot. Bounds are measured while drawing, for the editor.
     */
    public static void draw(Elem e, float scale, float ax, float ay, float sw, float sh, Runnable body) {
        float k = scale * e.scale;
        float tx, ty, ox, oy;
        if (e.placed && e.drawn) {
            float w = (e.local[2] - e.local[0]) * k, h = (e.local[3] - e.local[1]) * k;
            tx = e.right ? sw - e.fx * sw - w : e.fx * sw;
            ty = e.bottom ? sh - e.fy * sh - h : e.fy * sh;
            tx = Math.max(0, Math.min(sw - w, tx));
            ty = Math.max(0, Math.min(sh - h, ty));
            ox = e.local[0];
            oy = e.local[1];
        } else {
            tx = ax; ty = ay; ox = ax; oy = ay;
        }
        Gfx.pushPlace(ox, oy, tx, ty, k);
        Gfx.beginMeasure();
        long t0 = System.nanoTime();
        body.run();
        com.trace.refract.util.Prof.add("hud:el:" + e.id, System.nanoTime() - t0);
        float[] b = Gfx.endMeasure();
        Gfx.popPlace();
        if (b == null) { e.drawn = false; return; }
        System.arraycopy(b, 0, e.local, 0, 4);
        e.drawn = true;
        e.x = tx + (b[0] - ox) * k;
        e.y = ty + (b[1] - oy) * k;
        e.w = (b[2] - b[0]) * k;
        e.h = (b[3] - b[1]) * k;
    }

    /** Pins an element so its top-left is at (x, y) on screen, anchored to the nearest edges. */
    public static void moveTo(Elem e, float x, float y, float sw, float sh) {
        e.placed = true;
        e.right = x + e.w / 2 > sw / 2;
        e.bottom = y + e.h / 2 > sh / 2;
        e.fx = e.right ? (sw - x - e.w) / sw : x / sw;
        e.fy = e.bottom ? (sh - y - e.h) / sh : y / sh;
    }

    public static void reset(Elem e) { e.placed = false; e.scale = 1f; }

    public static JsonObject save() {
        JsonObject o = new JsonObject();
        for (Elem e : ELEMS.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("placed", e.placed);
            j.addProperty("right", e.right);
            j.addProperty("bottom", e.bottom);
            j.addProperty("fx", e.fx);
            j.addProperty("fy", e.fy);
            j.addProperty("scale", e.scale);
            o.add(e.id, j);
        }
        return o;
    }

    public static void load(JsonObject o) {
        for (Elem e : ELEMS.values()) {
            if (!o.has(e.id)) continue;
            JsonObject j = o.getAsJsonObject(e.id);
            e.placed = j.has("placed") && j.get("placed").getAsBoolean();
            e.right = j.has("right") && j.get("right").getAsBoolean();
            e.bottom = j.has("bottom") && j.get("bottom").getAsBoolean();
            if (j.has("fx")) e.fx = j.get("fx").getAsFloat();
            if (j.has("fy")) e.fy = j.get("fy").getAsFloat();
            if (j.has("scale")) e.scale = Math.max(0.5f, Math.min(2f, j.get("scale").getAsFloat()));
        }
    }
}
