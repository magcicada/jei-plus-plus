package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.common.input.IUserInputHandler;
import mezz.jei.common.input.UserInput;
import mezz.jei.common.util.ImmutableRect2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/** Compact creative-tab strip rendered over the top of JEI's ingredient list. */
public final class CreativeTabBar {
    private static final int HEIGHT = 18;
    private static final int RESERVED_HEIGHT = HEIGHT + 1;
    /** JEI's ingredient slot is 18x18; navigation controls use the same size. */
    private static final int SLOT_SIZE = 18;
    private static final int MAX_VISIBLE_TABS = 12;

    private CreativeTabBar() {
    }

    /** Space reserved above the ingredient grid for the tab strip. */
    public static int getReservedHeight() {
        return RESERVED_HEIGHT;
    }

    public static ImmutableRect2i getArea(ImmutableRect2i backgroundArea, ImmutableRect2i navigationArea) {
        if (backgroundArea.isEmpty()) {
            return ImmutableRect2i.EMPTY;
        }
        int x = backgroundArea.getX();
        int width = backgroundArea.getWidth();
        int y = navigationArea.isEmpty()
            ? Math.max(2, backgroundArea.getY() - RESERVED_HEIGHT)
            : Math.max(2, navigationArea.getY() - HEIGHT - 1);
        return new ImmutableRect2i(x, y, width, HEIGHT);
    }

    public static void draw(
        IngredientListFeatureSource source,
        GuiGraphics guiGraphics,
        ImmutableRect2i area,
        int mouseX,
        int mouseY
    ) {
        if (!isEnabled(source) || area.isEmpty()) {
            return;
        }
        List<CreativeModeTab> tabs = source.jeiPlusPlus$getCreativeTabs();
        int selected = source.jeiPlusPlus$getSelectedCreativeTab();
        int total = tabs.size() + 1;
        int capacity = getCapacity(area);
        int pageCount = getPageCount(total, capacity);
        int page = clampPage(source.jeiPlusPlus$getCreativeTabPage(), pageCount);
        if (page != source.jeiPlusPlus$getCreativeTabPage()) {
            source.jeiPlusPlus$setCreativeTabPage(page);
        }
        int start = page * capacity;

        guiGraphics.fill(
            RenderType.guiOverlay(),
            area.getX(),
            area.getY(),
            area.getX() + area.getWidth(),
            area.getY() + area.getHeight(),
            0xD0202020
        );

        for (int slot = 0; slot < capacity && start + slot < total; slot++) {
            int tabIndex = start + slot;
            int x = getTabX(area, slot, capacity);
            if (tabIndex == selected) {
                guiGraphics.fill(
                    RenderType.guiOverlay(),
                    x,
                    area.getY(),
                    x + SLOT_SIZE,
                    area.getY() + HEIGHT,
                    0xD0FFFFFF
                );
            }
            ItemStack icon = getIcon(tabs, tabIndex);
            if (!icon.isEmpty()) {
                guiGraphics.renderItem(icon, x + 1, area.getY() + 1);
            }
        }

        // Page controls are deliberately drawn after item icons.  JEI renders
        // stacks with depth enabled, so the controls and page label need a
        // high overlay layer to remain visible above them.
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0.0D, 0.0D, 300.0D);
        drawPageButton(guiGraphics, area.getX(), area.getY(), true, pageCount > 1,
            isInside(area, area.getX(), mouseX, mouseY));
        drawPageButton(guiGraphics, area.getX() + area.getWidth() - SLOT_SIZE, area.getY(), false,
            pageCount > 1,
            isInside(area, area.getX() + area.getWidth() - SLOT_SIZE, mouseX, mouseY));

