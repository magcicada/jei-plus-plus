package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.platform.Services;
import mezz.jei.common.util.SafeIngredientUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** EMI-style viewer and crafting assistant for the active JEI recipe tree. */
public final class RecipeTreeScreen extends Screen {
    private static final int RECIPE_NODE_WIDTH = 44;
    private static final int LEAF_NODE_WIDTH = 18;
    private static final int NODE_HEIGHT = 22;
    /** The transfer marker uses the same 7x7 glyph as the candidate marker. */
    private static final int TRANSFER_BUTTON_SIZE = 7;
    private static final int X_SPACING = 54;
    private static final int Y_SPACING = 44;
    private static final int COST_SPACING = 8;
    private static final int INPUT_CHOICE_COLUMNS = 9;
    private static final int INPUT_CHOICE_ROWS = 4;
    private static final int INPUT_CHOICE_PAGE_SIZE = INPUT_CHOICE_COLUMNS * INPUT_CHOICE_ROWS;
    private static final int INPUT_CHOICE_SLOT_SIZE = 20;
    private static final int INPUT_CHOICE_WIDTH = INPUT_CHOICE_COLUMNS * INPUT_CHOICE_SLOT_SIZE + 12;
    private static final int INPUT_CHOICE_HEIGHT = INPUT_CHOICE_ROWS * INPUT_CHOICE_SLOT_SIZE + 48;

    private final Screen parent;
    private final List<RecipeTreeData.Node> visibleNodes = new ArrayList<>();
    private final List<RenderCost> costs = new ArrayList<>();
    private float zoom = 1.0f;
    private float offsetX;
    private float offsetY;
    private boolean dragging;
    private int maxDepth;
    private int costY;
    private int costCenterX;
    private int modeX;
    private int modeY;
    private int batchX;
    private int batchY;
    private int batchWidth;
    private int refreshTicks;
    private RecipeTreeData.Node inputChoiceNode;
    private int inputChoicePage;

    private RecipeTreeScreen(Screen parent) {
        super(Component.translatable("jei_plus_plus.recipe_tree.title"));
        this.parent = parent;
    }

    public static void openFromRecipeButton(IRecipeLayoutDrawable<?> layout) {
        if (!JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get()) {
            return;
        }
        if (RecipeTreeSession.canResolve(layout) && RecipeTreeSession.resolve(layout)) {
            returnToTree();
            return;
        }
        if (RecipeTreeSession.setGoal(layout)) {
            openCurrent(Minecraft.getInstance().screen);
        }
    }

    public static void openCurrent(Screen parent) {
        if (JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get()) {
            RecipeTreeScreen screen = new RecipeTreeScreen(parent);
            Minecraft.getInstance().setScreen(screen);
            // Screen dimensions are available after setScreen; repeat the fit
            // here so every newly opened view starts fully visible even when
            // it was opened from an existing JEI overlay.
            screen.rebuildLayout(true);
        }
    }

