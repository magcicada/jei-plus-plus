package com.lingmu0.JeiPlusPlusMod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional integrations for network-backed crafting terminals.
 *
 * <p>
 * Normal crafting slots and result slots are deliberately left to JEI's
 * normal container-click transfer path. RS and Beyond Dimensions use fake
 * storage slots, however, so a vanilla click cannot extract an item from the
 * network. For those menus this class calls the mod's own client API/packet
 * only for the recipe-transfer operation. Every reference is reflective so
 * JEI++ remains a client-only, optional integration.
 * </p>
 */
final class StorageNetworkIntegration {
    private static final long CACHE_FALLBACK_NANOS = 50_000_000L;
    private static final String RS1_MENU = "com.refinedmods.refinedstorage.container.GridContainerMenu";
    private static final String RS2_MENU = "com.refinedmods.refinedstorage.common.grid.AbstractCraftingGridContainerMenu";
    private static final String RS1_SCREEN = "com.refinedmods.refinedstorage.screen.grid.GridScreen";
    private static final String BD_MENU = "com.wintercogs.beyonddimensions.common.menu.DimensionsCraftMenu";
    private static final String BBD_MENU_ACCESS = "net.xuwu.betterbeyonddimensions.common.NetworkStorageMenuAccess";
    private static final String BBD_NETWORK_SLOT = "net.xuwu.betterbeyonddimensions.common.NetworkStorageSlot";
    private static final String BBD_CLIENT_STORAGE_STATE = "net.xuwu.betterbeyonddimensions.client.ClientStorageState";
    private static final String BBD_RECIPE_FILL = "net.xuwu.betterbeyonddimensions.common.RecipeFill";
    private static final String BBD_NETWORK_HANDLER = "net.xuwu.betterbeyonddimensions.NetworkHandler";
    private static final String BBD_SIDEBAR_RENDERER = "net.xuwu.betterbeyonddimensions.client.SidebarRenderer";
    private static final String IT_MENU = "org.cyclops.integratedterminals.inventory.container.ContainerTerminalStorageBase";
    private static final String IT_SCREEN = "org.cyclops.integratedterminals.client.gui.container.ContainerScreenTerminalStorage";

    private static volatile Object cachedMenu;
    private static volatile long cachedGameTime = Long.MIN_VALUE;
    private static volatile long cachedAtNanos = Long.MIN_VALUE;
    private static volatile long cachedSnapshotRevision;
    private static volatile List<StoredStack> cachedStacks = List.of();
    private static volatile List<StoredFluid> cachedFluids = List.of();
    private static volatile Object prioritizedMenu;
    private static volatile Set<String> prioritizedKeys = Set.of();
    private static volatile long prioritizedSnapshotRevision = Long.MIN_VALUE;
    /** A partition requested by a tree refresh, applied before the next draw. */
    private static volatile Set<String> pendingPrioritizedKeys = Set.of();
    private static volatile boolean priorityPending;
    private static volatile Object prioritizedBetterBeyondView;
    private static volatile List<?> betterBeyondNativeEntries = List.of();
    private static volatile List<?> betterBeyondAppliedEntries = List.of();

    private StorageNetworkIntegration() {
    }

    static List<StoredStack> storedStacks() {
        Object menu = Ae2StorageIntegration.activeMenu();
        long now = System.nanoTime();
        if (menu == null) {
            if (cachedMenu != null || !cachedStacks.isEmpty() || !cachedFluids.isEmpty()) {
                cachedSnapshotRevision++;
            }
            cachedMenu = null;
            cachedGameTime = Long.MIN_VALUE;
            cachedAtNanos = now;
            cachedStacks = List.of();
            cachedFluids = List.of();
            prioritizedMenu = null;
            prioritizedKeys = Set.of();
            prioritizedSnapshotRevision = Long.MIN_VALUE;
            pendingPrioritizedKeys = Set.of();
            priorityPending = false;
            prioritizedBetterBeyondView = null;
            betterBeyondNativeEntries = List.of();
            betterBeyondAppliedEntries = List.of();
            return List.of();
        }
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level == null ? -1L : minecraft.level.getGameTime();
        long cacheAge = now - cachedAtNanos;
        if (menu == cachedMenu
                && gameTime == cachedGameTime
                && cacheAge >= 0L
                && cacheAge < CACHE_FALLBACK_NANOS) {
            return cachedStacks;
        }

        boolean refinedMenu = isRefinedStorageMenu(menu);
        boolean beyondMenu = isBeyondMenu(menu);
        boolean betterBeyondMenu = isBetterBeyondMenu(menu);
        boolean integratedMenu = isIntegratedTerminalMenu(menu);
        boolean ae2CraftingMenu = !refinedMenu && !beyondMenu && !betterBeyondMenu && !integratedMenu
                && Ae2StorageIntegration.isCraftingMenu(menu);
        // Do not probe AE2's reflective repository for a terminal that is
        // known to belong to another storage mod. The old order paid the
        // AE2 class/method lookup cost on every 50ms refresh of RS/Beyond/IT.
        List<StoredStack> ae2 = new ArrayList<>();
        List<StoredFluid> ae2Fluids = new ArrayList<>();
        if (!refinedMenu && !beyondMenu && !betterBeyondMenu && !integratedMenu) {
            for (Ae2StorageIntegration.StoredStack stored : Ae2StorageIntegration.storedStacks()) {
                ae2.add(new StoredStack(stored.stack(), stored.amount()));
            }
            for (StorageNetworkIntegration.StoredFluid stored : Ae2StorageIntegration.storedFluids()) {
                ae2Fluids.add(new StoredFluid(stored.key(), stored.amount()));
            }
        }
        List<StoredStack> result;
        List<StoredFluid> fluidResult;
        if (!ae2.isEmpty() || !ae2Fluids.isEmpty() || ae2CraftingMenu) {
            result = ae2;
            fluidResult = ae2Fluids;
        } else {
            RefinedSnapshot refined = refinedStorageSnapshot(menu);
            if (!refined.stacks().isEmpty() || !refined.fluids().isEmpty() || refinedMenu) {
                result = refined.stacks();
                fluidResult = refined.fluids();
            } else {
                BeyondSnapshot beyond = beyondSnapshot(menu);
                if (!beyond.stacks().isEmpty() || !beyond.fluids().isEmpty() || beyondMenu) {
                    result = beyond.stacks();
                    fluidResult = beyond.fluids();
                } else {
                    BetterBeyondSnapshot betterBeyond = betterBeyondSnapshot(menu);
                    if (!betterBeyond.stacks().isEmpty() || !betterBeyond.fluids().isEmpty()
                            || betterBeyondMenu) {
                        result = betterBeyond.stacks();
                        fluidResult = betterBeyond.fluids();
                    } else {
                        result = integratedTerminalStacks(menu);
                        fluidResult = integratedTerminalFluids(menu);
                    }
                }
            }
        }
        fluidResult = mergeFluids(fluidResult);
        boolean snapshotChanged = menu != cachedMenu
                || !sameSnapshot(cachedStacks, result)
                || !sameFluidSnapshot(cachedFluids, fluidResult);
        cachedMenu = menu;
        cachedGameTime = gameTime;
        cachedAtNanos = now;
        cachedStacks = List.copyOf(result);
        cachedFluids = List.copyOf(fluidResult);
        if (snapshotChanged) {
            cachedSnapshotRevision++;
        }
        return cachedStacks;
    }

    /**
     * Version of the combined client-side storage snapshot. It advances when
     * the cache is queried again, including the real-time fallback used while
     * an AE screen is waiting for its initial repository sync packet.
     */
    static long snapshotRevision() {
        storedStacks();
        return cachedSnapshotRevision;
    }

    static List<StoredFluid> storedFluids() {
        storedStacks();
        return cachedFluids;
    }

    /** Returns both network lists from one shared client-side snapshot. */
    static StorageSnapshot snapshot() {
        List<StoredStack> stacks = storedStacks();
        return new StorageSnapshot(stacks, cachedFluids, cachedSnapshotRevision);
    }

    record StorageSnapshot(List<StoredStack> stacks, List<StoredFluid> fluids, long revision) {
        StorageSnapshot {
            stacks = List.copyOf(stacks == null ? List.of() : stacks);
            fluids = List.copyOf(fluids == null ? List.of() : fluids);
        }
    }

    private static boolean sameSnapshot(List<StoredStack> previous, List<StoredStack> next) {
        // The terminal integrations are allowed to reorder their view lists
        // when JEI++ moves highlighted entries to the front. Comparing by
        // list position made that purely visual reorder look like a storage
        // update, which bumped the snapshot revision and caused the next
        // refresh to undo/reapply sorting repeatedly. Compare the actual
        // inventory contents instead of the current UI order.
        return stackAmounts(previous).equals(stackAmounts(next));
    }

