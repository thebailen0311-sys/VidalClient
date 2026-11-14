package com.trace.refract.module;

public enum Category {
    COMBAT("Combat", "Triggerbot, aim assist, anchors, totems, crystals"),
    HUNTING("Hunting", "Find bases, stashes and activity"),
    RENDER("Render", "See the world differently"),
    MISC("Misc", "Awareness and utilities"),
    CLIENT("Client", "Interface and appearance");

    public final String label;
    public final String subtitle;

    Category(String label, String subtitle) { this.label = label; this.subtitle = subtitle; }
}
