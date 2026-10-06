package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.mixin.BasicRecipeTransferHandlerAccessor;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.stream.Stream;

/** Exact-count JEI transfer plus a bottom-up queue for instant crafting stations. */
public final class RecipeTreeTransfer {
    private static final int AE2_WAIT_FRAMES = 600;
    /**
     * A small bounded wait for custom instant menus whose result slot is not a
     * vanilla menu type. We only enable this probe when the menu exposes a
     * non-placeable slot, so timed machines and ordinary input-only menus keep
     * their previous fail-safe behavior.
     */
    private static final int GENERIC_RESULT_WAIT_FRAMES = 40;
    private static List<PendingCraft> pendingCrafts = List.of();
    private static int pendingCraftIndex;
    private static int pendingMenuId = -1;
    private static int pendingResultSlot = -1;
    private static boolean pendingGenericResultProbe;
    private static int pendingChunk;
    private static int pendingLoadedBatches;
    private static int pendingCraftResultSlot = -1;
    private static boolean pendingAe2Carry;
    private static boolean pendingAe2NetworkDeposit;
    private static boolean pendingAe2NetworkRequest;
    private static int pendingAe2NetworkCount;
    private static int pendingAe2PlacementSlot = -1;
    private static boolean pendingAe2PlacementInFlight;
    private static int pendingAe2PlacementCount;
    private static long pendingAe2PlacementInventoryBefore;
    private static String pendingAe2OutputKey = "";
    private static ItemStack pendingAe2OutputStack = ItemStack.EMPTY;
    private static long pendingAe2InventoryBefore;
    private static boolean pendingVanillaPickup;
    private static int pendingPickupAttempts;
    private static int waitFrames;
    private static int emptyFrames;

    private RecipeTreeTransfer() {
    }

    public static void cancel() {
        clearPending();
    }

    public static boolean canTransfer(RecipeTreeData.CraftStep step, boolean recursive) {
        RecipeTreeData.CraftStep first = firstStep(step, recursive);
        if (first == null) {
            return false;
        }
        int chunk = transferChunk(first, first.batches(), recursive);
        return chunk > 0 && runTransfer(first, chunk, false, recursive, false);
    }

    public static boolean transfer(RecipeTreeData.CraftStep step, boolean recursive) {
        return transfer(step, recursive, false);
    }

    /**
     * Transfers a recipe tree step. {@code maxTransfer} mirrors JEI's
     * shift-click on its own plus button and lets the handler fill all
     * operations that the current inventory/container can accept.
     */
    public static boolean transfer(
            RecipeTreeData.CraftStep step,
            boolean recursive,
            boolean maxTransfer) {
        clearPending();
        if (!recursive) {
            if (!exposeParentContainer()) {
                return false;
            }
            int chunk = transferChunk(step, step == null ? 0 : step.batches(), false);
            return chunk > 0 && runTransfer(step, chunk, true, false, maxTransfer);
        }

        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (tree == null) {
            return false;
        }
        List<RecipeTreeData.CraftStep> plan = tree.recursiveCraftingSteps(step);
        if (plan.isEmpty()) {
            return false;
        }
        pendingCrafts = plan.stream()
                .filter(candidate -> candidate.batches() > 0)
                .map(candidate -> new PendingCraft(candidate, candidate.batches()))
                .toList();
        if (!exposeParentContainer()) {
            clearPending();
            return false;
        }
        return startNextCraft();
    }

    /** Called from container rendering so each transfer/craft packet can settle before the next step. */
    public static void tick(Screen currentScreen) {
        if (pendingCrafts.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(currentScreen instanceof AbstractContainerScreen<?> screen)
                || minecraft.player == null
                || minecraft.gameMode == null
                || screen.getMenu().containerId != pendingMenuId) {
            clearPending();
            return;
        }
        if (waitFrames-- > 0) {
            return;
        }

        if (pendingAe2Carry) {
            tickAe2Carry(screen, minecraft);
            return;
        }

        if (pendingVanillaPickup) {
            tickVanillaPickup(screen, minecraft);
            return;
        }

        if (pendingGenericResultProbe) {
            PendingCraft craft = pendingCrafts.get(pendingCraftIndex);
            int genericResult = findGenericResultSlot(
                    screen.getMenu(), craft.step, minecraft.player);
            if (genericResult >= 0) {
                pendingResultSlot = genericResult;
                pendingGenericResultProbe = false;
                emptyFrames = 0;
            } else if (++emptyFrames > GENERIC_RESULT_WAIT_FRAMES) {
                clearPending();
                return;
            } else {
                return;
            }
        }

        if (pendingResultSlot < 0) {
            startNextCraft();
            return;
        }
        if (pendingResultSlot >= screen.getMenu().slots.size()) {
            clearPending();
            return;
        }

        Slot result = screen.getMenu().getSlot(pendingResultSlot);
        if (!result.hasItem()) {
            if (++emptyFrames > AE2_WAIT_FRAMES) {
                clearPending();
            }
            return;
        }

        PendingCraft craft = pendingCrafts.get(pendingCraftIndex);
        ItemStack outputBefore = result.getItem().copy();
        if (!isExpectedCraftOutput(craft.step, outputBefore)) {
            // The network transfer is asynchronous. Validate the result rather
            // than requiring its 3x3 matrix to contain JEI++'s exact candidate:
            // the terminal may legally choose another item from the same tag.
            if (++emptyFrames > AE2_WAIT_FRAMES) {
                clearPending();
            }
            return;
        }

        emptyFrames = 0;
        long ownedBefore = RecipeTreeData.inventoryAmount(outputBefore);
        Boolean ae2Pickup = StorageNetworkIntegration.takeCraftingResult(
                screen.getMenu(),
                pendingResultSlot,
                true);
        if (Boolean.TRUE.equals(ae2Pickup)) {
            // CRAFT_ITEM places one result on the menu cursor. Wait for the
            // server sync, then put that exact one-result stack into the
            // player's inventory before advancing the recursive queue.
            pendingAe2Carry = true;
            pendingAe2PlacementSlot = -1;
            pendingAe2OutputKey = RecipeTreeData.ingredientKey(outputBefore);
            pendingAe2OutputStack = outputBefore.copy();
            pendingAe2OutputStack.setCount(1);
            // Capture this before the click. Generic container clicks are
            // predicted locally, so measuring afterwards can already include
            // the crafted item and make completion impossible to observe.
            pendingAe2InventoryBefore = ownedBefore;
            pendingResultSlot = -1;
            emptyFrames = 0;
            waitFrames = 2;
            return;
        }
        minecraft.gameMode.handleInventoryMouseClick(
                pendingMenuId,
                pendingResultSlot,
                0,
                ClickType.QUICK_MOVE,
                minecraft.player);
        pendingVanillaPickup = true;
        pendingPickupAttempts = 1;
        waitFrames = 1;
    }

