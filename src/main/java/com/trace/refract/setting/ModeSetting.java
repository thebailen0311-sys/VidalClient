package com.trace.refract.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.Consumer;

public class ModeSetting extends Setting<String> {
    public final String[] modes;
    private Consumer<String> onChange = s -> {};

    public ModeSetting(String name, String value, String... modes) { super(name, value); this.modes = modes; }

    public ModeSetting onChange(Consumer<String> c) { this.onChange = c; return this; }

    public boolean is(String m) { return value.equalsIgnoreCase(m); }

    public int index() {
        for (int i = 0; i < modes.length; i++) if (modes[i].equalsIgnoreCase(value)) return i;
        return 0;
    }

    public void cycle(int dir) { set(modes[Math.floorMod(index() + dir, modes.length)]); }

    @Override public void set(String v) {
        for (String m : modes) if (m.equalsIgnoreCase(v)) { super.set(m); onChange.accept(m); return; }
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override public void load(JsonElement e) { if (e.isJsonPrimitive()) set(e.getAsString()); }
}