        String pageLabel = (page + 1) + "/" + pageCount;
        if (JeiPlusPlusConfig.CREATIVE_TAB_PAGE_NUMBER_ENABLED.get()) {
            var font = Minecraft.getInstance().font;

            int labelSlotX = getPageLabelSlotX(area, capacity);

            int labelX =
                    labelSlotX
                            + (SLOT_SIZE - font.width(pageLabel)) / 2;
            guiGraphics.drawString(
                    font,
                pageLabel,
                labelX,
                area.getY() + 5,
                0xFFFFFFFF,
                true
            );
        }
        pose.popPose();
        if (source.jeiPlusPlus$isCreativeTabSelectorOpen()) {
            CreativeTabSelector.draw(source, guiGraphics, mouseX, mouseY);
        }
    }

    public static void drawTooltip(
        IngredientListFeatureSource source,
        GuiGraphics guiGraphics,
        ImmutableRect2i area,
        int mouseX,
        int mouseY
    ) {
        if (!isEnabled(source) || area.isEmpty()) {
            return;
        }
        if (source.jeiPlusPlus$isCreativeTabSelectorOpen()) {
            CreativeTabSelector.drawTooltip(source, guiGraphics, mouseX, mouseY);
            return;
        }
        int pageCount = getPageCount(source.jeiPlusPlus$getCreativeTabs().size() + 1, getCapacity(area));
        int page = clampPage(source.jeiPlusPlus$getCreativeTabPage(), pageCount);
        if (isPageButton(area, mouseX, mouseY, true) && pageCount > 1) {
            guiGraphics.renderTooltip(
                Minecraft.getInstance().font,
                Component.translatable("jei_plus_plus.creative_tab.previous_page"),
                mouseX,
                mouseY
            );
            return;
        }
        if (isPageButton(area, mouseX, mouseY, false) && pageCount > 1) {
            guiGraphics.renderTooltip(
                Minecraft.getInstance().font,
                Component.translatable("jei_plus_plus.creative_tab.next_page"),
                mouseX,
                mouseY
            );
            return;
        }
        int tabIndex = getTabAt(source, area, mouseX, mouseY, page, getCapacity(area));
        if (tabIndex < 0) {
            return;
        }
        Component title = getTitle(source.jeiPlusPlus$getCreativeTabs(), tabIndex);
        guiGraphics.renderTooltip(Minecraft.getInstance().font, title, mouseX, mouseY);
    }

    public static boolean handleClick(
        IngredientListFeatureSource source,
        ImmutableRect2i area,
        UserInput input
    ) {
        if (source != null && source.jeiPlusPlus$isCreativeTabSelectorOpen()) {
            return CreativeTabSelector.handleClick(source, area, input);
        }
        if (!isEnabled(source)
            || input.getKey().getType() != InputConstants.Type.MOUSE
            || !area.contains(input.getMouseX(), input.getMouseY())) {
            return false;
        }
        if (input.getKey().getValue() == 1) {
            if (!input.isSimulate()) {
                source.jeiPlusPlus$setCreativeTabSelectorPage(0);
                source.jeiPlusPlus$setCreativeTabSelectorOpen(true);
            }
            return true;
        }
        if (input.getKey().getValue() != 0) {
            return false;
        }
        int capacity = getCapacity(area);
        int pageCount = getPageCount(source.jeiPlusPlus$getCreativeTabs().size() + 1, capacity);
        int page = clampPage(source.jeiPlusPlus$getCreativeTabPage(), pageCount);
        boolean previous = isPageButton(area, input.getMouseX(), input.getMouseY(), true);
        boolean next = isPageButton(area, input.getMouseX(), input.getMouseY(), false);
        if (previous || next) {
            if (!input.isSimulate()) {
                int target = page + (next ? 1 : -1);
                source.jeiPlusPlus$setCreativeTabPage(Math.floorMod(target, pageCount));
            }
            return true;
        }

        int tabIndex = getTabAt(source, area, input.getMouseX(), input.getMouseY(), page, capacity);
        if (tabIndex < 0) {
            return false;
        }
        if (!input.isSimulate()) {
            source.jeiPlusPlus$setCreativeTabPage(tabIndex / capacity);
            source.jeiPlusPlus$selectCreativeTab(tabIndex);
        }
        return true;
    }

    public static Optional<IUserInputHandler> handleScroll(
        IngredientListFeatureSource source,
        ImmutableRect2i area,
        double mouseX,
        double mouseY,
        double scrollDeltaY,
        IUserInputHandler self
    ) {
        if (source != null && source.jeiPlusPlus$isCreativeTabSelectorOpen()) {
            if (CreativeTabSelector.handleScroll(source, mouseX, mouseY, scrollDeltaY)) {
                return Optional.of(self);
            }
        }
        if (!isEnabled(source) || scrollDeltaY == 0 || !area.contains(mouseX, mouseY)) {
            return Optional.empty();
        }
        int capacity = getCapacity(area);
        int pageCount = getPageCount(source.jeiPlusPlus$getCreativeTabs().size() + 1, capacity);
        int page = clampPage(source.jeiPlusPlus$getCreativeTabPage(), pageCount);
        int next = scrollDeltaY < 0 ? page + 1 : page - 1;
        source.jeiPlusPlus$setCreativeTabPage(Math.floorMod(next, pageCount));
        return Optional.of(self);
    }

    private static boolean isEnabled(IngredientListFeatureSource source) {
        return source != null && !source.jeiPlusPlus$getCreativeTabs().isEmpty();
    }

    static int getCapacity(ImmutableRect2i area) {
        // Reserve the left/right navigation slots and one center slot
        // for the page label so it never overlaps a creative-tab icon.
        int contentWidth = Math.max(
                SLOT_SIZE,
                area.getWidth() - SLOT_SIZE * 3
        );

        return Math.max(
                1,
                Math.min(MAX_VISIBLE_TABS, contentWidth / SLOT_SIZE)
        );
    }

    private static int getPageCount(int total, int capacity) {
        return Math.max(1, (total + capacity - 1) / capacity);
    }

    private static int clampPage(int page, int pageCount) {
        return Math.max(0, Math.min(page, pageCount - 1));
    }

    private static int getTabX(
            ImmutableRect2i area,
            int slot,
            int capacity
    ) {
        int x = area.getX() + SLOT_SIZE + slot * SLOT_SIZE;

        int centerSlot = capacity / 2;

        if (slot >= centerSlot) {
            x += SLOT_SIZE;
        }

        return x;
    }

    private static int getTabAt(
            IngredientListFeatureSource source,
            ImmutableRect2i area,
            double mouseX,
            double mouseY,
            int page,
            int capacity
    ) {
        if (!isEnabled(source) || !area.contains(mouseX, mouseY)) {
            return -1;
        }

        if (
                isPageButton(area, mouseX, mouseY, true) ||
                        isPageButton(area, mouseX, mouseY, false)
        ) {
            return -1;
        }

        for (int slot = 0; slot < capacity; slot++) {
            int x = getTabX(area, slot, capacity);

            if (
                    mouseX >= x &&
                            mouseX < x + SLOT_SIZE &&
                            mouseY >= area.getY() &&
                            mouseY < area.getY() + HEIGHT
            ) {
                int tabIndex = page * capacity + slot;
                int total =
                        source.jeiPlusPlus$getCreativeTabs().size() + 1;

                return tabIndex < total ? tabIndex : -1;
            }
        }

        return -1;
    }

    private static int getPageLabelSlotX(
            ImmutableRect2i area,
            int capacity
    ) {
        int centerSlot = capacity / 2;

        return area.getX()
                + SLOT_SIZE
                + centerSlot * SLOT_SIZE;
    }

    private static boolean isPageButton(ImmutableRect2i area, double mouseX, double mouseY, boolean left) {
        int x = left ? area.getX() : area.getX() + area.getWidth() - SLOT_SIZE;
        return mouseX >= x && mouseX < x + SLOT_SIZE && mouseY >= area.getY() && mouseY < area.getY() + HEIGHT;
    }

    private static boolean isInside(ImmutableRect2i area, int x, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + SLOT_SIZE
            && mouseY >= area.getY() && mouseY < area.getY() + HEIGHT;
    }

    private static void drawPageButton(
        GuiGraphics guiGraphics,
        int x,
        int y,
        boolean left,
        boolean enabled,
        boolean hovered
    ) {
        int background = hovered && enabled ? 0xFF777777 : enabled ? 0xFF555555 : 0xFF303030;
        int border = enabled ? 0xFFB0B0B0 : 0xFF555555;
        int arrow = enabled ? 0xFFFFFFFF : 0xFF707070;
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + SLOT_SIZE, y + HEIGHT, background);
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + SLOT_SIZE, y + 1, border);
        guiGraphics.fill(RenderType.guiOverlay(), x, y + HEIGHT - 1, x + SLOT_SIZE, y + HEIGHT, border);
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + 1, y + HEIGHT, border);
        guiGraphics.fill(RenderType.guiOverlay(), x + SLOT_SIZE - 1, y, x + SLOT_SIZE, y + HEIGHT, border);
        if (left) {
            // Point and shaft share the slot's centre (9, 9).
            guiGraphics.fill(RenderType.guiOverlay(), x + 5, y + 8, x + 8, y + 10, arrow);
            guiGraphics.fill(RenderType.guiOverlay(), x + 6, y + 7, x + 8, y + 11, arrow);
            guiGraphics.fill(RenderType.guiOverlay(), x + 7, y + 6, x + 9, y + 12, arrow);
            guiGraphics.fill(RenderType.guiOverlay(), x + 8, y + 8, x + 13, y + 10, arrow);
        } else {
            guiGraphics.fill(RenderType.guiOverlay(), x + 10, y + 8, x + 13, y + 10, arrow);
            guiGraphics.fill(RenderType.guiOverlay(), x + 10, y + 7, x + 12, y + 11, arrow);
            guiGraphics.fill(RenderType.guiOverlay(), x + 9, y + 6, x + 11, y + 12, arrow);
            guiGraphics.fill(RenderType.guiOverlay(), x + 5, y + 8, x + 10, y + 10, arrow);
        }
    }

    private static ItemStack getIcon(List<CreativeModeTab> tabs, int index) {
        if (index == 0) {
            return Items.CRAFTING_TABLE.getDefaultInstance();
        }
        if (index - 1 < tabs.size()) {
            return tabs.get(index - 1).getIconItem();
        }
        return ItemStack.EMPTY;
    }

    private static Component getTitle(List<CreativeModeTab> tabs, int index) {
        if (index == 0) {
            return Component.translatable("jei_plus_plus.creative_tab.all");
        }
        if (index - 1 < tabs.size()) {
            return tabs.get(index - 1).getDisplayName();
        }
        return Component.empty();
    }
}
