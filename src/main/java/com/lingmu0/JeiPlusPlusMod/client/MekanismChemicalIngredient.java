package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.Ae2ChemicalCompat;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Adapts a JEI chemical ingredient to the tree's stack and quantity model. */
final class MekanismChemicalIngredient {
    private record Chemical(String key, Object value, ITypedIngredient<?> typed, long amount) {}

    private static final String STACK = "mekanism.api.chemical.infuse.InfusionStack";
    private static final Map<ItemStack, Chemical> DISPLAY = new WeakHashMap<>();

    private MekanismChemicalIngredient() {}

    static void clear() {
        DISPLAY.clear();
    }

    static boolean isChemical(ITypedIngredient<?> typed) {
        return typed != null && typed.getIngredient() != null
            && STACK.equals(typed.getIngredient().getClass().getName());
    }

    static Optional<ItemStack> fromIngredient(ITypedIngredient<?> typed) {
        if (!isChemical(typed)) return Optional.empty();
        Object value = typed.getIngredient();
        try {
            long amount = amountOf(value);
            String key = keyOf(value);
            if (key.isEmpty() || amount <= 0 || amount > Integer.MAX_VALUE) return Optional.empty();
            return Optional.of(create(new Chemical(key, value, typed, amount), (int) amount));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    static Optional<ItemStack> fromNetworkKey(Object key) {
        if (!Ae2ChemicalCompat.isChemicalKey(key)) return Optional.empty();
        try {
            Object value = key.getClass().getMethod("getStack").invoke(key);
            if (value == null || !STACK.equals(value.getClass().getName())) return Optional.empty();
            String id = keyOf(value);
            return id.isEmpty() ? Optional.empty() : Optional.of(create(new Chemical(id, value, null, 1), 1));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    static long amountOf(Object chemical) throws ReflectiveOperationException {
        return ((Number) chemical.getClass().getMethod("getAmount").invoke(chemical)).longValue();
    }

    static String keyOf(Object chemical) throws ReflectiveOperationException {
        Object id = chemical.getClass().getMethod("getTypeRegistryName").invoke(chemical);
        return id == null ? "" : "mek_chemical:" + id;
    }

    private static ItemStack create(Chemical chemical, int amount) {
        ItemStack stack = new ItemStack(Items.BARRIER, amount);
        DISPLAY.put(stack, chemical);
        return stack;
    }

    static void copyDisplay(ItemStack source, ItemStack target) {
        Chemical chemical = DISPLAY.get(source);
        if (chemical != null && !target.isEmpty()) DISPLAY.put(target, chemical);
    }

    static String key(ItemStack stack) {
        Chemical chemical = DISPLAY.get(stack);
        return chemical == null ? "" : chemical.key();
    }

    static boolean hasChemical(List<ItemStack> alternatives) {
        return alternatives.stream().anyMatch(stack -> !key(stack).isEmpty());
    }

    static Optional<ITypedIngredient<?>> typed(ItemStack stack) {
        Chemical chemical = DISPLAY.get(stack);
        return chemical == null ? Optional.empty() : Optional.ofNullable(chemical.typed());
    }

    static ItemStack patternInput(ItemStack stack, int count) {
        Chemical chemical = DISPLAY.get(stack);
        if (chemical != null) return Ae2ChemicalCompat.wrap(chemical.value(), count);
        ItemStack copy = stack.copy();
        if (!copy.isEmpty()) copy.setCount(count);
        return copy;
    }

    static String formatAmount(long amount) {
        return amount + " mB";
    }

    static boolean render(GuiGraphics graphics, ItemStack stack, int x, int y) {
        var ingredient = typed(stack);
        var runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || ingredient.isEmpty()) return false;
        renderTyped(graphics, runtime.getIngredientManager(), ingredient.get(), x, y);
        return true;
    }

    private static <T> void renderTyped(GuiGraphics graphics,
                                        mezz.jei.api.runtime.IIngredientManager manager,
                                        ITypedIngredient<T> ingredient, int x, int y) {
        manager.getIngredientRenderer(ingredient.getType()).render(graphics, ingredient.getIngredient(), x, y);
    }

    static boolean renderTooltip(GuiGraphics graphics, ItemStack stack, int x, int y) {
        Chemical chemical = DISPLAY.get(stack);
        if (chemical == null) return false;
        Component name;
        try {
            name = (Component) chemical.value().getClass().getMethod("getTextComponent").invoke(chemical.value());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            name = Component.literal(chemical.key());
        }
        var lines = List.of(name, Component.literal(formatAmount(chemical.amount())));
        graphics.renderTooltip(Minecraft.getInstance().font, lines, Optional.empty(), x, y);
        return true;
    }
}
