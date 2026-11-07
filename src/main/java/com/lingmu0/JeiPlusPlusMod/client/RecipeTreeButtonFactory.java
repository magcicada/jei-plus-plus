package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Adds an item-free tree glyph next to each real JEI recipe. */
public final class RecipeTreeButtonFactory implements IRecipeButtonControllerFactory {
    private final IDrawable blankIcon;

    public RecipeTreeButtonFactory(IGuiHelper guiHelper) {
        this.blankIcon = guiHelper.createBlankDrawable(10, 10);
    }

    @Override
    @Nullable
    public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
        if (!RecipeTreeData.isSupported(layout)) {
            return null;
        }
        return new Controller<>(layout, blankIcon);
    }

    private static final class Controller<T> implements IIconButtonController {
        private final IRecipeLayoutDrawable<T> layout;
        private final IDrawable icon;

        private Controller(IRecipeLayoutDrawable<T> layout, IDrawable icon) {
            this.layout = layout;
            this.icon = icon;
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            if (!JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get()) {
                return false;
            }
            if (input.isSimulate()) {
                return true;
            }
            RecipeTreeScreen.openFromRecipeButton(layout);
            return true;
        }

        @Override
        public void initState(IButtonState state) {
            state.setIcon(icon);
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            state.setActive(JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get());
            state.setForcePressed(RecipeTreeSession.isCurrentResolution(layout));
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            String key = RecipeTreeSession.isCurrentResolution(layout)
                    ? "jei_plus_plus.recipe_tree.clear_resolution_button"
                    : (RecipeTreeSession.canResolve(layout)
                            ? "jei_plus_plus.recipe_tree.resolve_button"
                            : "jei_plus_plus.recipe_tree.button");
            tooltip.add(Component.translatable(key));
        }

        @Override
        public void drawExtras(GuiGraphics graphics, Rect2i area, int mouseX, int mouseY, float partialTicks) {
            int x = area.getX() + Math.max(0, (area.getWidth() - 10 + 1) / 2);
            int y = area.getY() + Math.max(0, (area.getHeight() - 10 + 1) / 2);
            RecipeTreeIcons.drawTree(graphics, x, y);
        }
    }
}
