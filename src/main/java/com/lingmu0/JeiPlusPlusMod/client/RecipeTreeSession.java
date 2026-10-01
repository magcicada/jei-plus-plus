package com.lingmu0.JeiPlusPlusMod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Holds the current recipe tree and an optional node awaiting a recipe choice. */
public final class RecipeTreeSession {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PERSISTED_FILE = "recipe_tree_session.json";
    private static RecipeTreeData.Tree tree;
    /** The tree that owns the persistent crafting/bookmark workflow. */
    private static RecipeTreeData.Tree craftingTree;
    /** True while {@link #tree} is a temporary view opened over craftingTree. */
    private static boolean transientView;
    private static String pendingIngredient;
    private static String pendingRecipeKey;
    private static Screen resolutionParent;
    /** Keep the active crafting tree while JEI recreates its runtime. */
    private static boolean runtimeReloadPending;
    private static Object runtimeReloadLevel;
    private static boolean persistenceChecked;
    private static boolean restoringPersistence;
    /**
     * JEI publishes its runtime before all recipe lookups are necessarily
     * populated. Do not permanently mark the session as checked when that
     * first lookup is empty; retry after the index has settled instead.
     */
    private static final long PERSISTENCE_RETRY_NANOS = 500_000_000L;
    private static long lastPersistenceAttemptNanos = Long.MIN_VALUE;

    private RecipeTreeSession() {
    }

    public static RecipeTreeData.Tree tree() {
        ensureRestored();
        return tree;
    }

    /** Returns the tree whose missing materials and synthetic bookmarks are active. */
    public static RecipeTreeData.Tree craftingTree() {
        ensureRestored();
        if (craftingTree != null) {
            return craftingTree;
        }
        return tree != null && tree.craftingMode() ? tree : null;
    }

    public static boolean setGoal(IRecipeLayoutDrawable<?> layout) {
        RecipeTreeData.Tree replacement = RecipeTreeData.build(layout).orElse(null);
        if (replacement == null) {
            // Keep an active crafting tree intact when a button press cannot
            // produce a valid new goal.
            return false;
        }
        // Opening another recipe tree while a crafting tree is active creates
        // a temporary view. The active tree, its progress, and any pending
        // transfer remain intact until the temporary view is closed. Turning
        // the new view into crafting mode is the explicit replacement action.
        if (!transientView && tree != null && tree.craftingMode()) {
            craftingTree = tree;
        }
        if (craftingTree == null) {
            RecipeTreeTransfer.cancel();
        }
        tree = replacement;
        transientView = craftingTree != null;
        pendingIngredient = null;
        pendingRecipeKey = null;
        resolutionParent = null;
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
        return tree != null;
    }

    public static void clear() {
        RecipeTreeTransfer.cancel();
        tree = null;
        craftingTree = null;
        transientView = false;
        runtimeReloadPending = false;
        runtimeReloadLevel = null;
        pendingIngredient = null;
        pendingRecipeKey = null;
        resolutionParent = null;
        deletePersistentState();
        persistenceChecked = true;
        lastPersistenceAttemptNanos = Long.MIN_VALUE;
        RecipeTreeFavorites.refreshNow();
    }

    /** Suspend transfers while JEI rebuilds, retaining the active tree data. */
    public static void suspendForRuntimeReload() {
        // JEI normally becomes unavailable before a world is attached during
        // startup, and again while returning to the title screen. That is a
        // runtime transition, not a user request to cancel the saved crafting
        // tree. Persist the latest in-memory state first, then retain the
        // JSON file while discarding only objects tied to the old runtime.
        persistIfCrafting();
        runtimeReloadPending = true;
        runtimeReloadLevel = Minecraft.getInstance().level;
        RecipeTreeTransfer.cancel();
        cancelResolution();
        if (runtimeReloadLevel == null) {
            discardRuntimeStatePreservingPersistence();
        }
    }

