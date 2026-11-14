package com.trace.refract.scan;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * One straight player-dug corridor found by the scanner.
 *
 * <p>What it is:
 * <ul>
 *   <li>{@link Kind#PICKAXE} — a plain 1x2 / 2x2 strip-mine shaft, the usual way to a base.</li>
 *   <li>{@link Kind#AMETHYST_DRILL} — a shaft that cuts into an amethyst geode (amethyst, calcite, smooth
 *       basalt around it): somebody drilled the geode, so their stash is usually at the far end.</li>
 *   <li>{@link Kind#RAILED} — a shaft with rails on the floor, which only players place.</li>
 * </ul>
 *
 * <p>Age: a client cannot read when a block was placed. What it <em>can</em> see is edits that happen while it is
 * watching, so a corridor is {@link Age#FRESH} when this session saw any of its blocks turn into air, or when its
 * chunk arrived with flowing fluids very recently (block updates still settling), and {@link Age#OLD} otherwise.
 * Fresh corridors are excluded from base leads by default: they are you or someone nearby still digging.
 */
public final class Tunnel {
    public enum Kind { PICKAXE, AMETHYST_DRILL, RAILED }

    public enum Age { OLD, FRESH }

    public final Box bounds;
    /** 0 = runs along X (east-west), 1 = along Z (north-south). */
    public final int axis;
    public final int length;
    public final int floorY;
    public final Kind kind;
    public Age age = Age.OLD;

    public Tunnel(Box bounds, int axis, int length, int floorY, Kind kind) {
        this.bounds = bounds;
        this.axis = axis;
        this.length = length;
        this.floorY = floorY;
        this.kind = kind;
    }

    public BlockPos center() {
        return BlockPos.ofFloored((bounds.minX + bounds.maxX) / 2, floorY, (bounds.minZ + bounds.maxZ) / 2);
    }

    /** The two horizontal ends of the corridor, low first. */
    public BlockPos endA() {
        return axis == 0 ? new BlockPos((int) Math.floor(bounds.minX), floorY, center().getZ())
                : new BlockPos(center().getX(), floorY, (int) Math.floor(bounds.minZ));
    }

    public BlockPos endB() {
        return axis == 0 ? new BlockPos((int) Math.ceil(bounds.maxX) - 1, floorY, center().getZ())
                : new BlockPos(center().getX(), floorY, (int) Math.ceil(bounds.maxZ) - 1);
    }

    /** True when the point is inside the corridor or within {@code pad} blocks of it. */
    public boolean near(double x, double y, double z, double pad) {
        return bounds.expand(pad).contains(x, y, z);
    }

    public String kindLabel() {
        return switch (kind) {
            case AMETHYST_DRILL -> "amethyst drill";
            case RAILED -> "railed shaft";
            default -> "pickaxe tunnel";
        };
    }

    /** "E–W" or "N–S": which way the corridor runs. */
    public String axisText() { return axis == 0 ? "E–W" : "N–S"; }

    public String describe() {
        return kindLabel() + ", " + length + "m " + axisText() + (age == Age.FRESH ? " (new)" : "");
    }
}
