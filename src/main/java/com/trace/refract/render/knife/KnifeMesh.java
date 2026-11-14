package com.trace.refract.render.knife;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural 3D knife meshes, built once in "pixel" units (a 16x16 item square, handle bottom-left, tip top-right,
 * centred on z = 8, the same layout a sword sits in). Blades are swept wedge sections (flat, bevel to a sharp
 * edge, spine); grips, rings, guards and pins are swept tubes. Every quad stores its flat normal, a material and
 * a 0..1 position along the blade, so the renderer can colour it with any wrap.
 */
public final class KnifeMesh {
    public static final int BLADE = 0, EDGE = 1, SPINE = 2, HANDLE = 3, ACCENT = 4, STEEL = 5;
    /** Quad layout: 12 position floats, face normal (3), material, u, group, then 12 per-vertex normal floats. */
    public final List<float[]> quads = new ArrayList<>();
    public float[] pivot = {8, 8}, latchPin;
    public float inspectSeconds = 1.3f;

    private static final float R2 = (float) Math.sqrt(0.5);

    /** Point along the knife axis: {@code s} pixels along the diagonal, {@code o} pixels to the spine side. */
    static float[] P(float s, float o) { return new float[]{1.5f + (s - o) * R2, 1.5f + (s + o) * R2}; }

    private void quad(float[] a, float[] b, float[] c, float[] d, float[] inside, int mat, float u, int group) { quad(a, b, c, d, null, inside, mat, u, group); }

