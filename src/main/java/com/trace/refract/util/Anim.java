package com.trace.refract.util;

/** Frame-rate independent spring-ish smoothing plus easing curves. */
public final class Anim {
    public float value, target;
    private final float speed;

    public Anim(float start, float speed) { this.value = start; this.target = start; this.speed = speed; }

    public Anim to(float t) { this.target = t; return this; }

    public float update(float dt) {
        value += (target - value) * (1f - (float) Math.exp(-speed * dt));
        if (Math.abs(target - value) < 0.0005f) value = target;
        return value;
    }

    public void snap(float v) { value = v; target = v; }

    public static float clamp01(float t) { return Math.max(0f, Math.min(1f, t)); }

    public static float easeOutCubic(float t) { t = clamp01(t); float f = 1 - t; return 1 - f * f * f; }

    public static float easeInOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    public static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    public static float easeOutElastic(float t) {
        t = clamp01(t);
        if (t == 0 || t == 1) return t;
        return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
    }
}
