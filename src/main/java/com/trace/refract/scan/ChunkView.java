package com.trace.refract.scan;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * A chunk prepared for scanning on another thread. The chunk's own sections are copied on the client thread
 * (a fast array clone), so the scan reads a consistent snapshot. Blocks just across the four chunk borders,
 * needed only for edge checks, are read from the live neighbour chunks; block-state storage is swapped
 * atomically, so such a read sees either the old or the new state and nothing worse.
 */
final class ChunkView {
    /** Returned for positions outside the snapshot (unloaded neighbours, diagonals, outside the world). */
    static final BlockState UNKNOWN = Blocks.VOID_AIR.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    final ChunkPos pos;
    final int baseX, baseZ, bottomY;
    final ChunkSection[] sections;
    /** Median terrain height of this chunk and its four neighbours (from the server heightmaps). */
    final int regionalTop;
    /** Neighbours: index 0 = -X, 1 = +X, 2 = -Z, 3 = +Z (may be null). */
    private final WorldChunk[] neighbours;

    private ChunkView(ChunkPos pos, int bottomY, ChunkSection[] sections, WorldChunk[] neighbours, int regionalTop) {
        this.regionalTop = regionalTop;
        this.pos = pos;
        this.baseX = pos.getStartX();
        this.baseZ = pos.getStartZ();
        this.bottomY = bottomY;
        this.sections = sections;
        this.neighbours = neighbours;
    }

    /** Client thread only. */
    static ChunkView snapshot(ClientWorld w, WorldChunk chunk) {
        ChunkSection[] live = chunk.getSectionArray();
        ChunkSection[] copy = new ChunkSection[live.length];
        for (int i = 0; i < live.length; i++) {
            ChunkSection s = live[i];
            copy[i] = s == null || s.isEmpty() ? null : s.copy();
        }
        ChunkPos p = chunk.getPos();
        WorldChunk[] n = new WorldChunk[4];
        int[][] off = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            int cx = p.x + off[i][0], cz = p.z + off[i][1];
            if (w.getChunkManager().isChunkLoaded(cx, cz)) n[i] = w.getChunk(cx, cz);
        }
        // Regional terrain height: a griefer's crater lowers some columns, but not the median of five chunks.
        int[] heights = new int[256 * 5];
        int count = 0;
        WorldChunk[] all = {chunk, n[0], n[1], n[2], n[3]};
        for (WorldChunk c : all) {
            if (c == null) continue;
            var hm = c.getHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) heights[count++] = hm.get(x, z) - 1;
        }
        java.util.Arrays.sort(heights, 0, count);
        int regional = count == 0 ? chunk.getBottomY() : heights[count / 2];
        return new ChunkView(p, chunk.getBottomY(), copy, n, regional);
    }

    BlockState get(int x, int y, int z) {
        int si = (y - bottomY) >> 4;
        if (y < bottomY || si >= sections.length) return UNKNOWN;
        int dx = (x >> 4) - pos.x, dz = (z >> 4) - pos.z;
        if (dx == 0 && dz == 0) {
            ChunkSection s = sections[si];
            return s == null ? AIR : s.getBlockState(x & 15, y & 15, z & 15);
        }
        int idx = dx == -1 && dz == 0 ? 0 : dx == 1 && dz == 0 ? 1 : dx == 0 && dz == -1 ? 2 : dx == 0 && dz == 1 ? 3 : -1;
        if (idx < 0 || neighbours[idx] == null) return UNKNOWN;
        try {
            ChunkSection[] arr = neighbours[idx].getSectionArray();
            if (si >= arr.length) return UNKNOWN;
            ChunkSection s = arr[si];
            return s == null || s.isEmpty() ? AIR : s.getBlockState(x & 15, y & 15, z & 15);
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /**
     * Highest solid (opaque full cube) block per column, indexed z * 16 + x. Water is ignored, so under an ocean
     * the sea floor is the surface. Columns with nothing solid report the bottom of the world, so nothing in
     * them counts as underground.
     */
    int[] surface() {
        int[] out = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int found = bottomY;
            outer:
            for (int si = sections.length - 1; si >= 0; si--) {
                ChunkSection s = sections[si];
                if (s == null) continue;
                for (int y = 15; y >= 0; y--) {
                    BlockState st = s.getBlockState(x, y, z);
                    if (st.isOpaqueFullCube()) { found = bottomY + si * 16 + y; break outer; }
                }
            }
            out[z * 16 + x] = found;
        }
        return out;
    }
}