    /** Rebuild the retained tree after JEI has published its new runtime. */
    public static void resumeAfterRuntimeReload() {
        if (!runtimeReloadPending) {
            return;
        }
        runtimeReloadPending = false;
        Object currentLevel = Minecraft.getInstance().level;
        boolean sameWorld = runtimeReloadLevel != null && runtimeReloadLevel == currentLevel;
        boolean hadWorld = runtimeReloadLevel != null;
        runtimeReloadLevel = null;
        if (!hadWorld || !sameWorld) {
            // A null level is normal during initial JEI startup and world
            // transitions. Do not call clear(): it intentionally deletes the
            // user's persistent session. The next tree() access will lazily
            // restore the JSON after JEI and the world are ready.
            discardRuntimeStatePreservingPersistence();
            RecipeTreeFavorites.refreshNow();
            return;
        }
        try {
            if (tree != null) {
                tree.rebuild();
            }
            if (craftingTree != null && craftingTree != tree) {
                craftingTree.rebuild();
            }
        } catch (RuntimeException ignored) {
            // A third-party recipe can disappear during the reload. Keep the
            // cached session and let the next explicit tree open rebuild it.
        }
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
    }

    public static void beginResolution(RecipeTreeData.Node node, Screen parent) {
        pendingIngredient = node == null ? null : node.ingredientKey();
        pendingRecipeKey = node == null || node.recipe() == null ? null : node.recipe().ref().key();
        resolutionParent = pendingIngredient == null ? null : parent;
    }

    public static void cancelResolution() {
        pendingIngredient = null;
        pendingRecipeKey = null;
        resolutionParent = null;
    }

    public static boolean isResolving() {
        return pendingIngredient != null && tree != null;
    }

    public static boolean canResolve(IRecipeLayoutDrawable<?> layout) {
        if (!isResolving()) {
            return false;
        }
        return RecipeTreeData.snapshot(layout)
                .map(snapshot -> snapshot.produces(pendingIngredient))
                .orElse(false);
    }

    public static boolean isCurrentResolution(IRecipeLayoutDrawable<?> layout) {
        if (!canResolve(layout) || pendingRecipeKey == null) {
            return false;
        }
        return RecipeTreeData.snapshot(layout)
                .map(snapshot -> snapshot.ref().key().equals(pendingRecipeKey))
                .orElse(false);
    }

    public static boolean resolve(IRecipeLayoutDrawable<?> layout) {
        if (!canResolve(layout)) {
            return false;
        }
        if (isCurrentResolution(layout)) {
            tree.clearResolution(pendingIngredient);
            pendingIngredient = null;
            pendingRecipeKey = null;
            RecipeTreeFavorites.refreshNow();
            persistIfCrafting();
            return true;
        }
        RecipeTreeData.RecipeSnapshot snapshot = RecipeTreeData.snapshot(layout).orElseThrow();
        tree.resolve(pendingIngredient, snapshot.ref());
        pendingIngredient = null;
        pendingRecipeKey = null;
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
        return true;
    }

    public static boolean selectInput(RecipeTreeData.Node node, ItemStack stack) {
        if (tree == null || node == null || stack == null || stack.isEmpty()) {
            return false;
        }
        String selectedKey = RecipeTreeData.ingredientKey(stack);
        boolean valid = node.alternatives().stream()
                .anyMatch(alternative -> RecipeTreeData.ingredientKey(alternative).equals(selectedKey));
        if (!valid) {
            return false;
        }
        tree.selectInput(node, stack);
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
        return true;
    }

    public static Screen takeResolutionParent() {
        Screen parent = resolutionParent;
        resolutionParent = null;
        return parent;
    }

    public static void clearResolution(RecipeTreeData.Node node) {
        if (tree != null && node != null) {
            tree.clearResolution(node.ingredientKey());
            RecipeTreeFavorites.refreshNow();
            persistIfCrafting();
        }
    }

    public static void clearInputSelection(RecipeTreeData.Node node) {
        if (tree != null && node != null) {
            tree.clearInputSelection(node);
            RecipeTreeFavorites.refreshNow();
            persistIfCrafting();
        }
    }