    public static void returnToTree() {
        Screen parent = RecipeTreeSession.takeResolutionParent();
        if (parent instanceof RecipeTreeScreen) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            openCurrent(Minecraft.getInstance().screen);
        }
    }

    /** The JEI/container screen underneath this tree, if one exists. */
    public Screen parentScreen() {
        return parent;
    }

    @Override
    protected void init() {
        RecipeTreeSession.cancelResolution();
        rebuildLayout(true);
    }

    @Override
    public void tick() {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree != null && tree.craftingMode() && ++refreshTicks >= 10) {
            refreshTicks = 0;
            rebuildCosts(tree.analyze());
            RecipeTreeFavorites.refreshNow();
        }
    }

    private void rebuildLayout(boolean recenter) {
        visibleNodes.clear();
        costs.clear();
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree == null) {
            inputChoiceNode = null;
            costCenterX = 0;
            offsetX = 0;
            offsetY = 0;
            return;
        }

        RecipeTreeData.Analysis analysis = tree.analyze();
        int[] nextLeaf = {0};
        maxDepth = 0;
        layoutNode(tree.root(), 0, nextLeaf);
        costCenterX = tree.root().x();

        costY = (maxDepth + 1) * Y_SPACING + 38;
        int totalWidth = font.width(Component.translatable("jei_plus_plus.recipe_tree.total_cost"));
        modeX = costCenterX + totalWidth / 2 + 6;
        modeY = costY - 22;
        String batches = "x" + tree.batches();
        batchX = tree.root().x() + nodeWidth(tree.root()) / 2 + 6;
        batchY = tree.root().y() - 11;
        batchWidth = font.width(batches) + 12;
        rebuildCosts(analysis);
        if (recenter) {
            fitViewToContent();
        }
    }

    private int layoutNode(RecipeTreeData.Node node, int depth, int[] nextLeaf) {
        maxDepth = Math.max(maxDepth, depth);
        visibleNodes.add(node);
        if (!node.expanded() || node.children().isEmpty()) {
            int x = nextLeaf[0]++ * X_SPACING;
            node.setPosition(x, depth * Y_SPACING);
            return x;
        }

        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (RecipeTreeData.Node child : node.children()) {
            int x = layoutNode(child, depth + 1, nextLeaf);
            first = Math.min(first, x);
            last = Math.max(last, x);
        }
        int x = (first + last) / 2;
        node.setPosition(x, depth * Y_SPACING);
        return x;
    }

    private void rebuildCosts(RecipeTreeData.Analysis analysis) {
        costs.clear();
        addCostRow(analysis.costs(), costY, false);
        addCostRow(analysis.leftovers(), costY + 40, true);
    }

    private void addCostRow(List<RecipeTreeData.Cost> entries, int y, boolean leftover) {
        int x = 0;
        List<RenderCost> row = new ArrayList<>();
        for (RecipeTreeData.Cost cost : entries) {
            String amount = costText(cost, leftover);
            RenderCost renderCost = new RenderCost(cost, x, y, leftover);
            row.add(renderCost);
            int amountWidth = Math.min(16, font.width(amount));
            x += 16 + COST_SPACING + Math.max(0, amountWidth - 8);
        }
        int offset = row.isEmpty() ? 0 : (x - COST_SPACING) / 2;
        for (RenderCost cost : row) {
            costs.add(new RenderCost(cost.cost, cost.x - offset + costCenterX, cost.y, cost.leftover));
        }
    }

    /** Fit the initial view to the complete tree, labels, and cost rows. */
    private void fitViewToContent() {
        if (visibleNodes.isEmpty()) {
            zoom = 1.0f;
            offsetX = 0;
            offsetY = 0;
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (RecipeTreeData.Node node : visibleNodes) {
            int halfWidth = nodeWidth(node) / 2;
            minX = Math.min(minX, node.x() - halfWidth);
            maxX = Math.max(maxX, node.x() + halfWidth);
            minY = Math.min(minY, node.y() - NODE_HEIGHT / 2);
            maxY = Math.max(maxY, node.y() + NODE_HEIGHT / 2);
        }

        int titleWidth = font.width(Component.translatable("jei_plus_plus.recipe_tree.total_cost"));
        minX = Math.min(minX, costCenterX - titleWidth / 2);
        maxX = Math.max(maxX, costCenterX + titleWidth / 2);
        minY = Math.min(minY, costY - 18);
        maxY = Math.max(maxY, costY + 8);
        if (costs.stream().anyMatch(RenderCost::leftover)) {
            int leftoversWidth = font.width(Component.translatable("jei_plus_plus.recipe_tree.leftovers"));
            minX = Math.min(minX, costCenterX - leftoversWidth / 2);
            maxX = Math.max(maxX, costCenterX + leftoversWidth / 2);
            maxY = Math.max(maxY, costY + 48);
        }
        for (RenderCost cost : costs) {
            String text = costText(cost.cost, cost.leftover);
            int textWidth = Math.min(16, font.width(text));
            minX = Math.min(minX, cost.x + 17 - textWidth);
            maxX = Math.max(maxX, cost.x + 17);
            minY = Math.min(minY, cost.y);
            maxY = Math.max(maxY, cost.y + 16);
        }
        minX = Math.min(minX, modeX);
        maxX = Math.max(maxX, modeX + 16);
        minY = Math.min(minY, modeY);
        maxY = Math.max(maxY, modeY + 16);
        minX = Math.min(minX, batchX);
        maxX = Math.max(maxX, batchX + batchWidth + 6);
        minY = Math.min(minY, batchY);
        maxY = Math.max(maxY, batchY + 22);

        float contentPadding = 20.0f;
        float contentWidth = Math.max(1, maxX - minX + contentPadding * 2.0f);
        float contentHeight = Math.max(1, maxY - minY + contentPadding * 2.0f);
        float availableWidth = Math.max(1, width - 24);
        float availableHeight = Math.max(1, height - 24);
        // Very wide/deep trees must still fit on the first frame. Users can
        // zoom back in after opening, but clipping the initial view hides the
        // active root and makes the saved crafting tree hard to recover.
        // Keep compact trees at their natural size. Enlarging a small tree to
        // fill the entire window makes every newly opened view feel displaced.
        zoom = Math.max(0.10f, Math.min(1.0f,
                Math.min(availableWidth / contentWidth, availableHeight / contentHeight)));
        offsetX = -(minX + maxX) / 2.0f;
        offsetY = -(minY + maxY) / 2.0f;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xDD000000);
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree == null) {
            renderWelcome(graphics);
        } else {
            graphics.pose().pushPose();
            // Offsets are expressed in tree coordinates. Applying them after
            // scaling keeps fitting, dragging and hit testing in one system.
            graphics.pose().translate(width / 2.0f, height / 2.0f, 0);
            graphics.pose().scale(zoom, zoom, 1.0f);
            graphics.pose().translate(offsetX, offsetY, 0);

            for (RecipeTreeData.Node node : visibleNodes) {
                if (node.expanded()) {
                    for (RecipeTreeData.Node child : node.children()) {
                        drawConnection(graphics, node, child);
                    }
                }
            }
            for (RecipeTreeData.Node node : visibleNodes) {
                drawNode(graphics, node, mouseX, mouseY);
            }

            graphics.drawCenteredString(
                    font,
                    Component.translatable("jei_plus_plus.recipe_tree.total_cost"),
                    costCenterX,
                    costY - 18,
                    0xFFFFFF);
            if (costs.stream().anyMatch(RenderCost::leftover)) {
                graphics.drawCenteredString(
                        font,
                        Component.translatable("jei_plus_plus.recipe_tree.leftovers"),
                        costCenterX,
                        costY + 22,
                        0xFFFFFF);
            }
            drawModeButton(graphics, tree, mouseX, mouseY);
            drawBatchLabel(graphics, tree, mouseX, mouseY);
            for (RenderCost cost : costs) {
                drawCost(graphics, cost);
            }
            graphics.pose().popPose();
        }

        drawHelp(graphics, mouseX, mouseY);
        if (inputChoiceNode != null) {
            renderInputChoice(graphics, mouseX, mouseY);
        }
        renderHoverTooltip(graphics, mouseX, mouseY);
    }

    private void renderInputChoice(GuiGraphics graphics, int mouseX, int mouseY) {
        List<ItemStack> choices = inputChoices();
        if (choices.isEmpty()) {
            inputChoiceNode = null;
            return;
        }
        int panelX = inputChoiceX();
        int panelY = inputChoiceY();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        graphics.fill(0, 0, width, height, 0x78000000);
        graphics.fill(panelX - 1, panelY - 1, panelX + INPUT_CHOICE_WIDTH + 1,
                panelY + INPUT_CHOICE_HEIGHT + 1, 0xFFE0E0E0);
        graphics.fill(panelX, panelY, panelX + INPUT_CHOICE_WIDTH, panelY + INPUT_CHOICE_HEIGHT, 0xF0101010);
        graphics.drawCenteredString(
                font,
                Component.translatable("jei_plus_plus.recipe_tree.input_choice.title", choices.size()),
                panelX + INPUT_CHOICE_WIDTH / 2,
                panelY + 8,
                0xFFFFFFFF);

        int start = inputChoicePage * INPUT_CHOICE_PAGE_SIZE;
        int end = Math.min(choices.size(), start + INPUT_CHOICE_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int slotX = panelX + 6 + local % INPUT_CHOICE_COLUMNS * INPUT_CHOICE_SLOT_SIZE;
            int slotY = panelY + 24 + local / INPUT_CHOICE_COLUMNS * INPUT_CHOICE_SLOT_SIZE;
            ItemStack choice = choices.get(index);
            boolean hovered = contains(mouseX, mouseY, slotX, slotY, 18, 18);
            boolean selected = inputChoiceNode.explicitChoice()
                    && inputChoiceNode.ingredientKey().equals(RecipeTreeData.ingredientKey(choice));
            int border = selected ? 0xFF55FFAA : (hovered ? 0xFF8099FF : 0xFF707070);
            graphics.fill(slotX, slotY, slotX + 18, slotY + 18, border);
            graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xFF202020);
            ItemStack renderStack = FluidRecipeCompat.copyWithDisplay(choice);
            renderStack.setCount(1);
            if (!FluidRecipeCompat.render(graphics, renderStack, slotX + 1, slotY + 1)) {
                graphics.renderItem(renderStack, slotX + 1, slotY + 1);
            }
        }

        int footerY = panelY + INPUT_CHOICE_HEIGHT - 18;
        int pageCount = inputChoicePageCount();
        drawChoicePageButton(graphics, panelX + 7, footerY, "<", inputChoicePage > 0,
                contains(mouseX, mouseY, panelX + 7, footerY, 16, 14));
        drawChoicePageButton(graphics, panelX + INPUT_CHOICE_WIDTH - 23, footerY, ">",
                inputChoicePage + 1 < pageCount,
                contains(mouseX, mouseY, panelX + INPUT_CHOICE_WIDTH - 23, footerY, 16, 14));
        graphics.drawCenteredString(font, (inputChoicePage + 1) + "/" + pageCount,
                panelX + INPUT_CHOICE_WIDTH / 2, footerY + 3, 0xFFD0D0D0);
        int closeX = panelX + INPUT_CHOICE_WIDTH - 17;
        graphics.drawCenteredString(font, "x", closeX + 6, panelY + 7,
                contains(mouseX, mouseY, closeX, panelY + 4, 12, 12) ? 0xFFFF7777 : 0xFFD0D0D0);
        graphics.pose().popPose();
    }

    private void drawChoicePageButton(
            GuiGraphics graphics,
            int x,
            int y,
            String text,
            boolean enabled,
            boolean hovered) {
        int border = enabled && hovered ? 0xFF8099FF : 0xFF707070;
        graphics.fill(x, y, x + 16, y + 14, border);
        graphics.fill(x + 1, y + 1, x + 15, y + 13, 0xFF202020);
        graphics.drawCenteredString(font, text, x + 8, y + 3, enabled ? 0xFFFFFFFF : 0xFF666666);
    }

    private void renderWelcome(GuiGraphics graphics) {
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 36, 0xFFFFFF);
        graphics.drawCenteredString(
                font,
                Component.translatable("jei_plus_plus.recipe_tree.empty"),
                width / 2,
                height / 2 - 12,
                0xBFD0D0D0);
        graphics.drawCenteredString(
                font,
                Component.translatable("jei_plus_plus.recipe_tree.empty_hint"),
                width / 2,
                height / 2 + 6,
                0xBFD0D0D0);
    }

    private void drawConnection(GuiGraphics graphics, RecipeTreeData.Node parent, RecipeTreeData.Node child) {
        int y1 = parent.y() + NODE_HEIGHT / 2;
        int y2 = child.y() - NODE_HEIGHT / 2;
        int middle = (y1 + y2) / 2;
        int color = lineColor(parent, false);
        graphics.fill(parent.x(), y1, parent.x() + 1, middle + 1, color);
        graphics.fill(child.x(), middle, child.x() + 1, y2 + 1, color);
        graphics.fill(Math.min(parent.x(), child.x()), middle, Math.max(parent.x(), child.x()) + 1, middle + 1, color);
    }

    private void drawNode(GuiGraphics graphics, RecipeTreeData.Node node, int mouseX, int mouseY) {
        int width = nodeWidth(node);
        int left = node.x() - width / 2;
        int top = node.y() - NODE_HEIGHT / 2;
        boolean hovered = hoveredNode(mouseX, mouseY) == node;
        if (node.recipe() != null) {
            int border = node.cycle() ? 0xFFFF5555 : lineColor(node, hovered);
            graphics.fill(left, top, left + width, top + 1, border);
            graphics.fill(left, top + NODE_HEIGHT - 1, left + width, top + NODE_HEIGHT, border);
            graphics.fill(left, top, left + 1, top + NODE_HEIGHT, border);
            graphics.fill(left + width - 1, top, left + width, top + NODE_HEIGHT, border);

            IDrawable icon = node.recipe().ref().category().getIcon();
            if (icon != null) {
                icon.draw(graphics, left + 3, top + 3);
            } else {
                drawCategoryFallback(graphics, left + 3, top + 3, border);
            }
        }

        int itemX = node.recipe() == null ? node.x() - 8 : left + 24;
        int itemY = node.y() - 8;
        ItemStack renderStack = FluidRecipeCompat.copyWithDisplay(node.stack());
        renderStack.setCount(1);
        if (!FluidRecipeCompat.render(graphics, renderStack, itemX, itemY)) {
            graphics.renderItem(renderStack, itemX, itemY);
        }
        drawAmount(graphics, formatNodeAmount(node.stack(), node.amount()), itemX, itemY, amountColor(node));
        if (node.hasAlternatives()) {
            drawAlternativeMarker(graphics, node, itemX, itemY);
        }
        if (node.recipe() != null) {
            drawTransferButton(graphics, node, left, top, mouseX, mouseY);
        }

        if (node.recipe() != null && !node.children().isEmpty()) {
            int markerColor = node.expanded() ? 0xFFFFFFFF : 0xFF80A8FF;
            graphics.fill(node.x() - 1, top + NODE_HEIGHT, node.x() + 2, top + NODE_HEIGHT + 3, markerColor);
        }
    }

    private void drawAlternativeMarker(GuiGraphics graphics, RecipeTreeData.Node node, int itemX, int itemY) {
        int x = itemX + 10;
        int y = itemY - 2;
        int color = node.explicitChoice() ? 0xFF55FFAA : 0xFF80A8FF;
        drawPlusMarker(graphics, x, y, color);
    }

    /** Draws the small plus glyph used by both candidate and transfer actions. */
    private void drawPlusMarker(GuiGraphics graphics, int x, int y, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(x, y, x + 7, y + 7, 0xE0202020);
        graphics.fill(x + 1, y + 3, x + 6, y + 4, color);
        graphics.fill(x + 3, y + 1, x + 4, y + 6, color);
        graphics.pose().popPose();
    }

    private void drawTransferButton(
            GuiGraphics graphics,
            RecipeTreeData.Node node,
            int left,
            int top,
            int mouseX,
            int mouseY) {
        // The transfer action belongs to the work-block/category icon, not
        // to the output item area on the right side of the recipe node.
        int x = left + 2 + 18 - TRANSFER_BUTTON_SIZE - 1;
        int y = top + 2 + 18 - TRANSFER_BUTTON_SIZE - 1;
        // Keep this visually identical to the candidate-selection marker.
        drawPlusMarker(graphics, x, y, 0xFF80A8FF);
    }

    private void drawCategoryFallback(GuiGraphics graphics, int x, int y, int color) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                graphics.fill(x + column * 5, y + row * 5, x + column * 5 + 3, y + row * 5 + 3, color);
            }
        }
    }

    private void drawModeButton(GuiGraphics graphics, RecipeTreeData.Tree tree, int mouseX, int mouseY) {
        int[] treeMouse = treeMouse(mouseX, mouseY);
        boolean hovered = containsTreeArea(treeMouse[0], treeMouse[1], modeX, modeY, 16, 16);
        int color = hovered ? 0xFF8099FF : 0xFFFFFFFF;
        graphics.fill(modeX + 2, modeY + 11, modeX + 13, modeY + 13, color);
        graphics.fill(modeX + 4, modeY + 8, modeX + 7, modeY + 13, color);
        graphics.fill(modeX + 7, modeY + 4, modeX + 10, modeY + 11, color);
        graphics.fill(modeX + 9, modeY + 2, modeX + 14, modeY + 5, color);
        if (tree.craftingMode()) {
            graphics.fill(modeX + 1, modeY + 1, modeX + 3, modeY + 7, 0xFF30CC88);
            graphics.fill(modeX + 2, modeY + 5, modeX + 5, modeY + 7, 0xFF30CC88);
        }
    }

    private void drawBatchLabel(GuiGraphics graphics, RecipeTreeData.Tree tree, int mouseX, int mouseY) {
        int[] treeMouse = treeMouse(mouseX, mouseY);
        int color = containsTreeArea(treeMouse[0], treeMouse[1], batchX, batchY, batchWidth, 22)
                ? 0xFF8099FF
                : 0xFFFFFFFF;
        graphics.drawString(font, "x" + tree.batches(), batchX + 6, batchY + 7, color, true);
    }

    private void drawCost(GuiGraphics graphics, RenderCost renderCost) {
        ItemStack stack = FluidRecipeCompat.copyWithDisplay(renderCost.cost.stack());
        stack.setCount(1);
        if (!FluidRecipeCompat.render(graphics, stack, renderCost.x, renderCost.y)) {
            graphics.renderItem(stack, renderCost.x, renderCost.y);
        }
        drawAmount(
                graphics,
                costText(renderCost.cost, renderCost.leftover),
                renderCost.x,
                renderCost.y,
                renderCost.leftover ? 0xFFFFFFFF : costColor(renderCost.cost));
    }

    private void drawAmount(GuiGraphics graphics, String text, int itemX, int itemY, int color) {
        if (text.isEmpty()) {
            return;
        }
        int textWidth = Math.max(1, font.width(text));
        float scale = Math.min(1.0f, 16.0f / textWidth);
        graphics.pose().pushPose();
        graphics.pose().translate(itemX + 17, itemY + 9, 300);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(font, text, -textWidth, 0, color, true);
        graphics.pose().popPose();
    }

    private void drawHelp(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = width - 18;
        int y = height - 18;
        int color = contains(mouseX, mouseY, x, y, 16, 16) ? 0xFF8099FF : 0xFFFFFFFF;
        graphics.drawCenteredString(font, "?", x + 8, y + 4, color);
        if (contains(mouseX, mouseY, x, y, 16, 16)) {
            graphics.renderTooltip(
                    font,
                    font.split(Component.translatable("jei_plus_plus.recipe_tree.help"), Math.min(280, width - 30)),
                    mouseX,
                    mouseY);
        }
    }

    private void renderHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inputChoiceNode != null) {
            ItemStack choice = hoveredInputChoice(mouseX, mouseY);
            if (!choice.isEmpty()) {
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 700);
                if (!FluidRecipeCompat.renderTooltip(graphics, choice, mouseX, mouseY)) {
                    graphics.renderTooltip(font, choice, mouseX, mouseY);
                }
                graphics.pose().popPose();
            }
            return;
        }
        RecipeTreeData.Node node = hoveredNode(mouseX, mouseY);
        if (node != null) {
            if (isTransferArea(node, mouseX, mouseY)) {
                graphics.renderTooltip(
                        font,
                        Component.translatable("jei_plus_plus.recipe_tree.transfer"),
                        mouseX,
                        mouseY);
            } else if (node.isOutputChoice() && isAlternativeArea(node, mouseX, mouseY)) {
                graphics.renderTooltip(
                        font,
                        Component.translatable("jei_plus_plus.recipe_tree.output_choice", node.alternatives().size()),
                        mouseX,
                        mouseY);
            } else if (isAlternativeArea(node, mouseX, mouseY)) {
                String key = node.explicitChoice()
                        ? "jei_plus_plus.recipe_tree.input_choice.selected"
                        : "jei_plus_plus.recipe_tree.input_choice";
                graphics.renderTooltip(font, Component.translatable(key, node.alternatives().size()), mouseX, mouseY);
            } else if ((hasUnfixedAlternatives(node) || node.isOutputChoice()) && isItemArea(node, mouseX, mouseY)) {
                renderAlternativeTooltip(graphics, node, mouseX, mouseY);
            } else if (isCategoryArea(node, mouseX, mouseY) && node.recipe() != null) {
                graphics.renderTooltip(font, node.recipe().ref().category().getTitle(), mouseX, mouseY);
            } else {
                if (!FluidRecipeCompat.renderTooltip(graphics, node.stack(), mouseX, mouseY)) {
                    graphics.renderTooltip(font, node.stack(), mouseX, mouseY);
                }
            }
            return;
        }
        int[] treeMouse = treeMouse(mouseX, mouseY);
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree != null && containsTreeArea(treeMouse[0], treeMouse[1], batchX, batchY, batchWidth, 22)) {
            graphics.renderTooltip(font, Component.translatable("jei_plus_plus.recipe_tree.batch", tree.batches()),
                    mouseX, mouseY);
        } else if (tree != null && containsTreeArea(treeMouse[0], treeMouse[1], modeX, modeY, 16, 16)) {
            String key = tree.craftingMode()
                    ? "jei_plus_plus.recipe_tree.mode.craft"
                    : "jei_plus_plus.recipe_tree.mode.view";
            graphics.renderTooltip(font, Component.translatable(key), mouseX, mouseY);
        } else {
            for (RenderCost cost : costs) {
                if (containsTreeArea(treeMouse[0], treeMouse[1], cost.x, cost.y, 16, 16)) {
                    if (!FluidRecipeCompat.renderTooltip(graphics, cost.cost.stack(), mouseX, mouseY)) {
                        graphics.renderTooltip(font, cost.cost.stack(), mouseX, mouseY);
                    }
                    return;
                }
            }
        }
    }

    /** Render the same rich tag/candidate tooltip used by JEI recipe slots. */
    private void renderAlternativeTooltip(
            GuiGraphics graphics,
            RecipeTreeData.Node node,
            int mouseX,
            int mouseY) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || node == null) {
            if (node == null || !FluidRecipeCompat.renderTooltip(graphics, node.stack(), mouseX, mouseY)) {
                graphics.renderTooltip(font, node == null ? ItemStack.EMPTY : node.stack(), mouseX, mouseY);
            }
            return;
        }

        List<ItemStack> alternatives = node.alternatives().stream()
                .map(stack -> {
                    ItemStack copy = FluidRecipeCompat.copyWithDisplay(stack);
                    copy.setCount(1);
                    return copy;
                })
                .toList();
        ItemStack displayed = FluidRecipeCompat.copyWithDisplay(node.stack());
        displayed.setCount(1);
        if (FluidRecipeCompat.renderTooltip(graphics, displayed, mouseX, mouseY)) {
            return;
        }
        var typed = runtime.getIngredientManager()
                .createTypedIngredient(VanillaTypes.ITEM_STACK, displayed);
        if (typed.isEmpty()) {
            graphics.renderTooltip(font, displayed, mouseX, mouseY);
            return;
        }

        IIngredientRenderer<ItemStack> renderer = runtime.getIngredientManager()
                .getIngredientRenderer(VanillaTypes.ITEM_STACK);
        IIngredientHelper<ItemStack> helper = runtime.getIngredientManager()
                .getIngredientHelper(VanillaTypes.ITEM_STACK);
        JeiTooltip tooltip = new JeiTooltip();
        addSafeIngredientTooltip(tooltip, runtime, renderer, typed.get());
        helper.getTagKeyEquivalent(alternatives).ifPresent(tagKey -> {
            tooltip.add(Component.translatable("jei.tooltip.recipe.tag", "")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Services.PLATFORM.getRenderHelper().getName(tagKey)
                    .copy().withStyle(ChatFormatting.GRAY));
        });
        if (alternatives.size() > 1) {
            JeiTooltipCompat.createTagContent(runtime, renderer, alternatives)
                    .ifPresent(tooltip::add);
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 700);
        tooltip.draw(graphics, mouseX, mouseY);
        graphics.pose().popPose();
    }

    /** JEI 15.48 renamed getTooltip to getRichTooltip without changing its arguments. */
    private static void addSafeIngredientTooltip(
            JeiTooltip tooltip,
            IJeiRuntime runtime,
            IIngredientRenderer<ItemStack> renderer,
            ITypedIngredient<ItemStack> typed) {
        for (String name : List.of("getRichTooltip", "getTooltip")) {
            for (Method method : SafeIngredientUtil.class.getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 4) {
                    continue;
                }
                try {
                    method.invoke(null, tooltip, runtime.getIngredientManager(), renderer, typed);
                    return;
                } catch (IllegalAccessException ignored) {
                    // Try the other stable method name below.
                } catch (InvocationTargetException exception) {
                    return;
                }
            }
        }
    }

    private RecipeTreeData.Node hoveredNode(double mouseX, double mouseY) {
        int[] point = treeMouse(mouseX, mouseY);
        for (RecipeTreeData.Node node : visibleNodes) {
            int width = nodeWidth(node);
            if (containsTreeArea(point[0], point[1], node.x() - width / 2, node.y() - NODE_HEIGHT / 2, width,
                    NODE_HEIGHT)) {
                return node;
            }
        }
        return null;
    }

    private boolean isCategoryArea(RecipeTreeData.Node node, double mouseX, double mouseY) {
        if (node.recipe() == null) {
            return false;
        }
        int[] point = treeMouse(mouseX, mouseY);
        int left = node.x() - nodeWidth(node) / 2;
        return containsTreeArea(point[0], point[1], left + 2, node.y() - 9, 18, 18);
    }

    private boolean isAlternativeArea(RecipeTreeData.Node node, double mouseX, double mouseY) {
        if (!node.hasAlternatives()) {
            return false;
        }
        int[] point = treeMouse(mouseX, mouseY);
        int width = nodeWidth(node);
        int left = node.x() - width / 2;
        int itemX = node.recipe() == null ? node.x() - 8 : left + 24;
        int itemY = node.y() - 8;
        return containsTreeArea(point[0], point[1], itemX + 10, itemY - 2, 7, 7);
    }

    private boolean isTransferArea(RecipeTreeData.Node node, double mouseX, double mouseY) {
        if (node == null || node.recipe() == null) {
            return false;
        }
        int[] point = treeMouse(mouseX, mouseY);
        int width = nodeWidth(node);
        int left = node.x() - width / 2;
        int top = node.y() - NODE_HEIGHT / 2;
        int x = left + 2 + 18 - TRANSFER_BUTTON_SIZE - 1;
        int y = top + 2 + 18 - TRANSFER_BUTTON_SIZE - 1;
        return containsTreeArea(point[0], point[1], x, y, TRANSFER_BUTTON_SIZE, TRANSFER_BUTTON_SIZE);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // JEI draws the global tree toggle on top of this screen. The normal
        // JEI input handler is not reached when Screen.mouseClicked consumes
        // the event, so handle the same hit box here as well.
        if (isGlobalTreeButton(mouseX, mouseY)) {
            if (button == 1) {
                RecipeTreeSession.clear();
                onClose();
                return true;
            }
            if (button == 0) {
                onClose();
                return true;
            }
        }
        if (inputChoiceNode != null) {
            return handleInputChoiceClick(mouseX, mouseY, button);
        }
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        int[] point = treeMouse(mouseX, mouseY);
        if (tree != null && containsTreeArea(point[0], point[1], modeX, modeY, 16, 16)) {
            RecipeTreeSession.setCraftingMode(!tree.craftingMode());
            rebuildLayout(false);
            RecipeTreeFavorites.refreshNow();
            return true;
        }
        if (tree != null && containsTreeArea(point[0], point[1], batchX, batchY, batchWidth, 22)) {
            tree.setBatches(tree.idealBatch());
            RecipeTreeSession.savePersistentState();
            rebuildLayout(false);
            RecipeTreeFavorites.refreshNow();
            return true;
        }

        RecipeTreeData.Node node = hoveredNode(mouseX, mouseY);
        if (node != null) {
            if (button == 0 && isTransferArea(node, mouseX, mouseY)) {
                boolean maxTransfer = hasShiftDown();
                tree.directTransferStep(node).ifPresent(step -> {
                    RecipeTreeTransfer.transfer(step, false, maxTransfer);
                });
                // JEI closes its recipe GUI after pressing +. Keep the same
                // behavior even when the node has no transferable inputs or
                // the handler reports an error.
                if (Minecraft.getInstance().screen == this) {
                    onClose();
                }
                return true;
            }
            if (isAlternativeArea(node, mouseX, mouseY)) {
                if (button == 0) {
                    chooseInput(node);
                } else if (button == 1) {
                    RecipeTreeSession.clearInputSelection(node);
                    rebuildLayout(false);
                }
                return true;
            }
            if (button == 1) {
                if (hasShiftDown()) {
                    RecipeTreeSession.clearResolution(node);
                    rebuildLayout(false);
                } else if (!node.children().isEmpty()) {
                    node.setExpanded(!node.expanded());
                    rebuildLayout(false);
                }
                return true;
            }
            if (button == 0) {
                if (!hasShiftDown() && hasUnfixedAlternatives(node)
                        && !node.isOutputChoice() && isItemArea(node, mouseX, mouseY)) {
                    showCandidateDirectory(node);
                } else if (isCategoryArea(node, mouseX, mouseY) && node.recipe() != null) {
                    showExactRecipe(node);
                } else if (hasShiftDown()) {
                    // Shift-clicking a canvas node only resolves its first
                    // non-excluded candidate. Recursive crafting is
                    // deliberately owned by the synthetic recipe bookmark.
                    if (RecipeTreeSession.autoResolve(node)) {
                        rebuildLayout(false);
                    }
                } else {
                    chooseRecipe(node);
                }
                return true;
            }
        }
        if (button == 0 || button == 2) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isGlobalTreeButton(double mouseX, double mouseY) {
        return mouseX >= 6 && mouseX < 26
                && mouseY >= height - 26 && mouseY < height - 6;
    }

    private void chooseInput(RecipeTreeData.Node node) {
        if (node == null || !node.hasAlternatives()) {
            return;
        }
        inputChoiceNode = node;
        inputChoicePage = 0;
        if (node.explicitChoice()) {
            for (int index = 0; index < node.alternatives().size(); index++) {
                if (node.ingredientKey().equals(RecipeTreeData.ingredientKey(node.alternatives().get(index)))) {
                    inputChoicePage = index / INPUT_CHOICE_PAGE_SIZE;
                    break;
                }
            }
        }
    }

    private boolean hasUnfixedAlternatives(RecipeTreeData.Node node) {
        return node != null && node.hasAlternatives() && !node.explicitChoice();
    }

    private boolean isItemArea(RecipeTreeData.Node node, double mouseX, double mouseY) {
        int[] point = treeMouse(mouseX, mouseY);
        int width = nodeWidth(node);
        int left = node.x() - width / 2;
        int itemX = node.recipe() == null ? node.x() - 8 : left + 24;
        int itemY = node.y() - 8;
        return containsTreeArea(point[0], point[1], itemX, itemY, 16, 16);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void showCandidateDirectory(RecipeTreeData.Node node) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || node == null) {
            return;
        }
        List<mezz.jei.api.ingredients.ITypedIngredient<?>> typed = new ArrayList<>();
        for (ItemStack alternative : node.alternatives()) {
            ItemStack stack = FluidRecipeCompat.copyWithDisplay(alternative);
            stack.setCount(1);
            FluidRecipeCompat.toTyped(runtime.getIngredientManager(), stack)
                    .ifPresent(typed::add);
        }
        DirectoryViewer.show(runtime.getRecipesGui(), typed);
    }

    private boolean handleInputChoiceClick(double mouseX, double mouseY, int button) {
        if (button == 1) {
            inputChoiceNode = null;
            return true;
        }
        if (button != 0) {
            return true;
        }
        int panelX = inputChoiceX();
        int panelY = inputChoiceY();
        int closeX = panelX + INPUT_CHOICE_WIDTH - 17;
        if (contains(mouseX, mouseY, closeX, panelY + 4, 12, 12)) {
            inputChoiceNode = null;
            return true;
        }

        int choiceIndex = hoveredInputChoiceIndex(mouseX, mouseY);
        List<ItemStack> choices = inputChoices();
        if (choiceIndex >= 0 && choiceIndex < choices.size()) {
            RecipeTreeData.Node selectedNode = inputChoiceNode;
            ItemStack selected = choices.get(choiceIndex);
            inputChoiceNode = null;
            if (RecipeTreeSession.selectInput(selectedNode, selected)) {
                rebuildLayout(false);
            }
            return true;
        }

        int footerY = panelY + INPUT_CHOICE_HEIGHT - 18;
        if (contains(mouseX, mouseY, panelX + 7, footerY, 16, 14)) {
            setInputChoicePage(inputChoicePage - 1);
        } else if (contains(mouseX, mouseY, panelX + INPUT_CHOICE_WIDTH - 23, footerY, 16, 14)) {
            setInputChoicePage(inputChoicePage + 1);
        } else if (!contains(mouseX, mouseY, panelX, panelY, INPUT_CHOICE_WIDTH, INPUT_CHOICE_HEIGHT)) {
            inputChoiceNode = null;
        }
        return true;
    }

    private List<ItemStack> inputChoices() {
        return inputChoiceNode == null ? List.of() : inputChoiceNode.alternatives();
    }

    private ItemStack hoveredInputChoice(double mouseX, double mouseY) {
        int index = hoveredInputChoiceIndex(mouseX, mouseY);
        List<ItemStack> choices = inputChoices();
        return index >= 0 && index < choices.size() ? choices.get(index) : ItemStack.EMPTY;
    }

    private int hoveredInputChoiceIndex(double mouseX, double mouseY) {
        int localX = (int) mouseX - inputChoiceX() - 6;
        int localY = (int) mouseY - inputChoiceY() - 24;
        if (localX < 0 || localY < 0
                || localX >= INPUT_CHOICE_COLUMNS * INPUT_CHOICE_SLOT_SIZE
                || localY >= INPUT_CHOICE_ROWS * INPUT_CHOICE_SLOT_SIZE) {
            return -1;
        }
        int column = localX / INPUT_CHOICE_SLOT_SIZE;
        int row = localY / INPUT_CHOICE_SLOT_SIZE;
        if (localX % INPUT_CHOICE_SLOT_SIZE >= 18 || localY % INPUT_CHOICE_SLOT_SIZE >= 18) {
            return -1;
        }
        return inputChoicePage * INPUT_CHOICE_PAGE_SIZE + row * INPUT_CHOICE_COLUMNS + column;
    }

    private int inputChoicePageCount() {
        return Math.max(1, (inputChoices().size() + INPUT_CHOICE_PAGE_SIZE - 1) / INPUT_CHOICE_PAGE_SIZE);
    }

    private void setInputChoicePage(int page) {
        inputChoicePage = Math.max(0, Math.min(inputChoicePageCount() - 1, page));
    }

    private int inputChoiceX() {
        return (width - INPUT_CHOICE_WIDTH) / 2;
    }

    private int inputChoiceY() {
        return (height - INPUT_CHOICE_HEIGHT) / 2;
    }

    private void chooseRecipe(RecipeTreeData.Node node) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || node.stack().isEmpty()) {
            return;
        }
        Optional<? extends IFocus<?>> fluidFocus = FluidRecipeCompat.createOutputFocus(runtime, node.stack());
        Optional<IFocus<ItemStack>> itemFocus = fluidFocus.isPresent()
                ? Optional.empty()
                : RecipeTreeData.createOutputFocus(runtime, node.stack());
        IFocus<?> focus = fluidFocus.isPresent()
                ? fluidFocus.get()
                : itemFocus.orElse(null);
        if (focus == null) {
            return;
        }
        RecipeTreeSession.beginResolution(node, this);
        runtime.getRecipesGui().show(List.of(focus));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void showExactRecipe(RecipeTreeData.Node node) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || node.recipe() == null) {
            return;
        }
        runtime.getRecipesGui().showRecipes(
                (mezz.jei.api.recipe.category.IRecipeCategory) node.recipe().ref().category(),
                List.of(node.recipe().ref().recipe()),
                List.<IFocus<?>>of());
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        if (inputChoiceNode != null) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (inputChoiceNode != null) {
            return true;
        }
        if (dragging && (button == 0 || button == 2)) {
            offsetX += (float) (dragX / zoom);
            offsetY += (float) (dragY / zoom);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (inputChoiceNode != null && scrollY != 0) {
            setInputChoicePage(inputChoicePage + (scrollY < 0 ? 1 : -1));
            return true;
        }
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        int[] point = treeMouse(mouseX, mouseY);
        if (tree != null && containsTreeArea(point[0], point[1], batchX, batchY, batchWidth, 22) && scrollY != 0) {
            long adjustment;
            if (hasControlDown()) {
                adjustment = scrollY > 0 ? tree.batches() : -Math.max(1, tree.batches() / 2);
            } else {
                adjustment = scrollY > 0 ? 1 : -1;
                if (hasShiftDown()) {
                    adjustment *= 16;
                }
            }
            tree.setBatches(tree.batches() + adjustment);
            RecipeTreeSession.savePersistentState();
            rebuildLayout(false);
            RecipeTreeFavorites.refreshNow();
            return true;
        }
        if (scrollY != 0) {
            float oldZoom = zoom;
            float newZoom = Math.max(0.10f, Math.min(2.5f, zoom + (scrollY > 0 ? 0.1f : -0.1f)));
            if (newZoom != oldZoom) {
                // Preserve the tree coordinate under the cursor while zooming.
                float treeX = (float) ((mouseX - width / 2.0) / oldZoom - offsetX);
                float treeY = (float) ((mouseY - height / 2.0) / oldZoom - offsetY);
                zoom = newZoom;
                offsetX = (float) ((mouseX - width / 2.0) / newZoom - treeX);
                offsetY = (float) ((mouseY - height / 2.0) / newZoom - treeY);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inputChoiceNode != null && keyCode == 256) {
            inputChoiceNode = null;
            return true;
        }
        if (keyCode == 256 || Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == 82) {
            rebuildLayout(true);
            return true;
        }
        if (hasControlDown() && keyCode == 67) {
            RecipeTreeSession.clear();
            rebuildLayout(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        RecipeTreeSession.cancelResolution();
        RecipeTreeSession.restoreCraftingTree();
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int[] treeMouse(double mouseX, double mouseY) {
        return new int[]{
                (int) ((mouseX - width / 2.0) / zoom - offsetX),
                (int) ((mouseY - height / 2.0) / zoom - offsetY)
        };
    }

    private static int nodeWidth(RecipeTreeData.Node node) {
        return node.recipe() == null ? LEAF_NODE_WIDTH : RECIPE_NODE_WIDTH;
    }

    private static int lineColor(RecipeTreeData.Node node, boolean hovered) {
        if (hovered) {
            return 0xFF8099FF;
        }
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree != null && tree.craftingMode()) {
            return switch (node.progress()) {
                case COMPLETED -> 0xFF30CC88;
                case PARTIAL -> 0xFFCC44DD;
                case UNSTARTED -> 0xFFFFFFFF;
            };
        }
        return 0xFFFFFFFF;
    }

    private static int amountColor(RecipeTreeData.Node node) {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree != null && tree.craftingMode()) {
            return switch (node.progress()) {
                case COMPLETED -> 0xFF55FFAA;
                case PARTIAL -> 0xFFFF66FF;
                case UNSTARTED -> 0xFFFFFFFF;
            };
        }
        return 0xFFFFFFFF;
    }

    private static int costColor(RecipeTreeData.Cost cost) {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree == null || !tree.craftingMode()) {
            return 0xFFFFFFFF;
        }
        boolean fluid = FluidRecipeCompat.treeFluid(cost.stack()).isPresent();
        long required = fluid
                ? FluidRecipeCompat.amountForUnits(cost.stack(), cost.required())
                : cost.required();
        return cost.supplied() >= required ? 0xFF55FFAA : 0xFFFF5555;
    }

    private static String costText(RecipeTreeData.Cost cost, boolean leftover) {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        boolean fluid = FluidRecipeCompat.treeFluid(cost.stack()).isPresent();
        if (!leftover && tree != null && tree.craftingMode()) {
            String supplied = fluid
                    ? FluidRecipeCompat.formatAmount(cost.supplied())
                    : quantityText(cost.stack(), cost.supplied(), false);
            return supplied
                    + "/" + quantityText(cost.stack(), cost.required(), fluid);
        }
        return !fluid && cost.required() == 1
                ? ""
                : quantityText(cost.stack(), cost.required(), fluid);
    }

    private static String formatNodeAmount(ItemStack stack, long amount) {
        boolean fluid = FluidRecipeCompat.treeFluid(stack).isPresent();
        if (!fluid && amount == 1) {
            return "";
        }
        return quantityText(stack, amount, fluid);
    }

    private static String quantityText(ItemStack stack, long units, boolean fluid) {
        if (fluid) {
            return FluidRecipeCompat.formatAmount(FluidRecipeCompat.amountForUnits(stack, units));
        }
        return formatNumber(units);
    }

    private static String formatNumber(long amount) {
        if (amount >= 1_000_000_000L) {
            return trim(amount / 1_000_000_000.0) + "B";
        }
        if (amount >= 1_000_000L) {
            return trim(amount / 1_000_000.0) + "M";
        }
        if (amount >= 10_000L) {
            return trim(amount / 1_000.0) + "K";
        }
        return Long.toString(amount);
    }

    private static String trim(double value) {
        return value >= 100
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value).replace(".0", "");
    }

    private static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static boolean containsTreeArea(int mouseX, int mouseY, int x, int y, int width, int height) {
        return contains(mouseX, mouseY, x, y, width, height);
    }

    private record RenderCost(RecipeTreeData.Cost cost, int x, int y, boolean leftover) {
    }
}
