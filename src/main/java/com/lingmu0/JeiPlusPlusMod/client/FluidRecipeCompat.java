package com.lingmu0.JeiPlusPlusMod.client;

import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side bridge between JEI fluid ingredients and filled item containers. */
final class FluidRecipeCompat {
    private static final IdentityHashMap<ItemStack, FluidStack> DISPLAY_FLUIDS = new IdentityHashMap<>();
    private static final Map<String, ItemStack> FLUID_REPRESENTATIONS = new ConcurrentHashMap<>();
    private static final List<String> FLUID_ACCESSOR_NAMES = List.of(
            "getFluidStack", "getReadOnlyStack", "getRenderStack", "getStack",
            "toFluidStack", "toStack", "getInstance", "getIngredient", "getResource",
            "getFluidResource", "getFluid", "fluid", "getWhat", "getKey", "getSource");
    private static final Map<Class<?>, List<Method>> FLUID_ACCESSORS = new ConcurrentHashMap<>();

    private FluidRecipeCompat() {
    }

    /** A loader-neutral description of a fluid value returned by an optional storage API. */
    record FluidInfo(String key, long amount) {
    }

    /** Reflectively unwraps optional AE/RS/Beyond/Integrated fluid wrappers. */
    static Optional<FluidInfo> describeFluid(Object value) {
        return describeFluid(value, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Optional<FluidInfo> describeFluid(Object value, Set<Object> seen, int depth) {
        if (value == null || depth > 4 || !seen.add(value)) {
            return Optional.empty();
        }
        if (value instanceof FluidStack stack) {
            if (stack.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new FluidInfo(
                    "fluid:" + BuiltInRegistries.FLUID.getKey(stack.getFluid()),
                    Math.max(0L, stack.getAmount())));
        }
        if (value instanceof ItemStack stack) {
            return displayFluid(stack).map(fluid -> new FluidInfo(
                    "fluid:" + BuiltInRegistries.FLUID.getKey(fluid.getFluid()),
                    Math.max(0L, fluid.getAmount())));
        }
        if (value instanceof Fluid fluid) {
            return Optional.of(new FluidInfo(
                    "fluid:" + BuiltInRegistries.FLUID.getKey(fluid),
                    0L));
        }
        for (Method method : FLUID_ACCESSORS.computeIfAbsent(value.getClass(), FluidRecipeCompat::findFluidAccessors)) {
            try {
                Optional<FluidInfo> result = describeFluid(method.invoke(value), seen, depth + 1);
                if (result.isPresent()) {
                    return result;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Try the next optional accessor.
            }
        }
        return Optional.empty();
    }

    private static List<Method> findFluidAccessors(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (String name : FLUID_ACCESSOR_NAMES) {
            try {
                Method method = type.getMethod(name);
                try {
                    method.trySetAccessible();
                } catch (RuntimeException ignored) {
                    // Public methods remain invokable when access is restricted.
                }
                methods.add(method);
            } catch (NoSuchMethodException | SecurityException ignored) {
                // Optional wrapper API; try the next accessor.
            }
        }
        return List.copyOf(methods);
    }

    static void clear() {
        DISPLAY_FLUIDS.clear();
        FLUID_REPRESENTATIONS.clear();
        FLUID_ACCESSORS.clear();
    }

    static Optional<FluidStack> fluid(ITypedIngredient<?> ingredient) {
        return ingredient == null
                ? Optional.empty()
                : ingredient.getIngredient(ForgeTypes.FLUID_STACK);
    }

    static Optional<ITypedIngredient<?>> scaled(
            ITypedIngredient<?> ingredient,
            int batches,
            IIngredientManager manager) {
        Optional<FluidStack> value = fluid(ingredient);
        if (value.isEmpty() || batches <= 1) {
            return Optional.ofNullable(ingredient);
        }
        long amount = (long) value.get().getAmount() * batches;
        if (amount > Integer.MAX_VALUE) {
            return Optional.of(ingredient);
        }
        FluidStack copy = value.get().copy();
        copy.setAmount((int) amount);
        return manager.createTypedIngredient(ForgeTypes.FLUID_STACK, copy)
                .map(stack -> (ITypedIngredient<?>) stack);
    }

    static List<ItemStack> matchingContainers(Player player, FluidStack required, int batches) {
        if (player == null || required == null || required.isEmpty()) {
            return List.of();
        }
        int requiredAmount = safeAmount(required.getAmount(), batches);
        for (ItemStack inventoryStack : player.getInventory().items) {
            if (inventoryStack.isEmpty()) {
                continue;
            }
            IFluidHandler handler = inventoryStack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).resolve()
                    .orElse(null);
            if (handler == null) {
                continue;
            }
            FluidStack contained = handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
            if (contained == null || contained.isEmpty() || !contained.isFluidEqual(required)) {
                continue;
            }
            int perContainer = Math.max(1, contained.getAmount());
            int containers = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(1L, ((long) requiredAmount + perContainer - 1L) / perContainer));
            if (containers <= inventoryStack.getCount()) {
                ItemStack candidate = inventoryStack.copy();
                candidate.setCount(containers);
                return List.of(candidate);
            }
        }
        return List.of();
    }

    static List<ItemStack> matchingContainers(Player player, ITypedIngredient<?> ingredient) {
        return fluid(ingredient)
                .map(value -> matchingContainers(player, value, 1))
                .orElseGet(List::of);
    }

    static Optional<ItemStack> representativeContainer(ITypedIngredient<?> ingredient) {
        return fluid(ingredient).map(value -> {
            // This is an internal bridge for item-slot transfer. The tree
            // renderer draws the registered FluidStack itself.
            var bucketItem = value.getFluid().getBucket();
            ItemStack bucket = new ItemStack(bucketItem == Items.AIR ? Items.BUCKET : bucketItem);
            int count = (int) Math.min(Integer.MAX_VALUE, containerCount(value.getAmount()));
            bucket.setCount(count);
            registerDisplay(bucket, value);
            return bucket;
        }).filter(stack -> !stack.isEmpty());
    }

    /**
     * A real bucket/item candidate for a fluid ingredient. It deliberately
     * has no display registration, so its ingredient key remains the bucket
     * item rather than the synthetic fluid key.
     */
    static Optional<ItemStack> containerCandidate(ITypedIngredient<?> ingredient) {
        return fluid(ingredient).map(value -> {
            var bucketItem = value.getFluid().getBucket();
            ItemStack bucket = new ItemStack(bucketItem == Items.AIR ? Items.BUCKET : bucketItem);
            bucket.setCount((int) Math.min(Integer.MAX_VALUE, containerCount(value.getAmount())));
            return bucket;
        }).filter(stack -> !stack.isEmpty());
    }

    private static void registerDisplay(ItemStack stack, FluidStack fluid) {
        if (stack == null || stack.isEmpty() || fluid == null || fluid.isEmpty()) {
            return;
        }
        DISPLAY_FLUIDS.put(stack, fluid.copy());
    }

    static Optional<FluidStack> displayFluid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        FluidStack fluid = DISPLAY_FLUIDS.get(stack);
        if (fluid != null) {
            return Optional.of(fluid.copy());
        }
        // Forge fluid handlers are commonly implemented for a single item.
        // Passing a stack with count > 1 can make the capability return an
        // empty handler even though every item carries the same fluid. Probe a
        // one-item copy for identity/highlight checks; callers that need the
        // total amount multiply the returned amount by the original count.
        ItemStack probe = stack;
        if (stack.getCount() > 1) {
            probe = stack.copy();
            probe.setCount(1);
        }
        IFluidHandler handler = probe.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM)
                .resolve().orElse(null);
        if (handler != null) {
            FluidStack contained = handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
            if (contained != null && !contained.isEmpty()) {
                return Optional.of(contained.copy());
            }
        }
        return Optional.empty();
    }

    /** Fluid explicitly represented by a recipe-tree synthetic stack. */
    static Optional<FluidStack> treeFluid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        FluidStack fluid = DISPLAY_FLUIDS.get(stack);
        return fluid == null ? Optional.empty() : Optional.of(fluid.copy());
    }

    static Optional<String> fluidKey(ItemStack stack) {
        return treeFluid(stack).map(value -> fluidKey(value));
    }

    static String fluidKey(FluidStack value) {
        return value == null || value.isEmpty()
                ? ""
                : "fluid:" + net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(value.getFluid());
    }

    /**
     * Returns the fluid key carried by a real container without converting it
     * into a synthetic tree stack. Used by inventory highlighting only.
     */
    static Optional<String> displayFluidKey(ItemStack stack) {
        return displayFluid(stack)
                .map(FluidRecipeCompat::fluidKey)
                .filter(key -> !key.isEmpty());
    }

    /** Creates a fluid-key alias for a real filled container in inventory. */
    static Optional<ItemStack> fluidRepresentation(ItemStack stack) {
        Optional<FluidStack> value = displayFluid(stack);
        if (value.isEmpty() || fluidKey(stack).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(cachedRepresentation(value.get()));
    }

    static Optional<ITypedIngredient<?>> toTyped(IIngredientManager manager, ItemStack stack) {
        if (manager == null || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        Optional<FluidStack> fluid = treeFluid(stack);
        if (fluid.isPresent()) {
            return manager.createTypedIngredient(ForgeTypes.FLUID_STACK, fluid.get())
                    .map(value -> (ITypedIngredient<?>) value);
        }
        return manager.createTypedIngredient(VanillaTypes.ITEM_STACK, stack)
                .map(value -> (ITypedIngredient<?>) value);
    }

    static long containerCount(long millibuckets) {
        return Math.max(1, (millibuckets + net.minecraftforge.fluids.FluidType.BUCKET_VOLUME - 1)
                / net.minecraftforge.fluids.FluidType.BUCKET_VOLUME);
    }

    /** Returns the actual fluid amount represented by every item in a stack. */
    static long amountInContainers(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        Optional<FluidStack> fluid = displayFluid(stack);
        if (fluid.isEmpty()) {
            return 0L;
        }
        try {
            return Math.multiplyExact(
                    Math.max(0L, fluid.get().getAmount()),
                    Math.max(1L, stack.getCount()));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Recreates a representative tree item for a fluid key reported by a network. */
    static Optional<ItemStack> representativeForKey(String key, long amount) {
        if (key == null || !key.startsWith("fluid:") || amount <= 0) {
            return Optional.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(key.substring("fluid:".length()));
        if (id == null) {
            return Optional.empty();
        }
        Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return Optional.empty();
        }
        int safeAmount = (int) Math.min(Integer.MAX_VALUE, amount);
        FluidStack value = new FluidStack(fluid, safeAmount);
        return Optional.of(cachedRepresentation(value));
    }

    private static ItemStack cachedRepresentation(FluidStack value) {
        String cacheKey = fluidKey(value) + "|" + value.getAmount();
        ItemStack prototype = FLUID_REPRESENTATIONS.computeIfAbsent(cacheKey, ignored -> {
            var bucketItem = value.getFluid().getBucket();
            ItemStack bucket = new ItemStack(bucketItem == Items.AIR ? Items.BUCKET : bucketItem);
            registerDisplay(bucket, value);
            return bucket;
        });
        return copyWithDisplay(prototype);
    }

    /**
     * Converts the tree's synthetic container units back to the amount of
     * fluid represented by the original recipe slot. A recipe slot carrying
     * 1500 mB is represented by two containers, so two tree units correspond
     * to 1500 mB rather than 3000 mB.
     */
    static long amountForUnits(ItemStack stack, long units) {
        if (stack == null || stack.isEmpty() || units <= 0) {
            return 0;
        }
        Optional<FluidStack> value = displayFluid(stack);
        if (value.isEmpty()) {
            return 0;
        }
        long denominator = Math.max(1, stack.getCount());
        long numerator;
        try {
            numerator = Math.multiplyExact((long) value.get().getAmount(), units);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        if (numerator > Long.MAX_VALUE - (denominator - 1L)) {
            return Long.MAX_VALUE;
        }
        return (numerator + denominator - 1L) / denominator;
    }

    /** Formats fluid quantities using JEI-style mB below one bucket and B above it. */
    static String formatAmount(long millibuckets) {
        long value = Math.max(0, millibuckets);
        if (value < net.minecraftforge.fluids.FluidType.BUCKET_VOLUME) {
            return value + "mB";
        }
        return String.format(Locale.ROOT, "%.1fB",
                value / (double) net.minecraftforge.fluids.FluidType.BUCKET_VOLUME);
    }

    static Optional<IFocus<FluidStack>> createOutputFocus(IJeiRuntime runtime, ItemStack stack) {
        Optional<FluidStack> value = treeFluid(stack);
        if (runtime == null || value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(runtime.getJeiHelpers().getFocusFactory().createFocus(
                    RecipeIngredientRole.OUTPUT,
                    ForgeTypes.FLUID_STACK,
                    value.get()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    static ItemStack copyWithDisplay(ItemStack stack) {
        ItemStack copy = stack == null ? ItemStack.EMPTY : stack.copy();
        if (stack != null) {
            FluidStack fluid = DISPLAY_FLUIDS.get(stack);
            if (fluid != null) {
                DISPLAY_FLUIDS.put(copy, fluid.copy());
            }
        }
        return copy;
    }

    static boolean render(GuiGraphics graphics, ItemStack stack, int x, int y) {
        Optional<FluidStack> value = treeFluid(stack);
        if (value.isEmpty()) {
            return false;
        }
        IIngredientManager manager = DirectoryRecipePlugin.getJeiRuntime() == null
                ? null
                : DirectoryRecipePlugin.getJeiRuntime().getIngredientManager();
        if (manager == null) {
            return false;
        }
        IIngredientRenderer<FluidStack> renderer = manager.getIngredientRenderer(ForgeTypes.FLUID_STACK);
        renderer.render(graphics, value.get(), x, y);
        return true;
    }

    static boolean renderTooltip(GuiGraphics graphics, ItemStack stack, int mouseX, int mouseY) {
        Optional<FluidStack> value = treeFluid(stack);
        if (value.isEmpty()) {
            return false;
        }
        IIngredientManager manager = DirectoryRecipePlugin.getJeiRuntime() == null
                ? null
                : DirectoryRecipePlugin.getJeiRuntime().getIngredientManager();
        if (manager == null) {
            return false;
        }
        IIngredientRenderer<FluidStack> renderer = manager.getIngredientRenderer(ForgeTypes.FLUID_STACK);
        List<Component> tooltip = renderer.getTooltip(value.get(), TooltipFlag.Default.NORMAL);
        graphics.renderTooltip(Minecraft.getInstance().font, tooltip, Optional.empty(), mouseX, mouseY);
        return true;
    }

    private static int safeAmount(int amount, int batches) {
        long value = (long) Math.max(1, amount) * Math.max(1, batches);
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
