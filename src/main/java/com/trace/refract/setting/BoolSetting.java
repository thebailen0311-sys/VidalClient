package com.trace.refract.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.trace.refract.util.Anim;

public class BoolSetting extends Setting<Boolean> {
    public final Anim knob;

    public BoolSetting(String name, boolean value) {
        super(name, value);
        knob = new Anim(value ? 1f : 0f, 16f);
    }

    public boolean on() { return value; }

    public void toggle() { set(!value); }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override public void load(JsonElement e) { if (e.isJsonPrimitive()) { set(e.getAsBoolean()); knob.snap(value ? 1 : 0); } }
}