    private static void tickVanillaPickup(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        if (pendingResultSlot < 0 || pendingResultSlot >= screen.getMenu().slots.size()) {
            clearPending();
            return;
        }
        Slot result = screen.getMenu().getSlot(pendingResultSlot);
        if (!result.hasItem()) {
            // QUICK_MOVE repeatedly calls the menu's quickMoveStack method
            // while the result remains the same item. Therefore one click
            // can craft and collect every operation that was loaded into the
            // input slots. Account for that whole loaded batch at once.
            pendingChunk = Math.max(1, pendingLoadedBatches);
            completePendingCraft();
            return;
        }
        if (++pendingPickupAttempts > 8) {
            // Do not advance the dependency queue while a full inventory (or
            // a third-party result slot) still owns the product.
            clearPending();
            return;
        }
        minecraft.gameMode.handleInventoryMouseClick(
                pendingMenuId,
                pendingResultSlot,
                0,
                ClickType.QUICK_MOVE,
                minecraft.player);
        waitFrames = 1;
    }

    private static void tickAe2Carry(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        AbstractContainerMenu menu = screen.getMenu();
        if (pendingAe2NetworkDeposit) {
            tickAe2NetworkDeposit(menu);
            return;
        }

        ItemStack carried = menu.getCarried();
        if (pendingAe2PlacementInFlight) {
            if (carried.isEmpty()) {
                if (!pendingAe2OutputKey.isEmpty()
                        && playerInventoryAmount(menu, minecraft.player,
                                pendingAe2OutputKey) > pendingAe2PlacementInventoryBefore) {
                    completePendingCraft();
                    return;
                }
                if (++emptyFrames > AE2_WAIT_FRAMES) {
                    clearPending();
                } else {
                    waitFrames = 1;
                }
                return;
            }

            long inventoryAmount = pendingAe2OutputKey.isEmpty()
                    ? 0
                    : playerInventoryAmount(menu, minecraft.player, pendingAe2OutputKey);
            if (carried.getCount() < pendingAe2PlacementCount
                    || inventoryAmount > pendingAe2PlacementInventoryBefore) {
                // The server acknowledged the click. If there is a remainder,
                // re-scan once the acknowledgement is visible; do not resend
                // the same click while the first packet is still in flight.
                pendingAe2PlacementInFlight = false;
                pendingAe2PlacementSlot = -1;
                pendingAe2PlacementCount = 0;
                emptyFrames = 0;
                waitFrames = 1;
                return;
            }
            if (++emptyFrames > AE2_WAIT_FRAMES) {
                pendingAe2PlacementInFlight = false;
                if (startAe2NetworkDeposit(menu)) {
                    return;
                }
                clearPending();
            } else {
                waitFrames = 1;
            }
            return;
        }

        if (carried.isEmpty()) {
            // Some terminal slots insert the result directly into the player
            // inventory or their network instead of synchronizing a carried
            // stack. Either destination completes this one crafting step.
            if (!pendingAe2OutputStack.isEmpty()
                    && RecipeTreeData.inventoryAmount(pendingAe2OutputStack) > pendingAe2InventoryBefore) {
                completePendingCraft();
                return;
            }
            if (++emptyFrames > AE2_WAIT_FRAMES) {
                clearPending();
            }
            return;
        }

        if (pendingAe2PlacementSlot < 0) {
            int target = findInventoryDestination(menu, minecraft.player, carried);
            if (target < 0) {
                if (startAe2NetworkDeposit(menu)) {
                    return;
                }
                clearPending();
                return;
            }
            pendingAe2PlacementSlot = target;
            pendingAe2PlacementInFlight = true;
            pendingAe2PlacementCount = carried.getCount();
            pendingAe2PlacementInventoryBefore = pendingAe2OutputKey.isEmpty()
                    ? 0
                    : playerInventoryAmount(menu, minecraft.player, pendingAe2OutputKey);
            minecraft.gameMode.handleInventoryMouseClick(
                    pendingMenuId,
                    target,
                    0,
                    ClickType.PICKUP,
                    minecraft.player);
            waitFrames = 1;
            return;
        }
    }

