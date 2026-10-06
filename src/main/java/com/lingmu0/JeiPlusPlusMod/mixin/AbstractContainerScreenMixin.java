package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeTransfer;
import com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void jeiPlusPlus$hideContainerTooltipWhileSelectorOpen(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            CallbackInfo ci) {
        if (CreativeTabGridCompat.isAnySelectorOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void jeiPlusPlus$updateRecipeTreeCrafting(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci) {
        // Refresh and apply the request before the terminal draws its native
        // list. Applying at render-tail changes the list after it was shown
        // and is the source of the one-frame order twitch on packet updates.
        RecipeTreeFavorites.applyPendingNetworkPriority();
        RecipeTreeFavorites.refreshThrottled();
        RecipeTreeFavorites.applyPendingNetworkPriority();
        RecipeTreeTransfer.tick((Screen) (Object) this);
    }

    @Inject(method = "renderSlot", at = @At("TAIL"))
    private void jeiPlusPlus$highlightRequiredInventoryStack(GuiGraphics graphics, Slot slot, CallbackInfo ci) {
        if (slot.getItem().isEmpty()) {
            return;
        }
        var player = net.minecraft.client.Minecraft.getInstance().player;
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        // Keep the player's crafting grid and other private UI slots out of
        // inventory highlights, while including slots in opened containers.
        if (player != null
                && screen.getMenu() == player.inventoryMenu
                && !(slot.container instanceof Inventory)) {
            return;
        }
        boolean finalProduct = RecipeTreeFavorites.isFinalProduct(slot.getItem());
        boolean intermediate = RecipeTreeFavorites.isIntermediate(slot.getItem());
        boolean required = RecipeTreeFavorites.isRequired(slot.getItem());
        if (!finalProduct && !intermediate && !required) {
            return;
        }
        int fill = finalProduct ? 0x4433CC66 : (intermediate ? 0x44FF2222 : 0x3300BBFF);
        int border = finalProduct ? 0xDD66FF88 : (intermediate ? 0xDDFF5555 : 0xCC55DDFF);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, fill);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 1, border);
        graphics.fill(slot.x, slot.y + 15, slot.x + 16, slot.y + 16, border);
        graphics.fill(slot.x, slot.y, slot.x + 1, slot.y + 16, border);
        graphics.fill(slot.x + 15, slot.y, slot.x + 16, slot.y + 16, border);
        graphics.pose().popPose();
    }

    /**
     * Network terminals render fake storage entries through their screen
     * override, so the renderSlot injection above does not see them. Use the
     * optional slot's runtime class name to keep every integration optional.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void jeiPlusPlus$highlightNetworkStorage(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci) {
        if (!RecipeTreeFavorites.isActive()) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        for (Slot slot : screen.getMenu().slots) {
            if (!RecipeTreeFavorites.isNetworkStorageSlot(slot) || slot.getItem().isEmpty()) {
                continue;
            }
            boolean finalProduct = RecipeTreeFavorites.isFinalProduct(slot.getItem());
            boolean intermediate = RecipeTreeFavorites.isIntermediate(slot.getItem());
            boolean required = RecipeTreeFavorites.isRequired(slot.getItem());
            if (!finalProduct && !intermediate && !required) {
                continue;
            }
            int fill = finalProduct ? 0x4433CC66 : (intermediate ? 0x44FF2222 : 0x3300BBFF);
            int border = finalProduct ? 0xDD66FF88 : (intermediate ? 0xDDFF5555 : 0xCC55DDFF);
            // render() has already restored the screen pose after drawing
            // the container. Unlike renderSlot(), the slot coordinates are
            // therefore relative to the container and must be translated to
            // the screen's GUI origin before drawing the overlay.
            int x = screen.getGuiLeft() + slot.x;
            int y = screen.getGuiTop() + slot.y;
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 300);
            graphics.fill(x, y, x + 16, y + 16, fill);
            graphics.fill(x, y, x + 16, y + 1, border);
            graphics.fill(x, y + 15, x + 16, y + 16, border);
            graphics.fill(x, y, x + 1, y + 16, border);
            graphics.fill(x + 15, y, x + 16, y + 16, border);
            graphics.pose().popPose();
        }
        RecipeTreeFavorites.renderVirtualNetworkHighlights(graphics, screen);
    }
}
