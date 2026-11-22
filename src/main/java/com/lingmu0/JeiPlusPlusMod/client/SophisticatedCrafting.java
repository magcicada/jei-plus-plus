package com.lingmu0.JeiPlusPlusMod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Optional client integration shared by Sophisticated Storage and Backpacks. */
final class SophisticatedCrafting {
    private static final String CORE = "net.p3pp3rf1y.sophisticatedcore.";
    private static final String MENU = CORE + "common.gui.StorageContainerMenuBase";
    private static final String UPGRADE = CORE + "upgrades.crafting.CraftingUpgradeContainer";

    private static final ClassValue<Boolean> STORAGE_MENU = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return isType(type, MENU);
        }
    };

    private SophisticatedCrafting() {}

    static Grid openGrid(AbstractContainerMenu menu) {
        if (!STORAGE_MENU.get(menu.getClass())) return null;
        if (!(JeiReflectionCompat.tryInvoke(menu, "getUpgradeContainers") instanceof Map<?, ?> upgrades)) return null;
        for (Object upgrade : upgrades.values()) {
            if (!isType(upgrade.getClass(), UPGRADE) || !Boolean.TRUE.equals(JeiReflectionCompat.tryInvoke(upgrade, "isOpen"))) continue;
            if (!(JeiReflectionCompat.tryInvoke(upgrade, "getRecipeSlots") instanceof List<?> inputs)
                || !(JeiReflectionCompat.tryInvoke(upgrade, "getSlots") instanceof List<?> slots)
                || !inputs.stream().allMatch(Slot.class::isInstance)) continue;

            for (Object slot : slots) {
                if (slot instanceof ResultSlot result && result.index >= 0 && menu.getSlot(result.index) == result) {
                    return new Grid(menu, upgrade, inputs.stream().map(Slot.class::cast).toList(), result);
                }
            }
        }
        return null;
    }

    private static boolean isType(Class<?> type, String name) {
        for (; type != null; type = type.getSuperclass()) {
            if (type.getName().equals(name)) return true;
        }
        return false;
    }

    record Grid(AbstractContainerMenu menu, Object upgrade, List<Slot> inputs, ResultSlot result) {
        boolean isOpen() {
            return Boolean.TRUE.equals(JeiReflectionCompat.tryInvoke(upgrade, "isOpen"))
                && menu.getSlot(result.index) == result;
        }

        boolean isRefillEnabled() {
            return Boolean.TRUE.equals(JeiReflectionCompat.tryInvoke(upgrade, "shouldRefillCraftingGrid"));
        }

        boolean setRefill(boolean enabled) {
            try {
                upgrade.getClass().getMethod("setRefillCraftingGrid", boolean.class).invoke(upgrade, enabled);
                return true;
            } catch (ReflectiveOperationException ignored) {
                return false; // Older SophisticatedCore releases have no refill setting.
            }
        }

        List<Slot> inventorySlots(Player player) {
            List<Slot> slots = new ArrayList<>();
            try {
                Method isStorageSlot = menu.getClass().getMethod("isStorageInventorySlot", int.class);
                for (Slot slot : menu.slots) {
                    if (!slot.isActive() || !slot.mayPickup(player)) continue;
                    boolean playerSlot = slot.container == player.getInventory()
                        && slot.getContainerSlot() >= 0 && slot.getContainerSlot() < 36;
                    if (playerSlot || Boolean.TRUE.equals(isStorageSlot.invoke(menu, slot.index))) slots.add(slot);
                }
            } catch (ReflectiveOperationException ignored) {
                return List.of();
            }
            return slots;
        }
    }

    /** The grid and result as confirmed by server slot packets, independent of click prediction. */
    static final class Sync {
        private final Grid grid;
        private final Map<Slot, ItemStack> stacks = new IdentityHashMap<>();
        private List<ItemStack> expectedInputs = List.of();
        private boolean resultUpdated;
        private long waitingSince = System.nanoTime();

        Sync(Grid grid) {
            this.grid = grid;
            updateAll();
        }

        void expectInputs(List<ItemStack> expected) {
            expectedInputs = expected;
        }

        Grid grid() { return grid; }

        boolean timedOut() { return System.nanoTime() - waitingSince > 10_000_000_000L; }

        void updateSlot(int index, ItemStack stack) {
            if (index == grid.result.index) {
                resultUpdated = true;
                stacks.put(grid.result, stack.copy());
            }
            for (Slot slot : grid.inputs) {
                if (slot.index == index) {
                    stacks.put(slot, stack.copy());
                    return;
                }
            }
        }

        void updateAll() {
            for (Slot slot : grid.inputs) stacks.put(slot, slot.getItem().copy());
            updateSlot(grid.result.index, grid.result.getItem());
        }

        boolean inputsReady() {
            for (int i = 0; i < expectedInputs.size(); i++) {
                if (!ItemStack.matches(expectedInputs.get(i), stacks.get(grid.inputs.get(i)))) return false;
            }
            return !expectedInputs.isEmpty();
        }

        ItemStack result() {
            return stacks.get(grid.result);
        }

        void awaitPickup() {
            resultUpdated = false;
            waitingSince = System.nanoTime();
        }

        boolean pickupConfirmed() {
            return resultUpdated && result().isEmpty() && !inputsReady();
        }
    }

    static Plan plan(Grid grid, List<ItemStack> templates, int batches, Player player) {
        if (templates.isEmpty() || templates.size() > grid.inputs.size() || !grid.menu.getCarried().isEmpty()) return null;
        for (int i = 0; i < templates.size(); i++) {
            ItemStack stack = templates.get(i);
            if (stack.isEmpty()) continue;
            Slot slot = grid.inputs.get(i);
            if (!slot.mayPlace(stack)) return null;
            batches = Math.min(batches, slot.getMaxStackSize(stack) / stack.getCount());
        }
        if (batches <= 0) return null;

        List<ItemStack> expected = new ArrayList<>(grid.inputs.size());
        List<Slot> inventory = grid.inventorySlots(player);
        Map<String, Long> supply = new HashMap<>();
        for (Slot slot : inventory) addSupply(supply, slot);
        for (Slot slot : grid.inputs) {
            if (slot.mayPickup(player)) addSupply(supply, slot);
        }
        boolean hasIngredients = false;
        for (int i = 0; i < grid.inputs.size(); i++) {
            ItemStack stack = i < templates.size() ? templates.get(i).copy() : ItemStack.EMPTY;
            if (!stack.isEmpty()) {
                hasIngredients = true;
                stack.setCount(stack.getCount() * batches);
                long left = supply.merge(RecipeTreeData.ingredientKey(stack), -(long) stack.getCount(), Long::sum);
                if (left < 0) return null;
            }
            Slot slot = grid.inputs.get(i);
            if (!slot.getItem().isEmpty() && !ItemStack.matches(slot.getItem(), stack) && !slot.mayPickup(player)) return null;
            expected.add(stack);
        }
        return hasIngredients ? new Plan(grid, expected, inventory, batches) : null;
    }

    private static void addSupply(Map<String, Long> supply, Slot slot) {
        ItemStack stack = slot.getItem();
        if (!stack.isEmpty()) supply.merge(RecipeTreeData.ingredientKey(stack), (long) stack.getCount(), Long::sum);
    }

    record Plan(Grid grid, List<ItemStack> expected, List<Slot> inventory, int batches) {
        boolean fill(Player player) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gameMode == null || !grid.menu.getCarried().isEmpty()) return false;
            for (int i = 0; i < expected.size(); i++) {
                Slot target = grid.inputs.get(i);
                if (target.hasItem() && !ItemStack.matches(target.getItem(), expected.get(i))) {
                    click(minecraft, player, target, 0, ClickType.QUICK_MOVE);
                    if (target.hasItem()) return false;
                }
            }
            for (int i = 0; i < expected.size(); i++) {
                ItemStack wanted = expected.get(i);
                Slot target = grid.inputs.get(i);
                if (ItemStack.matches(target.getItem(), wanted)) continue;

                String key = RecipeTreeData.ingredientKey(wanted);
                for (Slot source : inventory) {
                    int remaining = wanted.getCount() - target.getItem().getCount();
                    if (remaining <= 0) break;
                    if (!source.hasItem() || !RecipeTreeData.ingredientKey(source.getItem()).equals(key)) continue;
                    click(minecraft, player, source, 0, ClickType.PICKUP);
                    int carried = grid.menu.getCarried().getCount();
                    if (carried == 0) return false;
                    if (carried <= remaining) {
                        click(minecraft, player, target, 0, ClickType.PICKUP);
                    } else {
                        for (int count = 0; count < remaining; count++) {
                            click(minecraft, player, target, 1, ClickType.PICKUP);
                        }
                    }
                    if (!grid.menu.getCarried().isEmpty()) click(minecraft, player, source, 0, ClickType.PICKUP);
                    if (!grid.menu.getCarried().isEmpty()) return false;
                }
                if (!ItemStack.matches(target.getItem(), wanted)) return false;
            }
            return true;
        }

        private void click(Minecraft minecraft, Player player, Slot slot, int button, ClickType type) {
            minecraft.gameMode.handleInventoryMouseClick(grid.menu.containerId, slot.index, button, type, player);
        }
    }
}