    /** Toggle crafting mode and make an explicitly chosen view the active tree. */
    public static void setCraftingMode(boolean enabled) {
        if (tree == null) {
            return;
        }
        if (enabled) {
            if (transientView || (craftingTree != null && craftingTree != tree)) {
                RecipeTreeTransfer.cancel();
            }
            craftingTree = tree;
            transientView = false;
            tree.setCraftingMode(true);
            persistIfCrafting();
        } else {
            tree.setCraftingMode(false);
            if (craftingTree == tree) {
                craftingTree = null;
            }
            transientView = false;
            deletePersistentState();
            persistenceChecked = true;
        }
        RecipeTreeFavorites.refreshNow();
    }

    /** Restore the saved crafting tree when a temporary recipe view closes. */
    public static void restoreCraftingTree() {
        if (transientView && craftingTree != null) {
            tree = craftingTree;
            craftingTree = null;
        }
        transientView = false;
        RecipeTreeFavorites.refreshNow();
    }

    public static boolean autoResolve(RecipeTreeData.Node node) {
        if (tree == null || node == null) {
            return false;
        }
        List<RecipeTreeData.RecipeSnapshot> candidates = RecipeTreeData.candidateSnapshots(node.stack()).stream()
                .filter(candidate -> !RecipeTreeDefaults.isExcludedFromAutomaticSelection(
                        candidate.ref().registryId()))
                .toList();
        if (candidates.isEmpty()) {
            return false;
        }
        // This is an explicit user action (Ctrl-click), so resolve the first
        // non-excluded candidate without consulting inventory availability.
        tree.resolve(node.ingredientKey(), candidates.get(0).ref());
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
        return true;
    }

    public static void refreshDefaults() {
        if (tree != null) {
            tree.rebuild();
        }
        if (craftingTree != null && craftingTree != tree) {
            craftingTree.rebuild();
        }
        if (tree != null || craftingTree != null) {
            RecipeTreeFavorites.refreshNow();
            persistIfCrafting();
        }
    }

    /** Persists batch changes made directly by the recipe-tree screen. */
    static void savePersistentState() {
        persistIfCrafting();
    }

