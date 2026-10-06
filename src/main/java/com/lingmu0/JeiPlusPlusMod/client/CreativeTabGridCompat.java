package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.input.IUserInputHandler;
import mezz.jei.common.input.UserInput;
import mezz.jei.common.util.ImmutableRect2i;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/** Shared reflective bridge for JEI's pre- and post-15.48 ingredient grids. */
public final class CreativeTabGridCompat {
    private static volatile boolean selectorOpen;

    private static final ClassValue<GridAccess> ACCESS = new ClassValue<>() {
        @Override
        protected GridAccess computeValue(Class<?> type) {
            try {
                Field source = type.getDeclaredField("ingredientSource");
                source.setAccessible(true);
                return new GridAccess(source, type.getMethod("getBackgroundArea"), type.getMethod("getBackButtonArea"));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unsupported JEI ingredient-grid internals: " + type.getName(),
                        exception);
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

    public static IUserInputHandler wrapInput(Object owner, IUserInputHandler delegate) {
        return new CreativeTabInputHandler(owner, delegate);
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

    private static final class CreativeTabInputHandler implements IUserInputHandler {
        private final Object owner;
        private final IUserInputHandler delegate;

        private CreativeTabInputHandler(Object owner, IUserInputHandler delegate) {
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override
        public @NotNull Optional<IUserInputHandler> handleUserInput(@NotNull Screen screen, @NotNull UserInput input,
                @NotNull IInternalKeyMappings keyBindings) {
            IngredientListFeatureSource source = getFeatureSource(owner);
            if (source != null && CreativeTabBar.handleClick(source, getArea(owner), input)) {
                return Optional.of(this);
            }
            return delegate.handleUserInput(screen, input, keyBindings);
        }

        @Override
        public void unfocus() {
            delegate.unfocus();
        }

        @Override
        public @NotNull Optional<IUserInputHandler> handleMouseScrolled(double mouseX, double mouseY,
                double scrollDelta) {
            IngredientListFeatureSource source = getFeatureSource(owner);
            if (source != null) {
                Optional<IUserInputHandler> result = CreativeTabBar.handleScroll(
                        source, getArea(owner), mouseX, mouseY, scrollDelta, this);
                if (result.isPresent()) {
                    return result;
                }
            }
            return delegate.handleMouseScrolled(mouseX, mouseY, scrollDelta);
        }
    }
}
