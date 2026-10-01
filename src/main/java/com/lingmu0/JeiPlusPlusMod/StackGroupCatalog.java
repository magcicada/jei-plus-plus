package com.lingmu0.JeiPlusPlusMod;

import java.util.List;

/** Stable ids and display keys for the built-in item groups. */
public final class StackGroupCatalog {
    public record DefaultGroup(String id, String suffix, String translationKey) {
    }

    public static final List<DefaultGroup> DEFAULT_GROUPS = List.of(
            group("wool", "_wool"),
            group("carpet", "_carpet"),
            group("concrete", "_concrete"),
            group("concrete_powder", "_concrete_powder"),
            group("terracotta", "_terracotta"),
            group("glazed_terracotta", "_glazed_terracotta"),
            group("stained_glass", "_stained_glass"),
            group("stained_glass_pane", "_stained_glass_pane"),
            group("candle", "_candle"),
            group("bed", "_bed"),
            group("banner", "_banner"),
            group("shulker_box", "_shulker_box"),
            group("planks", "_planks"),
            group("stripped_logs", "_stripped_log"),
            group("logs", "_log"),
            group("stripped_wood", "_stripped_wood"),
            group("wood", "_wood"),
            group("slab", "_slab"),
            group("stairs", "_stairs"),
            group("wall", "_wall"),
            group("fence_gate", "_fence_gate"),
            group("fence", "_fence"),
            group("door", "_door"),
            group("trapdoor", "_trapdoor"),
            group("button", "_button"),
            group("pressure_plate", "_pressure_plate"),
            group("glass", "_glass"),
            group("pane", "_pane"),
            group("ore", "_ore"),
            group("raw_material", "_raw"),
            group("ingot", "_ingot"),
            group("nugget", "_nugget"),
            group("sword", "_sword"),
            group("pickaxe", "_pickaxe"),
            group("axe", "_axe"),
            group("shovel", "_shovel"),
            group("hoe", "_hoe"),
            group("boat", "_boat"),
            group("sapling", "_sapling"),
            group("seed", "_seeds"),
            group("flower", "_flower"),
            group("leaves", "_leaves"),
            group("rail", "_rail"),
            group("hanging_sign", "_hanging_sign"),
            group("sign", "_sign"));

    private StackGroupCatalog() {
    }

    private static DefaultGroup group(String id, String suffix) {
        return new DefaultGroup(id, suffix, "jei_plus_plus.group.default." + id);
    }
}
