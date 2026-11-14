package com.trace.refract.theme;

import com.trace.refract.util.Colors;

/** A colour palette. Blob colours drive the drifting aurora behind the glass. */
public record Theme(String name, int accent, int accent2, int glassTop, int glassBottom, int rim,
                    int text, int textDim, int vignette, int blobA, int blobB, int blobC) {

    public static Theme lerp(Theme a, Theme b, float t) {
        return new Theme(t < 0.5f ? a.name : b.name,
                Colors.lerp(a.accent, b.accent, t), Colors.lerp(a.accent2, b.accent2, t),
                Colors.lerp(a.glassTop, b.glassTop, t), Colors.lerp(a.glassBottom, b.glassBottom, t),
                Colors.lerp(a.rim, b.rim, t), Colors.lerp(a.text, b.text, t), Colors.lerp(a.textDim, b.textDim, t),
                Colors.lerp(a.vignette, b.vignette, t), Colors.lerp(a.blobA, b.blobA, t),
                Colors.lerp(a.blobB, b.blobB, t), Colors.lerp(a.blobC, b.blobC, t));
    }

    /** Accent gradient sample, t in [0,1]. */
    public int gradient(float t) {
        float s = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
        return Colors.lerp(accent, accent2, s);
    }
}
