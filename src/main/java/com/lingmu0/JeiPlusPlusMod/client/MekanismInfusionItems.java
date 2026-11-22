package com.lingmu0.JeiPlusPlusMod.client;

import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Optional Mekanism integration; uses the loaded conversion recipes, including datapack changes. */
final class MekanismInfusionItems {
    private MekanismInfusionItems() {}

    static List<ItemStack> alternatives(ITypedIngredient<?> ingredient) {
        if (ingredient == null) return List.of();
        Object chemical = ingredient.getIngredient();
        if (chemical == null || !chemical.getClass().getName().equals("mekanism.api.chemical.infuse.InfusionStack")) {
            return List.of();
        }
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        try {
            Object type = call(chemical, "getTypeRegistryName");
            long needed = ((Number) call(chemical, "getAmount")).longValue();
            if (type == null || needed <= 0) return List.of();
            Object registered = Class.forName("mekanism.common.recipe.MekanismRecipeType")
                .getField("INFUSION_CONVERSION").get(null);
            Object recipeType = call(registered, "getRecipeType");
            Object recipes = call(recipeType, "getRecipes", level);
            if (!(recipes instanceof Iterable<?> all)) return List.of();
            List<ItemStack> result = new ArrayList<>();
            for (Object recipe : all) {
                Object outputs = call(recipe, "getOutputDefinition");
                if (!(outputs instanceof Iterable<?> values)) continue;
                for (Object output : values) {
                    if (!type.equals(call(output, "getTypeRegistryName"))) continue;
                    long produced = ((Number) call(output, "getAmount")).longValue();
                    if (produced <= 0) continue;
                    long batches = 1 + (needed - 1) / produced;
                    Object input = call(recipe, "getInput");
                    Object variants = call(input, "getRepresentations");
                    if (variants instanceof Iterable<?> candidates) {
                        for (Object value : candidates) {
                            if (!(value instanceof ItemStack stack) || stack.isEmpty()) continue;
                            if (batches > Integer.MAX_VALUE / (long) stack.getCount()) continue;
                            long count = batches * stack.getCount();
                            ItemStack copy = stack.copy();
                            copy.setCount((int) count);
                            result.add(copy);
                        }
                    }
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean compatible = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null && !method.getParameterTypes()[i].isInstance(args[i])) compatible = false;
            }
            if (compatible) return method.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