    private void quad(float[] a, float[] b, float[] c, float[] d, float[][] vn, float[] inside, int mat, float u, int group) {
        float[][] v = {a, b, c, d};
        float nx = 0, ny = 0, nz = 0, cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) { // Newell's method: robust when two corners coincide (tips)
            float[] p = v[i], q = v[(i + 1) % 4];
            nx += (p[1] - q[1]) * (p[2] + q[2]);
            ny += (p[2] - q[2]) * (p[0] + q[0]);
            nz += (p[0] - q[0]) * (p[1] + q[1]);
            cx += p[0] / 4; cy += p[1] / 4; cz += p[2] / 4;
        }
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-7f) return;
        nx /= len; ny /= len; nz /= len;
        if (nx * (cx - inside[0]) + ny * (cy - inside[1]) + nz * (cz - inside[2]) < 0) { // face outward
            v = new float[][]{a, d, c, b};
            if (vn != null) vn = new float[][]{vn[0], vn[3], vn[2], vn[1]};
            nx = -nx; ny = -ny; nz = -nz;
        }
        float[] q = new float[30];
        for (int i = 0; i < 4; i++) System.arraycopy(v[i], 0, q, i * 3, 3);
        q[12] = nx; q[13] = ny; q[14] = nz; q[15] = mat; q[16] = u; q[17] = group;
        for (int i = 0; i < 4; i++) {
            float[] n = vn != null ? vn[i] : new float[]{nx, ny, nz};
            q[18 + i * 3] = n[0]; q[19 + i * 3] = n[1]; q[20 + i * 3] = n[2];
        }
        quads.add(q);
    }

    /** Blade section: spine point, edge point, thickness at the spine, position along the blade. */
    static float[] st(float s, float os, float oe, float thick, float u) {
        float[] a = P(s, os), b = P(s, oe);
        return new float[]{a[0], a[1], b[0], b[1], thick, u};
    }

    void blade(List<float[]> st, int group) {
        int n = st.size();
        float[][][] ring = new float[n][][];
        float[][] ctr = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] s = st.get(i);
            float t = s[4] / 2, gx = s[0] + (s[2] - s[0]) * 0.42f, gy = s[1] + (s[3] - s[1]) * 0.42f;
            ring[i] = new float[][]{{s[0], s[1], 8 + t}, {gx, gy, 8 + t * 0.85f}, {s[2], s[3], 8}, {gx, gy, 8 - t * 0.85f}, {s[0], s[1], 8 - t}};
            ctr[i] = new float[]{(s[0] + s[2]) / 2, (s[1] + s[3]) / 2, 8};
        }
        int[] mats = {BLADE, EDGE, EDGE, BLADE, SPINE};
        for (int i = 0; i < n - 1; i++) {
            float[] in = mid(ctr[i], ctr[i + 1]);
            float u = (st.get(i)[5] + st.get(i + 1)[5]) / 2;
            for (int k = 0; k < 5; k++)
                quad(ring[i][k], ring[i][(k + 1) % 5], ring[i + 1][(k + 1) % 5], ring[i + 1][k], in, mats[k], u, group);
        }
        float[] in0 = toward(ctr[0], ctr[1], 0.25f);
        quad(ring[0][0], ring[0][1], ring[0][2], ring[0][3], in0, STEEL, 0, group);
        quad(ring[0][0], ring[0][3], ring[0][4], ring[0][4], in0, STEEL, 0, group);
    }

    /** Tube along centres {x, y, in-plane half width, depth half width}. */
    void tube(List<float[]> c, int seg, int mat, int group, boolean loop) {
        int n = c.size();
        float[][][] r = new float[n][seg][];
        float[][][] rn = new float[n][seg][];
        float[][] ctr = new float[n][];
        float[][] tan = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] a = c.get(loop ? (i - 1 + n) % n : Math.max(0, i - 1)), b = c.get(loop ? (i + 1) % n : Math.min(n - 1, i + 1));
            float tx = b[0] - a[0], ty = b[1] - a[1], tl = (float) Math.sqrt(tx * tx + ty * ty);
            tx /= tl; ty /= tl;
            float[] p = c.get(i);
            ctr[i] = new float[]{p[0], p[1], 8};
            tan[i] = new float[]{tx, ty, 0};
            for (int k = 0; k < seg; k++) {
                double ang = Math.PI * 2 * k / seg + Math.PI / seg;
                float cs = (float) Math.cos(ang) * p[2], sn = (float) Math.sin(ang) * p[3];
                r[i][k] = new float[]{p[0] - ty * cs, p[1] + tx * cs, 8 + sn};
                // Smooth normal of the ellipse at this angle, so grips and rings shade round, not faceted.
                float ex = (float) Math.cos(ang) / Math.max(0.01f, p[2]), ez = (float) Math.sin(ang) / Math.max(0.01f, p[3]);
                float el = (float) Math.sqrt(ex * ex + ez * ez);
                rn[i][k] = new float[]{-ty * ex / el, tx * ex / el, ez / el};
            }
        }
        for (int i = 0; i < (loop ? n : n - 1); i++) {
            int j = (i + 1) % n;
            float[] in = mid(ctr[i], ctr[j]);
            for (int k = 0; k < seg; k++)
                quad(r[i][k], r[i][(k + 1) % seg], r[j][(k + 1) % seg], r[j][k],
                        new float[][]{rn[i][k], rn[i][(k + 1) % seg], rn[j][(k + 1) % seg], rn[j][k]}, in, mat, 0, group);
        }
        if (loop) return;
        for (int e = 0; e < 2; e++) {
            int i = e == 0 ? 0 : n - 1;
            float sgn = e == 0 ? 1 : -1;
            float[] in = {ctr[i][0] + tan[i][0] * 0.2f * sgn, ctr[i][1] + tan[i][1] * 0.2f * sgn, 8};
            for (int k = 0; k < seg; k++) quad(ctr[i], r[i][k], r[i][(k + 1) % seg], r[i][(k + 1) % seg], in, mat, 0, group);
        }
    }

    /** Straight tube along the knife axis from s0 to s1 at offset o. */
    void rod(float s0, float s1, float o, float w, float d, int seg, int mat, int group) {
        List<float[]> c = new ArrayList<>();
        int n = Math.max(2, Math.round((s1 - s0) / 0.6f) + 1);
        for (int i = 0; i < n; i++) {
            float[] p = P(s0 + (s1 - s0) * i / (n - 1), o);
            c.add(new float[]{p[0], p[1], w, d});
        }
        tube(c, seg, mat, group, false);
    }

    /** Straight tube across the knife (along the spine-edge direction) at s, from o0 to o1: guards. */
    void cross(float s, float o0, float o1, float w, float d, int mat) {
        float[] a = P(s, o0), b = P(s, o1);
        tube(List.of(new float[]{a[0], a[1], w, d}, new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, w, d}, new float[]{b[0], b[1], w, d}), 8, mat, 0, false);
    }

    private static float[] mid(float[] a, float[] b) { return new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, 8}; }

    private static float[] toward(float[] a, float[] b, float t) { return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, 8}; }

    // ------------------------------------------------------------------ the knives

    public static KnifeMesh create(String kind) {
        return switch (kind) {
            case "karambit" -> karambit();
            case "butterfly" -> butterfly();
            case "bowie" -> bowie();
            default -> tactical();
        };
    }

    /** Grip with gentle finger grooves along s0..s1. */
    private void grip(float s0, float s1, float w, float d, float groove, int group) {
        List<float[]> c = new ArrayList<>();
        for (float s = s0; s <= s1 + 1e-3f; s += 0.45f) {
            float f = (s - s0) / (s1 - s0);
            float k = 1 + 0.08f * (float) Math.sin(f * Math.PI) - groove * (float) Math.max(0, Math.cos(f * Math.PI * 7));
            float[] p = P(s, 0);
            c.add(new float[]{p[0], p[1], w * k, d * k});
        }
        tube(c, 14, HANDLE, group, false);
    }

    static KnifeMesh tactical() {
        KnifeMesh m = new KnifeMesh();
        m.rod(0.0f, 0.7f, 0, 1.3f, 0.95f, 14, STEEL, 0);
        m.grip(0.6f, 8.5f, 1.15f, 0.85f, 0.04f, 0);
        m.rod(3.0f, 3.35f, 0, 1.24f, 0.92f, 14, ACCENT, 0);
        m.rod(6.1f, 6.45f, 0, 1.24f, 0.92f, 14, ACCENT, 0);
        m.cross(8.9f, -2.3f, 2.1f, 0.42f, 0.8f, STEEL);
        float a = 9.2f, b = 21.3f;
        m.blade(List.of(st(9.2f, 1.0f, -1.25f, 0.6f, 0), st(12f, 1.05f, -1.4f, 0.58f, (12 - a) / (b - a)),
                st(15f, 1.0f, -1.42f, 0.55f, (15 - a) / (b - a)), st(17.5f, 0.85f, -1.3f, 0.5f, (17.5f - a) / (b - a)),
                st(19.3f, 0.35f, -1.0f, 0.38f, (19.3f - a) / (b - a)), st(20.6f, -0.15f, -0.6f, 0.18f, (20.6f - a) / (b - a)),
                st(21.3f, -0.35f, -0.4f, 0.02f, 1)), 0);
        m.pivot = P(10f, 0);
        m.inspectSeconds = 1.2f;
        return m;
    }

    static KnifeMesh bowie() {
        KnifeMesh m = new KnifeMesh();
        m.rod(0.0f, 0.9f, 0, 1.45f, 1.05f, 14, STEEL, 0);
        m.grip(0.8f, 8.0f, 1.25f, 0.9f, 0.09f, 0);
        m.rod(4.2f, 4.5f, 0, 1.3f, 0.97f, 14, ACCENT, 0);
        m.cross(8.5f, -2.9f, 2.7f, 0.5f, 0.9f, STEEL);
        float a = 8.9f, b = 21.3f;
        m.blade(List.of(st(8.9f, 1.3f, -1.6f, 0.7f, 0), st(12f, 1.4f, -1.9f, 0.68f, (12 - a) / (b - a)),
                st(15f, 1.35f, -2.05f, 0.64f, (15 - a) / (b - a)), st(17.5f, 1.2f, -1.95f, 0.58f, (17.5f - a) / (b - a)),
                st(19f, 0.7f, -1.7f, 0.5f, (19 - a) / (b - a)), st(20.2f, 0.0f, -1.25f, 0.32f, (20.2f - a) / (b - a)),
                st(20.9f, -0.5f, -0.8f, 0.12f, (20.9f - a) / (b - a)), st(21.3f, -0.68f, -0.7f, 0.02f, 1)), 0);
        m.pivot = P(10f, 0);
        m.inspectSeconds = 1.25f;
        return m;
    }

    static KnifeMesh karambit() {
        KnifeMesh m = new KnifeMesh();
        float[] rc = P(2.6f, 0);
        List<float[]> ring = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            double a = Math.PI * 2 * i / 28;
            ring.add(new float[]{rc[0] + (float) Math.cos(a) * 1.9f, rc[1] + (float) Math.sin(a) * 1.9f, 0.5f, 0.55f});
        }
        m.tube(ring, 10, STEEL, 0, true);
        m.grip(4.3f, 9.4f, 1.0f, 0.78f, 0.1f, 0);
        m.rod(5.2f, 5.45f, 0, 1.06f, 0.84f, 12, ACCENT, 0);
        m.rod(8.6f, 8.85f, 0, 1.04f, 0.82f, 12, ACCENT, 0);
        m.rod(9.3f, 9.9f, 0, 1.12f, 0.82f, 12, STEEL, 0);
        // Claw: the spine sweeps round a centre on the edge side, the cutting edge on the inside of the curve.
        float[] c = P(9.6f, 1.1f - 5.6f);
        List<float[]> st = new ArrayList<>();
        int n = 12;
        for (int i = 0; i < n; i++) {
            float f = (float) i / (n - 1);
            double ang = Math.toRadians(135 - 88 * f);
            float rs = 5.6f - f * f * f * 0.9f, w = 2.15f * (float) Math.pow(1 - f, 0.85) + 0.04f;
            float cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
            st.add(new float[]{c[0] + cs * rs, c[1] + sn * rs, c[0] + cs * (rs - w), c[1] + sn * (rs - w), 0.62f * (1 - f) + 0.03f, f});
        }
        m.blade(st, 0);
        m.pivot = rc;
        m.inspectSeconds = 1.4f;
        return m;
    }

    static KnifeMesh butterfly() {
        KnifeMesh m = new KnifeMesh();
        // Safe handle (group 0) and latch handle (group 1), each with a glowing accent line along its outer side.
        m.rod(0.6f, 10.0f, 0.8f, 0.62f, 0.95f, 8, HANDLE, 0);
        m.rod(1.4f, 9.2f, 1.45f, 0.11f, 0.45f, 6, ACCENT, 0);
        m.rod(0.6f, 10.0f, -0.8f, 0.62f, 0.95f, 8, HANDLE, 1);
        m.rod(1.4f, 9.2f, -1.45f, 0.11f, 0.45f, 6, ACCENT, 1);
        m.rod(0.1f, 0.7f, -0.8f, 0.4f, 0.6f, 8, STEEL, 1);
        m.rod(9.7f, 10.3f, 0.8f, 0.36f, 1.05f, 8, STEEL, 0);
        m.rod(9.7f, 10.3f, -0.8f, 0.36f, 1.05f, 8, STEEL, 1);
        float a = 10.4f, b = 21.2f;
        m.blade(List.of(st(10.4f, 0.95f, -0.95f, 0.5f, 0), st(13f, 0.95f, -1.1f, 0.5f, (13 - a) / (b - a)),
                st(16f, 0.9f, -1.15f, 0.48f, (16 - a) / (b - a)), st(18.6f, 0.8f, -1.05f, 0.44f, (18.6f - a) / (b - a)),
                st(19.9f, 0.62f, -0.4f, 0.32f, (19.9f - a) / (b - a)), st(21.2f, 0.45f, 0.4f, 0.02f, 1)), 0);
        m.latchPin = P(10.0f, -0.8f);
        m.pivot = P(10.5f, 0);
        m.inspectSeconds = 2.0f;
        return m;
    }
}