    private static void ensureRestored() {
        long now = System.nanoTime();
        if (persistenceChecked || restoringPersistence || tree != null
                || Minecraft.getInstance().level == null
                || DirectoryRecipePlugin.getJeiRuntime() == null
                || (lastPersistenceAttemptNanos != Long.MIN_VALUE
                        && now - lastPersistenceAttemptNanos >= 0L
                        && now - lastPersistenceAttemptNanos < PERSISTENCE_RETRY_NANOS)) {
            return;
        }
        lastPersistenceAttemptNanos = now;
        restoringPersistence = true;
        try {
            Path file = persistentFile();
            if (!Files.isRegularFile(file)) {
                persistenceChecked = true;
                return;
            }
            JsonObject saved;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                saved = GSON.fromJson(reader, JsonObject.class);
            } catch (Exception ignored) {
                // A malformed file is a terminal result for this runtime;
                // leave it on disk so the player can inspect or remove it.
                persistenceChecked = true;
                return;
            }
            if (saved == null || !saved.has("crafting") || !saved.get("crafting").getAsBoolean()) {
                persistenceChecked = true;
                return;
            }
            String recipeKey = string(saved, "recipe");
            String outputKey = string(saved, "output");
            String itemId = string(saved, "item");
            String categoryKey = string(saved, "category");
            String registryId = string(saved, "registry");
            if (recipeKey.isEmpty() || outputKey.isEmpty()) {
                persistenceChecked = true;
                return;
            }
            ItemStack lookup = lookupStack(outputKey, itemId);
            if (lookup.isEmpty()) {
                // Registries can still be loading during the first client
                // tick after a world is attached. Retry instead of losing
                // the session to an early empty lookup.
                return;
            }
            List<RecipeTreeData.RecipeRef> candidates = RecipeTreeData.candidates(lookup);
            if (candidates.isEmpty()) {
                // RecipeTreeData caches lookup results for performance. An
                // empty result during JEI startup is not authoritative, so
                // clear it before the next throttled attempt.
                RecipeTreeData.clearCaches();
                return;
            }
            RecipeTreeData.RecipeRef ref = candidates.stream()
                    .filter(candidate -> matchesPersistedRecipe(
                            candidate, recipeKey, categoryKey, registryId))
                    .findFirst()
                    .orElse(null);
            if (ref == null) {
                // The recipe index is ready and the saved recipe is no
                // longer present. Keep the file, but stop retrying every
                // frame for a genuinely stale entry.
                persistenceChecked = true;
                return;
            }
            int outputCount = Math.max(1, integer(saved, "count", 1));
            long batches = Math.max(1L, longValue(saved, "batches", 1L));
            Optional<RecipeTreeData.Tree> restored = RecipeTreeData.restore(
                    ref, outputKey, outputCount, batches, true);
            if (restored.isEmpty()) {
                // The matching recipe may still be waiting for its layout to
                // be created. Leave persistenceChecked false and retry.
                RecipeTreeData.clearCaches();
                return;
            }
            tree = restored.get();
            craftingTree = tree;
            transientView = false;
            persistenceChecked = true;
            RecipeTreeFavorites.refreshNow();
        } catch (Exception ignored) {
            // A stale recipe id or malformed optional state must never prevent
            // the client from opening JEI. Runtime/index failures are
            // retried on a later tick; the file is left for inspection.
        } finally {
            restoringPersistence = false;
        }
    }

    private static boolean matchesPersistedRecipe(
            RecipeTreeData.RecipeRef candidate,
            String recipeKey,
            String categoryKey,
            String registryId) {
        if (recipeKey.equals(candidate.key())) {
            return true;
        }
        if (registryId.isEmpty() || !registryId.equals(candidate.registryId())) {
            return false;
        }
        return categoryKey.isEmpty()
                || categoryKey.equals(candidate.category().getRecipeType().getUid().toString());
    }

    private static ItemStack lookupStack(String outputKey, String itemId) {
        if (outputKey.startsWith("fluid:")) {
            return FluidRecipeCompat.representativeForKey(outputKey, 1000).orElse(ItemStack.EMPTY);
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId.isEmpty() ? outputKey : itemId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        return stack.isEmpty() ? ItemStack.EMPTY : stack;
    }

    private static void persistIfCrafting() {
        RecipeTreeData.Tree active = craftingTree;
        if (active == null && tree != null && tree.craftingMode()) {
            active = tree;
        }
        if (active == null || !active.craftingMode() || active.root() == null || active.root().recipe() == null) {
            return;
        }
        try {
            Path file = persistentFile();
            Files.createDirectories(file.getParent());
            JsonObject saved = new JsonObject();
            saved.addProperty("crafting", true);
            saved.addProperty("recipe", active.root().recipe().ref().key());
            saved.addProperty(
                    "category",
                    active.root().recipe().ref().category().getRecipeType().getUid().toString());
            saved.addProperty("registry", active.root().recipe().ref().registryId());
            saved.addProperty("output", active.root().ingredientKey());
            saved.addProperty("count", active.root().stack().getCount());
            saved.addProperty("batches", active.batches());
            String key = active.root().ingredientKey();
            if (!key.startsWith("fluid:")) {
                saved.addProperty("item", BuiltInRegistries.ITEM.getKey(active.root().stack().getItem()).toString());
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(saved, writer);
            }
            persistenceChecked = true;
        } catch (Exception ignored) {
            // A read-only config directory must not break recipe-tree use.
        }
    }

    private static void deletePersistentState() {
        try {
            Files.deleteIfExists(persistentFile());
        } catch (Exception ignored) {
            // Keep the in-memory clear operation safe on read-only instances.
        }
    }

    /** Drops runtime-bound objects without treating a reload as user cancel. */
    private static void discardRuntimeStatePreservingPersistence() {
        tree = null;
        craftingTree = null;
        transientView = false;
        pendingIngredient = null;
        pendingRecipeKey = null;
        resolutionParent = null;
        // Allow the next world/JEI runtime to read the retained JSON again.
        persistenceChecked = false;
        lastPersistenceAttemptNanos = Long.MIN_VALUE;
    }

    private static Path persistentFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("jei_plus_plus")
                .resolve(PERSISTED_FILE);
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

}
