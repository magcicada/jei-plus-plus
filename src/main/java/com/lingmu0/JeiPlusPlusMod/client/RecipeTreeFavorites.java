package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.mixin.BookmarkListAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.Internal;
import mezz.jei.common.platform.Services;
import mezz.jei.common.input.UserInput;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.util.FocusUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** EMI-style, non-persistent tree products and costs appended to JEI bookmarks. */
public final class RecipeTreeFavorites {
    private static final long REFRESH_INTERVAL_NANOS = 200_000_000L;
    private static BookmarkList bookmarkList;
    private static List<IElement<?>> elements = List.of();
    private static Set<String> requiredIngredientKeys = Set.of();
    private static Set<String> intermediateIngredientKeys = Set.of();
    private static Set<String> finalProductIngredientKeys = Set.of();
    private static String signature = "";
    private static long lastRefreshNanos = Long.MIN_VALUE;
    private static Object lastRefreshMenu;
    private static long lastRefreshStorageRevision = Long.MIN_VALUE;
    private static int layoutNativeBookmarkCount = -1;
    private static int layoutBookmarkColumns = -1;
    private static boolean synchronizingNativeBookmarkLayout;
    @SuppressWarnings("unchecked")
    /* Structural ingredient used only by the row-aware JEI renderer. */
    private static final ITypedIngredient<ItemStack> ROW_BREAK_INGREDIENT = (ITypedIngredient<ItemStack>) Proxy
            .newProxyInstance(
                    ITypedIngredient.class.getClassLoader(),
                    new Class<?>[]{ITypedIngredient.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getType" -> VanillaTypes.ITEM_STACK;
                        case "getIngredient" -> ItemStack.EMPTY;
                        case "getItemStack" -> Optional.empty();

                        case "normalize", "castToItemStackType" -> proxy;

                        case "cast" -> {
                            Object requestedType = arguments == null || arguments.length == 0
                                    ? null
                                    : arguments[0];

                            yield VanillaTypes.ITEM_STACK.equals(requestedType)
                                    ? proxy
                                    : null;
                        }

                        case "getCastIngredient" -> {
                            Object requestedType = arguments == null || arguments.length == 0
                                    ? null
                                    : arguments[0];

                            yield VanillaTypes.ITEM_STACK.equals(requestedType)
                                    ? ItemStack.EMPTY
                                    : null;
                        }

                        case "equals" ->
                            proxy == (arguments == null ? null : arguments[0]);

                        case "hashCode" ->
                            System.identityHashCode(proxy);

                        case "toString" ->
                            "JEI++ recipe-tree row-break ingredient";

                        default -> throw new UnsupportedOperationException(
                                "Unsupported ITypedIngredient method: " + method);
                    });

    private static final Set<IElement<?>> ROW_BREAKS = Collections.newSetFromMap(new IdentityHashMap<>());

    private RecipeTreeFavorites() {
    }

    public static void bind(BookmarkList list) {
        bookmarkList = list;
    }

