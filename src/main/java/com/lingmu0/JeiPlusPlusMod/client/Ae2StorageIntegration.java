package com.lingmu0.JeiPlusPlusMod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Optional Applied Energistics 2 storage lookup for recipe-tree accounting.
 *
 * <p>
 * The integration intentionally uses reflection. JEI++ remains loadable
 * without AE2, and the AE2 classes are only touched while an AE2 menu is open.
 * The result is a client-side snapshot of the network's cached item inventory;
 * it is used for counts and recursive supply checks. Crafting-terminal
 * transfers and the optional AE2 grid highlight/order integration use AE2's
 * client-side objects only; no JEI++ server packet is introduced.
 * </p>
 */
final class Ae2StorageIntegration {
    private static final long CACHE_FALLBACK_NANOS = 50_000_000L;
    private static final String GRID_NODE = "appeng.api.networking.IGridNode";
    private static final String ITEM_KEY = "appeng.api.stacks.AEItemKey";
    private static final String CRAFTING_MENU = "appeng.menu.me.items.CraftingTermMenu";
    private static final String CRAFTING_RESULT_SLOT = "appeng.menu.slot.CraftingTermSlot";
    private static final String[] NETWORK_HANDLERS = {
            // AE2 1.20.x
            "appeng.core.sync.network.NetworkHandler",
            // AE2 1.21.x
            "appeng.core.network.NetworkHandler"
    };
    private static final String[] FILL_PACKETS = {
            // AE2 1.20.x
            "appeng.core.sync.packets.FillCraftingGridFromRecipePacket",
            // AE2 1.21.x
            "appeng.core.network.serverbound.FillCraftingGridFromRecipePacket"
    };
    private static final String[] INVENTORY_ACTION_PACKETS = {
            // AE2 1.20.x
            "appeng.core.sync.packets.InventoryActionPacket",
            // AE2 1.21.x
            "appeng.core.network.serverbound.InventoryActionPacket"
    };
    private static final String[] INVENTORY_ACTION_TYPES = {
            "appeng.helpers.InventoryAction"
    };

    private static volatile Object cachedMenu;
    private static volatile long cachedGameTime = Long.MIN_VALUE;
    private static volatile long cachedAtNanos = Long.MIN_VALUE;
    private static volatile List<StoredStack> cachedStacks = List.of();
    private static volatile List<StorageNetworkIntegration.StoredFluid> cachedFluids = List.of();
    private static volatile Object prioritizedRepo;
    private static volatile boolean repoWasPrioritized;

    private Ae2StorageIntegration() {
    }

    static List<StoredStack> storedStacks() {
        Minecraft minecraft = Minecraft.getInstance();
        Object menu = activeMenu();
        long gameTime = minecraft.level == null ? -1L : minecraft.level.getGameTime();
        long now = System.nanoTime();
        if (menu == null) {
            cachedMenu = null;
            cachedGameTime = gameTime;
            cachedAtNanos = now;
            cachedStacks = List.of();
            cachedFluids = List.of();
            return List.of();
        }

        List<StoredStack> previous = cachedStacks;
        long cacheAge = now - cachedAtNanos;
        if (menu == cachedMenu
                && gameTime == cachedGameTime
                && cacheAge >= 0L
                && cacheAge < CACHE_FALLBACK_NANOS) {
            return previous;
        }

        Object sharedClientEntries = clientEntries(menu);
        List<StoredStack> result;
        List<StorageNetworkIntegration.StoredFluid> fluids;
        if (sharedClientEntries instanceof Iterable<?> entries) {
            try {
                Class<?> itemKeyType = Class.forName(ITEM_KEY);
                RepositorySnapshot snapshot = readEntriesAndFluids(entries, itemKeyType);
                result = snapshot.stacks();
                fluids = snapshot.fluids();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                result = List.of();
                fluids = List.of();
            }
        } else {
            result = query(menu);
            fluids = queryFluids(menu);
        }
        synchronized (Ae2StorageIntegration.class) {
            cachedMenu = menu;
            cachedGameTime = gameTime;
            cachedAtNanos = now;
            cachedStacks = List.copyOf(result);
            cachedFluids = List.copyOf(fluids);
            return cachedStacks;
        }
    }

    static List<StorageNetworkIntegration.StoredFluid> storedFluids() {
        storedStacks();
        return cachedFluids;
    }

    /** Returns the menu currently backed by the player's screen/container. */
    static Object activeMenu() {
        Minecraft minecraft = Minecraft.getInstance();
        return activeMenu(minecraft.screen, minecraft);
    }