    private static void tickAe2NetworkDeposit(AbstractContainerMenu menu) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            completePendingCraft();
            return;
        }
        if (pendingAe2NetworkRequest) {
            if (carried.getCount() < pendingAe2NetworkCount) {
                // A partial network insertion was acknowledged. Send the
                // next request only after the client sees that change.
                pendingAe2NetworkRequest = false;
                pendingAe2NetworkCount = carried.getCount();
                emptyFrames = 0;
                waitFrames = 1;
                return;
            }
            if (++emptyFrames > AE2_WAIT_FRAMES) {
                clearPending();
            } else {
                waitFrames = 1;
            }
            return;
        }
        if (!startAe2NetworkDeposit(menu)) {
            clearPending();
        }
    }

    /** Requests AE2 to insert the complete cursor stack into its network. */
    private static boolean startAe2NetworkDeposit(AbstractContainerMenu menu) {
        Boolean deposited = StorageNetworkIntegration.putCarriedItemIntoNetwork(menu, false);
        if (!Boolean.TRUE.equals(deposited)) {
            return false;
        }
        pendingAe2NetworkDeposit = true;
        pendingAe2NetworkRequest = true;
        pendingAe2NetworkCount = Math.max(1, menu.getCarried().getCount());
        emptyFrames = 0;
        waitFrames = 1;
        return true;
    }

    private static void completePendingCraft() {
        if (pendingCraftIndex >= pendingCrafts.size()) {
            clearPending();
            return;
        }
        PendingCraft craft = pendingCrafts.get(pendingCraftIndex);
        int completedBatches = Math.max(1, pendingChunk);
        craft.remainingBatches = Math.max(0, craft.remainingBatches - completedBatches);
        pendingLoadedBatches = Math.max(0, pendingLoadedBatches - completedBatches);
        if (craft.remainingBatches <= 0) {
            pendingCraftIndex++;
        }

        // A counted transfer may have filled several operations into the
        // input slots. The result slot still represents one operation at a
        // time, so keep extracting from the same loaded inputs before asking
        // JEI to transfer another recipe or starting the next dependency.
        if (pendingLoadedBatches > 0) {
            pendingResultSlot = pendingCraftResultSlot;
            pendingGenericResultProbe = pendingResultSlot < 0;
            pendingAe2Carry = false;
            pendingAe2NetworkDeposit = false;
            pendingAe2NetworkRequest = false;
            pendingAe2NetworkCount = 0;
            pendingAe2PlacementSlot = -1;
            pendingAe2PlacementInFlight = false;
            pendingAe2PlacementCount = 0;
            pendingAe2PlacementInventoryBefore = 0;
            pendingAe2OutputKey = "";
            pendingAe2OutputStack = ItemStack.EMPTY;
            pendingAe2InventoryBefore = 0;
            pendingVanillaPickup = false;
            pendingPickupAttempts = 0;
            pendingChunk = 1;
            emptyFrames = 0;
            waitFrames = 2;
            return;
        }

        pendingResultSlot = -1;
        pendingGenericResultProbe = false;
        pendingAe2Carry = false;
        pendingAe2NetworkDeposit = false;
        pendingAe2NetworkRequest = false;
        pendingAe2NetworkCount = 0;
        pendingAe2PlacementSlot = -1;
        pendingAe2PlacementInFlight = false;
        pendingAe2PlacementCount = 0;
        pendingAe2PlacementInventoryBefore = 0;
        pendingAe2OutputKey = "";
        pendingAe2OutputStack = ItemStack.EMPTY;
        pendingAe2InventoryBefore = 0;
        pendingCraftResultSlot = -1;
        pendingVanillaPickup = false;
        pendingPickupAttempts = 0;
        pendingChunk = 0;
        emptyFrames = 0;
        waitFrames = 2;
    }

    private static int findInventoryDestination(
            AbstractContainerMenu menu,
            Player player,
            ItemStack stack) {
        if (player == null || stack.isEmpty()) {
            return -1;
        }
        int empty = -1;
        int partial = -1;
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory() || !slot.mayPlace(stack)) {
                continue;
            }
            ItemStack existing = slot.getItem();
            if (!existing.isEmpty()
                    && RecipeTreeData.ingredientKey(existing).equals(RecipeTreeData.ingredientKey(stack))
                    && existing.getCount() < Math.min(slot.getMaxStackSize(), stack.getMaxStackSize())) {
                int capacity = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize()) - existing.getCount();
                if (capacity >= stack.getCount()) {
                    return slot.index;
                }
                if (partial < 0) {
                    partial = slot.index;
                }
            }
            if (empty < 0 && existing.isEmpty()) {
                empty = slot.index;
            }
        }
        return empty >= 0 ? empty : partial;
    }

    private static long playerInventoryAmount(
            AbstractContainerMenu menu,
            Player player,
            String key) {
        if (menu == null || player == null || key == null || key.isEmpty()) {
            return 0;
        }
        long amount = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() && !slot.getItem().isEmpty()
                    && key.equals(RecipeTreeData.ingredientKey(slot.getItem()))) {
                amount += slot.getItem().getCount();
            }
        }
        return amount;
    }

    private static RecipeTreeData.CraftStep firstStep(RecipeTreeData.CraftStep step, boolean recursive) {
        if (step == null) {
            return null;
        }
        if (!recursive) {
            return step;
        }
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (tree == null) {
            return null;
        }
        return tree.recursiveCraftingSteps(step).stream().findFirst().orElse(null);
    }

    private static boolean isExpectedCraftOutput(RecipeTreeData.CraftStep step, ItemStack actual) {
        if (step == null || actual == null || actual.isEmpty()) {
            return false;
        }
        String actualKey = RecipeTreeData.ingredientKey(actual);
        if (actualKey.equals(RecipeTreeData.ingredientKey(step.stack()))) {
            return true;
        }
        // A bookmarked by-product can point at a recipe whose physical result
        // slot contains the primary output. Both are valid outputs of the same
        // recipe and must allow that crafting operation to complete.
        return RecipeTreeData.snapshot(step.recipe()).outputs().stream()
                .anyMatch(output -> actualKey.equals(RecipeTreeData.ingredientKey(output)));
    }

    private static boolean startNextCraft() {
        if (pendingCraftIndex >= pendingCrafts.size()) {
            clearPending();
            RecipeTreeFavorites.refreshNow();
            return true;
        }

        AbstractContainerScreen<?> screen = containerScreen();
        if (screen == null) {
            clearPending();
            return false;
        }
        PendingCraft craft = pendingCrafts.get(pendingCraftIndex);
        AbstractContainerMenu menu = screen.getMenu();
        Minecraft minecraft = Minecraft.getInstance();
        int resultSlot = instantResultSlot(menu);
        boolean genericResult = resultSlot < 0
                && hasGenericResultSlot(menu, minecraft.player);
        int chunk;
        if (resultSlot >= 0 || genericResult) {
            int requested = transferChunk(craft.step, craft.remainingBatches, true);
            int inputCapacity = maxBatchesForInstantInputs(
                    menu, craft.step, craft.step.selectedInputs(), true);
            chunk = Math.min(requested, inputCapacity);
        } else {
            chunk = transferChunk(craft.step, craft.remainingBatches, true);
        }
        if (chunk <= 0 || !runTransfer(craft.step, chunk, true, true, false)) {
            clearPending();
            return false;
        }

        if (resultSlot < 0 && !genericResult) {
            clearPending();
            return true;
        }
        pendingMenuId = menu.containerId;
        pendingResultSlot = resultSlot;
        pendingGenericResultProbe = genericResult;
        pendingChunk = 1;
        pendingLoadedBatches = chunk;
        pendingCraftResultSlot = resultSlot;
        pendingAe2Carry = false;
        pendingAe2NetworkDeposit = false;
        pendingAe2NetworkRequest = false;
        pendingAe2NetworkCount = 0;
        pendingAe2PlacementSlot = -1;
        pendingAe2PlacementInFlight = false;
        pendingAe2PlacementCount = 0;
        pendingAe2PlacementInventoryBefore = 0;
        waitFrames = 3;
        emptyFrames = 0;
        return true;
    }

    /**
     * Finds an already populated output-like slot in a custom menu. The
     * non-placeable-slot check is the common contract used by vanilla result
     * slots and prevents us from ever clicking a normal input slot by guess.
     */
    private static int findGenericResultSlot(
            AbstractContainerMenu menu,
            RecipeTreeData.CraftStep step,
            Player player) {
        if (menu == null || step == null || player == null) {
            return -1;
        }
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()
                    || slot.mayPlace(ItemStack.EMPTY)
                    || !slot.hasItem()
                    || !isExpectedCraftOutput(step, slot.getItem())
                    || !slot.mayPickup(player)) {
                continue;
            }
            return slot.index;
        }
        return -1;
    }

    private static boolean hasGenericResultSlot(
            AbstractContainerMenu menu,
            Player player) {
        if (menu == null || player == null) {
            return false;
        }
        return menu.slots.stream()
                .anyMatch(slot -> slot.container != player.getInventory()
                        && !slot.mayPlace(ItemStack.EMPTY));
    }

    private static int transferChunk(RecipeTreeData.CraftStep step) {
        return transferChunk(step, step == null ? 0 : step.batches(), false);
    }

    private static int transferChunk(
            RecipeTreeData.CraftStep step,
            long requested,
            boolean recursiveCandidates) {
        if (step == null || requested <= 0) {
            return 0;
        }
        IRecipeLayoutDrawable<?> layout = RecipeTreeData.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return 0;
        }
        int capacity = maxBatchesPerTransfer(
                layout.getRecipeSlotsView(),
                step.selectedInputs(),
                recursiveCandidates);
        return (int) Math.max(1, Math.min(Math.min(Integer.MAX_VALUE, requested), capacity));
    }

    /**
     * Returns the number of recipe operations that can remain in the current
     * instant-workstation input slots. A one-item input slot therefore
     * returns one and preserves the old one-operation-at-a-time behaviour.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int maxBatchesForInstantInputs(
            AbstractContainerMenu menu,
            RecipeTreeData.CraftStep step,
            List<String> selectedInputs,
            boolean recursiveCandidates) {
        if (menu == null || step == null || Ae2StorageIntegration.isCraftingMenu(menu)) {
            return 1;
        }
        IRecipeLayoutDrawable<?> layout = RecipeTreeData.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return 1;
        }

        List<IRecipeSlotView> inputs = layout.getRecipeSlotsView().getSlotViews().stream()
                .filter(slot -> slot.getRole() == RecipeIngredientRole.INPUT)
                .toList();
        if (inputs.isEmpty()) {
            return 1;
        }

        TransferSlots transferSlots = null;
        try {
            IRecipeTransferManager manager = Internal.getJeiRuntime().getRecipeTransferManager();
            Optional<IRecipeTransferHandler<AbstractContainerMenu, Object>> handler = (Optional) manager
                    .getRecipeTransferHandler(menu, (IRecipeCategory) layout.getRecipeCategory());
            if (handler.isPresent()) {
                transferSlots = findTransferSlots(handler.get(), menu, layout.getRecipe());
            }
        } catch (RuntimeException ignored) {
            // Fall through to the conservative menu-slot probe below.
        }

        if (transferSlots != null && inputs.size() <= transferSlots.recipeSlots().size()) {
            int result = Integer.MAX_VALUE;
            boolean foundItemInput = false;
            for (int index = 0; index < inputs.size(); index++) {
                if (inputs.get(index).isEmpty()) {
                    // Shaped crafting recipes expose their blank grid cells
                    // as INPUT views too. They do not constrain the batch.
                    continue;
                }
                Optional<ItemStack> input = selectedInput(
                        inputs.get(index),
                        index < selectedInputs.size() ? selectedInputs.get(index) : "",
                        recursiveCandidates);
                if (input.isEmpty()) {
                    // Fluids and other non-item ingredients are intentionally
                    // left on the old safe path.
                    return 1;
                }
                foundItemInput = true;
                ItemStack stack = input.get();
                int perBatch = Math.max(1, stack.getCount());
                int slotCapacity = transferSlots.recipeSlots().get(index)
                        .getMaxStackSize(stack);
                result = Math.min(result, Math.max(1, slotCapacity / perBatch));
            }
            return foundItemInput ? Math.max(1, result) : 1;
        }

        // Vanilla crafting menus have a stable, stackable input grid even if
        // a third-party JEI handler does not expose its transfer info.
        if (menu instanceof CraftingMenu || menu instanceof InventoryMenu) {
            return Integer.MAX_VALUE;
        }

        // Unknown custom handlers are probed conservatively. If any
        // non-player slot that accepts an input is single-item, keep the old
        // one-at-a-time path. This also avoids treating result slots as
        // inputs because they reject the concrete candidate stack.
        int result = Integer.MAX_VALUE;
        boolean foundItemInput = false;
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 1;
        }
        for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
            IRecipeSlotView inputView = inputs.get(inputIndex);
            if (inputView.isEmpty()) {
                continue;
            }
            Optional<ItemStack> input = selectedInput(
                    inputView,
                    inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : "",
                    recursiveCandidates);
            if (input.isEmpty()) {
                return 1;
            }
            foundItemInput = true;
            ItemStack stack = input.get();
            int perBatch = Math.max(1, stack.getCount());
            boolean foundSlot = false;
            for (Slot slot : menu.slots) {
                if (slot.container == player.getInventory() || !slot.mayPlace(stack)) {
                    continue;
                }
                foundSlot = true;
                int slotCapacity = slot.getMaxStackSize(stack);
                result = Math.min(result, Math.max(1, slotCapacity / perBatch));
            }
            if (!foundSlot) {
                return 1;
            }
        }
        return foundItemInput ? Math.max(1, result) : 1;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean runTransfer(
            RecipeTreeData.CraftStep step,
            int batches,
            boolean doTransfer,
            boolean recursiveCandidates,
            boolean maxTransfer) {
        if (step == null || batches <= 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.gameMode == null) {
            return false;
        }
        AbstractContainerScreen<?> screen = containerScreen();
        if (screen == null) {
            return false;
        }
        IRecipeLayoutDrawable<?> layout = RecipeTreeData.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        // Prefer JEI's normal container-transfer handler. It is the safest
        // path for ordinary menus and for network mods that already expose a
        // JEI handler. The reflective network packet is only the fallback
        // for a fake-slot terminal where no generic handler can move items.
        boolean ae2Menu = Ae2StorageIntegration.isCraftingMenu(menu);
        if (!ae2Menu || maxTransfer) {
            Boolean generic = tryJeiTransfer(
                    menu, layout, step.selectedInputs(), batches, player,
                    doTransfer, recursiveCandidates, maxTransfer);
            if (Boolean.TRUE.equals(generic)) {
                return true;
            }
        }

        Boolean networkTransfer = tryNetworkCraftingTransfer(
                menu, layout, step.selectedInputs(), doTransfer, recursiveCandidates);
        if (networkTransfer != null) {
            return networkTransfer;
        }
        return ae2Menu
                ? tryJeiTransfer(
                        menu, layout, step.selectedInputs(), batches, player,
                        doTransfer, recursiveCandidates, maxTransfer)
                : false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Boolean tryJeiTransfer(
            AbstractContainerMenu menu,
            IRecipeLayoutDrawable<?> layout,
            List<String> selectedInputs,
            int batches,
            Player player,
            boolean doTransfer,
            boolean recursiveCandidates,
            boolean maxTransfer) {
        IRecipeTransferManager manager = Internal.getJeiRuntime().getRecipeTransferManager();
        Optional<IRecipeTransferHandler<AbstractContainerMenu, Object>> handler = (Optional) manager
                .getRecipeTransferHandler(menu, (IRecipeCategory) layout.getRecipeCategory());
        if (handler.isEmpty()) {
            return false;
        }
        IRecipeSlotsView slots = adjustInputs(
                layout.getRecipeSlotsView(), selectedInputs, batches, recursiveCandidates, player);
        IRecipeTransferError validation;
        try {
            validation = handler.get().transferRecipe(
                    menu, layout.getRecipe(), slots, player, maxTransfer, false);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (validation != null && !validation.getType().allowsTransfer) {
            return false;
        }
        if (!doTransfer) {
            return true;
        }

        // JEI 15.x has no counted transfer packet. Its handler always moves
        // one set, even when an adjusted ingredient stack contains a larger
        // count. Fill the real recipe slots directly so the configured batch
        // count is honoured before crafting or recursive output extraction.
        if (batches > 1 && !containsFluidInput(layout.getRecipeSlotsView())) {
            Boolean exact = transferExactInputs(
                    handler.get(),
                    menu,
                    layout.getRecipe(),
                    layout.getRecipeSlotsView(),
                    selectedInputs,
                    batches,
                    player,
                    recursiveCandidates);
            if (exact != null) {
                return exact;
            }
        }

        try {
            IRecipeTransferError error = handler.get().transferRecipe(
                    menu, layout.getRecipe(), slots, player, maxTransfer, true);
            return error == null || error.getType().allowsTransfer;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Boolean transferExactInputs(
            IRecipeTransferHandler handler,
            AbstractContainerMenu menu,
            Object recipe,
            IRecipeSlotsView slotsView,
            List<String> selectedInputs,
            int batches,
            Player player,
            boolean recursiveCandidates) {
        TransferSlots transferSlots = findTransferSlots(handler, menu, recipe);
        if (transferSlots == null) {
            return null;
        }

        List<IRecipeSlotView> inputViews = slotsView.getSlotViews().stream()
                .filter(slot -> slot.getRole() == RecipeIngredientRole.INPUT)
                .toList();
        if (inputViews.size() > transferSlots.recipeSlots().size()) {
            return null;
        }

        List<InputRequirement> requirements = new ArrayList<>();
        for (int i = 0; i < inputViews.size(); i++) {
            IRecipeSlotView input = inputViews.get(i);
            Optional<ItemStack> perBatch = selectedInput(input,
                    i < selectedInputs.size() ? selectedInputs.get(i) : "",
                    recursiveCandidates);
            if (perBatch.isEmpty()) {
                continue;
            }
            long amount = (long) Math.max(1, perBatch.get().getCount()) * batches;
            if (amount > Integer.MAX_VALUE) {
                return false;
            }
            Slot target = transferSlots.recipeSlots().get(i);
            if (!target.allowModification(player) || !target.mayPlace(perBatch.get())) {
                return null;
            }
            requirements.add(new InputRequirement(
                    target,
                    perBatch.get(),
                    (int) amount));
        }

        if (requirements.isEmpty()) {
            return null;
        }

        if (!hasExactSupply(requirements, transferSlots.inventorySlots(), transferSlots.recipeSlots())) {
            return false;
        }

        // Match JEI's normal transfer behaviour by returning any old recipe
        // inputs to the inventory before placing the requested amount.
        for (Slot slot : transferSlots.recipeSlots()) {
            if (!slot.getItem().isEmpty()) {
                if (!slot.allowModification(player)) {
                    return false;
                }
                Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                        menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, player);
            }
        }

        for (InputRequirement requirement : requirements) {
            int remaining = requirement.count();
            while (remaining > 0) {
                Slot source = transferSlots.inventorySlots().stream()
                        .filter(slot -> !slot.getItem().isEmpty())
                        .filter(slot -> RecipeTreeData.ingredientKey(slot.getItem())
                                .equals(RecipeTreeData.ingredientKey(requirement.stack())))
                        .findFirst()
                        .orElse(null);
                if (source == null) {
                    return false;
                }
                int capacity = requirement.target().getMaxStackSize(requirement.stack())
                        - requirement.target().getItem().getCount();
                int move = Math.min(remaining, Math.min(source.getItem().getCount(), capacity));
                if (move <= 0 || !moveItems(menu, player, source, requirement.target(), move)) {
                    return false;
                }
                remaining -= move;
            }
        }
        return true;
    }

    private static Optional<ItemStack> selectedInput(
            IRecipeSlotView slot,
            String selectedKey,
            boolean recursiveCandidates) {
        String preferred = preferredInputKey(slot, selectedKey, recursiveCandidates);
        return slot.getItemStacks()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> preferred.isEmpty() || RecipeTreeData.ingredientKey(stack).equals(preferred))
                .findFirst()
                .map(ItemStack::copy);
    }

    private static boolean hasExactSupply(
            List<InputRequirement> requirements,
            List<Slot> inventorySlots,
            List<Slot> recipeSlots) {
        java.util.Map<String, Long> available = new HashMap<>();
        Stream.concat(inventorySlots.stream(), recipeSlots.stream())
                .map(Slot::getItem)
                .filter(stack -> !stack.isEmpty())
                .forEach(stack -> available.merge(
                        RecipeTreeData.ingredientKey(stack),
                        (long) stack.getCount(),
                        Long::sum));
        for (InputRequirement requirement : requirements) {
            String key = RecipeTreeData.ingredientKey(requirement.stack());
            long count = available.getOrDefault(key, 0L);
            if (count < requirement.count()) {
                return false;
            }
            available.put(key, count - requirement.count());
        }
        return true;
    }

    private static boolean moveItems(
            AbstractContainerMenu menu,
            Player player,
            Slot source,
            Slot target,
            int count) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || !menu.getCarried().isEmpty()) {
            return false;
        }
        ItemStack sourceStack = source.getItem();
        if (sourceStack.isEmpty()) {
            return false;
        }
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, source.index, 0, ClickType.PICKUP, player);
        if (menu.getCarried().isEmpty()) {
            return false;
        }

        int placed = Math.min(count, menu.getCarried().getCount());
        boolean canPlaceAll = target.getItem().isEmpty()
                && placed == menu.getCarried().getCount()
                && target.getMaxStackSize(menu.getCarried()) >= placed;
        if (canPlaceAll) {
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, target.index, 0, ClickType.PICKUP, player);
        } else {
            for (int i = 0; i < placed; i++) {
                minecraft.gameMode.handleInventoryMouseClick(menu.containerId, target.index, 1, ClickType.PICKUP,
                        player);
            }
            if (!menu.getCarried().isEmpty()) {
                minecraft.gameMode.handleInventoryMouseClick(menu.containerId, source.index, 0, ClickType.PICKUP,
                        player);
            }
        }
        return menu.getCarried().isEmpty() && target.getItem().getCount() >= placed;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TransferSlots findTransferSlots(
            IRecipeTransferHandler handler,
            AbstractContainerMenu menu,
            Object recipe) {
        if (handler instanceof BasicRecipeTransferHandlerAccessor accessor) {
            try {
                IRecipeTransferInfo info = accessor.jeiPlusPlus$getTransferInfo();
                return new TransferSlots(
                        List.copyOf(info.getRecipeSlots(menu, recipe)),
                        List.copyOf(info.getInventorySlots(menu, recipe)));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!IRecipeTransferInfo.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    IRecipeTransferInfo info = (IRecipeTransferInfo) field.get(handler);
                    return new TransferSlots(
                            List.copyOf(info.getRecipeSlots(menu, recipe)),
                            List.copyOf(info.getInventorySlots(menu, recipe)));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * During a tree view, JEI's container screen is the parent rather than the
     * current screen. Simulations can use it directly; real transfers first
     * return to it so menu clicks and result extraction target the live menu.
     */
    private static AbstractContainerScreen<?> containerScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.screen;
        if (current instanceof RecipeTreeScreen treeScreen) {
            current = treeScreen.parentScreen();
        }
        return findContainerScreen(current);
    }

    private static boolean exposeParentContainer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RecipeTreeScreen treeScreen) {
            if (findContainerScreen(treeScreen.parentScreen()) == null) {
                return false;
            }
            treeScreen.onClose();
            closeRecipeGuiLayers();
        }
        return containerScreen() != null;
    }

    /** Return through any JEI recipe GUI layers to the actual container screen. */
    private static void closeRecipeGuiLayers() {
        Minecraft minecraft = Minecraft.getInstance();
        for (int depth = 0; depth < 6; depth++) {
            Screen screen = minecraft.screen;
            if (screen == null
                    || screen instanceof AbstractContainerScreen<?>
                    || !screen.getClass().getName().contains("RecipesGui")) {
                return;
            }
            screen.onClose();
        }
    }

    /**
     * JEI's recipe GUI can sit between the tree and the actual container. New
     * JEI versions expose getParentScreen(), while older versions keep the
     * same value in a private field. Resolve both forms so the tree's +
     * transfer behaves like JEI's own transfer button across supported JEI
     * versions.
     */
    private static AbstractContainerScreen<?> findContainerScreen(Screen start) {
        Screen current = start;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current instanceof AbstractContainerScreen<?> screen) {
                return screen;
            }
            Screen parent = parentScreenOf(current);
            if (parent == current) {
                break;
            }
            current = parent;
        }
        return null;
    }

    private static Screen parentScreenOf(Screen screen) {
        if (screen instanceof RecipeTreeScreen treeScreen) {
            return treeScreen.parentScreen();
        }
        if (!screen.getClass().getName().contains("RecipesGui")) {
            return null;
        }
        try {
            Method method = screen.getClass().getMethod("getParentScreen");
            Object value = method.invoke(screen);
            if (value instanceof Optional<?> optional) {
                return optional.orElse(null) instanceof Screen parent ? parent : null;
            }
            if (value instanceof Screen parent) {
                return parent;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 15.x keeps the parent screen private; use the field fallback.
        }
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("parentScreen");
                field.setAccessible(true);
                Object value = field.get(screen);
                return value instanceof Screen parent ? parent : null;
            } catch (NoSuchFieldException ignored) {
                // Continue through the class hierarchy.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static IRecipeSlotsView adjustInputs(
            IRecipeSlotsView original,
            List<String> selectedInputs,
            int batches,
            boolean recursiveCandidates,
            Player player) {
        List<IRecipeSlotView> adjusted = new ArrayList<>();
        int inputIndex = 0;
        for (IRecipeSlotView slot : original.getSlotViews()) {
            if (slot.getRole() == RecipeIngredientRole.INPUT) {
                String selected = preferredInputKey(
                        slot,
                        inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : "",
                        recursiveCandidates);
                adjusted.add(new AdjustedSlot(slot, selected, batches, player));
                inputIndex++;
            } else {
                adjusted.add(slot);
            }
        }
        List<IRecipeSlotView> immutable = List.copyOf(adjusted);
        return () -> immutable;
    }

    private static boolean containsFluidInput(IRecipeSlotsView slots) {
        return slots.getSlotViews().stream()
                .filter(slot -> slot.getRole() == RecipeIngredientRole.INPUT)
                .flatMap(IRecipeSlotView::getAllIngredients)
                .anyMatch(ingredient -> FluidRecipeCompat.fluid(ingredient).isPresent());
    }

    private static int maxBatchesPerTransfer(
            IRecipeSlotsView slots,
            List<String> selectedInputs,
            boolean recursiveCandidates) {
        int result = Integer.MAX_VALUE;
        int inputIndex = 0;
        boolean foundItemInput = false;
        for (IRecipeSlotView slot : slots.getSlotViews()) {
            if (slot.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            String selected = preferredInputKey(
                    slot,
                    inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : "",
                    recursiveCandidates);
            inputIndex++;
            int slotCapacity = RecipeTreeData.candidateStacks(slot).stream()
                    .filter(stack -> selected.isEmpty() || RecipeTreeData.ingredientKey(stack).equals(selected))
                    .mapToInt(stack -> Math.max(1, stack.getMaxStackSize() / Math.max(1, stack.getCount())))
                    .max()
                    .orElse(Integer.MAX_VALUE);
            if (slotCapacity != Integer.MAX_VALUE) {
                foundItemInput = true;
                result = Math.min(result, slotCapacity);
            }
        }
        return foundItemInput ? Math.max(1, result) : 1;
    }

    /**
     * JEI transfer handlers normally use the displayed ingredient of a slot.
     * For an unfixed tag/directory input that used to be the first entry,
     * even when another candidate was the one actually present in the
     * inventory or can be reached by recursively crafting its inputs. Resolve
     * a concrete candidate at transfer time instead.
     */
    private static String preferredInputKey(
            IRecipeSlotView slot,
            String selectedKey,
            boolean recursiveCandidates) {
        if (!selectedKey.isEmpty()) {
            return selectedKey;
        }
        // A normal left-click is deliberately direct-only. Recursive
        // candidate search is reserved for the explicit Ctrl-click crafting
        // path; otherwise use only an inventory/network match or JEI's normal
        // first-candidate fallback.
        return RecipeTreeData.findCandidateWithSupply(
                RecipeTreeData.candidateStacks(slot),
                recursiveCandidates && RecipeTreeSession.tree() != null)
                .map(RecipeTreeData::ingredientKey)
                .orElse("");
    }

    private static int instantResultSlot(AbstractContainerMenu menu) {
        int networkResult = StorageNetworkIntegration.craftingResultSlot(menu);
        if (networkResult >= 0) {
            return networkResult;
        }
        if (menu instanceof CraftingMenu || menu instanceof InventoryMenu)
            return 0;
        if (menu instanceof StonecutterMenu)
            return 1;
        if (menu instanceof SmithingMenu || menu instanceof LoomMenu)
            return 3;
        if (menu instanceof CartographyTableMenu || menu instanceof GrindstoneMenu)
            return 2;
        return -1;
    }

    /**
     * AE2's crafting terminal has a server-authoritative packet that fills its
     * 3x3 matrix directly from ME storage. Use it for recipe-tree transfers so
     * network items behave like player-inventory inputs without adding a JEI++
     * server packet or submitting an AE autocrafting job.
     */
    private static Boolean tryNetworkCraftingTransfer(
            AbstractContainerMenu menu,
            IRecipeLayoutDrawable<?> layout,
            List<String> selectedInputs,
            boolean doTransfer,
            boolean recursiveCandidates) {
        if (!(layout.getRecipe() instanceof CraftingRecipe)) {
            return null;
        }
        List<ItemStack> templates = ae2Templates(layout, selectedInputs, recursiveCandidates);
        if (templates == null) {
            return null;
        }
        return StorageNetworkIntegration.tryFillCraftingGrid(menu, templates, doTransfer);
    }

    private static List<ItemStack> ae2Templates(
            IRecipeLayoutDrawable<?> layout,
            List<String> selectedInputs,
            boolean recursiveCandidates) {
        List<IRecipeSlotView> inputs = layout.getRecipeSlotsView().getSlotViews().stream()
                .filter(slot -> slot.getRole() == RecipeIngredientRole.INPUT)
                .toList();
        if (inputs.size() != 9) {
            return null;
        }

        List<ItemStack> templates = new ArrayList<>(9);
        for (int index = 0; index < inputs.size(); index++) {
            IRecipeSlotView input = inputs.get(index);
            String selected = preferredInputKey(
                    input,
                    index < selectedInputs.size() ? selectedInputs.get(index) : "",
                    recursiveCandidates);
            ItemStack template = RecipeTreeData.candidateStacks(input).stream()
                    .filter(stack -> !stack.isEmpty())
                    .filter(stack -> selected.isEmpty() || RecipeTreeData.ingredientKey(stack).equals(selected))
                    .findFirst()
                    .map(ItemStack::copy)
                    .orElse(ItemStack.EMPTY);
            if (!template.isEmpty()) {
                template.setCount(1);
            }
            templates.add(template);
        }
        return templates;
    }

    private static void clearPending() {
        pendingCrafts = List.of();
        pendingCraftIndex = 0;
        pendingMenuId = -1;
        pendingResultSlot = -1;
        pendingChunk = 0;
        pendingLoadedBatches = 0;
        pendingCraftResultSlot = -1;
        pendingAe2Carry = false;
        pendingAe2NetworkDeposit = false;
        pendingAe2NetworkRequest = false;
        pendingAe2NetworkCount = 0;
        pendingAe2PlacementSlot = -1;
        pendingAe2PlacementInFlight = false;
        pendingAe2PlacementCount = 0;
        pendingAe2PlacementInventoryBefore = 0;
        pendingAe2OutputKey = "";
        pendingAe2OutputStack = ItemStack.EMPTY;
        pendingAe2InventoryBefore = 0;
        pendingVanillaPickup = false;
        pendingPickupAttempts = 0;
        waitFrames = 0;
        emptyFrames = 0;
    }

    private static final class PendingCraft {
        private final RecipeTreeData.CraftStep step;
        private long remainingBatches;

        private PendingCraft(RecipeTreeData.CraftStep step, long remainingBatches) {
            this.step = step;
            this.remainingBatches = remainingBatches;
        }
    }

    private record TransferSlots(List<Slot> recipeSlots, List<Slot> inventorySlots) {
    }

    private record InputRequirement(Slot target, ItemStack stack, int count) {
    }

    private static final class AdjustedSlot implements IRecipeSlotView {
        private final IRecipeSlotView delegate;
        private final List<ITypedIngredient<?>> ingredients;

        private AdjustedSlot(IRecipeSlotView delegate, String selectedKey, int batches, Player player) {
            this.delegate = delegate;
            IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
            List<ITypedIngredient<?>> adjusted = new ArrayList<>();
            if (runtime != null) {
                delegate.getAllIngredients().forEach(ingredient -> {
                    Optional<ItemStack> itemStack = ingredient.getItemStack();
                    if (itemStack.isEmpty()) {
                        // Preserve FluidStack ingredients for custom tank
                        // handlers and add matching filled containers for
                        // handlers that expose an item input slot instead.
                        Optional<net.minecraftforge.fluids.FluidStack> fluid = FluidRecipeCompat.fluid(ingredient);
                        if (fluid.isPresent()) {
                            String fluidKey = FluidRecipeCompat.fluidKey(fluid.get());
                            if (selectedKey.isEmpty() || selectedKey.equals(fluidKey)) {
                                FluidRecipeCompat.scaled(ingredient, batches, runtime.getIngredientManager())
                                        .ifPresent(adjusted::add);
                            }
                            for (ItemStack container : FluidRecipeCompat.matchingContainers(player, fluid.get(),
                                    batches)) {
                                if (selectedKey.isEmpty()
                                        || RecipeTreeData.ingredientKey(container).equals(selectedKey)) {
                                    runtime.getIngredientManager()
                                            .createTypedIngredient(VanillaTypes.ITEM_STACK, container)
                                            .ifPresent(adjusted::add);
                                }
                            }
                        } else {
                            adjusted.add(ingredient);
                        }
                        return;
                    }
                    ItemStack stack = itemStack.get();
                    if (!selectedKey.isEmpty() && !RecipeTreeData.ingredientKey(stack).equals(selectedKey)) {
                        return;
                    }
                    ItemStack scaled = stack.copy();
                    long count = (long) Math.max(1, stack.getCount()) * batches;
                    if (count > stack.getMaxStackSize()) {
                        return;
                    }
                    scaled.setCount((int) count);
                    runtime.getIngredientManager()
                            .createTypedIngredient(VanillaTypes.ITEM_STACK, scaled)
                            .ifPresent(adjusted::add);
                });
            }
            this.ingredients = adjusted.isEmpty()
                    ? delegate.getAllIngredients().toList()
                    : List.copyOf(adjusted);
        }

        @Override
        public Stream<ITypedIngredient<?>> getAllIngredients() {
            return ingredients.stream();
        }

        /** Required by JEI 15.48; harmless as an extra method on older JEI. */
        public List<ITypedIngredient<?>> getAllIngredientsList() {
            return ingredients;
        }

        @Override
        public Optional<ITypedIngredient<?>> getDisplayedIngredient() {
            return ingredients.stream().findFirst();
        }

        /** Added as an abstract method by JEI 15.58; harmless on older JEI. */
        public Stream<ITypedIngredient<?>> getDisplayedIngredients() {
            return ingredients.stream();
        }

        /** Added as an abstract method by JEI 15.58. */
        public Optional<TagKey<?>> getTagKey() {
            return Optional.empty();
        }

        @Override
        public RecipeIngredientRole getRole() {
            return delegate.getRole();
        }
        @Override
        public void drawHighlight(GuiGraphics graphics, int color) {
            delegate.drawHighlight(graphics, color);
        }
        @Override
        public Optional<String> getSlotName() {
            return delegate.getSlotName();
        }
    }
}
