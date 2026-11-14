package com.trace.refract.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A user-chosen set of blocks, each with its own colour. Edited in the GUI with a search box. */
public class BlockListSetting extends Setting<Map<Block, Integer>> {
    /** Colours a newly added block cycles through (and the swatch click steps through). */
    public static final int[] PALETTE = {0xFF4DA3FF, 0xFFFF5FB4, 0xFF34D399, 0xFFFFC94D, 0xFFB06BFF, 0xFFFF6B4D,
            0xFF4DE3FF, 0xFFF2555A, 0xFF9BE15D, 0xFFFF9A3D, 0xFFE0E7FF, 0xFF8C5BFF};

    public BlockListSetting(String name) { super(name, new LinkedHashMap<>()); }

    public void add(Block b) { if (!value.containsKey(b)) value.put(b, PALETTE[value.size() % PALETTE.length]); }

    public void remove(Block b) { value.remove(b); }

    public void cycleColor(Block b) {
        Integer c = value.get(b);
        if (c == null) return;
        int i = 0;
        for (int k = 0; k < PALETTE.length; k++) if (PALETTE[k] == c) i = k + 1;
        value.put(b, PALETTE[i % PALETTE.length]);
    }

    /** Blocks whose name or id contains the query (not already chosen), best matches first. */
    public List<Block> search(String query, int max) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Block> starts = new ArrayList<>(), contains = new ArrayList<>();
        if (q.isEmpty()) return starts;
        for (Block b : Registries.BLOCK) {
            if (value.containsKey(b) || b.getDefaultState().isAir()) continue;
            String name = b.getName().getString().toLowerCase(Locale.ROOT), id = Registries.BLOCK.getId(b).getPath();
            if (name.startsWith(q) || id.startsWith(q)) starts.add(b);
            else if (name.contains(q) || id.contains(q.replace(' ', '_'))) contains.add(b);
            if (starts.size() >= max) break;
        }
        starts.addAll(contains);
        return starts.subList(0, Math.min(max, starts.size()));
    }

    @Override public JsonElement save() {
        JsonObject o = new JsonObject();
        value.forEach((b, c) -> o.addProperty(Registries.BLOCK.getId(b).toString(), c));
        return o;
    }

    @Override public void load(JsonElement e) {
        if (!e.isJsonObject()) return;
        value.clear();
        for (var en : e.getAsJsonObject().entrySet()) {
            Identifier id = Identifier.tryParse(en.getKey());
            if (id == null || !Registries.BLOCK.containsId(id)) continue;
            value.put(Registries.BLOCK.get(id), en.getValue().getAsInt());
        }
    }
}
