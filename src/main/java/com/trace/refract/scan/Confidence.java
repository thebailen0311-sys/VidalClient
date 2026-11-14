package com.trace.refract.scan;

/**
 * Turns raw chunk evidence into probabilities and named confidence levels.
 *
 * Kelp: generated kelp tops have age 20-23 and columns of at most 11 blocks; each growth step adds one
 * block and one age, capping at 25. Growth only happens while the chunk is loaded (random ticks), at
 * about one step per ~8 minutes per column (3 random ticks / section / tick, 14% chance). The share of
 * columns that reached the cap is inverted through that Poisson model to estimate loaded time.
 *
 * Amethyst: geodes generate with buds/clusters in roughly equal shares; buds only mature while loaded.
 * A one-sided binomial z-test against a 30% natural cluster share measures how unusual the chunk is.
 */
public final class Confidence {
    public enum Level {
        NONE("None", 0xFF8A93A6), LOW("Low", 0xFF7CC4FF), MEDIUM("Medium", 0xFFFFD84D), HIGH("High", 0xFFFF9A3D), VERY_HIGH("Very High", 0xFFFF4D6D);

        public final String label;
        public final int color;

        Level(String label, int color) { this.label = label; this.color = color; }
    }

    /** Kelp growth events per second per eligible column at the default random tick speed. */
    private static final double KELP_RATE = 20.0 * 3.0 / 4096.0 * 0.14;
    private static final double NATURAL_CLUSTER_SHARE = 0.30;

    private Confidence() {}

    public static Level level(float p) {
        if (p >= 0.85f) return Level.VERY_HIGH;
        if (p >= 0.65f) return Level.HIGH;
        if (p >= 0.35f) return Level.MEDIUM;
        if (p >= 0.12f) return Level.LOW;
        return Level.NONE;
    }

    public static float fromScore(double score, double scale) { return (float) (1 - Math.exp(-Math.max(0, score) / scale)); }

    public static void score(ChunkReport r) {
        // Kelp: only strictly grown columns are counted (top age 25, or taller than worldgen allows).
        r.kelpMinutes = -1;
        if (r.kelpEligible >= 4) {
            double share = (double) r.kelpCapped / r.kelpEligible;
            if (share > 0) r.kelpMinutes = estimateMinutes(share);
        }
        float pk = r.kelpGrown > 0 ? fromScore(r.kelpGrown, 3) : 0;
        if (r.kelpMinutes > 90) pk = Math.max(pk, 0.9f);
        else if (r.kelpMinutes > 30 && r.kelpGrown >= 3) pk = Math.max(pk, 0.7f);
        r.pKelp = r.kelpGrown >= 3 ? pk : 0; // one or two grown columns is noise near any busy area

        // Amethyst (geodes span chunk borders, so totals come from this chunk plus its neighbours).
        // Worldgen picks small/medium/large/cluster uniformly, so ~25% of a fresh geode's buds are clusters;
        // they only mature through random ticks while the chunk is loaded. Two tests: a maturity threshold
        // (>=12 clusters and >=75% fully grown) and a one-sided binomial z-test against a 30% cluster share.
        int n = r.geodeTotal, k = r.geodeClusters;
        r.amethystZ = 0;
        float pa = 0;
        boolean mature = false;
        if (n >= 6) {
            double z = (k - NATURAL_CLUSTER_SHARE * n) / Math.sqrt(n * NATURAL_CLUSTER_SHARE * (1 - NATURAL_CLUSTER_SHARE));
            r.amethystZ = z;
            pa = (float) Math.max(0, Math.min(1, (z - 2.0) / 3.0));
            double maturity = (double) k / n;
            if (k >= 12 && maturity >= 0.75) pa = Math.max(pa, 0.9f);
            else if (k >= 8 && maturity >= 0.6) pa = Math.max(pa, 0.5f);
            mature = k >= 8 && maturity >= 0.6 && z >= 3.0;
        }
        r.pAmethyst = r.clusters + r.buds > 0 ? pa : 0;
        r.geodeMature = mature && r.clusters > 0;

        // Underground-only evidence (the scanner already dropped anything at the surface).
        // Rotated deepslate never generates; one block can be a stray placement, two or more is building.
        r.pRotated = r.rotated >= 2 ? fromScore(r.rotated - 1, 3) : 0;
        // Straight 1x1 / 1x2 strip-mine tunnels. Common on busy servers, so it takes a long run to matter.
        r.pTunnel = r.tunnelLength >= 12 ? fromScore(r.tunnelLength - 8, 30) : 0;
        // Deepslate placed far above its natural range.
        r.pHigh = r.highDeepslate >= 3 ? fromScore(r.highDeepslate - 2, 8) * (r.structure != null ? 0.3f : 1f) : 0;
        // Cave vines at full length: weak on their own (they can generate long), so weighted low.
        r.pPlants = fromScore(r.caveVinesGrown, 6);

        // Player activity (natural-only chunks already score zero in the scanner). Built blocks only count when
        // they are varied, there is a dug room, or there are many of them; a large excavated cavity with a couple
        // of placed blocks counts as well, which is what an emptied or griefed base leaves behind.
        float builtScore = r.built >= 3 && (r.builtTypes >= 2 || !r.rooms.isEmpty() || r.built >= 8) ? r.built : 0f;
        float dug = r.placements >= 2 && r.excavated >= 80
                ? Math.min(6f, r.excavated / 30f) + Math.min(2f, r.placements * 0.25f) : 0f;
        r.pActivity = fromScore(r.activity + builtScore + dug, 20);
        r.baseConfidence = 1 - (1 - r.pActivity) * (1 - r.pRotated * 0.6f) * (1 - r.pTunnel * 0.5f);

        float none = (1 - r.pKelp) * (1 - r.pAmethyst) * (1 - r.pRotated) * (1 - r.pTunnel * 0.9f) * (1 - r.pHigh * 0.7f)
                * (1 - r.pPlants * 0.3f) * (1 - r.pActivity * 0.8f);
        r.susConfidence = 1 - none;
    }

    /** Invert P(capped | T) = mean over needed steps k in 2..5 of P(Poisson(rate*T) >= k). */
    static double estimateMinutes(double share) {
        share = Math.min(share, 0.995);
        double lo = 0, hi = 72 * 3600;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (cappedShare(mid) < share) lo = mid; else hi = mid;
        }
        return lo / 60.0;
    }

    private static double cappedShare(double seconds) {
        double mu = KELP_RATE * seconds;
        double total = 0;
        for (int k = 2; k <= 5; k++) {
            double cdf = 0, term = Math.exp(-mu);
            for (int i = 0; i < k; i++) { cdf += term; term *= mu / (i + 1); }
            total += 1 - cdf;
        }
        return total / 4;
    }

    public static String duration(double minutes) {
        if (minutes < 0) return "?";
        if (minutes < 60) return Math.round(minutes) + " min";
        double h = minutes / 60;
        return h >= 10 ? Math.round(h) + " h" : String.format(java.util.Locale.ROOT, "%.1f h", h);
    }
}
