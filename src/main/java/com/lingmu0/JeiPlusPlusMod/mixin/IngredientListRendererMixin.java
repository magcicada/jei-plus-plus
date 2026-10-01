package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites;
import mezz.jei.gui.overlay.elements.IElement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/** JEI 15.48+ row-aware bookmark renderer. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.overlay.ingredients.IngredientListRenderer", remap = false)
public abstract class IngredientListRendererMixin {
    @Shadow
    @Final
    private List<?> slots;
    @Shadow
    private int blocked;

    @Inject(method = "set", at = @At("HEAD"), cancellable = true, remap = false)
    private void jeiPlusPlus$setWithRowBreaks(int startIndex, List<IElement<?>> ingredientList, CallbackInfo ci) {
        if (ingredientList.stream().noneMatch(RecipeTreeFavorites::isRowBreak)) {
            return;
        }
        jeiPlusPlus$clearRenderState();
        blocked = 0;
        Iterator<IElement<?>> iterator = ingredientList.listIterator(Math.max(0, startIndex));
        for (Object slot : slots) {
            if (jeiPlusPlus$invokeBoolean(slot, "isBlocked")) {
                jeiPlusPlus$invoke(slot, "clear");
                blocked++;
                continue;
            }
            boolean assigned = false;
            while (iterator.hasNext()) {
                IElement<?> element = iterator.next();
                if (RecipeTreeFavorites.isRowBreak(element)) {
                    jeiPlusPlus$invoke(slot, "clear");
                    assigned = true;
                    break;
                }
                if (!element.isVisible()) {
                    continue;
                }
                jeiPlusPlus$invoke(slot, "setElement", element);
                jeiPlusPlus$invoke(this, "addRenderElement", slot);
                assigned = true;
                break;
            }
            if (!assigned) {
                jeiPlusPlus$invoke(slot, "clear");
            }
        }
        ci.cancel();
    }

    private void jeiPlusPlus$clearRenderState() {
        for (String name : new String[]{"renderElementsByType", "renderOverlays"}) {
            Object value = jeiPlusPlus$field(name);
            if (value instanceof Collection<?> collection) {
                collection.clear();
            } else if (value != null) {
                jeiPlusPlus$invoke(value, "clear");
            }
        }
    }

    private Object jeiPlusPlus$field(String name) {
        Class<?> type = getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(this);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static boolean jeiPlusPlus$invokeBoolean(Object target, String name) {
        return jeiPlusPlus$invoke(target, name) instanceof Boolean bool && bool;
    }

    private static Object jeiPlusPlus$invoke(Object target, String name, Object... arguments) {
        if (target == null) {
            return null;
        }
        // Mixin methods are merged directly into IngredientListRenderer; the
        // target object is not a subclass whose superclass contains JEI's
        // private methods. Starting at getSuperclass() skips addRenderElement
        // completely, leaving every populated bookmark slot absent from the
        // render batch. Search the transformed target class itself first.
        Class<?> type = target.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(target, arguments);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try an inherited/bridge method with the same name.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
