package com.lingmu0.JeiPlusPlusMod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Wire data only; neither JEI nor AE2 is linked from the common classloader. */
public record Ae2PatternPlan(ResourceLocation recipeId, List<ItemStack> inputs,
        List<ItemStack> outputs, boolean substitute) {
    public Ae2PatternPlan {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }
}
