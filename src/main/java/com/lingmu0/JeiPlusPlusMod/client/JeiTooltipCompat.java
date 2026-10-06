package com.lingmu0.JeiPlusPlusMod.client;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Runtime bridge for JEI's changing tag-content tooltip component. */
final class JeiTooltipCompat {
    private static final String TAG_CONTENT_COMPONENT = "mezz.jei.library.gui.ingredients.TagContentTooltipComponent";

    private JeiTooltipCompat() {
    }

    static Optional<TooltipComponent> createTagContent(
            IJeiRuntime runtime,
            IIngredientRenderer<ItemStack> renderer,
            List<ItemStack> alternatives) {
        if (runtime == null || renderer == null || alternatives == null || alternatives.isEmpty()) {
            return Optional.empty();
        }
        try {
            IIngredientManager manager = runtime.getIngredientManager();
            Class<?> componentType = Class.forName(TAG_CONTENT_COMPONENT);
            List<ITypedIngredient<?>> typed = new ArrayList<>();
            for (ItemStack stack : alternatives) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                manager.createTypedIngredient(VanillaTypes.ITEM_STACK, stack)
                        .ifPresent(value -> typed.add((ITypedIngredient<?>) value));
            }
            for (Constructor<?> constructor : componentType.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 2 || parameters[1] != List.class) {
                    continue;
                }
                Object first;
                List<?> values;
                if (parameters[0].isAssignableFrom(manager.getClass())) {
                    // Newer JEI versions render typed ingredients through the manager.
                    first = manager;
                    values = typed;
                } else if (parameters[0].isAssignableFrom(renderer.getClass())) {
                    // Older JEI versions render raw values through the renderer.
                    first = renderer;
                    values = alternatives;
                } else {
                    continue;
                }
                constructor.trySetAccessible();
                return Optional.of((TooltipComponent) constructor.newInstance(first, values));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The extra tooltip is optional; the normal tag line remains.
        }
        return Optional.empty();
    }
}