    /**
     * Fills an AE2 crafting terminal using the terminal's own server packet.
     * The packet is deliberately sent by AE2 itself: JEI++ stays client-only,
     * while AE2 validates access, power, and extraction on the server.
     *
     * @return {@code null} when this is not an AE2 crafting terminal,
     *         otherwise whether the request was sent (or would be valid in a
     *         simulation).
     */
    static Boolean tryFillCraftingGrid(Object menu, List<ItemStack> templates, boolean send) {
        if (menu == null || templates == null || templates.size() != 9) {
            return null;
        }
        try {
            if (!isCraftingMenu(menu)) {
                return null;
            }
            if (!send) {
                return true;
            }

            NonNullList<ItemStack> normalized = NonNullList.withSize(9, ItemStack.EMPTY);
            for (int index = 0; index < templates.size(); index++) {
                ItemStack stack = templates.get(index);
                normalized.set(index, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            }

            Class<?> packetType = firstLoadable(FILL_PACKETS);
            if (packetType == null) {
                return false;
            }
            Constructor<?> constructor = findFillPacketConstructor(packetType);
            if (constructor == null) {
                return false;
            }
            Object packet = constructor.newInstance(null, normalized, false);

            Class<?> networkType = firstLoadable(NETWORK_HANDLERS);
            if (networkType == null) {
                return false;
            }
            Method instance = findNoArgMethod(networkType, "instance");
            Object network = instance == null ? null : instance.invoke(null);
            Method sendToServer = findSingleArgumentMethod(networkType, "sendToServer", packetType);
            if (network == null || sendToServer == null) {
                return false;
            }
            sendToServer.invoke(network, packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Takes exactly one result from an AE2 crafting terminal.
     *
     * <p>
     * A normal {@code QUICK_MOVE} container click is not the operation used
     * by AE2's terminal screen. Its output slot is handled by
     * {@code CraftingTermSlot.doClick}, which is reached through AE2's
     * {@code InventoryActionPacket}. Calling the packet directly avoids
     * leaving the output in the terminal and, unlike {@code CRAFT_SHIFT},
     * {@code CRAFT_ITEM} performs one operation so the recipe-tree queue can
     * keep its exact-count accounting.
     * </p>
     *
     * @return {@code null} for a non-AE2 menu, otherwise whether the action
     *         was sent (or would be valid in a simulation)
     */
    static Boolean takeCraftingResult(Object menu, int resultSlot, boolean send) {
        if (menu == null || resultSlot < 0) {
            return null;
        }
        try {
            if (!isCraftingMenu(menu)) {
                return null;
            }
            if (!send) {
                return true;
            }

            Class<?> packetType = firstLoadable(INVENTORY_ACTION_PACKETS);
            Class<?> actionType = firstLoadable(INVENTORY_ACTION_TYPES);
            if (packetType == null || actionType == null || !actionType.isEnum()) {
                return false;
            }
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object action = Enum.valueOf((Class<? extends Enum>) actionType.asSubclass(Enum.class), "CRAFT_ITEM");
            Constructor<?> constructor = findInventoryActionPacketConstructor(packetType, actionType);
            if (constructor == null) {
                return false;
            }
            Object packet = constructor.newInstance(action, resultSlot, 0L);

            Class<?> networkType = firstLoadable(NETWORK_HANDLERS);
            if (networkType == null) {
                return false;
            }
            Method instance = findNoArgMethod(networkType, "instance");
            Object network = instance == null ? null : instance.invoke(null);
            Method sendToServer = findSingleArgumentMethod(networkType, "sendToServer", packetType);
            if (network == null || sendToServer == null) {
                return false;
            }
            sendToServer.invoke(network, packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Stores the item currently held by an AE2 menu cursor in the ME network.
     *
     * <p>
     * AE2 exposes this operation through
     * {@code MEStorageMenu.handleInteraction(-1, action)}. Calling that
     * client-side entry point makes AE2 send its own interaction packet; the
     * server then invokes the protected, powered insertion method. This is
     * important because invoking the protected method on the client menu alone
     * would only change a local copy. The boolean argument is AE2's "single
     * item" flag; recipe-tree output must be inserted as a complete stack, so
     * callers pass {@code false}.
     * </p>
     *
     * @return {@code null} for a non-AE2 crafting menu, {@code true} when the
     *         AE2 method was invoked, or {@code false} when this AE2 version
     *         does not expose the expected method
     */
    static Boolean putCarriedItemIntoNetwork(Object menu, boolean single) {
        if (menu == null) {
            return null;
        }
        try {
            if (!isCraftingMenu(menu)) {
                return null;
            }
            Class<?> actionType = firstLoadable(INVENTORY_ACTION_TYPES);
            if (actionType == null || !actionType.isEnum()) {
                return false;
            }
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object action = Enum.valueOf(
                    (Class<? extends Enum>) actionType.asSubclass(Enum.class),
                    single ? "SPLIT_OR_PLACE_SINGLE" : "PICKUP_OR_SET_DOWN");
            Method method = findInteractionMethod(menu.getClass(), actionType);
            if (method == null) {
                return false;
            }
            method.invoke(menu, -1L, action);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Finds AE2's client-side menu interaction hook without loading AE2 classes eagerly. */
    private static Method findInteractionMethod(Class<?> type, Class<?> actionType) {
        if (type == null || actionType == null) {
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("handleInteraction")
                        && parameters.length == 2
                        && parameters[0] == long.class
                        && parameters[1].isAssignableFrom(actionType)) {
                    try {
                        method.trySetAccessible();
                    } catch (RuntimeException ignored) {
                        // Let Method.invoke report a real access failure.
                    }
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * Moves recipe-tree-relevant entries to the front of AE2's visible item
     * list. AE2 keeps its rendered grid in a private {@code view} list, so the
     * optional integration changes that list reflectively and leaves the
     * server/network inventory untouched.
     */
    static void prioritizeVisibleEntries(Set<String> highlightedKeys) {
        Object menu = activeMenu();
        Object repo = menu == null ? null : invokeNoArgQuietly(menu, "getClientRepo");
        if (repo == null) {
            return;
        }
        Set<String> keys = highlightedKeys == null ? Set.of() : Set.copyOf(highlightedKeys);
        try {
            Field viewField = findField(repo.getClass(), "view");
            if (viewField == null) {
                return;
            }
            Object value = viewField.get(repo);
            if (!(value instanceof List<?> rawView)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> view = (List<Object>) rawView;
            if (keys.isEmpty()) {
                if (repo == prioritizedRepo && repoWasPrioritized) {
                    Method updateView = findNoArgMethod(repo.getClass(), "updateView");
                    if (updateView != null) {
                        updateView.invoke(repo);
                    }
                }
                prioritizedRepo = repo;
                repoWasPrioritized = false;
                return;
            }
            if (isPrioritized(view, keys)) {
                prioritizedRepo = repo;
                repoWasPrioritized = true;
                return;
            }
            view.sort((left, right) -> Boolean.compare(
                    isHighlightedEntry(right, keys),
                    isHighlightedEntry(left, keys)));
            prioritizedRepo = repo;
            repoWasPrioritized = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // AE2 is optional and its client repository is internal API.
        }
    }

    /** Returns whether the active menu has AE2's 3x3 crafting terminal grid. */
    static boolean isCraftingMenu(Object menu) {
        if (menu == null) {
            return false;
        }
        try {
            return Class.forName(CRAFTING_MENU).isInstance(menu);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** Finds the AE2 output slot so recursive crafting can take its result. */
    static int craftingResultSlot(AbstractContainerMenu menu) {
        if (menu == null) {
            return -1;
        }
        try {
            Class<?> resultType = Class.forName(CRAFTING_RESULT_SLOT);
            for (int index = 0; index < menu.slots.size(); index++) {
                Slot slot = menu.getSlot(index);
                if (resultType.isInstance(slot)) {
                    return index;
                }
            }
        } catch (RuntimeException | LinkageError | ClassNotFoundException ignored) {
            // AE2 is optional; ordinary vanilla/JEI menus use their own slot.
        }
        return -1;
    }

    /**
     * Checks that AE2 has acknowledged the requested 3x3 matrix contents.
     * Fill packets are asynchronous, so a stale output slot must not be
     * clicked before the new matrix has arrived on the client.
     */
    static Boolean craftingGridMatches(Object menu, List<ItemStack> templates) {
        if (menu == null || templates == null || templates.size() != 9) {
            return null;
        }
        try {
            if (!isCraftingMenu(menu)) {
                return null;
            }
            Object matrix = invokeNoArg(menu, "getCraftingMatrix");
            if (matrix == null) {
                return false;
            }
            int size = (int) numberValue(invokeNoArg(matrix, "size"));
            Method getStack = findSingleArgumentMethod(matrix.getClass(), "getStackInSlot", int.class);
            if (size < templates.size() || getStack == null) {
                return false;
            }
            for (int index = 0; index < templates.size(); index++) {
                Object value = getStack.invoke(matrix, index);
                ItemStack actual = value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
                ItemStack expected = templates.get(index);
                if (expected == null || expected.isEmpty()) {
                    if (!actual.isEmpty()) {
                        return false;
                    }
                } else if (actual.isEmpty()
                        || !RecipeTreeData.ingredientKey(actual).equals(RecipeTreeData.ingredientKey(expected))) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Object activeMenu(Screen screen, Minecraft minecraft) {
        Screen current = screen;
        for (int depth = 0; depth < 4 && current instanceof RecipeTreeScreen treeScreen; depth++) {
            current = treeScreen.parentScreen();
        }
        if (current instanceof AbstractContainerScreen<?> containerScreen) {
            return containerScreen.getMenu();
        }
        // This also covers a terminal menu whose screen was temporarily
        // replaced by a JEI screen. Non-AE menus simply have no network node.
        return minecraft.player == null ? null : minecraft.player.containerMenu;
    }

    private static List<StoredStack> query(Object menu) {
        try {
            Class<?> itemKeyType = Class.forName(ITEM_KEY);

            // AE2 menus use this client-side repository after the server has
            // synchronized the terminal contents. The server-only network
            // node is normally null on the client, so this is the primary
            // path for actual gameplay.
            Object clientRepo = invokeNoArg(menu, "getClientRepo");
            Object clientEntries = clientRepo == null ? null : invokeNoArg(clientRepo, "getAllEntries");
            if (clientEntries instanceof Iterable<?> entries) {
                return readEntries(entries, itemKeyType, "getWhat", "getStoredAmount");
            }

            Object networkNode = invokeNoArg(menu, "getNetworkNode");
            Class<?> nodeType = Class.forName(GRID_NODE);
            if (networkNode == null || !nodeType.isInstance(networkNode)) {
                return List.of();
            }

            Object grid = invokeNoArg(networkNode, "getGrid");
            if (grid == null) {
                return List.of();
            }
            Object storageService = invokeNoArg(grid, "getStorageService");
            if (storageService == null) {
                return List.of();
            }
            Object cachedInventory = invokeNoArg(storageService, "getCachedInventory");
            if (!(cachedInventory instanceof Iterable<?> entries)) {
                return List.of();
            }
            return readEntries(entries, itemKeyType, "getKey", "getLongValue");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // AE2 is optional and its internal menu implementation changes
            // between releases. A missing/changed API must degrade to the
            // normal player-inventory behavior instead of breaking JEI++.
            return List.of();
        }
    }

    private static Object clientEntries(Object menu) {
        try {
            Object clientRepo = invokeNoArg(menu, "getClientRepo");
            return clientRepo == null ? null : invokeNoArg(clientRepo, "getAllEntries");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static List<StorageNetworkIntegration.StoredFluid> queryFluids(Object menu) {
        try {
            Object networkNode = invokeNoArg(menu, "getNetworkNode");
            Class<?> nodeType = Class.forName(GRID_NODE);
            if (networkNode == null || !nodeType.isInstance(networkNode)) {
                return List.of();
            }
            Object grid = invokeNoArg(networkNode, "getGrid");
            Object storageService = invokeNoArg(grid, "getStorageService");
            if (storageService == null) {
                return List.of();
            }
            Object cachedInventory = invokeNoArg(storageService, "getCachedInventory");
            List<StorageNetworkIntegration.StoredFluid> result = readFluidEntries(cachedInventory, "getKey",
                    "getLongValue");
            if (!result.isEmpty()) {
                return result;
            }
            for (String accessor : List.of("getCachedFluidInventory", "getFluidInventory", "getCachedFluids")) {
                Object fluidInventory = invokeNoArg(storageService, accessor);
                result = readFluidEntries(fluidInventory, "getKey", "getLongValue");
                if (!result.isEmpty()) {
                    return result;
                }
            }
            return List.of();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    /**
     * Reads AE2's mixed item/fluid repository once instead of walking it once
     * for items and a second time for fluids. Large networks commonly expose
     * thousands of entries, so keeping this pass linear is important while a
     * recipe tree is refreshing.
     */
    private static RepositorySnapshot readEntriesAndFluids(
            Iterable<?> entries,
            Class<?> itemKeyType) throws ReflectiveOperationException {
        List<StoredStack> stacks = new ArrayList<>();
        List<StorageNetworkIntegration.StoredFluid> fluids = new ArrayList<>();
        for (Object entry : entries) {
            Object key = invokeNoArg(entry, "getWhat");
            if (key == null) {
                key = invokeNoArg(entry, "getKey");
            }
            if (key == null) {
                continue;
            }
            long amount = entryAmount(entry, "getStoredAmount");
            if (amount <= 0) {
                continue;
            }
            if (itemKeyType.isInstance(key)) {
                ItemStack stack = stackFromAeKey(key);
                if (!stack.isEmpty()) {
                    ItemStack representative = stack.copy();
                    representative.setCount(1);
                    stacks.add(new StoredStack(representative, amount));
                }
                continue;
            }
            Optional<FluidRecipeCompat.FluidInfo> info = FluidRecipeCompat.describeFluid(key);
            if (info.isPresent()) {
                long fluidAmount = Math.max(amount, info.get().amount());
                if (fluidAmount > 0) {
                    fluids.add(new StorageNetworkIntegration.StoredFluid(info.get().key(), fluidAmount));
                }
            }
        }
        return new RepositorySnapshot(List.copyOf(stacks), List.copyOf(fluids));
    }

    private static long entryAmount(Object entry, String preferredMethod) throws ReflectiveOperationException {
        long amount = numberValue(invokeNoArg(entry, preferredMethod));
        if (amount > 0) {
            return amount;
        }
        for (String method : List.of("getStoredAmount", "getAmount", "getLongValue", "getValue")) {
            amount = numberValue(invokeNoArg(entry, method));
            if (amount > 0) {
                return amount;
            }
        }
        return 0L;
    }

    private record RepositorySnapshot(
            List<StoredStack> stacks,
            List<StorageNetworkIntegration.StoredFluid> fluids) {
    }

    private static List<StoredStack> readEntries(
            Iterable<?> entries,
            Class<?> itemKeyType,
            String keyMethod,
            String amountMethod) throws ReflectiveOperationException {
        List<StoredStack> result = new ArrayList<>();
        for (Object entry : entries) {
            Object key = invokeNoArg(entry, keyMethod);
            if (key == null) {
                key = invokeNoArg(entry, "getWhat");
            }
            if (key == null) {
                key = invokeNoArg(entry, "getKey");
            }
            if (key == null || !itemKeyType.isInstance(key)) {
                continue;
            }
            long amount = numberValue(invokeNoArg(entry, amountMethod));
            if (amount <= 0) {
                amount = numberValue(invokeNoArg(entry, "getStoredAmount"));
            }
            if (amount <= 0) {
                amount = numberValue(invokeNoArg(entry, "getAmount"));
            }
            if (amount <= 0) {
                amount = numberValue(invokeNoArg(entry, "getLongValue"));
            }
            if (amount <= 0) {
                amount = numberValue(invokeNoArg(entry, "getValue"));
            }
            if (amount <= 0) {
                continue;
            }

            Object stackValue = invokeNoArg(key, "toStack");
            if (!(stackValue instanceof ItemStack stack) || stack.isEmpty()) {
                stackValue = invokeNoArg(key, "getReadOnlyStack");
            }
            if (!(stackValue instanceof ItemStack stack) || stack.isEmpty()) {
                continue;
            }
            ItemStack representative = stack.copy();
            representative.setCount(1);
            result.add(new StoredStack(representative, amount));
        }
        return result;
    }

    private static List<StorageNetworkIntegration.StoredFluid> readFluidEntries(
            Object source,
            String keyMethod,
            String amountMethod) throws ReflectiveOperationException {
        if (!(source instanceof Iterable<?> entries)) {
            return List.of();
        }
        List<StorageNetworkIntegration.StoredFluid> result = new ArrayList<>();
        for (Object entry : entries) {
            Object key = invokeNoArg(entry, keyMethod);
            if (key == null) {
                key = invokeNoArg(entry, "getWhat");
            }
            if (key == null) {
                key = invokeNoArg(entry, "getKey");
            }
            Optional<FluidRecipeCompat.FluidInfo> info = FluidRecipeCompat.describeFluid(key);
            if (info.isEmpty()) {
                continue;
            }
            long amount = numberValue(invokeNoArg(entry, amountMethod));
            if (amount <= 0) {
                for (String method : List.of("getStoredAmount", "getAmount", "getLongValue", "getValue")) {
                    amount = numberValue(invokeNoArg(entry, method));
                    if (amount > 0) {
                        break;
                    }
                }
            }
            if (info.get().amount() > 0) {
                amount = Math.max(amount, info.get().amount());
            }
            if (amount > 0) {
                result.add(new StorageNetworkIntegration.StoredFluid(info.get().key(), amount));
            }
        }
        return result;
    }

    private static long numberValue(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findNoArgMethod(target.getClass(), name);
        return method == null ? null : method.invoke(target);
    }

    private static Object invokeNoArgQuietly(Object target, String name) {
        try {
            return invokeNoArg(target, name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                try {
                    field.trySetAccessible();
                } catch (RuntimeException ignored) {
                    // Let Field.get report an access failure below.
                }
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through the class hierarchy.
            }
        }
        return null;
    }

    private static boolean isPrioritized(List<?> view, Set<String> keys) {
        boolean seenUnhighlighted = false;
        for (Object entry : view) {
            boolean highlighted = isHighlightedEntry(entry, keys);
            if (highlighted && seenUnhighlighted) {
                return false;
            }
            if (!highlighted) {
                seenUnhighlighted = true;
            }
        }
        return true;
    }

    private static boolean isHighlightedEntry(Object entry, Set<String> keys) {
        if (entry == null || keys.isEmpty()) {
            return false;
        }
        Object key = invokeNoArgQuietly(entry, "getWhat");
        ItemStack stack = stackFromAeKey(key);
        if (stack != null && !stack.isEmpty()
                && StorageNetworkIntegration.matchesHighlightKey(stack, keys)) {
            return true;
        }
        return FluidRecipeCompat.describeFluid(key)
                .map(info -> keys.contains(info.key()))
                .orElse(false);
    }

    private static ItemStack stackFromAeKey(Object key) {
        if (key == null) {
            return ItemStack.EMPTY;
        }
        Object stack = invokeNoArgQuietly(key, "wrapForDisplayOrFilter");
        if (!(stack instanceof ItemStack item) || item.isEmpty()) {
            stack = invokeNoArgQuietly(key, "toStack");
        }
        if (!(stack instanceof ItemStack item) || item.isEmpty()) {
            stack = invokeNoArgQuietly(key, "getReadOnlyStack");
        }
        return stack instanceof ItemStack item ? item : ItemStack.EMPTY;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                try {
                    method.trySetAccessible();
                } catch (RuntimeException ignored) {
                    // Public methods can still be invoked if access was not
                    // needed; let Method.invoke report a real failure.
                }
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue through the hierarchy and interfaces below.
            }
        }
        try {
            Method method = type.getMethod(name);
            try {
                method.trySetAccessible();
            } catch (RuntimeException ignored) {
                // See the comment above.
            }
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Class<?> firstLoadable(String[] names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the package used by the other supported AE2 line.
            }
        }
        return null;
    }

    private static Constructor<?> findFillPacketConstructor(Class<?> packetType) {
        for (Constructor<?> constructor : packetType.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 3
                    && parameters[0] == ResourceLocation.class
                    && parameters[1].isAssignableFrom(NonNullList.class)
                    && parameters[2] == boolean.class) {
                return constructor;
            }
        }
        return null;
    }

    private static Constructor<?> findInventoryActionPacketConstructor(
            Class<?> packetType,
            Class<?> actionType) {
        for (Constructor<?> constructor : packetType.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 3
                    && parameters[0].isAssignableFrom(actionType)
                    && parameters[1] == int.class
                    && parameters[2] == long.class) {
                return constructor;
            }
        }
        return null;
    }

    private static Method findSingleArgumentMethod(Class<?> type, String name, Class<?> argumentType) {
        if (type == null) {
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals(name)
                        && parameters.length == 1
                        && parameters[0].isAssignableFrom(argumentType)) {
                    try {
                        method.trySetAccessible();
                    } catch (RuntimeException ignored) {
                        // Public methods can still be invoked if access was not needed.
                    }
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name)
                    && parameters.length == 1
                    && parameters[0].isAssignableFrom(argumentType)) {
                return method;
            }
        }
        return null;
    }

    static final class StoredStack {
        private final ItemStack stack;
        private final long amount;

        private StoredStack(ItemStack stack, long amount) {
            this.stack = stack.copy();
            this.stack.setCount(1);
            this.amount = amount;
        }

        ItemStack stack() {
            return stack;
        }

        long amount() {
            return amount;
        }
    }
}
