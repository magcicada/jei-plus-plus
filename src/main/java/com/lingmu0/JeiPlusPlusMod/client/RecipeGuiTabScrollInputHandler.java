package com.lingmu0.JeiPlusPlusMod.client;

import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.input.IUserInputHandler;
import mezz.jei.common.input.UserInput;
import net.minecraft.client.gui.screens.Screen;

import java.util.Objects;
import java.util.Optional;

/**
 * Wraps JEI's recipe-tab input handler and gives JEI++ the first chance
 * to consume mouse-wheel events.
 */
public final class RecipeGuiTabScrollInputHandler implements IUserInputHandler {
    private final IUserInputHandler delegate;
    private final ScrollHandler scrollHandler;

    public RecipeGuiTabScrollInputHandler(
            IUserInputHandler delegate,
            ScrollHandler scrollHandler) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scrollHandler = Objects.requireNonNull(scrollHandler, "scrollHandler");
    }

    @Override
    public Optional<IUserInputHandler> handleUserInput(
            Screen screen,
            UserInput input,
            IInternalKeyMappings keyBindings) {
        return delegate.handleUserInput(screen, input, keyBindings);
    }

    @Override
    public void unfocus() {
        delegate.unfocus();
    }

    @Override
    public Optional<IUserInputHandler> handleMouseScrolled(
            double mouseX,
            double mouseY,
            double scrollDelta) {
        if (scrollHandler.handle(mouseX, mouseY, scrollDelta)) {
            return Optional.of(this);
        }

        return delegate.handleMouseScrolled(mouseX, mouseY, scrollDelta);
    }

    @FunctionalInterface
    public interface ScrollHandler {
        boolean handle(
                double mouseX,
                double mouseY,
                double scrollDelta);
    }
}
