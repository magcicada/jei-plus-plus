package com.lingmu0.JeiPlusPlusMod.client;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.platform.Services;
import mezz.jei.common.util.SafeIngredientUtil;
import mezz.jei.library.gui.ingredients.TagContentTooltipComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Builds JEI tooltips for the tree's item, fluid and chemical ingredients. */
final class JeiTooltipCompat {
    private JeiTooltipCompat() {
    }

    static <T> void addIngredientTooltip(JeiTooltip tooltip, IIngredientManager manager, ITypedIngredient<T> ingredient) {
        IIngredientRenderer<T> renderer = manager.getIngredientRenderer(ingredient.getType());
        SafeIngredientUtil.getRichTooltip(tooltip, manager, renderer, ingredient);
    }

    static void addAlternatives(JeiTooltip tooltip, IJeiRuntime runtime, List<ItemStack> alternatives) {
        if (runtime == null || alternatives.size() < 2) return;

        IIngredientManager manager = runtime.getIngredientManager();
        List<ItemStack> displayStacks = new ArrayList<>();
        List<ITypedIngredient<?>> ingredients = new ArrayList<>();
        for (ItemStack stack : alternatives) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack display = FluidRecipeCompat.copyWithDisplay(stack);
            display.setCount(1);
            FluidRecipeCompat.toTyped(manager, display).ifPresent(typed -> {
                displayStacks.add(display);
                ingredients.add(typed);
            });
        }
        if (ingredients.size() < 2) return;

        if (ingredients.stream().allMatch(ingredient -> ingredient.getType() == VanillaTypes.ITEM_STACK)) {
            manager.getIngredientHelper(VanillaTypes.ITEM_STACK).getTagKeyEquivalent(displayStacks).ifPresent(tag -> {
                tooltip.add(Component.translatable("jei.tooltip.recipe.tag", "").withStyle(ChatFormatting.GRAY));
                tooltip.add(Services.PLATFORM.getRenderHelper().getName(tag).copy().withStyle(ChatFormatting.GRAY));
            });
        }
        tooltip.add(new TagContentTooltipComponent(manager, ingredients));
    }
}
