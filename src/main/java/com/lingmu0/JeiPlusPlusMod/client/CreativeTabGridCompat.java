package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.common.util.ImmutableRect2i;
import net.minecraft.client.gui.GuiGraphics;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Shared reflective bridge for JEI's pre- and post-19.42 ingredient grids. */
public final class CreativeTabGridCompat {
    private static volatile boolean selectorOpen;

    private static final ClassValue<GridAccess> ACCESS = new ClassValue<>() {
        @Override
        protected GridAccess computeValue(Class<?> type) {
            try {
                Field source = type.getDeclaredField("ingredientSource");
                source.setAccessible(true);
                return new GridAccess(
                    source,
                    type.getMethod("getBackgroundArea"),
                    type.getMethod("getBackButtonArea")
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unsupported JEI ingredient-grid internals: " + type.getName(), exception);
            }
        }
    };

    private CreativeTabGridCompat() {
    }

    public static ImmutableRect2i reserveRow(Object owner, ImmutableRect2i availableArea) {
        if (getFeatureSource(owner) == null || !JeiPlusPlusConfig.CREATIVE_TAB_BAR_ENABLED.get()) {
            return availableArea;
        }
        return availableArea.cropTop(CreativeTabBar.getReservedHeight());
    }

    public static void draw(Object owner, GuiGraphics graphics, int mouseX, int mouseY) {
        IngredientListFeatureSource source = getFeatureSource(owner);
        if (source != null && JeiPlusPlusConfig.CREATIVE_TAB_BAR_ENABLED.get()) {
            CreativeTabBar.draw(source, graphics, getArea(owner), mouseX, mouseY);
        }
    }

    public static void drawTooltip(Object owner, GuiGraphics graphics, int mouseX, int mouseY) {
        IngredientListFeatureSource source = getFeatureSource(owner);
        if (source != null && JeiPlusPlusConfig.CREATIVE_TAB_BAR_ENABLED.get()) {
            CreativeTabBar.drawTooltip(source, graphics, getArea(owner), mouseX, mouseY);
        }
    }

    /** Called only from JEI's final screen pass, never from the ingredient background pass. */
    public static void drawModal(Object overlay, GuiGraphics graphics, int mouseX, int mouseY) {
        if (!selectorOpen || !JeiPlusPlusConfig.CREATIVE_TAB_BAR_ENABLED.get()) return;
        Object owner = JeiReflectionCompat.readField(overlay, "contents");
        IngredientListFeatureSource source = getFeatureSource(owner);
        if (source == null || !source.jeiPlusPlus$isCreativeTabSelectorOpen()) return;
        graphics.flush();
        CreativeTabSelector.draw(source, graphics, mouseX, mouseY);
        CreativeTabSelector.drawTooltip(source, graphics, mouseX, mouseY);
        graphics.flush();
    }

    public static Object wrapInput(Object owner, Object delegate) {
        return JeiReflectionCompat.customInputHandler(
            (proxy, method, args) -> handleTabInput(owner, delegate, proxy, method, args)
        );
    }

    public static boolean containsExtraInput(Object owner, double x, double y) {
        IngredientListFeatureSource source = getFeatureSource(owner);
        return source != null && JeiPlusPlusConfig.CREATIVE_TAB_BAR_ENABLED.get()
            && !source.jeiPlusPlus$getCreativeTabs().isEmpty()
            && (source.jeiPlusPlus$isCreativeTabSelectorOpen() || getArea(owner).contains(x, y));
    }

    /** Unlike isSelectorOpen, only the actual ingredient list can own the modal picker. */
    public static boolean ownsSelector(Object owner) {
        IngredientListFeatureSource source = getFeatureSource(owner);
        return source != null && source.jeiPlusPlus$isCreativeTabSelectorOpen();
    }

    public static boolean isSelectorOpen(Object owner) {
        if (selectorOpen) {
            return true;
        }
        IngredientListFeatureSource source = getFeatureSource(owner);
        return source != null && source.jeiPlusPlus$isCreativeTabSelectorOpen();
    }

    public static void setSelectorOpen(boolean open) {
        selectorOpen = open;
    }

    public static boolean isAnySelectorOpen() {
        return selectorOpen;
    }

    private static IngredientListFeatureSource getFeatureSource(Object owner) {
        try {
            Object source = ACCESS.get(owner.getClass()).ingredientSource().get(owner);
            return source instanceof IngredientListFeatureSource features ? features : null;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access JEI ingredient source", exception);
        }
    }

    private static ImmutableRect2i getArea(Object owner) {
        GridAccess access = ACCESS.get(owner.getClass());
        try {
            ImmutableRect2i background = (ImmutableRect2i) access.getBackgroundArea().invoke(owner);
            ImmutableRect2i backButton = (ImmutableRect2i) access.getBackButtonArea().invoke(owner);
            return CreativeTabBar.getArea(background, backButton);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read JEI ingredient-grid bounds", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("JEI ingredient-grid bounds failed", exception.getCause());
        }
    }

    private record GridAccess(Field ingredientSource, Method getBackgroundArea, Method getBackButtonArea) {
    }

    private static Object handleTabInput(
        Object owner,
        Object delegate,
        Object proxy,
        Method method,
        Object[] args
    ) throws Throwable {
        switch (method.getName()) {
            case "equals" -> {
                return proxy == args[0];
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "toString" -> {
                return "JEI++ creative tab input handler";
            }
            case "resetInput" -> {
                return JeiReflectionCompat.invokeInputHandler(delegate, method, args);
            }
            case "beginInput" -> {
                IngredientListFeatureSource source = getFeatureSource(owner);
                return source != null && CreativeTabBar.handleClick(source, getArea(owner), JeiReflectionCompat.inputArgument(args))
                    ? java.util.Optional.of(proxy)
                    : JeiReflectionCompat.invokeInputHandler(delegate, method, args);
            }
            case "scroll" -> {
                IngredientListFeatureSource source = getFeatureSource(owner);
                if (source != null) {
                    java.util.Optional<Object> result = CreativeTabBar.handleScroll(
                        source,
                        getArea(owner),
                        ((Number) args[0]).doubleValue(),
                        ((Number) args[1]).doubleValue(),
                        ((Number) args[args.length - 1]).doubleValue(),
                        proxy
                    );
                    if (result.isPresent()) {
                        return result;
                    }
                }
                double x = ((Number) args[0]).doubleValue();
                double y = ((Number) args[1]).doubleValue();
                double dy = ((Number) args[args.length - 1]).doubleValue();
                if (dy != 0 && JeiInputAreaCompat.insideBand(x, y,
                    JeiReflectionCompat.tryInvoke(owner, "getBackButtonArea"),
                    JeiReflectionCompat.tryInvoke(owner, "getNextPageButtonArea"))) {
                    Object pages = JeiReflectionCompat.tryInvoke(owner, "getPageDelegate");
                    if (pages != null) {
                        JeiReflectionCompat.invokeNoArgsUnchecked(pages, dy < 0 ? "nextPage" : "previousPage");
                        return java.util.Optional.of(proxy);
                    }
                }
                return JeiReflectionCompat.invokeInputHandler(delegate, method, args);
            }
            default -> {
                return JeiReflectionCompat.invokeInputHandler(delegate, method, args);
            }
        }
    }
}
