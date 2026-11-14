package com.trace.refract.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.trace.refract.util.Anim;

public class NumberSetting extends Setting<Double> {
    public final double min, max, step;
    public final String unit;
    public final Anim fill;

    public NumberSetting(String name, double value, double min, double max, double step, String unit) {
        super(name, value);
        this.min = min; this.max = max; this.step = step; this.unit = unit;
        this.fill = new Anim((float) frac(), 18f);
    }

    public double frac() { return (value - min) / (max - min); }

    public int asInt() { return (int) Math.round(value); }

    public float asFloat() { return value.floatValue(); }

    @Override public void set(Double v) {
        double snapped = Math.round(v / step) * step;
        super.set(Math.max(min, Math.min(max, snapped)));
    }

    public void setFrac(double f) { set(min + (max - min) * Math.max(0, Math.min(1, f))); }

    public String display() {
        String num = step >= 1 ? String.valueOf(asInt()) : String.format(java.util.Locale.ROOT, step >= 0.1 ? "%.1f" : "%.2f", value);
        return num + unit;
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override public void load(JsonElement e) { if (e.isJsonPrimitive()) { set(e.getAsDouble()); fill.snap((float) frac()); } }
}
