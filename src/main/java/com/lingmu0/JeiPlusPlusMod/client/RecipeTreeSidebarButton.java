package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.input.UserInput;
import mezz.jei.gui.elements.GuiIconToggleButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** JEI 15 native-style sidebar toggle used beside its bookmark button. */
public final class RecipeTreeSidebarButton extends GuiIconToggleButton {
    public RecipeTreeSidebarButton() {
        super(new TreeIcon(), new TreeIcon());
    }

    @Override
    protected void getTooltips(JeiTooltip tooltip) {
        tooltip.add(Component.translatable("jei_plus_plus.recipe_tree.global_button"));
        if (RecipeTreeSession.tree() == null) {
            tooltip.add(Component.translatable("jei_plus_plus.recipe_tree.empty_hint"));
        } else {
            tooltip.add(Component.translatable("jei_plus_plus.recipe_tree.global_button.clear"));
        }
    }

    @Override
    protected boolean isIconToggledOn() {
        return RecipeTreeSession.tree() != null;
    }

    @Override
    protected boolean onMouseClicked(UserInput input) {
        if (!JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get()) return false;
        if (!input.isSimulate()) {
            if (Minecraft.getInstance().screen instanceof RecipeTreeScreen treeScreen) {
                treeScreen.onClose();
            } else {
                RecipeTreeScreen.openCurrent(Minecraft.getInstance().screen);
            }
        }
        return true;
    }

    private record TreeIcon() implements IDrawable {
        @Override public int getWidth() { return 18; }
        @Override public int getHeight() { return 18; }
        @Override public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
            RecipeTreeIcons.drawGlobalTree(graphics, xOffset, yOffset);
        }
    }
}
