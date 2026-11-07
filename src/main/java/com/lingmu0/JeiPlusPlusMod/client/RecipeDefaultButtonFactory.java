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

/** EMI-style toggle for choosing the preferred recipe for every output. */
public final class RecipeDefaultButtonFactory implements IRecipeButtonControllerFactory {
    private final IDrawable blankIcon;

    public RecipeDefaultButtonFactory(IGuiHelper guiHelper) {
        this.blankIcon = guiHelper.createBlankDrawable(10, 10);
    }

    @Override
    @Nullable
    public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
        return RecipeTreeData.snapshot(layout)
                .<IIconButtonController>map(snapshot -> new Controller(snapshot, blankIcon))
                .orElse(null);
    }

    private static final class Controller implements IIconButtonController {
        private final RecipeTreeData.RecipeSnapshot recipe;
        private final IDrawable icon;

        private Controller(RecipeTreeData.RecipeSnapshot recipe, IDrawable icon) {
            this.recipe = recipe;
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
            RecipeTreeDefaults.toggle(recipe);
            return true;
        }

        @Override
        public void initState(IButtonState state) {
            state.setIcon(icon);
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            RecipeTreeDefaults.Status status = RecipeTreeDefaults.getStatus(recipe);
            state.setActive(JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get());
            state.setForcePressed(status == RecipeTreeDefaults.Status.FULL);
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            RecipeTreeDefaults.Status status = RecipeTreeDefaults.getStatus(recipe);
            String key = switch (status) {
                case FULL -> "jei_plus_plus.recipe_tree.default.unset";
                case PARTIAL -> "jei_plus_plus.recipe_tree.default.partial";
                case EMPTY -> "jei_plus_plus.recipe_tree.default.set";
            };
            tooltip.add(Component.translatable(key));
        }

        @Override
        public void drawExtras(GuiGraphics graphics, Rect2i area, int mouseX, int mouseY, float partialTicks) {
            int x = area.getX() + Math.max(0, (area.getWidth() - 10 + 1) / 2);
            int y = area.getY() + Math.max(0, (area.getHeight() - 10 + 1) / 2);
            RecipeTreeIcons.drawDefault(graphics, x, y);
        }
    }
}
