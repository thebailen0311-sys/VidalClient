package com.trace.refract.util;

/** Global frame clock used by every animation. */
public final class Time {
    private static long last = System.nanoTime();
    private static float dt = 0.016f;
    private static final long START = System.nanoTime();

    private Time() {}

    /** Call once per rendered frame. */
    private static long frameId;

    /** Increases by one every rendered frame. */
    public static long frameId() { return frameId; }

    public static void frame() {
        frameId++;
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - last) / 1_000_000_000f);
        last = now;
    }

    public static float dt() { return dt; }

    public static float seconds() { return (System.nanoTime() - START) / 1_000_000_000f; }

    public static long ms() { return System.currentTimeMillis(); }
}
