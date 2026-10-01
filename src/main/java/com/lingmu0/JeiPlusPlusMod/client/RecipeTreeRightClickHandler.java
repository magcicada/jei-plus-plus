package com.lingmu0.JeiPlusPlusMod.client;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.input.IUserInputHandler;
import mezz.jei.common.input.UserInput;
import net.minecraft.client.gui.screens.Screen;

import java.util.Objects;
import java.util.Optional;

/**
 * Handles right-clicks on the recipe-tree sidebar button.
 *
 * <p>
 * This is deliberately a normal JEI input handler instead of an inner
 * class of {@code BookmarkOverlayMixin}. Mixin inner classes are treated as
 * part of the mixin class structure, which causes Mixin tooling to reject or
 * warn about ordinary non-mixin inner classes. Keeping the handler separate
 * also avoids an implicit reference to the whole BookmarkOverlay instance.
 * </p>
 */
public final class RecipeTreeRightClickHandler implements IUserInputHandler {
    private static final int RIGHT_MOUSE_BUTTON = 1;

    private final RecipeTreeSidebarButton treeButton;

    public RecipeTreeRightClickHandler(RecipeTreeSidebarButton treeButton) {
        this.treeButton = Objects.requireNonNull(treeButton, "treeButton");
    }

    @Override
    public Optional<IUserInputHandler> handleUserInput(
            Screen screen,
            UserInput input,
            IInternalKeyMappings keyBindings) {
        if (input.getKey().getType() != InputConstants.Type.MOUSE
                || input.getKey().getValue() != RIGHT_MOUSE_BUTTON
                || !treeButton.isMouseOver(input.getMouseX(), input.getMouseY())) {
            return Optional.empty();
        }

        if (!input.isSimulate()) {
            RecipeTreeSession.clear();
            if (screen instanceof RecipeTreeScreen treeScreen) {
                treeScreen.onClose();
            }
        }

        return Optional.of(this);
    }
}
