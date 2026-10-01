package com.lingmu0.JeiPlusPlusMod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lingmu0.JeiPlusPlusMod.JeiPlusPlus;
import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.StackGroupCatalog;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Resolves built-in and user-defined expandable JEI stack groups. */
public final class StackGroupManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RESCAN_INTERVAL_NANOS = 500_000_000L;
    private static final Map<Path, FileStamp> EMPTY_STAMPS = Map.of();

    private static volatile List<JsonGroup> jsonGroups = List.of();
    private static volatile Map<Path, FileStamp> fileStamps = EMPTY_STAMPS;
    private static volatile List<GroupDefinition> definitionsCache = List.of();
    private static volatile String definitionsCacheSignature = "";
    private static long nextRescanNanos;

    private StackGroupManager() {
    }

    public record Match(String key, Component label) {
    }

    public interface ItemMatcher {
        boolean matches(ItemStack stack);
    }

    public static final class GroupDefinition {
        private final String id;
        private final int priority;
        private final Component label;
        private final ItemMatcher matcher;
        private final boolean defaultGroup;
        private final boolean nbtGroup;
        private final boolean mixNamespaces;

        private GroupDefinition(
                String id,
                int priority,
                Component label,
                ItemMatcher matcher,
                boolean defaultGroup,
                boolean nbtGroup,
                boolean mixNamespaces) {
            this.id = id;
            this.priority = priority;
            this.label = label;
            this.matcher = matcher;
            this.defaultGroup = defaultGroup;
            this.nbtGroup = nbtGroup;
            this.mixNamespaces = mixNamespaces;
        }

        public int priority() {
            return priority;
        }

        private Match match(ItemStack stack) {
            if (!matcher.matches(stack)) {
                return null;
            }
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) {
                return null;
            }

            String key;
            Component matchLabel = label;
            if (nbtGroup) {
                key = "nbt:" + itemId;
                matchLabel = Component.translatable("jei_plus_plus.group.nbt", stack.getHoverName());
            } else if (defaultGroup) {
                String namespace = mixNamespaces ? "" : itemId.getNamespace() + ":";
                key = "default:" + namespace + id;
            } else {
                key = "json:" + id;
            }
            return new Match(key, matchLabel);
        }
    }

    private record JsonGroup(
            String id,
            String type,
            String name,
            boolean enabled,
            int priority,
            ItemMatcher matcher,
            boolean hasMatcher) {
    }

    private record FileStamp(long modified, long size) {
    }

    /** Returns the current ordered group definitions. */
    public static List<GroupDefinition> getDefinitions() {
        reloadJsonGroupsIfNeeded();
        String signature = definitionSignature();
        if (signature.equals(definitionsCacheSignature)) {
            return definitionsCache;
        }

        // Config callbacks and background list rebuilds can observe this
        // method from different threads. Avoid publishing a partially
        // rebuilt matcher list.
        synchronized (StackGroupManager.class) {
            reloadJsonGroupsIfNeeded();
            signature = definitionSignature();
            if (signature.equals(definitionsCacheSignature)) {
                return definitionsCache;
            }

            Map<String, JsonGroup> overrides = new HashMap<>();
            List<JsonGroup> customGroups = new ArrayList<>();
            if (JeiPlusPlusConfig.JSON_GROUPING_ENABLED.get()) {
                for (JsonGroup group : jsonGroups) {
                    if (isDefaultOverride(group)) {
                        overrides.put(group.id(), group);
                    } else if (group.enabled
                            && (!"tag".equals(group.type) || JeiPlusPlusConfig.TAG_GROUPING_ENABLED.get())) {
                        if (group.hasMatcher) {
                            customGroups.add(group);
                        }
                    }
                }
            }

            List<GroupDefinition> definitions = new ArrayList<>();
            for (JsonGroup group : customGroups) {
                definitions.add(new GroupDefinition(
                        group.id,
                        group.priority,
                        getJsonLabel(group,
                                "tag".equals(group.type) ? "jei_plus_plus.group.tag" : "jei_plus_plus.group.json"),
                        group.matcher,
                        false,
                        false,
                        true));
            }
            for (StackGroupCatalog.DefaultGroup group : StackGroupCatalog.DEFAULT_GROUPS) {
                JsonGroup override = findDefaultOverride(overrides, group.id());
                if (override != null) {
                    if (!override.enabled) {
                        continue;
                    }
                    if (override.hasMatcher && override.matcher != null) {
                        if ("tag".equals(override.type) && !JeiPlusPlusConfig.TAG_GROUPING_ENABLED.get()) {
                            override = null;
                        } else {
                            definitions.add(new GroupDefinition(
                                    override.id,
                                    override.priority,
                                    getJsonLabel(override, group.translationKey()),
                                    override.matcher,
                                    false,
                                    false,
                                    true));
                            continue;
                        }
                    }
                }
                if (!JeiPlusPlusConfig.isDefaultGroupEnabled(group.id())) {
                    continue;
                }
                definitions.add(new GroupDefinition(
                        group.id(),
                        0,
                        Component.translatable(group.translationKey()),
                        stack -> matchesDefaultGroup(group, stack),
                        true,
                        false,
                        JeiPlusPlusConfig.MIX_NAMESPACE_GROUPS.get()));
            }

            if (JeiPlusPlusConfig.NBT_GROUPING_ENABLED.get()) {
                definitions.add(new GroupDefinition(
                        "nbt",
                        Integer.MIN_VALUE,
                        Component.empty(),
                        stack -> true,
                        false,
                        true,
                        true));
            }

            // JSON groups are collected before the built-ins, so equal priorities
            // give the user-defined group precedence. List.sort is stable.
            definitions.sort(Comparator.comparingInt(GroupDefinition::priority).reversed());
            List<GroupDefinition> immutable = List.copyOf(definitions);
            definitionsCacheSignature = signature;
            definitionsCache = immutable;
            return immutable;
        }
    }

    private static String definitionSignature() {
        StringBuilder signature = new StringBuilder(96)
                .append(System.identityHashCode(jsonGroups)).append('|')
                .append(JeiPlusPlusConfig.JSON_GROUPING_ENABLED.get()).append('|')
                .append(JeiPlusPlusConfig.TAG_GROUPING_ENABLED.get()).append('|')
                .append(JeiPlusPlusConfig.NBT_GROUPING_ENABLED.get()).append('|')
                .append(JeiPlusPlusConfig.MIX_NAMESPACE_GROUPS.get());
        for (StackGroupCatalog.DefaultGroup group : StackGroupCatalog.DEFAULT_GROUPS) {
            signature.append('|').append(group.id()).append('=')
                    .append(JeiPlusPlusConfig.isDefaultGroupEnabled(group.id()));
        }
        return signature.toString();
    }

    public static Match findMatch(ItemStack stack, List<GroupDefinition> definitions) {
        if (stack.isEmpty()) {
            return null;
        }
        for (GroupDefinition definition : definitions) {
            Match match = definition.match(stack);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static boolean matchesDefaultGroup(StackGroupCatalog.DefaultGroup group, ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(java.util.Locale.ROOT);
        if ("stripped_logs".equals(group.id())) {
            return path.startsWith("stripped_") && path.endsWith("_log");
        }
        if ("stripped_wood".equals(group.id())) {
            return path.startsWith("stripped_") && path.endsWith("_wood");
        }
        return path.endsWith(group.suffix());
    }

    private static boolean isDefaultOverride(JsonGroup group) {
        if (!JeiPlusPlusConfig.JSON_GROUPING_ENABLED.get()) {
            return false;
        }
        return group.id.startsWith(JeiPlusPlus.MODID + ":")
                && (group.id.substring((JeiPlusPlus.MODID + ":").length()).startsWith("default/")
                        || StackGroupCatalog.DEFAULT_GROUPS.stream()
                                .anyMatch(defaultGroup -> group.id.equals(defaultGroupId(defaultGroup.id()))));
    }

    private static JsonGroup findDefaultOverride(Map<String, JsonGroup> overrides, String id) {
        JsonGroup direct = overrides.get(defaultGroupId(id));
        return direct != null ? direct : overrides.get(JeiPlusPlus.MODID + ":default/" + id);
    }

    private static String defaultGroupId(String id) {
        return JeiPlusPlus.MODID + ":" + id;
    }

    private static Component getJsonLabel(JsonGroup group, String fallbackKey) {
        if (group.name == null || group.name.isBlank()) {
            return Component.translatable(fallbackKey, group.id);
        }
        return looksLikeTranslationKey(group.name)
                ? Component.translatable(group.name)
                : Component.literal(group.name);
    }

    private static boolean looksLikeTranslationKey(String value) {
        return value.indexOf('.') >= 0 || value.indexOf(':') >= 0;
    }

    private static synchronized void reloadJsonGroupsIfNeeded() {
        long now = System.nanoTime();
        if (now < nextRescanNanos) {
            return;
        }
        nextRescanNanos = now + RESCAN_INTERVAL_NANOS;

        Path directory = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve(JeiPlusPlus.MODID)
                .resolve("stack_groups");
        try {
            Files.createDirectories(directory);
            Map<Path, FileStamp> currentStamps = collectFileStamps(directory);
            if (currentStamps.equals(fileStamps)) {
                return;
            }

            List<JsonGroup> loaded = new ArrayList<>();
            for (Path file : currentStamps.keySet()) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonElement root = JsonParser.parseReader(reader);
                    loaded.addAll(parseGroups(root, file));
                } catch (Exception exception) {
                    LOGGER.warn("Unable to load JEI++ stack group {}", file, exception);
                }
            }
            jsonGroups = List.copyOf(loaded);
            fileStamps = Map.copyOf(currentStamps);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to scan JEI++ stack group directory {}", directory, exception);
            jsonGroups = List.of();
            fileStamps = EMPTY_STAMPS;
        }
    }

    private static Map<Path, FileStamp> collectFileStamps(Path directory) throws IOException {
        Map<Path, FileStamp> stamps = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            stamps.put(path,
                                    new FileStamp(Files.getLastModifiedTime(path).toMillis(), Files.size(path)));
                        } catch (IOException exception) {
                            LOGGER.warn("Unable to inspect JEI++ stack group {}", path, exception);
                        }
                    });
        }
        return stamps;
    }

    /**
     * Accept both the original one-group document and a document containing
     * several group objects. The array form is convenient for hand-written
     * files, while the named object form leaves room for document metadata.
     */
    private static List<JsonGroup> parseGroups(JsonElement root, Path file) {
        if (root == null || root.isJsonNull()) {
            return List.of();
        }
        List<JsonGroup> result = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                addParsedGroup(result, element, file);
            }
            return List.copyOf(result);
        }
        if (!root.isJsonObject()) {
            return List.of();
        }

        JsonObject object = root.getAsJsonObject();
        JsonElement groups = object.get("groups");
        if (groups != null && groups.isJsonArray()) {
            for (JsonElement element : groups.getAsJsonArray()) {
                addParsedGroup(result, element, file);
            }
            return List.copyOf(result);
        }

        // Keep the legacy one-object format fully compatible.
        addParsedGroup(result, object, file);
        return List.copyOf(result);
    }

    private static void addParsedGroup(List<JsonGroup> destination, JsonElement element, Path file) {
        if (!element.isJsonObject()) {
            LOGGER.warn("Ignoring non-object JEI++ stack group in {}", file);
            return;
        }
        JsonGroup group = parseGroup(element.getAsJsonObject(), file);
        if (group != null) {
            destination.add(group);
        }
    }

    private static JsonGroup parseGroup(JsonObject object, Path file) {
        String id = stringValue(object, "id");
        if (id == null || id.isBlank()) {
            LOGGER.warn("Ignoring JEI++ stack group without an id: {}", file);
            return null;
        }
        String type = simpleType(stringValue(object, "type"));
        boolean enabled = booleanValue(object, "enabled", true);
        int priority = intValue(object, "priority", 0);
        String name = stringValue(object, "name");

        if (type == null) {
            // A type-less file is useful for overriding a default group with
            // only {"enabled": false}, matching Reliable-EMI's behaviour.
            return new JsonGroup(id, "override", name, enabled, priority, null, false);
        }

        ItemMatcher matcher = switch (type) {
            case "group" -> buildGroupMatcher(object, false);
            case "tag" -> buildGroupMatcher(object, true);
            case "regex" -> buildRegexMatcher(object);
            default -> {
                LOGGER.warn("Ignoring JEI++ stack group {} with unknown type {}", file, type);
                yield null;
            }
        };
        return new JsonGroup(id, type, name, enabled, priority, matcher, matcher != null);
    }

    private static ItemMatcher buildGroupMatcher(JsonObject object, boolean tagOnly) {
        List<ItemMatcher> entries = new ArrayList<>();
        if (tagOnly) {
            addTagMatcher(entries, stringValue(object, "tag"));
            for (String entry : stringArray(object, "contents")) {
                addTagMatcher(entries, entry);
            }
        } else {
            for (String entry : stringArray(object, "contents")) {
                ItemMatcher matcher = parseEntryMatcher(entry);
                if (matcher != null) {
                    entries.add(matcher);
                }
            }
        }
        ItemMatcher include = anyOf(entries);
        List<ItemMatcher> exclusions = new ArrayList<>();
        for (String entry : stringArray(object, "exclusions")) {
            ItemMatcher matcher = tagOnly ? tagMatcher(entry) : parseEntryMatcher(entry);
            if (matcher != null) {
                exclusions.add(matcher);
            }
        }
        return withExclusions(include, exclusions);
    }

    private static ItemMatcher buildRegexMatcher(JsonObject object) {
        List<ItemMatcher> matchers = new ArrayList<>();
        String regex = stringValue(object, "regex");
        if (regex != null) {
            addRegexMatcher(matchers, regex);
        }
        for (String value : stringArray(object, "regexes")) {
            addRegexMatcher(matchers, value);
        }
        ItemMatcher include = anyOf(matchers);
        List<ItemMatcher> exclusions = new ArrayList<>();
        for (String entry : stringArray(object, "exclusions")) {
            ItemMatcher matcher = parseEntryMatcher(entry);
            if (matcher != null) {
                exclusions.add(matcher);
            }
        }
        return withExclusions(include, exclusions);
    }

    private static ItemMatcher withExclusions(ItemMatcher include, List<ItemMatcher> exclusions) {
        return stack -> include != null && include.matches(stack)
                && exclusions.stream().noneMatch(matcher -> matcher.matches(stack));
    }

    private static ItemMatcher anyOf(List<ItemMatcher> matchers) {
        if (matchers.isEmpty()) {
            return null;
        }
        return stack -> matchers.stream().anyMatch(matcher -> matcher.matches(stack));
    }

    private static void addTagMatcher(List<ItemMatcher> matchers, String value) {
        ItemMatcher matcher = tagMatcher(value);
        if (matcher != null) {
            matchers.add(matcher);
        }
    }

    private static ItemMatcher tagMatcher(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String location = value.startsWith("#") ? value.substring(1) : value;
        ResourceLocation id = ResourceLocation.tryParse(location);
        if (id == null) {
            return null;
        }
        TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
        return stack -> stack.is(tag);
    }

    private static ItemMatcher parseEntryMatcher(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.startsWith("#")) {
            return tagMatcher(value);
        }
        String location = value.startsWith("item:") ? value.substring("item:".length()) : value;
        ResourceLocation id = ResourceLocation.tryParse(location);
        if (id == null) {
            return null;
        }
        return stack -> Objects.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()), id);
    }

    private static void addRegexMatcher(List<ItemMatcher> matchers, String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            Pattern pattern = Pattern.compile(expression);
            matchers.add(stack -> {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                return id != null && pattern.matcher(id.toString()).matches();
            });
        } catch (RuntimeException exception) {
            LOGGER.warn("Ignoring invalid JEI++ stack group regex {}", expression, exception);
        }
    }

    private static String simpleType(String type) {
        if (type == null) {
            return null;
        }
        int separator = type.indexOf(':');
        return separator >= 0 ? type.substring(separator + 1) : type;
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean()
                : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static List<String> stringArray(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (!(value instanceof JsonArray array)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement entry : array) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                values.add(entry.getAsString());
            }
        }
        return values;
    }
}
