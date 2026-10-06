package com.lingmu0.JeiPlusPlusMod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent EMI-style preferred recipes, keyed per output ingredient. */
public final class RecipeTreeDefaults {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BUILTIN_RESOURCE = "/assets/jei_plus_plus/recipe/defaults/emi.json";
    private static final Map<String, String> RESOLUTIONS = new HashMap<>();
    private static final Set<String> DISABLED_RECIPES = new HashSet<>();
    private static final Set<String> BUILTIN_RECIPE_IDS = new HashSet<>();
    private static final Map<String, Status> STATUS_CACHE = new HashMap<>();
    private static boolean loaded;

    private RecipeTreeDefaults() {
    }

    public enum Status {
        EMPTY, PARTIAL, FULL
    }

    public static void reload() {
        STATUS_CACHE.clear();
        RESOLUTIONS.clear();
        DISABLED_RECIPES.clear();
        BUILTIN_RECIPE_IDS.clear();
        loadBuiltins();
        loadUserData();
        loaded = true;
    }

    public static RecipeTreeData.RecipeRef getPreferredRecipe(
            ItemStack output,
            List<RecipeTreeData.RecipeRef> candidates) {
        ensureLoaded();
        String ingredientKey = RecipeTreeData.ingredientKey(output);
        String resolution = RESOLUTIONS.get(ingredientKey);
        if (resolution != null) {
            for (RecipeTreeData.RecipeRef candidate : candidates) {
                if (candidate.key().equals(resolution) && !DISABLED_RECIPES.contains(candidate.key())) {
                    return candidate;
                }
            }
        }
        for (RecipeTreeData.RecipeRef candidate : candidates) {
            if (!candidate.registryId().isEmpty()
                    && BUILTIN_RECIPE_IDS.contains(candidate.registryId())
                    && !isNuggetToIngot(candidate.registryId())
                    && !DISABLED_RECIPES.contains(candidate.key())) {
                return candidate;
            }
        }
        return null;
    }

    public static Status getStatus(RecipeTreeData.RecipeSnapshot recipe) {
        if (recipe == null || recipe.outputs().isEmpty()) {
            return Status.EMPTY;
        }
        ensureLoaded();
        return STATUS_CACHE.computeIfAbsent(recipe.ref().key(), ignored -> calculateStatus(recipe));
    }

    private static Status calculateStatus(RecipeTreeData.RecipeSnapshot recipe) {
        int matches = 0;
        int outputs = 0;
        Set<String> visited = new HashSet<>();
        for (ItemStack output : recipe.outputs()) {
            String key = RecipeTreeData.ingredientKey(output);
            if (!visited.add(key)) {
                continue;
            }
            outputs++;
            RecipeTreeData.RecipeRef preferred = getPreferredRecipe(output, RecipeTreeData.candidates(output));
            if (preferred != null && preferred.key().equals(recipe.ref().key())) {
                matches++;
            }
        }
        if (matches == 0) {
            return Status.EMPTY;
        }
        return matches >= outputs ? Status.FULL : Status.PARTIAL;
    }

    public static void toggle(RecipeTreeData.RecipeSnapshot recipe) {
        if (recipe == null || recipe.outputs().isEmpty()) {
            return;
        }
        Status status = getStatus(recipe);
        String recipeKey = recipe.ref().key();
        if (status == Status.FULL) {
            for (ItemStack output : recipe.outputs()) {
                RESOLUTIONS.remove(RecipeTreeData.ingredientKey(output), recipeKey);
            }
            if (!recipe.ref().registryId().isEmpty() && BUILTIN_RECIPE_IDS.contains(recipe.ref().registryId())) {
                DISABLED_RECIPES.add(recipeKey);
            }
        } else {
            DISABLED_RECIPES.remove(recipeKey);
            for (ItemStack output : recipe.outputs()) {
                RESOLUTIONS.put(RecipeTreeData.ingredientKey(output), recipeKey);
            }
        }
        STATUS_CACHE.clear();
        saveUserData();
        RecipeTreeSession.refreshDefaults();
    }

    private static void ensureLoaded() {
        if (!loaded) {
            reload();
        }
    }

    private static void loadBuiltins() {
        try (Reader reader = new InputStreamReader(
                RecipeTreeDefaults.class.getResourceAsStream(BUILTIN_RESOURCE),
                StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            JsonArray added = root == null ? null : root.getAsJsonArray("added");
            if (added != null) {
                for (JsonElement element : added) {
                    if (element.isJsonPrimitive()) {
                        String recipeId = element.getAsString();
                        if (!isNuggetToIngot(recipeId)) {
                            BUILTIN_RECIPE_IDS.add(recipeId);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // The button still works with user-defined defaults if the bundled
            // compatibility list is missing from a development resource set.
        }
    }

    private static void loadUserData() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }
            JsonObject resolutions = root.getAsJsonObject("resolutions");
            if (resolutions != null) {
                for (Map.Entry<String, JsonElement> entry : resolutions.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        RESOLUTIONS.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            JsonArray disabled = root.getAsJsonArray("disabled");
            if (disabled != null) {
                for (JsonElement element : disabled) {
                    if (element.isJsonPrimitive()) {
                        DISABLED_RECIPES.add(element.getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep a malformed user file untouched; a later button press writes
            // a fresh valid representation of the in-memory defaults.
        }
    }

    private static void saveUserData() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            JsonObject resolutions = new JsonObject();
            RESOLUTIONS.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> resolutions.addProperty(entry.getKey(), entry.getValue()));
            root.add("resolutions", resolutions);
            JsonArray disabled = new JsonArray();
            DISABLED_RECIPES.stream().sorted().forEach(disabled::add);
            root.add("disabled", disabled);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception ignored) {
            // A read-only config directory should not break JEI's recipe GUI.
        }
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("jei_plus_plus")
                .resolve("recipe_defaults.json");
    }

    /**
     * Nugget-to-ingot conversions are intentionally excluded from automatic
     * defaults and the explicit Ctrl-click auto-resolution path. Explicit
     * recipe choices still pass through the normal resolution path.
     */
    static boolean isExcludedFromAutomaticSelection(String recipeId) {
        if (recipeId == null) {
            return false;
        }
        String path = recipeId;
        int separator = path.indexOf(':');
        if (separator >= 0) {
            path = path.substring(separator + 1);
        }
        return path.endsWith("_ingot_from_nuggets") || path.endsWith("_ingot_from_nugget");
    }

    private static boolean isNuggetToIngot(String recipeId) {
        return isExcludedFromAutomaticSelection(recipeId);
    }
}
