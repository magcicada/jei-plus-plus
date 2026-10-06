package com.lingmu0.JeiPlusPlusMod;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Server-authoritative AE2 pattern creation, isolated behind an optional reflection bridge. */
public final class Ae2PatternServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ae2PatternServer.class);
    private Ae2PatternServer() {
    }

    public static void create(ServerPlayer player, List<Ae2PatternPlan> plans, boolean force) {
        Object menu = player.containerMenu;
        if (!menu.getClass().getName().equals("appeng.menu.me.items.PatternEncodingTermMenu"))
            return;
        int created = 0, existing = 0, invalid = 0;
        try {
            // AE2 1.20.1 exposes the terminal node as getNetworkNode().
            Object node = call(menu, "getNetworkNode");
            if (node == null || !Boolean.TRUE.equals(call(node, "isActive"))) {
                tell(player, "jei_plus_plus.ae2.no_network");
                return;
            }
            Object crafting = call(call(node, "getGrid"), "getCraftingService");
            Slot blanks = (Slot) field(menu, "blankPatternSlot");
            Slot output = (Slot) field(menu, "encodedPatternSlot");
            Set<Object> produced = new HashSet<>();
            for (Ae2PatternPlan plan : plans) {
                ItemStack pattern;
                try {
                    pattern = encode(player, plan);
                } catch (ReflectiveOperationException | IllegalArgumentException error) {
                    LOGGER.warn("Could not encode AE2 pattern for recipe {}", plan.recipeId(), error);
                    invalid++;
                    continue;
                }
                if (pattern == null || pattern.isEmpty()) {
                    invalid++;
                    continue;
                }
                Object definition = itemKey(pattern);
                if (definition == null) {
                    invalid++;
                    continue;
                }
                if (!produced.add(definition)) {
                    existing++;
                    continue;
                }
                if (!force && installed(crafting, plan.outputs().get(0), definition)) {
                    existing++;
                    continue;
                }
                if (blanks.getItem().isEmpty())
                    break;
                // Keep any pattern already in AE2's output slot intact. Use the player
                // inventory first, then the empty output slot as one last destination.
                ItemStack leftover = pattern.copy();
                player.getInventory().add(leftover);
                if (!leftover.isEmpty()) {
                    if (output.hasItem())
                        break;
                    output.set(leftover);
                    output.setChanged();
                }
                blanks.remove(1);
                blanks.setChanged();
                created++;
            }
            ((AbstractContainerMenu) menu).broadcastChanges();
            player.getInventory().setChanged();
            player.displayClientMessage(Component.translatable("jei_plus_plus.ae2.result", created, existing, invalid),
                    true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.error("Failed to create AE2 recipe-tree patterns", error);
            tell(player, "jei_plus_plus.ae2.failed");
        }
    }

    private static boolean installed(Object crafting, ItemStack primaryOutput, Object definition)
            throws ReflectiveOperationException {
        Object key = itemKey(primaryOutput);
        if (key == null)
            return false;
        Object recipes = call(crafting, "getCraftingFor", key);
        if (recipes instanceof Collection<?> collection) {
            for (Object recipe : collection) {
                if (definition.equals(call(recipe, "getDefinition")))
                    return true;
            }
        }
        return false;
    }

    private static ItemStack encode(ServerPlayer player, Ae2PatternPlan plan) throws ReflectiveOperationException {
        if (plan.inputs().isEmpty() || plan.outputs().isEmpty() || plan.outputs().get(0).isEmpty())
            return null;
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        Object recipe = recipe(player, plan.recipeId());
        // In 1.20.1 RecipeManager.byKey returns an Optional recipe. Only
        // CraftingRecipe can be encoded as an AE2 crafting pattern; calling
        // value() on a smelting or other machine recipe throws and used to
        // count it as invalid instead of reaching the processing fallback.
        if (recipe instanceof CraftingRecipe) {
            ItemStack[] grid = new ItemStack[9];
            Arrays.fill(grid, ItemStack.EMPTY);
            for (int i = 0; i < plan.inputs().size() && i < 9; i++) {
                grid[i] = plan.inputs().get(i).copy();
                if (!grid[i].isEmpty())
                    grid[i].setCount(1);
            }
            for (Method method : helper.getMethods()) {
                if (method.getName().equals("encodeCraftingPattern") && method.getParameterCount() == 5
                        && method.getParameterTypes()[0].isInstance(recipe)) {
                    return (ItemStack) method.invoke(null, recipe, grid, plan.outputs().get(0),
                            plan.substitute(), plan.substitute());
                }
            }
        }
        // Non-crafting JEI recipes are represented as processing patterns.
        // AE2 does not offer an input-substitution flag for processing patterns.
        Class<?> generic = Class.forName("appeng.api.stacks.GenericStack");
        Method fromItem = generic.getMethod("fromItemStack", ItemStack.class);
        Object[] inputs = (Object[]) java.lang.reflect.Array.newInstance(generic, 9);
        Object[] outputs = (Object[]) java.lang.reflect.Array.newInstance(generic, 3);
        for (int i = 0; i < Math.min(9, plan.inputs().size()); i++) {
            if (!plan.inputs().get(i).isEmpty())
                inputs[i] = fromItem.invoke(null, plan.inputs().get(i));
        }
        for (int i = 0; i < Math.min(3, plan.outputs().size()); i++) {
            if (!plan.outputs().get(i).isEmpty())
                outputs[i] = fromItem.invoke(null, plan.outputs().get(i));
        }
        for (Method method : helper.getMethods()) {
            if (!method.getName().equals("encodeProcessingPattern") || method.getParameterCount() != 2)
                continue;
            if (method.getParameterTypes()[0].isArray())
                return (ItemStack) method.invoke(null, inputs, outputs);
            if (List.class.isAssignableFrom(method.getParameterTypes()[0])) {
                return (ItemStack) method.invoke(null, Arrays.asList(inputs), Arrays.asList(outputs));
            }
        }
        return null;
    }

    private static Object recipe(ServerPlayer player, ResourceLocation id) {
        if (id == null)
            return null;
        // Minecraft methods are remapped in production. A string-based reflective
        // lookup for "byKey" works in dev but fails against the obfuscated game.
        return player.serverLevel().getRecipeManager().byKey(id).orElse(null);
    }

    private static Object itemKey(ItemStack stack) throws ReflectiveOperationException {
        return Class.forName("appeng.api.stacks.AEItemKey").getMethod("of", ItemStack.class).invoke(null, stack);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + '.' + name);
    }

    private static void tell(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