    /** Binds lazily when a tree is opened before JEI has drawn its overlay. */
    private static void ensureBound() {
        if (bookmarkList != null) {
            return;
        }
        try {
            Object overlay = Internal.getJeiRuntime().getBookmarkOverlay();
            Class<?> type = overlay == null ? null : overlay.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("bookmarkList");
                    field.setAccessible(true);
                    Object value = field.get(overlay);
                    if (value instanceof BookmarkList list) {
                        bookmarkList = list;
                        return;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    type = type.getSuperclass();
                }
            }
        } catch (RuntimeException ignored) {
            // JEI may still be constructing its runtime during a reload.
        }
    }

    /** Returns the player's bookmarked recipe for this output, if any. */
    public static Optional<RecipeTreeData.RecipeRef> bookmarkedRecipe(
            ItemStack output,
            List<RecipeTreeData.RecipeRef> candidates) {
        ensureBound();
        if (bookmarkList == null || output == null || output.isEmpty() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String outputKey = RecipeTreeData.ingredientKey(output);
        for (IElement<?> element : bookmarkList.getElements()) {
            Optional<IBookmark> bookmark = element.getBookmark();
            if (bookmark.isEmpty() || !isRecipeBookmark(bookmark.get())) {
                continue;
            }
            if (!outputKey.equals(typedIngredientKey(element.getTypedIngredient()))) {
                continue;
            }
            Object category = invokeNoArg(bookmark.get(), "getRecipeCategory");
            Object recipe = invokeNoArg(bookmark.get(), "getRecipe");
            if (category == null || recipe == null) {
                continue;
            }
            for (RecipeTreeData.RecipeRef candidate : candidates) {
                if (candidate.category() == category && Objects.equals(candidate.recipe(), recipe)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** Returns a bookmarked ingredient that is one of the supplied alternatives. */
    public static Optional<ItemStack> bookmarkedCandidate(List<ItemStack> alternatives) {
        ensureBound();
        if (bookmarkList == null || alternatives == null || alternatives.isEmpty()) {
            return Optional.empty();
        }
        Set<String> candidateKeys = alternatives.stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(RecipeTreeData::ingredientKey)
                .collect(java.util.stream.Collectors.toSet());
        for (IElement<?> element : bookmarkList.getElements()) {
            Optional<IBookmark> bookmark = element.getBookmark();
            if (bookmark.isEmpty()
                    || (!isIngredientBookmark(bookmark.get()) && !isRecipeBookmark(bookmark.get()))) {
                continue;
            }
            String bookmarkedKey = typedIngredientKey(element.getTypedIngredient());
            if (bookmarkedKey.isEmpty() || !candidateKeys.contains(bookmarkedKey)) {
                continue;
            }
            for (ItemStack alternative : alternatives) {
                if (RecipeTreeData.ingredientKey(alternative).equals(bookmarkedKey)
                        || FluidRecipeCompat.fluidKey(alternative).map(bookmarkedKey::equals).orElse(false)) {
                    return Optional.of(FluidRecipeCompat.copyWithDisplay(alternative));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the ingredient keys bookmarked by the player. Recipe-tree
     * candidate resolution uses these keys as virtual terminal materials when
     * it performs its recursive availability check. A bookmark is only a
     * preference signal (it is not counted as inventory), but treating it as a
     * recursive leaf makes a bookmarked spruce log select spruce planks just
     * like an actual spruce log in the player's inventory would.
     */
    public static Set<String> bookmarkedIngredientKeys() {
        ensureBound();
        if (bookmarkList == null) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (IElement<?> element : bookmarkList.getElements()) {
            Optional<IBookmark> bookmark = element.getBookmark();
            if (bookmark.isEmpty() || !isIngredientBookmark(bookmark.get())) {
                continue;
            }
            String key = typedIngredientKey(element.getTypedIngredient());
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return Set.copyOf(keys);
    }

    private static boolean isIngredientBookmark(IBookmark bookmark) {
        return bookmark.getClass().getName().endsWith("IngredientBookmark");
    }

    private static boolean isRecipeBookmark(IBookmark bookmark) {
        return bookmark.getClass().getName().endsWith("RecipeBookmark");
    }

    private static Object invokeNoArg(Object value, String methodName) {
        try {
            return value.getClass().getMethod(methodName).invoke(value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String typedIngredientKey(ITypedIngredient<?> typed) {
        if (typed == null) {
            return "";
        }
        Optional<ItemStack> item = typed.getIngredient(VanillaTypes.ITEM_STACK);
        if (item.isPresent() && !item.get().isEmpty()) {
            return RecipeTreeData.ingredientKey(item.get());
        }
        return FluidRecipeCompat.fluid(typed)
                .map(value -> "fluid:" + net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(value.getFluid()))
                .orElse("");
    }

    public static List<IElement<?>> elements() {
        return elements;
    }

    public static boolean isActive() {
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        // The overlay asks whether it has room before its first draw pass.
        // Refresh lazily here so a newly opened crafting tree cannot be
        // classified as an empty bookmark list for that pass.
        if (tree != null && tree.craftingMode() && elements.isEmpty()) {
            refreshThrottled();
        }
        // Native JEI bookmarks must remain visible even when the current tree
        // has no synthetic cost/product entry yet (for example while a new
        // tree is rebuilding or when every requirement is already supplied).
        return tree != null && tree.craftingMode();
    }

    public static boolean isRequired(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isActive()
                && containsIngredientKey(requiredIngredientKeys, stack);
    }

    static boolean isRequiredKey(String key) {
        return isActive() && key != null && requiredIngredientKeys.contains(key);
    }

    public static boolean isIntermediate(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isActive()
                && containsIngredientKey(intermediateIngredientKeys, stack);
    }

    static boolean isIntermediateKey(String key) {
        return isActive() && key != null && intermediateIngredientKeys.contains(key);
    }

    public static boolean isFinalProduct(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && isActive()
                && containsIngredientKey(finalProductIngredientKeys, stack);
    }

    static boolean isFinalProductKey(String key) {
        return isActive() && key != null && finalProductIngredientKeys.contains(key);
    }

    private static boolean containsIngredientKey(Set<String> keys, ItemStack stack) {
        String key = RecipeTreeData.ingredientKey(stack);
        return keys.contains(key)
                || FluidRecipeCompat.displayFluidKey(stack).map(keys::contains).orElse(false);
    }

    /** Network terminals often render fake storage slots outside JEI's normal slot hook. */
    public static boolean isNetworkStorageSlot(Slot slot) {
        if (slot == null) {
            return false;
        }
        String name = slot.getClass().getName();
        return name.endsWith(".RepoSlot")
                || (name.contains("beyonddimensions") && name.contains("StackTypedSlot"));
    }

    /** Draws overlays for terminal entries that are not vanilla menu slots. */
    public static void renderVirtualNetworkHighlights(
            GuiGraphics graphics,
            AbstractContainerScreen<?> screen) {
        StorageNetworkIntegration.renderVirtualStorageHighlights(graphics, screen);
    }

    /** Draws recipe-tree overlays on Better Beyond Dimensions' virtual sidebar slots. */
    public static void renderBetterBeyondHighlights(
            GuiGraphics graphics,
            AbstractContainerScreen<?> screen) {
        StorageNetworkIntegration.renderBetterBeyondHighlights(graphics, screen);
    }

    /** Draws Integrated Terminals overlays before that screen renders its tooltip. */
    public static void renderIntegratedTerminalHighlightsBeforeTooltip(
            GuiGraphics graphics,
            AbstractContainerScreen<?> screen) {
        StorageNetworkIntegration.renderIntegratedTerminalHighlightsBeforeTooltip(graphics, screen);
    }

    public static void refreshThrottled() {
        long now = System.nanoTime();
        Object menu = Ae2StorageIntegration.activeMenu();
        RecipeTreeData.Tree activeTree = RecipeTreeSession.craftingTree();
        long storageRevision = activeTree != null && activeTree.craftingMode()
                ? StorageNetworkIntegration.snapshotRevision()
                : Long.MIN_VALUE;
        long elapsed = now - lastRefreshNanos;
        if (menu == lastRefreshMenu
                && storageRevision == lastRefreshStorageRevision
                && lastRefreshNanos != Long.MIN_VALUE
                && elapsed >= 0L
                && elapsed < REFRESH_INTERVAL_NANOS) {
            return;
        }
        refreshNow();
    }

    /**
     * JEI mutates its native bookmark list before notifying its layout
     * listeners. Rebuild our row padding at that exact point so the first
     * listener pass already sees the final positions instead of rendering one
     * frame with padding calculated for the previous bookmark count.
     */
    public static void refreshBeforeBookmarkListNotification() {
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (synchronizingNativeBookmarkLayout
                || bookmarkList == null
                || tree == null
                || !tree.craftingMode()) {
            return;
        }
        int columns = bookmarkColumns();
        int nativeBookmarks = nativeBookmarkCount();
        if (columns == layoutBookmarkColumns && nativeBookmarks == layoutNativeBookmarkCount) {
            return;
        }
        synchronizingNativeBookmarkLayout = true;
        try {
            refreshNow();
        } finally {
            synchronizingNativeBookmarkLayout = false;
        }
    }

    public static void refreshNow() {
        lastRefreshNanos = System.nanoTime();
        lastRefreshMenu = Ae2StorageIntegration.activeMenu();
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        lastRefreshStorageRevision = tree != null && tree.craftingMode()
                ? StorageNetworkIntegration.snapshotRevision()
                : Long.MIN_VALUE;
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        List<IElement<?>> finalProducts = new ArrayList<>();
        List<IElement<?>> intermediateProducts = new ArrayList<>();
        List<IElement<?>> rawMaterials = new ArrayList<>();
        Set<String> nextRequired = new HashSet<>();
        Set<String> nextIntermediate = new HashSet<>();
        Set<String> nextFinal = new HashSet<>();
        StringBuilder nextSignature = new StringBuilder();
        int columns = bookmarkColumns();
        int nativeBookmarks = 0;
        Map<String, Long> inventoryAmounts = Map.of();
        if (tree != null && tree.craftingMode() && runtime != null) {
            inventoryAmounts = RecipeTreeData.inventoryAmounts();
            nativeBookmarks = nativeBookmarkCount();
            // Native JEI bookmarks are prepended to the synthetic tree rows.
            // Their count is therefore part of the layout state: when one is
            // added or removed, the leading padding must be rebuilt and JEI
            // must be told to lay the list out again.
            nextSignature.append('L').append(columns).append(':').append(nativeBookmarks).append(';');
            String rootKey = tree.root().ingredientKey();
            nextFinal.add(rootKey);
            for (RecipeTreeData.CraftStep step : tree.craftingSteps()) {
                String stepKey = RecipeTreeData.ingredientKey(step.stack());
                long owned = RecipeTreeData.inventoryAmount(step.alternatives(), inventoryAmounts);
                if (!stepKey.equals(rootKey)) {
                    for (ItemStack alternative : step.alternatives()) {
                        nextIntermediate.add(RecipeTreeData.ingredientKey(alternative));
                    }
                }
                createTyped(runtime, step.stack()).ifPresent(typed -> {
                    SyntheticBookmark bookmark = new SyntheticBookmark(typed, step, null, !stepKey.equals(rootKey));
                    if (stepKey.equals(rootKey)) {
                        finalProducts.add(bookmark.getElement());
                    } else {
                        intermediateProducts.add(bookmark.getElement());
                    }
                    nextSignature.append('R').append(step.recipe().key())
                            .append(':').append(owned).append(':').append(step.total())
                            .append(':').append(step.alternatives().stream()
                                    .map(RecipeTreeData::ingredientKey).sorted().toList())
                            .append(':').append(step.selectedInputs()).append(';');
                });
            }
            RecipeTreeData.Analysis analysis = tree.analyze();
            for (RecipeTreeData.Cost cost : analysis.costs()) {
                long owned = RecipeTreeData.inventoryAmount(cost.alternatives(), inventoryAmounts);
                for (ItemStack alternative : cost.alternatives()) {
                    nextRequired.add(RecipeTreeData.ingredientKey(alternative));
                }
                createTyped(runtime, cost.stack()).ifPresent(typed -> {
                    SyntheticBookmark bookmark = new SyntheticBookmark(typed, null, cost, false);
                    rawMaterials.add(bookmark.getElement());
                    nextSignature.append('C').append(cost.alternatives().stream()
                            .map(RecipeTreeData::ingredientKey).sorted().toList())
                            .append(':').append(owned).append(':').append(cost.required()).append(';');
                });
            }
        }
        List<IElement<?>> next = new ArrayList<>();
        ROW_BREAKS.clear();
        int cursor = nativeBookmarks;
        cursor = appendGroup(next, finalProducts, columns, cursor);
        cursor = appendGroup(next, intermediateProducts, columns, cursor);
        appendGroup(next, rawMaterials, columns, cursor);
        String newSignature = nextSignature.toString();
        boolean changed = !newSignature.equals(signature);
        elements = List.copyOf(next);
        requiredIngredientKeys = Set.copyOf(nextRequired);
        intermediateIngredientKeys = Set.copyOf(nextIntermediate);
        finalProductIngredientKeys = Set.copyOf(nextFinal);
        Set<String> highlightedAeKeys = new HashSet<>(nextRequired);
        highlightedAeKeys.addAll(nextIntermediate);
        highlightedAeKeys.addAll(nextFinal);
        // Native storage screens rebuild their view from packet callbacks.
        // Queue the partition for the next pre-render pass; native-update
        // mixins also apply it immediately at the end of those callbacks.
        StorageNetworkIntegration.queueVisibleEntries(highlightedAeKeys);
        layoutNativeBookmarkCount = tree != null && tree.craftingMode() && runtime != null
                ? nativeBookmarks
                : -1;
        layoutBookmarkColumns = tree != null && tree.craftingMode() && runtime != null
                ? columns
                : -1;
        signature = newSignature;
        if (changed && bookmarkList != null && !synchronizingNativeBookmarkLayout) {
            ((BookmarkListAccessor) (Object) bookmarkList).jeiPlusPlus$notifyListenersOfChange();
        }
    }

    /** Applies a queued storage-list partition from the next render pass. */
    public static void applyPendingNetworkPriority() {
        StorageNetworkIntegration.applyPendingVisibleEntries();
    }

    /**
     * Re-applies the current recipe-tree partition immediately after an
     * optional storage terminal rebuilds its native view. Network terminals
     * rebuild their list from a packet/update callback, so a render-end sort
     * can otherwise be overwritten one frame later. Keeping this entry point
     * here also lets the optional integration mixins stay completely
     * reflective/client-only.
     */
    public static void applyCurrentNetworkPriority() {
        if (!isActive()) {
            return;
        }
        Set<String> keys = new HashSet<>(requiredIngredientKeys);
        keys.addAll(intermediateIngredientKeys);
        keys.addAll(finalProductIngredientKeys);
        StorageNetworkIntegration.prioritizeVisibleEntries(keys);
    }

    private static int appendGroup(List<IElement<?>> destination, List<IElement<?>> group, int columns,
            int currentCount) {
        if (group.isEmpty()) {
            return currentCount;
        }
        int padding = (columns - (currentCount % columns)) % columns;
        for (int i = 0; i < padding; i++) {
            destination.add(createRowBreak());
        }
        destination.addAll(group);
        int remainder = group.size() % columns;
        if (remainder != 0) {
            for (int i = remainder; i < columns; i++) {
                destination.add(createRowBreak());
            }
        }
        return currentCount + padding + group.size() + ((columns - remainder) % columns);
    }

    private static int nativeBookmarkCount() {
        if (bookmarkList == null) {
            return 0;
        }
        int count = 0;
        for (IElement<?> element : bookmarkList.getElements()) {
            Optional<IBookmark> bookmark = element.getBookmark();
            if (bookmark.isPresent() && !bookmark.get().getClass().getName().contains("SyntheticBookmark")) {
                count++;
            }
        }
        return count;
    }

    private static int bookmarkColumns() {
        // JEI can shrink or move the bookmark grid when another overlay (for
        // example FTB's buttons) occupies the left side of the screen. The
        // configured maximum is only a fallback; the laid-out slot geometry
        // is the authoritative column count for our synthetic row breaks.
        int laidOut = laidOutBookmarkColumns();
        if (laidOut > 0) {
            return laidOut;
        }

        try {
            return Math.max(
                    1,
                    Internal.getClientConfigs()
                            .getBookmarkListConfig()
                            .getMaxColumns());
        } catch (RuntimeException ignored) {
            // JEI may not have initialized its client configs yet.
            return 9;
        }
    }

    private static int laidOutBookmarkColumns() {
        try {
            Object overlay = Internal.getJeiRuntime().getBookmarkOverlay();
            if (overlay == null) {
                return -1;
            }
            Object contents = null;
            Class<?> type = overlay.getClass();
            while (type != null && contents == null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("contents");
                    field.setAccessible(true);
                    contents = field.get(overlay);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (contents == null) {
                return -1;
            }
            Object slotsValue = invokeNoArg(contents, "getSlots");
            List<?> slots;
            if (slotsValue instanceof java.util.stream.Stream<?> stream) {
                slots = stream.toList();
            } else if (slotsValue instanceof Iterable<?> iterable) {
                List<Object> copy = new ArrayList<>();
                iterable.forEach(copy::add);
                slots = copy;
            } else {
                return -1;
            }
            int firstRow = Integer.MAX_VALUE;
            for (Object slot : slots) {
                Object area = invokeNoArg(slot, "getArea");
                Object y = invokeNoArg(area, "getY");
                if (y instanceof Number number) {
                    firstRow = Math.min(firstRow, number.intValue());
                }
            }
            if (firstRow == Integer.MAX_VALUE) {
                return -1;
            }
            int columns = 0;
            for (Object slot : slots) {
                Object area = invokeNoArg(slot, "getArea");
                Object y = invokeNoArg(area, "getY");
                if (y instanceof Number number && number.intValue() == firstRow) {
                    columns++;
                }
            }
            return columns > 0 ? columns : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static IElement<?> createRowBreak() {
        IElement<?> element = (IElement<?>) Proxy.newProxyInstance(
                IElement.class.getClassLoader(),
                new Class<?>[]{IElement.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getTypedIngredient" -> ROW_BREAK_INGREDIENT;
                    case "getBookmark" -> Optional.empty();
                    case "createRenderOverlay", "show", "getTooltip", "tick" -> null;
                    case "isVisible" -> false;
                    case "handleClick" -> false;
                    case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "JEI++ recipe-tree row spacer";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
        ROW_BREAKS.add(element);
        return element;
    }

    /** Returns true for the structural row markers used by this tree list. */
    public static boolean isRowBreak(Object element) {
        return element instanceof IElement<?> value && ROW_BREAKS.contains(value);
    }

    private static Optional<ITypedIngredient<?>> createTyped(IJeiRuntime runtime, ItemStack source) {
        ItemStack stack = FluidRecipeCompat.copyWithDisplay(source);
        stack.setCount(1);
        return FluidRecipeCompat.toTyped(runtime.getIngredientManager(), stack);
    }

    private static String amount(long value) {
        if (value >= 1_000_000_000L)
            return String.format(java.util.Locale.ROOT, "%.1fB", value / 1_000_000_000.0).replace(".0", "");
        if (value >= 1_000_000L)
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000.0).replace(".0", "");
        if (value >= 10_000L)
            return String.format(java.util.Locale.ROOT, "%.1fK", value / 1_000.0).replace(".0", "");
        return Long.toString(value);
    }

    private static final class SyntheticBookmark implements IBookmark {
        private final IElement<?> element;

        private SyntheticBookmark(
                ITypedIngredient<?> typed,
                RecipeTreeData.CraftStep step,
                RecipeTreeData.Cost cost,
                boolean intermediate) {
            this.element = SyntheticElement.create(this, typed, step, cost, intermediate);
        }

        @Override
        public IElement<?> getElement() {
            return element;
        }
        @Override
        public boolean isVisible() {
            return true;
        }
        @Override
        public void setVisible(boolean visible) {
        }
    }

    /** Runtime bridge for JEI 15.19-15.48 IElement API changes. */
    private static final class SyntheticElement implements InvocationHandler {
        private final IBookmark bookmark;
        private final ITypedIngredient<?> typed;
        private final RecipeTreeData.CraftStep step;
        private final RecipeTreeData.Cost cost;
        private final boolean intermediate;

        private SyntheticElement(
                IBookmark bookmark,
                ITypedIngredient<?> typed,
                RecipeTreeData.CraftStep step,
                RecipeTreeData.Cost cost,
                boolean intermediate) {
            this.bookmark = bookmark;
            this.typed = typed;
            this.step = step;
            this.cost = cost;
            this.intermediate = intermediate;
        }

        private static IElement<?> create(
                IBookmark bookmark,
                ITypedIngredient<?> typed,
                RecipeTreeData.CraftStep step,
                RecipeTreeData.Cost cost,
                boolean intermediate) {
            return (IElement<?>) Proxy.newProxyInstance(
                    IElement.class.getClassLoader(),
                    new Class<?>[]{IElement.class},
                    new SyntheticElement(bookmark, typed, step, cost, intermediate));
        }

        private IDrawable createRenderOverlay() {
            long remaining = remainingDisplayAmount();
            int color = step == null
                    ? (remaining == 0 ? 0xFF55FF55 : 0xFFFF5555)
                    : (remaining == 0 ? 0xFF55FF55 : (intermediate ? 0xFFFFAA33 : 0xFF55CCFF));
            return new AmountOverlay(remainingText(remaining), color);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void show(IRecipesGui recipesGui, FocusUtil focusUtil, List<RecipeIngredientRole> roles) {
            if (step != null) {
                IRecipeCategory category = step.recipe().category();
                if (!roles.isEmpty() && !roles.contains(RecipeIngredientRole.OUTPUT)) {
                    recipesGui.show(focusUtil.createFocuses(typed, roles));
                    return;
                }
                List<IFocus<?>> focuses = focusUtil.createFocuses(
                        typed,
                        List.of(RecipeIngredientRole.OUTPUT));
                IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
                if (runtime == null) {
                    recipesGui.showRecipes(category, List.of(step.recipe().recipe()), focuses);
                    return;
                }
                // A tree bookmark represents one preferred recipe, but it
                // must not narrow the lookup to that recipe's category. Use
                // JEI's normal cross-category focus search and carry the
                // category as ordering context so the preferred machine page
                // still opens first when BOOKMARKED sorting is active.
                RecipeBookmarkNavigationContext.showInCategoryFirst(
                        category,
                        () -> recipesGui.show(focuses));
                return;
            }
            recipesGui.show(focusUtil.createFocuses(typed, roles));
        }

        private boolean handleClick(UserInput input) {
            // Synthetic recipe bookmarks must not shadow JEI's native cheat
            // item handler. When cheat mode is enabled, JEI owns the normal
            // left/shift-left click (one item or a stack); returning false
            // lets FocusInputHandler run that original operation.
            if (step != null && jeiCheatItemsEnabled()) {
                return false;
            }
            if (step == null || input.getKey().getType() != InputConstants.Type.MOUSE
                    || input.getKey().getValue() != 0) {
                return false;
            }
            // A normal click only transfers this recipe's direct inputs. The
            // recursive plan is an explicit Ctrl-click action, including for
            // intermediate products whose direct inputs are not in the inventory.
            boolean recursive = JeiPlusPlusConfig.AUTOMATIC_CRAFTING_ENABLED.get()
                    && net.minecraft.client.gui.screens.Screen.hasControlDown();
            if (input.isSimulate()) {
                // Let JEI handle the click normally when this recipe cannot
                // be transferred. In particular, a plain left click on a
                // recipe with no available direct ingredients should open its
                // recipe page instead of being swallowed by the bookmark
                // element. Recursive (Ctrl) clicks use the same dry-run so
                // JEI still falls back only when the recursive plan cannot be
                // started at all.
                return RecipeTreeTransfer.canTransfer(step, recursive);
            }
            RecipeTreeTransfer.transfer(step, recursive);
            return true;
        }

        private static boolean jeiCheatItemsEnabled() {
            try {
                return Internal.getClientToggleState().isCheatItemsEnabled();
            } catch (RuntimeException ignored) {
                // JEI may be between runtime reload stages; fail open so the
                // tree transfer behavior remains available in that window.
                return false;
            }
        }

        private void getTooltip(JeiTooltip tooltip, Object tooltipHelper, Object renderer, Object helper)
                throws Throwable {
            @SuppressWarnings("rawtypes")
            IIngredientRenderer ingredientRenderer = (IIngredientRenderer) renderer;
            @SuppressWarnings("rawtypes")
            IIngredientHelper ingredientHelper = (IIngredientHelper) helper;
            invokeIngredientTooltipHelper(tooltipHelper, tooltip, ingredientRenderer, ingredientHelper);
            if (FluidRecipeCompat.fluid(typed).isPresent()) {
                FluidRecipeCompat.fluid(typed).ifPresent(value -> tooltip.add(
                        Component.translatable(
                                "jei_plus_plus.recipe_tree.favorite.fluid_amount",
                                FluidRecipeCompat.formatAmount(value.getAmount())).withStyle(ChatFormatting.GRAY)));
            } else {
                @SuppressWarnings("unchecked")
                IIngredientHelper<ItemStack> itemHelper = (IIngredientHelper<ItemStack>) ingredientHelper;
                @SuppressWarnings("unchecked")
                IIngredientRenderer<ItemStack> itemRenderer = (IIngredientRenderer<ItemStack>) ingredientRenderer;
                List<ItemStack> alternatives = tooltipAlternatives();
                if (alternatives.size() > 1) {
                    itemHelper.getTagKeyEquivalent(alternatives).ifPresent(tagKey -> {
                        tooltip.add(Component.translatable("jei.tooltip.recipe.tag", "")
                                .withStyle(ChatFormatting.GRAY));
                        tooltip.add(Services.PLATFORM.getRenderHelper().getName(tagKey)
                                .copy().withStyle(ChatFormatting.GRAY));
                    });
                    JeiTooltipCompat.createTagContent(
                            DirectoryRecipePlugin.getJeiRuntime(), itemRenderer, alternatives).ifPresent(tooltip::add);
                }
            }
            long owned = owned();
            long total = total();
            ItemStack source = sourceStack();
            if (!source.isEmpty() && FluidRecipeCompat.treeFluid(source).isPresent()) {
                long requiredAmount = FluidRecipeCompat.amountForUnits(source, total);
                long remainingAmount = Math.max(0L, requiredAmount - owned);
                tooltip.add(Component.translatable(
                        "jei_plus_plus.recipe_tree.favorite.remaining",
                        FluidRecipeCompat.formatAmount(remainingAmount)).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable(
                        "jei_plus_plus.recipe_tree.favorite.obtained",
                        FluidRecipeCompat.formatAmount(owned),
                        FluidRecipeCompat.formatAmount(requiredAmount)).withStyle(ChatFormatting.GRAY));
            } else {
                long remaining = Math.max(0, total - owned);
                tooltip.add(
                        Component.translatable("jei_plus_plus.recipe_tree.favorite.remaining", quantityText(remaining))
                                .withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable("jei_plus_plus.recipe_tree.favorite.obtained", quantityText(owned),
                        quantityText(total)).withStyle(ChatFormatting.GRAY));
            }
            if (step != null) {
                String clickKey = intermediate
                        ? "jei_plus_plus.recipe_tree.favorite.click_intermediate"
                        : "jei_plus_plus.recipe_tree.favorite.click";
                tooltip.add(Component.translatable(clickKey).withStyle(ChatFormatting.AQUA));
                if (JeiPlusPlusConfig.AUTOMATIC_CRAFTING_ENABLED.get()) {
                    tooltip.add(Component.translatable("jei_plus_plus.recipe_tree.favorite.control_click")
                            .withStyle(ChatFormatting.AQUA));
                    if (jeiCheatItemsEnabled()) {
                        tooltip.add(Component.translatable("jei_plus_plus.recipe_tree.favorite.close_cheat_mode")
                                .withStyle(ChatFormatting.RED));
                    }
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            return switch (method.getName()) {
                case "getTypedIngredient" -> typed;
                case "getBookmark" -> Optional.of(bookmark);
                case "createRenderOverlay" -> createRenderOverlay();
                case "show" -> {
                    show((IRecipesGui) args[0], (FocusUtil) args[1], (List<RecipeIngredientRole>) args[2]);
                    yield null;
                }
                case "handleClick" -> handleClick((UserInput) args[0]);
                case "getTooltip" -> {
                    getTooltip((JeiTooltip) args[0], args[1], args[2], args[3]);
                    yield null;
                }
                case "isVisible" -> true;
                case "tick" -> null;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "JEI++ recipe-tree bookmark " + typed.getIngredient();
                default -> throw new UnsupportedOperationException("Unsupported JEI element method: " + method);
            };
        }

        private void invokeIngredientTooltipHelper(
                Object tooltipHelper,
                JeiTooltip tooltip,
                IIngredientRenderer ingredientRenderer,
                IIngredientHelper ingredientHelper) throws Throwable {
            Method target = null;
            for (Method method : tooltipHelper.getClass().getMethods()) {
                if (method.getName().equals("getIngredientTooltip") && method.getParameterCount() == 4) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException(tooltipHelper.getClass().getName() + "#getIngredientTooltip");
            }
            try {
                target.invoke(tooltipHelper, tooltip, typed, ingredientRenderer, ingredientHelper);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        private List<ItemStack> tooltipAlternatives() {
            List<ItemStack> source = step == null
                    ? (cost == null ? List.of() : cost.alternatives())
                    : step.alternatives();
            return source.stream()
                    .filter(stack -> stack != null && !stack.isEmpty())
                    .map(stack -> {
                        ItemStack copy = stack.copy();
                        copy.setCount(1);
                        return copy;
                    })
                    .toList();
        }

        private String quantityText(long units) {
            ItemStack source = sourceStack();
            if (!source.isEmpty() && FluidRecipeCompat.treeFluid(source).isPresent()) {
                return FluidRecipeCompat.formatAmount(FluidRecipeCompat.amountForUnits(source, units));
            }
            return amount(units);
        }

        private ItemStack sourceStack() {
            return step == null
                    ? (cost == null ? ItemStack.EMPTY : cost.stack())
                    : step.stack();
        }

        private long remainingDisplayAmount() {
            ItemStack source = sourceStack();
            if (!source.isEmpty() && FluidRecipeCompat.treeFluid(source).isPresent()) {
                return Math.max(0L, FluidRecipeCompat.amountForUnits(source, total()) - owned());
            }
            return Math.max(0L, total() - owned());
        }

        private String remainingText(long amount) {
            ItemStack source = sourceStack();
            return !source.isEmpty() && FluidRecipeCompat.treeFluid(source).isPresent()
                    ? FluidRecipeCompat.formatAmount(amount)
                    : quantityText(amount);
        }

        private long owned() {
            return step == null
                    ? RecipeTreeData.inventoryAmount(cost.alternatives())
                    : RecipeTreeData.inventoryAmount(step.alternatives());
        }

        private long total() {
            return step == null ? cost.required() : step.total();
        }

    }

    private record AmountOverlay(String value, int color) implements IDrawable {
        @Override
        public int getWidth() {
            return 16;
        }
        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
            var font = Minecraft.getInstance().font;
            int textWidth = Math.max(1, font.width(value));
            float scale = Math.min(1.0f, 16.0f / textWidth);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 300);
            graphics.fill(xOffset, yOffset, xOffset + 2, yOffset + 2, color);
            graphics.pose().translate(xOffset + 17, yOffset + 9, 0);
            graphics.pose().scale(scale, scale, 1.0f);
            graphics.drawString(font, value, -textWidth, 0, color, true);
            graphics.pose().popPose();
        }
    }
}