    private static boolean sameFluidSnapshot(List<StoredFluid> previous, List<StoredFluid> next) {
        Map<String, Long> left = new java.util.HashMap<>();
        Map<String, Long> right = new java.util.HashMap<>();
        if (previous != null) {
            for (StoredFluid value : previous) {
                if (value != null && !value.key.isEmpty() && value.amount > 0) {
                    left.merge(value.key, value.amount, StorageNetworkIntegration::safeAdd);
                }
            }
        }
        if (next != null) {
            for (StoredFluid value : next) {
                if (value != null && !value.key.isEmpty() && value.amount > 0) {
                    right.merge(value.key, value.amount, StorageNetworkIntegration::safeAdd);
                }
            }
        }
        return left.equals(right);
    }

    private static Map<String, Long> stackAmounts(List<StoredStack> values) {
        Map<String, Long> amounts = new java.util.HashMap<>();
        if (values != null) {
            for (StoredStack value : values) {
                if (value != null && !value.key.isEmpty() && value.amount > 0) {
                    amounts.merge(value.key, value.amount, StorageNetworkIntegration::safeAdd);
                }
            }
        }
        return amounts;
    }

    private static List<StoredFluid> mergeFluids(List<StoredFluid> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Map<String, Long> amounts = new java.util.LinkedHashMap<>();
        for (StoredFluid value : values) {
            if (value != null && !value.key.isEmpty() && value.amount > 0) {
                amounts.merge(value.key, value.amount, StorageNetworkIntegration::safeAdd);
            }
        }
        return amounts.entrySet().stream()
                .map(entry -> new StoredFluid(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, left + right);
    }

    /** Queues a stable network-list partition for the next pre-render pass. */
    static void queueVisibleEntries(Set<String> keys) {
        pendingPrioritizedKeys = keys == null || keys.isEmpty() ? Set.of() : Set.copyOf(keys);
        priorityPending = true;
    }

    /** Applies the queued partition before the terminal draws its native view. */
    static void applyPendingVisibleEntries() {
        if (!priorityPending) {
            return;
        }
        Set<String> keys = pendingPrioritizedKeys;
        priorityPending = false;
        pendingPrioritizedKeys = Set.of();
        prioritizeVisibleEntries(keys);
    }

    /** Moves network-backed entries ahead of ordinary entries where the mod exposes a mutable view. */
    static void prioritizeVisibleEntries(Set<String> keys) {
        Object menu = Ae2StorageIntegration.activeMenu();
        Set<String> normalizedKeys = keys == null || keys.isEmpty() ? Set.of() : Set.copyOf(keys);
        long snapshotRevision = cachedSnapshotRevision;
        if (menu == null) {
            prioritizedMenu = null;
            prioritizedKeys = Set.of();
            prioritizedSnapshotRevision = Long.MIN_VALUE;
            return;
        }
        // Do not use the snapshot revision as a blanket early-out here. A
        // terminal's own quantity/name sort can reorder the same resources
        // without changing their contents. Each integration below checks
        // whether its visible list is already highlighted-first and reapplies
        // the stable partition only when the UI order actually needs repair.
        try {
            Ae2StorageIntegration.prioritizeVisibleEntries(normalizedKeys);
            if (!normalizedKeys.isEmpty() || !prioritizedKeys.isEmpty()) {
                prioritizeRs2(menu, normalizedKeys);
                prioritizeRs1(normalizedKeys);
                prioritizeBeyond(menu, normalizedKeys);
                prioritizeBetterBeyond(menu, normalizedKeys);
                prioritizeIntegrated(menu, normalizedKeys);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Optional internal APIs are allowed to change without affecting JEI++.
        } finally {
            prioritizedMenu = menu;
            prioritizedKeys = normalizedKeys;
            prioritizedSnapshotRevision = snapshotRevision;
        }
    }

    /**
     * Uses a network mod's own recipe-transfer packet when its slots are fake.
     * A null result means the menu is not one of the supported network menus,
     * allowing the caller to fall back to JEI's generic transfer handler.
     */
    static Boolean tryFillCraftingGrid(
            AbstractContainerMenu menu,
            List<ItemStack> templates,
            boolean send) {
        Boolean ae2 = Ae2StorageIntegration.tryFillCraftingGrid(menu, templates, send);
        if (ae2 != null) {
            return ae2;
        }
        if (isRs1Menu(menu)) {
            return sendRs1Recipe(menu, templates, send);
        }
        if (isRs2Menu(menu)) {
            return sendRs2Recipe(menu, templates, send);
        }
        if (isBeyondMenu(menu)) {
            return sendBeyondRecipe(menu, templates, send);
        }
        if (isBetterBeyondMenu(menu)) {
            return sendBetterBeyondRecipe(menu, templates, send);
        }
        return null;
    }

    static Boolean craftingGridMatches(Object menu, List<ItemStack> templates) {
        Boolean ae2 = Ae2StorageIntegration.craftingGridMatches(menu, templates);
        if (ae2 != null) {
            return ae2;
        }
        try {
            List<ItemStack> actual = new ArrayList<>(9);
            if (isRs1Menu(menu)) {
                Object grid = invokeNoArg(menu, "getGrid");
                Object matrix = invokeNoArg(grid, "getCraftingMatrix");
                readContainer(matrix, actual, 9);
            } else if (isRs2Menu(menu)) {
                Object slots = invokeNoArg(menu, "getCraftingMatrixSlots");
                if (!(slots instanceof List<?> list) || list.size() < 9) {
                    return false;
                }
                for (int i = 0; i < 9; i++) {
                    Object slot = list.get(i);
                    actual.add(stackOf(invokeNoArg(slot, "getItem")));
                }
            } else if (isBeyondMenu(menu)) {
                int start = intValue(readField(menu, "craftSlotStartIndex"));
                int end = intValue(readField(menu, "craftSlotEndIndex"));
                if (start < 0 || end - start < 9) {
                    return false;
                }
                for (int i = 0; i < 9; i++) {
                    actual.add(stackOf(invokeNoArg(invoke(menu, "getSlot", int.class, start + i), "getItem")));
                }
            } else if (isBetterBeyondMenu(menu)) {
                List<Integer> slots = betterBeyondCraftingSlotIds(menu);
                if (slots.size() < 9) {
                    return false;
                }
                for (int index = 0; index < 9; index++) {
                    actual.add(stackOf(menu instanceof AbstractContainerMenu container
                            ? container.getSlot(slots.get(index)).getItem()
                            : null));
                }
            } else if (isIntegratedTerminalMenu(menu)) {
                Object commonTab = integratedSelectedCommonTab(menu);
                Object matrix = invokeNoArg(commonTab, "getInventoryCrafting");
                readContainer(matrix, actual, 9);
            } else {
                return null;
            }
            if (actual.size() < templates.size()) {
                return false;
            }
            for (int i = 0; i < templates.size(); i++) {
                ItemStack expected = templates.get(i);
                ItemStack present = actual.get(i);
                if (expected == null || expected.isEmpty()) {
                    if (!present.isEmpty()) {
                        return false;
                    }
                } else if (present.isEmpty()
                        || !RecipeTreeData.ingredientKey(expected).equals(RecipeTreeData.ingredientKey(present))) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static int craftingResultSlot(AbstractContainerMenu menu) {
        int ae2 = Ae2StorageIntegration.craftingResultSlot(menu);
        if (ae2 >= 0 || menu == null) {
            return ae2;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            String name = menu.getSlot(i).getClass().getName();
            if (name.endsWith("ResultCraftingGridSlot")
                    || name.endsWith("CraftingGridResultSlot")
                    || name.endsWith("AutoRefillResultSlot")) {
                return i;
            }
        }
        if (isIntegratedTerminalMenu(menu)) {
            try {
                Object commonTab = integratedSelectedCommonTab(menu);
                Object result = invokeNoArg(commonTab, "getSlotCrafting");
                for (int i = 0; i < menu.slots.size(); i++) {
                    if (menu.getSlot(i) == result) {
                        return i;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Optional Integrated Terminals internals.
            }
        }
        return -1;
    }

    /**
     * Network menus must take one result with a normal pickup. RS and Beyond
     * Dimensions override or special-case quick-move, so using QUICK_MOVE can
     * leave the result in place forever and stall the recursive queue.
     */
    static Boolean takeCraftingResult(Object menu, int resultSlot, boolean send) {
        Boolean ae2 = Ae2StorageIntegration.takeCraftingResult(menu, resultSlot, send);
        if (ae2 != null) {
            return ae2;
        }
        if (!isRefinedStorageMenu(menu) && !isBeyondMenu(menu) && !isIntegratedTerminalMenu(menu)) {
            return null;
        }
        if (!send) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(menu instanceof AbstractContainerMenu container)
                || minecraft.player == null
                || minecraft.gameMode == null
                || resultSlot < 0
                || resultSlot >= container.slots.size()) {
            return false;
        }
        minecraft.gameMode.handleInventoryMouseClick(
                container.containerId,
                resultSlot,
                0,
                ClickType.PICKUP,
                minecraft.player);
        return true;
    }

    /** Uses each network terminal's own cursor-insertion hook when available. */
    static Boolean putCarriedItemIntoNetwork(Object menu, boolean single) {
        Boolean ae2 = Ae2StorageIntegration.putCarriedItemIntoNetwork(menu, single);
        if (ae2 != null) {
            return ae2;
        }
        try {
            if (isRs1Menu(menu)) {
                Class<?> messageType = Class.forName(
                        "com.refinedmods.refinedstorage.network.grid.GridItemInsertHeldMessage");
                Object message = constructor(messageType, boolean.class).newInstance(single);
                Class<?> rsType = Class.forName("com.refinedmods.refinedstorage.RS");
                Object handler = readStaticField(rsType, "NETWORK_HANDLER");
                Method sender = findCompatibleMethod(handler == null ? null : handler.getClass(), "sendToServer",
                        messageType);
                if (handler != null && sender != null) {
                    sender.invoke(handler, message);
                    return true;
                }
                return false;
            }
            if (isRs2Menu(menu)) {
                Class<?> modeType = Class.forName(
                        "com.refinedmods.refinedstorage.api.network.node.grid.GridInsertMode");
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object mode = Enum.valueOf(
                        (Class<? extends Enum>) modeType.asSubclass(Enum.class),
                        single ? "SINGLE_RESOURCE" : "ENTIRE_RESOURCE");
                Class<?> packets = Class.forName(
                        "com.refinedmods.refinedstorage.common.support.packet.c2s.C2SPackets");
                Method sender = findCompatibleMethod(packets, "sendGridInsert", modeType, boolean.class);
                if (sender != null) {
                    sender.invoke(null, mode, true);
                    return true;
                }
                return false;
            }
            if (isBeyondMenu(menu)) {
                return sendBeyondCursorInsert(menu);
            }
            if (isIntegratedTerminalMenu(menu)) {
                return sendIntegratedCursorInsert(menu);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Optional network insertion APIs.
        }
        return null;
    }

    private static Boolean sendBeyondCursorInsert(Object menu) {
        try {
            Slot target = null;
            for (Slot slot : menu instanceof AbstractContainerMenu container ? container.slots : List.<Slot>of()) {
                String name = slot.getClass().getName();
                if (name.contains("beyonddimensions") && name.contains("StackTypedSlot")) {
                    target = slot;
                    break;
                }
            }
            if (target == null) {
                return false;
            }
            Object clickItem = invokeNoArg(target, "getVanillaActualStack");
            Class<?> packetType = Class.forName(
                    "com.wintercogs.beyonddimensions.network.packet.c2s.CallSeverClickPacket");
            Constructor<?> packetConstructor = constructor(
                    packetType,
                    int.class,
                    clickItem == null ? Object.class : clickItem.getClass(),
                    int.class,
                    boolean.class);
            if (packetConstructor == null) {
                return false;
            }
            int index = intValue(readField(target, "index"));
            if (index < 0) {
                index = intValue(invokeNoArg(target, "getContainerSlot"));
            }
            Object packet = packetConstructor.newInstance(index, clickItem, 0, false);
            for (String distributorName : List.of(
                    "net.neoforged.neoforge.network.PacketDistributor",
                    "net.minecraftforge.network.PacketDistributor")) {
                try {
                    Class<?> distributor = Class.forName(distributorName);
                    Method sender = findCompatibleMethod(distributor, "sendToServer", packetType);
                    if (sender != null) {
                        sender.invoke(null, packet);
                        return true;
                    }
                } catch (ClassNotFoundException ignored) {
                    // Try the other loader's packet distributor.
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Optional Beyond Dimensions network click packet.
        }
        return false;
    }

    /** Lets Integrated Terminals build and send its own PLAYER_PLACE_STORAGE packet. */
    private static Boolean sendIntegratedCursorInsert(Object menu) {
        try {
            Object tab = integratedItemClientTab(menu);
            if (tab == null || !(menu instanceof AbstractContainerMenu container)) {
                return false;
            }
            int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
            Method click = findCompatibleMethod(
                    tab.getClass(),
                    "handleClick",
                    AbstractContainerMenu.class,
                    int.class,
                    int.class,
                    int.class,
                    boolean.class,
                    boolean.class,
                    int.class,
                    boolean.class);
            if (click == null) {
                return false;
            }
            Object handled = click.invoke(tab, container, channel, 0, 0, false, true, -1, false);
            return Boolean.TRUE.equals(handled);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static boolean isCraftingMenu(Object menu) {
        return Ae2StorageIntegration.isCraftingMenu(menu)
                || isRs1Menu(menu)
                || isRs2Menu(menu)
                || isBeyondMenu(menu)
                || isIntegratedTerminalMenu(menu);
    }

    /** Reads RS 1.x/2.x item and fluid views in one pass when they share a list. */
    private static RefinedSnapshot refinedStorageSnapshot(Object menu) {
        try {
            if (isRs1Menu(menu)) {
                AbstractContainerScreen<?> screen = activeContainerScreen();
                if (screen != null && classOrSuper(screen.getClass(), RS1_SCREEN)) {
                    Object view = invokeNoArg(screen, "getView");
                    Object entries = invokeNoArg(view, "getAllStacks");
                    if (entries instanceof Iterable<?> iterable) {
                        List<StoredStack> stacks = new ArrayList<>();
                        List<StoredFluid> fluids = new ArrayList<>();
                        for (Object entry : iterable) {
                            ItemStack stack = rs1EntryStack(entry);
                            long amount = numberValue(invokeNoArg(entry, "getQuantity"));
                            if (!stack.isEmpty() && amount > 0) {
                                stacks.add(new StoredStack(stack, amount));
                            } else {
                                StoredFluid fluid = fluidEntry(entry, "getQuantity", null);
                                if (fluid != null) {
                                    fluids.add(fluid);
                                }
                            }
                        }
                        return new RefinedSnapshot(List.copyOf(stacks), List.copyOf(fluids));
                    }
                }
                Object grid = invokeNoArg(menu, "getGrid");
                List<StoredStack> stacks = new ArrayList<>();
                Object cache = invokeNoArg(grid, "getStorageCache");
                Object list = invokeNoArg(cache, "getList");
                Object entries = invokeNoArg(list, "getStacks");
                if (entries instanceof Iterable<?> iterable) {
                    for (Object entry : iterable) {
                        ItemStack stack = stackOf(entry);
                        if (stack.isEmpty()) {
                            stack = stackOf(invokeNoArg(entry, "getStack"));
                        }
                        if (!stack.isEmpty() && stack.getCount() > 0) {
                            stacks.add(new StoredStack(stack, stack.getCount()));
                        }
                    }
                }
                List<StoredFluid> fluids = new ArrayList<>();
                for (String cacheName : List.of("getFluidStorageCache", "getFluidCache")) {
                    Object fluidCache = invokeNoArg(grid, cacheName);
                    Object fluidList = invokeNoArg(fluidCache, "getList");
                    Object fluidEntries = invokeNoArg(fluidList, "getStacks");
                    fluids.addAll(collectFluidEntries(fluidEntries, null));
                    if (!fluids.isEmpty()) {
                        break;
                    }
                }
                return new RefinedSnapshot(List.copyOf(stacks), List.copyOf(fluids));
            }
            if (isRs2Menu(menu)) {
                Object repository = invokeNoArg(menu, "getRepository");
                Object entries = invokeNoArg(repository, "getViewList");
                if (entries instanceof Iterable<?> iterable) {
                    List<StoredStack> stacks = new ArrayList<>();
                    List<StoredFluid> fluids = new ArrayList<>();
                    for (Object entry : iterable) {
                        String name = entry == null ? "" : entry.getClass().getName();
                        if (name.endsWith("ItemGridResource")) {
                            ItemStack stack = stackOf(invokeNoArg(entry, "getItemStack"));
                            long amount = numberValue(invoke(entry, "getAmount", repository.getClass(), repository));
                            if (amount <= 0) {
                                amount = stack.getCount();
                            }
                            if (!stack.isEmpty() && amount > 0) {
                                stack.setCount(1);
                                stacks.add(new StoredStack(stack, amount));
                            }
                        } else {
                            StoredFluid fluid = fluidEntry(entry, null, repository);
                            if (fluid != null) {
                                fluids.add(fluid);
                            }
                        }
                    }
                    return new RefinedSnapshot(List.copyOf(stacks), List.copyOf(fluids));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Optional RS APIs.
        }
        return RefinedSnapshot.EMPTY;
    }

    private record RefinedSnapshot(List<StoredStack> stacks, List<StoredFluid> fluids) {
        private static final RefinedSnapshot EMPTY = new RefinedSnapshot(List.of(), List.of());
    }

    private static List<StoredStack> integratedTerminalStacks(Object menu) {
        if (!isIntegratedTerminalMenu(menu)) {
            return List.of();
        }
        try {
            Object tab = integratedItemClientTab(menu);
            if (tab == null) {
                return List.of();
            }
            int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
            Object entries = invoke(tab, "createUnfilteredIngredientsView", int.class, channel);
            if (!(entries instanceof Iterable<?> iterable)) {
                return List.of();
            }
            List<StoredStack> result = new ArrayList<>();
            for (Object entry : iterable) {
                // Crafting-option rows are recipes, not items physically stored
                // in the Integrated Dynamics network.
                if (invokeNoArg(entry, "getCraftingOption") != null) {
                    continue;
                }
                ItemStack stack = stackOf(invokeNoArg(entry, "getInstance"));
                if (!stack.isEmpty() && stack.getCount() > 0) {
                    result.add(new StoredStack(stack, stack.getCount()));
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    private static List<StoredFluid> integratedTerminalFluids(Object menu) {
        if (!isIntegratedTerminalMenu(menu)) {
            return List.of();
        }
        try {
            Object tab = integratedFluidClientTab(menu);
            if (tab == null) {
                return List.of();
            }
            int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
            Object entries = invoke(tab, "createUnfilteredIngredientsView", int.class, channel);
            return collectFluidEntries(entries, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    /** Scans Beyond Dimensions' mixed item/fluid storage only once per snapshot. */
    private static BeyondSnapshot beyondSnapshot(Object menu) {
        if (!isBeyondMenu(menu)) {
            return BeyondSnapshot.EMPTY;
        }
        try {
            Object storage = readField(menu, "storage");
            Object entries = invokeNoArg(storage, "getStorage");
            if (!(entries instanceof Iterable<?> iterable)) {
                return BeyondSnapshot.EMPTY;
            }
            List<StoredStack> stacks = new ArrayList<>();
            List<StoredFluid> fluids = new ArrayList<>();
            for (Object entry : iterable) {
                if (entry == null || invokeNoArg(entry, "getCraftingOption") != null) {
                    continue;
                }
                long amount = numberValue(invokeNoArg(entry, "amount"));
                if (amount <= 0) {
                    continue;
                }
                Object key = invokeNoArg(entry, "key");
                ItemStack stack = storageItemStack(key);
                if (!stack.isEmpty()) {
                    stack.setCount(1);
                    stacks.add(new StoredStack(stack, amount));
                    continue;
                }
                var info = FluidRecipeCompat.describeFluid(key == null ? entry : key);
                if (info.isEmpty()) {
                    Object instance = invokeNoArg(entry, "getInstance");
                    info = FluidRecipeCompat.describeFluid(instance);
                }
                if (info.isPresent()) {
                    long fluidAmount = Math.max(amount, info.get().amount());
                    if (fluidAmount > 0) {
                        fluids.add(new StoredFluid(info.get().key(), fluidAmount));
                    }
                }
            }
            return new BeyondSnapshot(List.copyOf(stacks), List.copyOf(fluids));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return BeyondSnapshot.EMPTY;
        }
    }

    private record BeyondSnapshot(List<StoredStack> stacks, List<StoredFluid> fluids) {
        private static final BeyondSnapshot EMPTY = new BeyondSnapshot(List.of(), List.of());
    }

    /** Reads Better Beyond Dimensions' client-authoritative sidebar snapshot. */
    private static BetterBeyondSnapshot betterBeyondSnapshot(Object menu) {
        if (!isBetterBeyondMenu(menu)) {
            return BetterBeyondSnapshot.EMPTY;
        }
        try {
            Class<?> stateType = Class.forName(BBD_CLIENT_STORAGE_STATE);
            // The 0.4.x sidebar can be hidden while its network snapshot is
            // still valid. Recipe-tree planning must continue to see storage
            // in that state; visual sorting below handles the hidden case.
            if (!Boolean.TRUE.equals(invokeStaticNoArg(stateType, "available"))) {
                return BetterBeyondSnapshot.EMPTY;
            }
            Object entries = invokeStaticNoArg(stateType, "entries");
            if (!(entries instanceof Iterable<?> iterable)) {
                return BetterBeyondSnapshot.EMPTY;
            }

            List<StoredStack> stacks = new ArrayList<>();
            for (Object entry : iterable) {
                if (entry == null) {
                    continue;
                }
                ItemStack stack = stackOf(invokeNoArg(entry, "stack"));
                long amount = numberValue(invokeNoArg(entry, "amount"));
                if (stack.isEmpty() || amount <= 0L) {
                    continue;
                }
                stack.setCount(1);
                stacks.add(new StoredStack(stack, amount));
            }
            return new BetterBeyondSnapshot(List.copyOf(stacks), List.of());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return BetterBeyondSnapshot.EMPTY;
        }
    }

    private record BetterBeyondSnapshot(List<StoredStack> stacks, List<StoredFluid> fluids) {
        private static final BetterBeyondSnapshot EMPTY = new BetterBeyondSnapshot(List.of(), List.of());
    }

    private static ItemStack storageItemStack(Object value) throws ReflectiveOperationException {
        if (value instanceof ItemStack stack && !stack.isEmpty()) {
            return stack.copy();
        }
        ItemStack stack = stackOf(invokeNoArg(value, "getReadOnlyStack"));
        if (stack.isEmpty()) {
            stack = stackOf(invokeNoArg(value, "toStack"));
        }
        return stack;
    }

    private static Boolean sendRs1Recipe(Object menu, List<ItemStack> templates, boolean send) {
        if (templates.size() != 9) {
            return false;
        }
        if (!send) {
            return true;
        }
        try {
            Class<?> messageType = Class.forName(
                    "com.refinedmods.refinedstorage.network.grid.GridTransferMessage");
            List<List<ItemStack>> inputs = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                ItemStack stack = templates.get(i);
                inputs.add(stack == null || stack.isEmpty() ? List.of() : List.of(stack.copy()));
            }
            Object message;
            Constructor<?> listConstructor = findConstructor(messageType, List.class);
            if (listConstructor != null) {
                message = listConstructor.newInstance(inputs);
            } else {
                ItemStack[][] recipe = new ItemStack[9][];
                for (int i = 0; i < inputs.size(); i++) {
                    recipe[i] = inputs.get(i).toArray(ItemStack[]::new);
                }
                Constructor<?> arrayConstructor = findConstructor(messageType, ItemStack[][].class);
                if (arrayConstructor == null) {
                    return false;
                }
                message = arrayConstructor.newInstance((Object) recipe);
            }
            Class<?> rsType = Class.forName("com.refinedmods.refinedstorage.RS");
            Object handler = readStaticField(rsType, "NETWORK_HANDLER");
            Method sender = findCompatibleMethod(handler.getClass(), "sendToServer", messageType);
            if (sender == null) {
                return false;
            }
            sender.invoke(handler, message);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Boolean sendRs2Recipe(Object menu, List<ItemStack> templates, boolean send) {
        if (templates.size() != 9) {
            return false;
        }
        if (!send) {
            return true;
        }
        try {
            Class<?> itemResourceType = Class.forName(
                    "com.refinedmods.refinedstorage.common.support.resource.ItemResource");
            Method ofStack = findCompatibleMethod(itemResourceType, "ofItemStack", ItemStack.class);
            if (ofStack == null) {
                return false;
            }
            List<List<Object>> recipe = new ArrayList<>(9);
            for (ItemStack template : templates) {
                if (template == null || template.isEmpty()) {
                    recipe.add(List.of());
                } else {
                    recipe.add(List.of(ofStack.invoke(null, template.copy())));
                }
            }
            Class<?> packets = Class.forName(
                    "com.refinedmods.refinedstorage.common.support.packet.c2s.C2SPackets");
            Method sender = findCompatibleMethod(packets, "sendCraftingGridRecipeTransfer", List.class);
            if (sender == null) {
                return false;
            }
            sender.invoke(null, recipe);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Boolean sendBeyondRecipe(Object menu, List<ItemStack> templates, boolean send) {
        if (templates.size() != 9) {
            return false;
        }
        if (!send) {
            return true;
        }
        try {
            Class<?> keyType = Class.forName(
                    "com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey");
            Field emptyField = findField(keyType, "EMPTY");
            Object empty = emptyField == null ? null : emptyField.get(null);
            Constructor<?> keyConstructor = constructor(keyType, ItemStack.class);
            List<Object> keys = new ArrayList<>(9);
            List<Long> amounts = new ArrayList<>(9);
            for (ItemStack template : templates) {
                if (template == null || template.isEmpty()) {
                    keys.add(empty);
                    amounts.add(0L);
                } else {
                    keys.add(keyConstructor.newInstance(template.copy()));
                    amounts.add(1L);
                }
            }
            Class<?> packetType = Class.forName(
                    "com.wintercogs.beyonddimensions.network.packet.c2s.RecipeFillC2SPacket");
            Object packet = constructor(packetType, List.class, List.class).newInstance(keys, amounts);
            for (String distributorName : List.of(
                    "net.neoforged.neoforge.network.PacketDistributor",
                    "net.minecraftforge.network.PacketDistributor")) {
                try {
                    Class<?> distributor = Class.forName(distributorName);
                    Method sendToServer = findCompatibleMethod(distributor, "sendToServer", packetType);
                    if (sendToServer != null) {
                        sendToServer.invoke(null, packet);
                        return true;
                    }
                } catch (ClassNotFoundException ignored) {
                    // Try the other loader's packet distributor.
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Optional Beyond Dimensions API.
        }
        return false;
    }

    /** Uses Better Beyond Dimensions' server-validated recipe-fill packet for a normal 3x3 grid. */
    private static Boolean sendBetterBeyondRecipe(Object menu, List<ItemStack> templates, boolean send) {
        if (templates.size() != 9) {
            return null;
        }
        List<Integer> slotIds = betterBeyondCraftingSlotIds(menu);
        if (slotIds.size() < 9) {
            return null;
        }
        try {
            Class<?> fillType = Class.forName(BBD_RECIPE_FILL);
            Constructor<?> fillConstructor = constructor(fillType, int.class, ItemStack.class, int.class);
            Class<?> handlerType = Class.forName(BBD_NETWORK_HANDLER);
            Method fillRecipe = findCompatibleMethod(handlerType, "fillRecipe", List.class);
            boolean extendedFill = false;
            if (fillRecipe == null) {
                // Newer Better Beyond builds keep the old overload, but use
                // the extended form when a build removes the compatibility
                // overload in the future.
                fillRecipe = findCompatibleMethod(
                        handlerType, "fillRecipe", List.class, boolean.class, boolean.class);
                extendedFill = fillRecipe != null;
            }
            if (fillRecipe == null) {
                return false;
            }
            if (send) {
                List<Object> fills = new ArrayList<>(9);
                for (int index = 0; index < 9; index++) {
                    ItemStack stack = templates.get(index) == null
                            ? ItemStack.EMPTY
                            : templates.get(index).copy();
                    int amount = stack.isEmpty() ? 0 : Math.max(1, stack.getCount());
                    if (amount > 0) {
                        stack.setCount(1);
                    }
                    fills.add(fillConstructor.newInstance(slotIds.get(index), stack, amount));
                }
                if (extendedFill) {
                    fillRecipe.invoke(null, fills, false, false);
                } else {
                    fillRecipe.invoke(null, fills);
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static List<Integer> betterBeyondCraftingSlotIds(Object menu) {
        if (!(menu instanceof AbstractContainerMenu container) || !isBetterBeyondMenu(menu)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>(9);
        for (int index = 0; index < container.slots.size(); index++) {
            Slot slot = container.getSlot(index);
            if (slot.container != null
                    && slot.container.getClass().getName().endsWith("CraftingContainer")) {
                result.add(index);
            }
        }
        return result.size() < 9 ? List.of() : List.copyOf(result.subList(0, 9));
    }

    private static boolean isRefinedStorageMenu(Object menu) {
        return isRs1Menu(menu) || isRs2Menu(menu);
    }

    private static boolean isRs1Menu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), RS1_MENU);
    }

    private static boolean isRs2Menu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), RS2_MENU);
    }

    private static boolean isBeyondMenu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), BD_MENU);
    }

    private static boolean isBetterBeyondMenu(Object menu) {
        if (!(menu instanceof AbstractContainerMenu container)) {
            return false;
        }
        if (classOrInterface(container.getClass(), BBD_MENU_ACCESS)) {
            return true;
        }
        for (Slot slot : container.slots) {
            if (slot != null && BBD_NETWORK_SLOT.equals(slot.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    private static List<StoredFluid> collectFluidEntries(Object entries, String amountMethod)
            throws ReflectiveOperationException {
        return collectFluidEntries(entries, amountMethod, null);
    }

    /**
     * Collects fluid resources from a virtual grid. RS 2.x keeps the amount
     * on {@code GridResource#getAmount(ResourceRepository)} rather than on
     * the resource entry itself, so the optional context is passed through
     * reflectively when that API is present.
     */
    private static List<StoredFluid> collectFluidEntries(
            Object entries,
            String amountMethod,
            Object amountContext) throws ReflectiveOperationException {
        if (!(entries instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<StoredFluid> result = new ArrayList<>();
        for (Object entry : iterable) {
            StoredFluid fluid = fluidEntry(entry, amountMethod, amountContext);
            if (fluid != null) {
                result.add(fluid);
            }
        }
        return result;
    }

    private static StoredFluid fluidEntry(Object entry, String amountMethod, Object amountContext)
            throws ReflectiveOperationException {
        if (entry == null || invokeNoArg(entry, "getCraftingOption") != null) {
            return null;
        }
        Object value = entry;
        Object key = invokeNoArg(entry, "key");
        if (key != null) {
            value = key;
        }
        if (!storageItemStack(value).isEmpty()) {
            return null;
        }
        var info = FluidRecipeCompat.describeFluid(value);
        if (info.isEmpty()) {
            info = FluidRecipeCompat.describeFluid(invokeNoArg(entry, "getInstance"));
        }
        if (info.isEmpty()) {
            return null;
        }
        long amount = amountMethod == null ? 0L : numberValue(invokeNoArg(entry, amountMethod));
        if (amount <= 0) {
            for (String method : List.of("getQuantity", "getTotalQuantity", "getAmount", "amount", "getCount")) {
                amount = numberValue(invokeNoArg(entry, method));
                if (amount > 0) {
                    break;
                }
            }
        }
        if (amount <= 0 && amountContext != null) {
            for (String method : List.of("getAmount", "getQuantity")) {
                amount = numberValue(invoke(entry, method, amountContext.getClass(), amountContext));
                if (amount > 0) {
                    break;
                }
            }
        }
        if (info.get().amount() > 0) {
            amount = Math.max(amount, info.get().amount());
        }
        return amount > 0 ? new StoredFluid(info.get().key(), amount) : null;
    }

    private static boolean isIntegratedTerminalMenu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), IT_MENU);
    }

    private static void prioritizeRs2(Object menu, Set<String> keys) throws ReflectiveOperationException {
        Object repository = invokeNoArg(menu, "getRepository");
        if (repository == null) {
            return;
        }
        Object view = invokeNoArg(repository, "getViewList");
        if (view instanceof List<?> list) {
            Comparator<Object> comparator = Comparator.comparing(
                    entry -> !isHighlightedResource(entry, repository, keys));
            if (isPrioritized(list, entry -> isHighlightedResource(entry, repository, keys))) {
                return;
            }
            sortExposedOrBackingList(repository, list, comparator);
        }
    }

    private static void prioritizeRs1(Set<String> keys) throws ReflectiveOperationException {
        Object screen = Minecraft.getInstance().screen;
        if (screen == null || !classOrSuper(screen.getClass(), RS1_SCREEN)) {
            return;
        }
        Object view = invokeNoArg(screen, "getView");
        Object entries = invokeNoArg(view, "getStacks");
        if (entries instanceof List<?> list) {
            if (isPrioritized(list, entry -> isHighlightedGridStack(entry, keys))) {
                return;
            }
            sortExposedOrBackingList(view, list,
                    Comparator.comparing(entry -> !isHighlightedGridStack(entry, keys)));
        }
    }

    private static void prioritizeBeyond(Object menu, Set<String> keys) throws ReflectiveOperationException {
        if (!isBeyondMenu(menu)) {
            return;
        }
        Object clientStorage = readField(menu, "clientNetStorage");
        if (clientStorage == null) {
            return;
        }
        Class<?> settings = Class.forName("com.wintercogs.beyonddimensions.config.CommonConfigRuntime");
        Object primary = readStaticField(settings, "uiSortButton");
        Object secondary = readStaticField(settings, "uiSecondSortButton");
        Object reverseState = readStaticField(settings, "uiReverseButton");
        boolean reverse = reverseState != null && "ENABLED".equals(reverseState.toString());
        Method builder = findCompatibleMethod(
                clientStorage.getClass(),
                "buildSortedIndex",
                primary == null ? null : primary.getClass(),
                secondary == null ? null : secondary.getClass(),
                boolean.class);
        if (builder == null) {
            return;
        }
        Object built = builder.invoke(clientStorage, primary, secondary, reverse);
        if (!(built instanceof List<?> raw)) {
            return;
        }
        List<Integer> indexes = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof Number number) {
                indexes.add(number.intValue());
            }
        }
        indexes.sort(Comparator.comparing(index -> !isHighlightedBeyondIndex(clientStorage, index, keys)));
        int first = Math.max(0, intValue(readField(menu, "lineData")) * 9);
        int visible = Math.max(0, intValue(invokeNoArg(menu, "getLines")) * 9);
        ArrayList<Integer> page = new ArrayList<>(visible);
        for (int i = 0; i < visible; i++) {
            int index = first + i;
            page.add(index < indexes.size() ? indexes.get(index) : -1);
        }
        Method loader = findCompatibleMethod(menu.getClass(), "loadIndexList", ArrayList.class);
        if (loader != null) {
            loader.invoke(menu, page);
        }
    }

    /**
     * Better Beyond Dimensions rebuilds its sidebar from a private client view
     * that already applies the mod's configured sort. Keep that order as the
     * base list and replace only the final visible order with a stable
     * highlighted-first partition.
     */
    private static void prioritizeBetterBeyond(Object menu, Set<String> keys)
            throws ReflectiveOperationException {
        if (!isBetterBeyondMenu(menu)) {
            prioritizedBetterBeyondView = null;
            betterBeyondNativeEntries = List.of();
            betterBeyondAppliedEntries = List.of();
            return;
        }
        AbstractContainerScreen<?> screen = activeContainerScreen();
        if (screen == null || screen.getMenu() != menu) {
            return;
        }
        if (!isBetterBeyondSidebarEnabled(screen)) {
            return;
        }

        Class<?> rendererType = Class.forName(BBD_SIDEBAR_RENDERER);
        Object storageView = readStaticField(rendererType, "STORAGE_VIEW");
        if (storageView == null) {
            return;
        }
        Class<?> stateType = Class.forName(BBD_CLIENT_STORAGE_STATE);
        if (!Boolean.TRUE.equals(invokeStaticNoArg(stateType, "available"))
                || Boolean.TRUE.equals(invokeStaticNoArg(stateType, "isSidebarHidden"))) {
            return;
        }
        Object snapshot = invokeStaticNoArg(stateType, "snapshot");
        Object searchBox = invokeNoArg(screen, "bbd$getSearchBox");
        Object searchValue = invokeNoArg(searchBox, "getValue");
        if (snapshot == null || !(searchValue instanceof String searchText)) {
            return;
        }

        Method entriesMethod = findCompatibleMethod(
                storageView.getClass(),
                "entries",
                snapshot.getClass(),
                String.class);
        if (entriesMethod == null) {
            return;
        }
        Object entriesValue = entriesMethod.invoke(storageView, snapshot, searchText);
        if (!(entriesValue instanceof List<?> entries)) {
            return;
        }

        if (storageView != prioritizedBetterBeyondView) {
            prioritizedBetterBeyondView = storageView;
            betterBeyondNativeEntries = List.of();
            betterBeyondAppliedEntries = List.of();
        }
        // A new list identity means BBD has rebuilt its native quantity/name/
        // time-sorted view. Capture it before applying our partition so the
        // original order inside both partitions remains BBD's order.
        if (entries != betterBeyondAppliedEntries) {
            betterBeyondNativeEntries = List.copyOf(entries);
        }

        List<Object> prioritized = new ArrayList<>(betterBeyondNativeEntries);
        prioritized.sort(Comparator.comparing(entry -> !isHighlightedBetterBeyondEntry(entry, keys)));
        if (sameElementOrder(entries, prioritized)) {
            betterBeyondAppliedEntries = entries;
            return;
        }

        Field orderedEntries = findField(storageView.getClass(), "orderedEntries");
        if (orderedEntries == null) {
            return;
        }
        List<Object> applied = List.copyOf(prioritized);
        orderedEntries.set(storageView, applied);
        betterBeyondAppliedEntries = applied;
    }

    private static void prioritizeIntegrated(Object menu, Set<String> keys) throws ReflectiveOperationException {
        if (!isIntegratedTerminalMenu(menu)) {
            return;
        }
        Object tab = integratedItemClientTab(menu);
        if (tab == null) {
            return;
        }
        int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
        int count = Math.max(1, intValue(invoke(tab, "getSlotCount", int.class, channel)));
        invokeThreeInts(tab, "getSlots", channel, 0, count);
        Object views = readField(tab, "filteredIngredientsViews");
        Object entries = invoke(views, "get", int.class, channel);
        if (entries instanceof List<?> list) {
            if (isPrioritized(list, entry -> isHighlightedIntegratedEntry(entry, keys))) {
                return;
            }
            sortExposedOrBackingList(views, list,
                    Comparator.comparing(entry -> !isHighlightedIntegratedEntry(entry, keys)));
        }
    }

    static void renderVirtualStorageHighlights(
            GuiGraphics graphics,
            AbstractContainerScreen<?> screen) {
        if (graphics == null || screen == null || !RecipeTreeFavorites.isActive()) {
            return;
        }
        // Refresh after the terminal has been opened as well as after its
        // network snapshot changes. This is especially important for RS fluid
        // entries: the menu can be created before the first fluid sync arrives.
        RecipeTreeFavorites.refreshThrottled();
        try {
            if (classOrSuper(screen.getClass(), RS1_SCREEN)) {
                renderRs1Highlights(graphics, screen);
            }
            Object menu = screen.getMenu();
            if (isRs2Menu(menu) || isRs2Screen(screen)) {
                renderRs2Highlights(graphics, screen);
            }
            if (Ae2StorageIntegration.isCraftingMenu(menu)
                    || classOrSuper(screen.getClass(), "appeng.client.gui.me.common.MEStorageScreen")) {
                renderAe2Highlights(graphics, screen);
            }
            if (isBeyondMenu(menu)) {
                renderBeyondHighlights(graphics, screen);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Rendering integrations must never make an optional terminal fatal.
        }
    }

    /**
     * Integrated Terminals draws its virtual slots and tooltips from renderLabels.
     * This hook is called after its background slot contents, but before its
     * foreground slot tooltips, so the overlay cannot cover tooltip text even
     * though CyclopsCore renders tooltips with depth testing disabled.
     */
    static void renderIntegratedTerminalHighlightsBeforeTooltip(
            GuiGraphics graphics,
            AbstractContainerScreen<?> screen) {
        if (graphics == null || screen == null || !RecipeTreeFavorites.isActive()) {
            return;
        }
        RecipeTreeFavorites.refreshThrottled();
        try {
            if (classOrSuper(screen.getClass(), IT_SCREEN)) {
                renderIntegratedHighlights(graphics, screen);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Rendering integrations must never make an optional terminal fatal.
        }
    }

    private static void renderRs1Highlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
            throws ReflectiveOperationException {
        Object view = invokeNoArg(screen, "getView");
        Object entries = invokeNoArg(view, "getStacks");
        if (!(entries instanceof List<?> list)) {
            return;
        }
        int rowOffset = Math.max(0, intValue(invokeNoArg(screen, "getCurrentOffset")));
        int rows = Math.max(0, intValue(invokeNoArg(screen, "getVisibleRows")));
        int start = rowOffset * 9;
        for (int local = 0; local < rows * 9 && start + local < list.size(); local++) {
            ItemStack stack = rs1EntryStack(list.get(start + local));
            int x = screen.getGuiLeft() + 8 + local % 9 * 18;
            int y = screen.getGuiTop() + 19 + local / 9 * 18;
            drawHighlight(graphics, x, y, stack);
            if (stack.isEmpty()) {
                FluidRecipeCompat.describeFluid(list.get(start + local))
                        .ifPresent(info -> drawHighlightKey(graphics, x, y, info.key()));
            }
        }
    }

    /**
     * RS 2.x renders virtual resources instead of vanilla slots. Its grid
     * uses the same 18-pixel cell geometry as RS 1.x, but keeps scrolling and
     * pinned-row state on the screen. Read those values reflectively so the
     * highlight follows the resource even when RS changes its screen class.
     */
    private static void renderRs2Highlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
            throws ReflectiveOperationException {
        Object repository = invokeNoArg(screen.getMenu(), "getRepository");
        Object entries = invokeNoArg(repository, "getViewList");
        if (!(entries instanceof List<?> list)) {
            return;
        }
        int pinRows = Math.max(0, intValue(readField(screen, "pinRows")));
        int visibleRows = intValue(invokeNoArg(screen, "getVisibleRows"));
        if (visibleRows <= 0) {
            visibleRows = intValue(readField(screen, "visibleRows"));
        }
        int scrollOffset = Math.max(0, intValue(invokeNoArg(screen, "getScrollbarOffset")));
        int rowOffset = scrollOffset / 18;
        int partialPixelOffset = scrollOffset % 18;
        int resourceRows = Math.max(0, visibleRows - pinRows);
        int maxCells = resourceRows * 9;
        int first = rowOffset * 9;
        int x0 = screen.getGuiLeft() + 8;
        int y0 = screen.getGuiTop() + 20 + pinRows * 18 - partialPixelOffset;
        for (int local = 0; local < maxCells && first + local < list.size(); local++) {
            int row = local / 9;
            int column = local % 9;
            int x = x0 + column * 18;
            int y = y0 + row * 18;
            Object entry = list.get(first + local);
            ItemStack stack = stackOf(invokeNoArg(entry, "getItemStack"));
            drawHighlight(graphics, x, y, stack);
            if (stack.isEmpty()) {
                FluidRecipeCompat.describeFluid(entry)
                        .ifPresent(info -> drawHighlightKey(graphics, x, y, info.key()));
            }
        }
    }

    private static void renderAe2Highlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
            throws ReflectiveOperationException {
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.getClass().getName().endsWith("RepoSlot")) {
                continue;
            }
            Object entry = invokeNoArg(slot, "getEntry");
            Object key = invokeNoArg(entry, "getWhat");
            int x = screen.getGuiLeft() + slot.x;
            int y = screen.getGuiTop() + slot.y;
            ItemStack stack = storageItemStack(key);
            if (!stack.isEmpty()) {
                drawHighlight(graphics, x, y, stack);
            } else {
                FluidRecipeCompat.describeFluid(key)
                        .ifPresent(info -> drawHighlightKey(graphics, x, y, info.key()));
            }
        }
    }

    private static void renderBeyondHighlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
            throws ReflectiveOperationException {
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.getClass().getName().contains("StackTypedSlot")) {
                continue;
            }
            Object typed = invokeNoArg(slot, "getTypedStackFromUnifiedStorage");
            Object key = invokeNoArg(typed, "key");
            if (key == null) {
                key = invokeNoArg(typed, "getKey");
            }
            FluidRecipeCompat.describeFluid(key)
                    .ifPresent(info -> drawHighlightKey(
                            graphics,
                            screen.getGuiLeft() + slot.x,
                            screen.getGuiTop() + slot.y,
                            info.key()));
        }
    }

    /** Draws recipe-tree overlays on Better Beyond Dimensions' virtual sidebar slots. */
    static void renderBetterBeyondHighlights(
            GuiGraphics graphics,
            AbstractContainerScreen<?> screen) {
        if (graphics == null || screen == null || !RecipeTreeFavorites.isActive()
                || !isBetterBeyondMenu(screen.getMenu())
                || !isBetterBeyondSidebarEnabled(screen)) {
            return;
        }
        try {
            for (Slot slot : screen.getMenu().slots) {
                if (slot == null
                        || !BBD_NETWORK_SLOT.equals(slot.getClass().getName())
                        || !slot.hasItem()) {
                    continue;
                }
                drawHighlight(
                        graphics,
                        screen.getGuiLeft() + slot.x,
                        screen.getGuiTop() + slot.y,
                        slot.getItem());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The optional sidebar must never make a container render fatal.
        }
    }

    private static void renderIntegratedHighlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
            throws ReflectiveOperationException {
        Object menu = screen.getMenu();
        Object tab = integratedItemClientTab(menu);
        if (tab == null) {
            tab = integratedFluidClientTab(menu);
        }
        if (tab == null) {
            return;
        }
        int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
        int firstRow = Math.max(0, intValue(invokeNoArg(screen, "getSelectedFirstRow")));
        int rowLength = Math.max(1, intValue(invokeNoArg(screen, "getSlotRowLength")));
        int visibleRows = Math.max(0, intValue(invokeNoArg(screen, "getSlotVisibleRows")));
        int start = firstRow * rowLength;
        Object visible = invokeThreeInts(tab, "getSlots", channel, start, visibleRows * rowLength);
        if (!(visible instanceof List<?> list)) {
            return;
        }
        for (int local = 0; local < list.size(); local++) {
            Object instance = invokeNoArg(list.get(local), "getInstance");
            ItemStack stack = stackOf(instance);
            Object value = invoke(screen, "getStorageSlotRect", int.class, start + local);
            // Rect2i belongs to Minecraft, so reflective Mojmap method names
            // are not stable in a production Forge runtime. Cast it and let
            // the loader remap the direct calls instead.
            if (value instanceof Rect2i rect) {
                // Integrated Terminals exposes the 16x16 item-content rect,
                // while our border starts one pixel outside that content. This
                // renderer runs inside renderLabels, whose pose is already
                // translated by the container's GUI origin.
                drawHighlight(
                        graphics,
                        rect.getX() - screen.getGuiLeft() - 1,
                        rect.getY() - screen.getGuiTop() - 1,
                        stack,
                        200);
                if (stack.isEmpty()) {
                    FluidRecipeCompat.describeFluid(instance)
                            .ifPresent(info -> drawHighlightKey(
                                    graphics,
                                    rect.getX() - screen.getGuiLeft() - 1,
                                    rect.getY() - screen.getGuiTop() - 1,
                                    info.key(),
                                    200));
                }
            }
        }
    }

    private static AbstractContainerScreen<?> activeContainerScreen() {
        Object current = Minecraft.getInstance().screen;
        for (int depth = 0; depth < 4 && current instanceof RecipeTreeScreen tree; depth++) {
            current = tree.parentScreen();
        }
        return current instanceof AbstractContainerScreen<?> screen ? screen : null;
    }

    /**
     * 0.4.x added a client-side sidebar-disabled setting. Older releases do
     * not expose this accessor, so absence remains compatible and means the
     * sidebar is treated as enabled.
     */
    private static boolean isBetterBeyondSidebarEnabled(AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return false;
        }
        try {
            Object value = invokeNoArg(screen, "bbd$isSidebarEnabled");
            return !(value instanceof Boolean) || (Boolean) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return true;
        }
    }

    private static void drawHighlight(GuiGraphics graphics, int x, int y, ItemStack stack) {
        drawHighlight(graphics, x, y, stack, 300);
    }

    private static void drawHighlight(GuiGraphics graphics, int x, int y, ItemStack stack, int z) {
        if (stack != null && !stack.isEmpty()) {
            String key = RecipeTreeData.ingredientKey(stack);
            boolean direct = RecipeTreeFavorites.isFinalProductKey(key)
                    || RecipeTreeFavorites.isIntermediateKey(key)
                    || RecipeTreeFavorites.isRequiredKey(key);
            if (direct) {
                drawHighlightKey(graphics, x, y, key, z);
            } else {
                FluidRecipeCompat.displayFluidKey(stack)
                        .ifPresent(fluidKey -> drawHighlightKey(graphics, x, y, fluidKey, z));
            }
        }
    }

    private static void drawHighlightKey(GuiGraphics graphics, int x, int y, String key) {
        drawHighlightKey(graphics, x, y, key, 300);
    }

    private static void drawHighlightKey(GuiGraphics graphics, int x, int y, String key, int z) {
        boolean finalProduct = RecipeTreeFavorites.isFinalProductKey(key);
        boolean intermediate = RecipeTreeFavorites.isIntermediateKey(key);
        boolean required = RecipeTreeFavorites.isRequiredKey(key);
        if (!finalProduct && !intermediate && !required) {
            return;
        }
        int fill = finalProduct ? 0x4433CC66 : (intermediate ? 0x44FF2222 : 0x3300BBFF);
        int border = finalProduct ? 0xDD66FF88 : (intermediate ? 0xDDFF5555 : 0xCC55DDFF);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, z);
        graphics.fill(x, y, x + 16, y + 16, fill);
        graphics.fill(x, y, x + 16, y + 1, border);
        graphics.fill(x, y + 15, x + 16, y + 16, border);
        graphics.fill(x, y, x + 1, y + 16, border);
        graphics.fill(x + 15, y, x + 16, y + 16, border);
        graphics.pose().popPose();
    }

    private static boolean isHighlightedGridStack(Object entry, Set<String> keys) {
        try {
            ItemStack stack = rs1EntryStack(entry);
            if (!stack.isEmpty()) {
                return matchesHighlightKey(stack, keys);
            }
            return FluidRecipeCompat.describeFluid(entry)
                    .map(info -> keys.contains(info.key()))
                    .orElse(false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** RS 1.x has used both raw ItemStacks and wrapper entries across releases. */
    private static ItemStack rs1EntryStack(Object entry) throws ReflectiveOperationException {
        ItemStack stack = stackOf(entry);
        if (stack.isEmpty()) {
            stack = stackOf(invokeNoArg(entry, "getIngredient"));
        }
        if (stack.isEmpty()) {
            stack = stackOf(invokeNoArg(entry, "getStack"));
        }
        return stack;
    }

    private static boolean isHighlightedBeyondIndex(Object storage, int index, Set<String> keys) {
        try {
            Object entry = invoke(storage, "getStackBySlot", int.class, index);
            ItemStack stack = stackOf(invokeNoArg(entry, "toStack"));
            if (!stack.isEmpty()) {
                return matchesHighlightKey(stack, keys);
            }
            return FluidRecipeCompat.describeFluid(entry)
                    .map(info -> keys.contains(info.key()))
                    .orElse(false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isHighlightedBetterBeyondEntry(Object entry, Set<String> keys) {
        try {
            Object key = invokeNoArg(entry, "key");
            ItemStack stack = stackOf(invokeNoArg(key, "copyStack"));
            if (stack.isEmpty()) {
                stack = stackOf(invokeNoArg(key, "getRenderStack"));
            }
            if (stack.isEmpty()) {
                stack = storageItemStack(key);
            }
            return !stack.isEmpty() && matchesHighlightKey(stack, keys);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isHighlightedIntegratedEntry(Object entry, Set<String> keys) {
        try {
            Object instance = invokeNoArg(entry, "getInstance");
            ItemStack stack = stackOf(instance);
            if (!stack.isEmpty()) {
                return matchesHighlightKey(stack, keys);
            }
            return FluidRecipeCompat.describeFluid(instance)
                    .map(info -> keys.contains(info.key()))
                    .orElse(false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Object integratedSelectedCommonTab(Object menu) throws ReflectiveOperationException {
        Object selected = invokeNoArg(menu, "getSelectedTab");
        return selected instanceof String id
                ? invoke(menu, "getTabCommon", String.class, id)
                : null;
    }

    private static Object integratedItemClientTab(Object menu) throws ReflectiveOperationException {
        if (!isIntegratedTerminalMenu(menu)) {
            return null;
        }
        Object selected = invokeNoArg(menu, "getSelectedTab");
        if (selected instanceof String id) {
            Object tab = invoke(menu, "getTabClient", String.class, id);
            if (isIntegratedItemTab(tab)) {
                return tab;
            }
        }
        Object tabs = invokeNoArg(menu, "getTabsClient");
        if (tabs instanceof Map<?, ?> map) {
            for (Object tab : map.values()) {
                if (isIntegratedItemTab(tab)) {
                    return tab;
                }
            }
        }
        return null;
    }

    private static Object integratedFluidClientTab(Object menu) throws ReflectiveOperationException {
        if (!isIntegratedTerminalMenu(menu)) {
            return null;
        }
        Object selected = invokeNoArg(menu, "getSelectedTab");
        if (selected instanceof String id) {
            Object tab = invoke(menu, "getTabClient", String.class, id);
            if (isIntegratedFluidTab(tab)) {
                return tab;
            }
        }
        Object tabs = invokeNoArg(menu, "getTabsClient");
        if (tabs instanceof Map<?, ?> map) {
            for (Object tab : map.values()) {
                if (isIntegratedFluidTab(tab)) {
                    return tab;
                }
            }
        }
        return null;
    }

    private static boolean isIntegratedItemTab(Object tab) {
        if (tab == null) {
            return false;
        }
        String name = tab.getClass().getName();
        if (name.contains("TerminalStorageTabIngredientComponentItemStack")) {
            return true;
        }
        try {
            Object component = invokeNoArg(tab, "getIngredientComponent");
            if (component == null) {
                return false;
            }
            String componentName = String.valueOf(invokeNoArg(component, "getName"));
            return !componentName.toLowerCase(java.util.Locale.ROOT).contains("fluid");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isIntegratedFluidTab(Object tab) {
        if (tab == null) {
            return false;
        }
        if (tab.getClass().getName().contains("FluidStack")) {
            return true;
        }
        try {
            Object component = invokeNoArg(tab, "getIngredientComponent");
            if (component == null) {
                return false;
            }
            String componentName = String.valueOf(invokeNoArg(component, "getName"));
            return componentName.toLowerCase(java.util.Locale.ROOT).contains("fluid");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean classOrSuper(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (name.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean classOrInterface(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (name.equals(current.getName())) {
                return true;
            }
            for (Class<?> interfaceType : current.getInterfaces()) {
                if (classOrInterface(interfaceType, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHighlightedResource(Object resource, Object repository, Set<String> keys) {
        try {
            ItemStack stack = stackOf(invokeNoArg(resource, "getItemStack"));
            if (!stack.isEmpty()) {
                return matchesHighlightKey(stack, keys);
            }
            return FluidRecipeCompat.describeFluid(resource)
                    .map(info -> keys.contains(info.key()))
                    .orElse(false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static boolean matchesHighlightKey(ItemStack stack, Set<String> keys) {
        String key = RecipeTreeData.ingredientKey(stack);
        return keys.contains(key)
                || FluidRecipeCompat.displayFluidKey(stack).map(keys::contains).orElse(false);
    }

    /**
     * Returns whether a list is already partitioned with all highlighted
     * entries before every non-highlighted entry. The original order inside
     * each partition is intentionally preserved by the stable sort.
     */
    private static boolean isPrioritized(
            List<?> list,
            java.util.function.Predicate<Object> highlighted) {
        boolean seenUnhighlighted = false;
        for (Object entry : list) {
            if (highlighted.test(entry)) {
                if (seenUnhighlighted) {
                    return false;
                }
            } else {
                seenUnhighlighted = true;
            }
        }
        return true;
    }

    private static boolean sameElementOrder(List<?> first, List<?> second) {
        if (first == null || second == null || first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (first.get(index) != second.get(index)) {
                return false;
            }
        }
        return true;
    }

    /**
     * RS2 and some optional integrations expose an unmodifiable view list.
     * In those versions the actual mutable list is held by a small private
     * view-state object. Sort that backing list without calling the mod's
     * native sort routine (which would discard the recipe-tree priority).
     */
    @SuppressWarnings("unchecked")
    private static void sortExposedOrBackingList(
            Object owner,
            List<?> exposed,
            Comparator<Object> comparator) throws ReflectiveOperationException {
        try {
            ((List<Object>) exposed).sort(comparator);
            return;
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // Fall through to the private backing list.
        }
        // Integrated Terminals keeps one list per channel. A channel view can
        // be immutable even though the outer list is mutable; replace only the
        // matching channel with a sorted copy in that case.
        if (owner instanceof List<?> outer) {
            int index = identityIndex(outer, exposed);
            if (index >= 0) {
                List<Object> sorted = new ArrayList<>();
                for (Object value : exposed) {
                    sorted.add(value);
                }
                sorted.sort(comparator);
                try {
                    ((List<Object>) outer).set(index, sorted);
                    return;
                } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                    // Continue with reflective backing-list lookup below.
                }
            }
        }
        List<Object> backing = findBackingList(owner, exposed, 0);
        if (backing != null) {
            backing.sort(comparator);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> findBackingList(Object owner, List<?> exposed, int depth)
            throws ReflectiveOperationException {
        if (owner == null || depth > 2) {
            return null;
        }
        for (Class<?> current = owner.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.trySetAccessible();
                    Object value = field.get(owner);
                    if (value == exposed) {
                        continue;
                    }
                    if (value instanceof List<?> list) {
                        if (sharesElements(list, exposed)) {
                            try {
                                // Probe mutability without changing the
                                // element order; an unmodifiable wrapper can
                                // also share the same element identities.
                                ((List<Object>) list).sort((left, right) -> 0);
                                return (List<Object>) list;
                            } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                                continue;
                            }
                        }
                    }
                    if (depth < 2 && value != null
                            && !value.getClass().getName().startsWith("java.")) {
                        List<Object> nested = findBackingList(value, exposed, depth + 1);
                        if (nested != null) {
                            return nested;
                        }
                    }
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // Optional internal implementation; inspect the next field.
                }
            }
        }
        return null;
    }

    private static int identityIndex(List<?> values, Object target) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == target) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Confirms that a candidate list is the backing list of the exposed view,
     * rather than an unrelated resource/index list owned by the same object.
     * The view wrappers retain element identity, so sampling the first, middle
     * and last entries is enough without walking a large network twice.
     */
    private static boolean sharesElements(List<?> candidate, List<?> exposed) {
        int size = exposed == null ? 0 : exposed.size();
        if (candidate == null || size == 0 || candidate.size() != size) {
            return false;
        }
        int[] samples = {0, size / 2, size - 1};
        for (int index : samples) {
            if (candidate.get(index) != exposed.get(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRs2Screen(AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName();
        return name.contains("refinedstorage") && name.contains("GridScreen");
    }

    private static void readContainer(Object container, List<ItemStack> target, int size)
            throws ReflectiveOperationException {
        int actualSize = intValue(invokeNoArg(container, "getContainerSize"));
        if (actualSize <= 0) {
            actualSize = intValue(invokeNoArg(container, "size"));
        }
        for (int i = 0; i < Math.min(size, actualSize); i++) {
            target.add(stackOf(invoke(container, "getItem", int.class, i)));
        }
    }

    private static ItemStack stackOf(Object value) {
        return value instanceof ItemStack stack ? stack.copy() : ItemStack.EMPTY;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static long numberValue(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = findField(target == null ? null : target.getClass(), name);
        return field == null ? null : field.get(target);
    }

    private static Object readStaticField(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = findField(type, name);
        return field == null ? null : field.get(null);
    }

    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findNoArgMethod(target.getClass(), name);
        return method == null ? null : method.invoke(target);
    }

    private static Object invokeStaticNoArg(Class<?> type, String name) throws ReflectiveOperationException {
        Method method = findNoArgMethod(type, name);
        return method == null ? null : method.invoke(null);
    }

    private static Object invoke(Object target, String name, Class<?> parameterType, Object argument)
            throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findCompatibleMethod(target.getClass(), name, parameterType);
        return method == null ? null : method.invoke(target, argument);
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        try {
            constructor.trySetAccessible();
        } catch (RuntimeException ignored) {
            // Let newInstance report a real access problem.
        }
        return constructor;
    }

    private static Constructor<?> findConstructor(Class<?> type, Class<?>... parameterTypes) {
        try {
            return constructor(type, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Object invokeThreeInts(Object target, String name, int first, int second, int third)
            throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findCompatibleMethod(target.getClass(), name, int.class, int.class, int.class);
        return method == null ? null : method.invoke(target, first, second, third);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                try {
                    field.trySetAccessible();
                } catch (RuntimeException ignored) {
                    // Best effort for optional internals.
                }
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            }
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        return findCompatibleMethod(type, name);
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?>... arguments) {
        if (type == null) {
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!method.getName().equals(name) || parameters.length != arguments.length) {
                    continue;
                }
                boolean compatible = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (arguments[i] != null && !parameters[i].isAssignableFrom(arguments[i])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    try {
                        method.trySetAccessible();
                    } catch (RuntimeException ignored) {
                        // Best effort.
                    }
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals(name) || parameters.length != arguments.length) {
                continue;
            }
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (arguments[i] != null && !parameters[i].isAssignableFrom(arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        return null;
    }

    static final class StoredStack {
        private final ItemStack stack;
        private final String key;
        private final long amount;

        private StoredStack(ItemStack stack, long amount) {
            this.stack = stack.copy();
            this.stack.setCount(1);
            this.key = RecipeTreeData.ingredientKey(this.stack);
            this.amount = amount;
        }

        ItemStack stack() {
            return stack;
        }

        long amount() {
            return amount;
        }
    }

    static final class StoredFluid {
        private final String key;
        private final long amount;

        StoredFluid(String key, long amount) {
            this.key = key == null ? "" : key;
            this.amount = Math.max(0L, amount);
        }

        String key() {
            return key;
        }

        long amount() {
            return amount;
        }
    }
}
