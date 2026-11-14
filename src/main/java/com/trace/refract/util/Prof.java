package com.trace.refract.util;

import java.util.*;

/** Tiny always-on profiler: accumulated nanoseconds per named section, reported as average ms per frame. */
public final class Prof {
    private static final Map<String, long[]> SECTIONS = new HashMap<>();
    private static long frames, ticks;

    private Prof() {}

    public static void add(String name, long nanos) { SECTIONS.computeIfAbsent(name, k -> new long[1])[0] += nanos; }

    public static void frame() { frames++; }

    public static void tick() { ticks++; }

    public static void reset() { SECTIONS.clear(); frames = 0; ticks = 0; }

    /** The heaviest sections: "name 0.42ms/frame" (tick sections per tick, marked with t:). */
    public static String report(int top) {
        List<Map.Entry<String, long[]>> list = new ArrayList<>(SECTIONS.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder("frames " + frames + ", ticks " + ticks + " | ");
        for (int i = 0; i < Math.min(top, list.size()); i++) {
            var e = list.get(i);
            boolean t = e.getKey().startsWith("t:");
            double per = e.getValue()[0] / 1e6 / Math.max(1, t ? ticks : frames);
            sb.append(String.format(Locale.ROOT, "%s %.3fms%s  ", e.getKey(), per, t ? "/tick" : "/frame"));
        }
        return sb.toString();
    }
}
