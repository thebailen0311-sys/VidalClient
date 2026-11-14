package com.trace.refract.setting;

import com.google.gson.JsonElement;
import com.trace.refract.util.Anim;

import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    public final String name;
    protected T value;
    private BooleanSupplier visible = () -> true;
    /** Visibility animation used by the click GUI. */
    public final Anim shown = new Anim(1f, 14f);

    protected Setting(String name, T value) { this.name = name; this.value = value; }

    public T get() { return value; }

    public void set(T v) { this.value = v; }

    public boolean visible() { return visible.getAsBoolean(); }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier s) { this.visible = s; return (S) this; }

    public abstract JsonElement save();

    public abstract void load(JsonElement e);
}
