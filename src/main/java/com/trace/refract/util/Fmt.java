package com.trace.refract.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Short, consistent text for coordinates and distances. */
public final class Fmt {
    private Fmt() {}

    public static String xyz(BlockPos p) { return p.getX() + "  " + p.getY() + "  " + p.getZ(); }

    public static String xyz(Vec3d v) { return xyz(BlockPos.ofFloored(v)); }

    public static String dist(double meters) {
        if (meters >= 10_000) return String.format(java.util.Locale.ROOT, "%.1fkm", meters / 1000);
        return Math.round(meters) + "m";
    }
}
