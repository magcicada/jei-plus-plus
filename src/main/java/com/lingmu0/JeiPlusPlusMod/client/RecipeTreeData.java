package com.lingmu0.JeiPlusPlusMod.client;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JEI-backed material tree data, adapted to JEI from EMI's MIT-licensed
 * material-tree model. Only real item-output recipes are accepted; JEI's tag
 * information pages are deliberately excluded.
 */
public final class RecipeTreeData {
    public static final int MAX_DEPTH = 12;
    public static final int MAX_NODES = 384;
    private static final int MAX_CANDIDATE_SEARCH_DEPTH = 12;
    /**
     * Candidate resolution runs on the client thread while the recipe tree is
     * opened. A depth limit alone is not sufficient for large modpacks: a
     * single ingredient can have hundreds of recipes and alternatives, which
     * makes the recursive search grow exponentially. This visit budget keeps
     * the lookup responsive while still allowing normal multi-step chains to
     * be resolved.
     */
    private static final int MAX_CANDIDATE_SEARCH_VISITS = 4096;

    private static final Map<String, List<RecipeRef>> CANDIDATE_CACHE = new HashMap<>();
    private static final Map<String, List<RecipeSnapshot>> SNAPSHOT_CACHE = new HashMap<>();
    private static final Map<String, RecipeSnapshot> RECIPE_SNAPSHOT_CACHE = new HashMap<>();
    private static final Map<String, IRecipeLayoutDrawable<?>> LAYOUT_CACHE = new HashMap<>();
    private static CandidateContext cachedCandidateContext;
    private static Object cachedCandidateMenu;
    private static long cachedCandidateGameTime = Long.MIN_VALUE;
    private static long cachedCandidateStorageRevision = Long.MIN_VALUE;
    private static Map<String, MutableCost> cachedInventory = Map.of();
    private static Map<String, Long> cachedInventoryAmounts = Map.of();
    private static Object cachedInventoryMenu;
    private static long cachedInventoryGameTime = Long.MIN_VALUE;
    private static long cachedInventoryStorageRevision = Long.MIN_VALUE;

    private RecipeTreeData() {
    }

    private static ItemStack copyStack(ItemStack stack) {
        ItemStack copy = FluidRecipeCompat.copyWithDisplay(stack);
        return copy == null ? ItemStack.EMPTY : copy;
    }

    public enum Progress {
        UNSTARTED, PARTIAL, COMPLETED
    }

    public record RecipeRef(
            IRecipeCategory<?> category,
            Object recipe,
            String key,
            String registryId) {
    }

    /** One or more equivalent consumed recipe slots, merged for tree display. */
    public record RecipeInput(List<ItemStack> alternatives, List<Integer> slotIndexes) {
        public RecipeInput {
            alternatives = alternatives.stream()
                    .filter(stack -> stack != null && !stack.isEmpty())
                    .map(RecipeTreeData::copyStack)
                    .toList();
            slotIndexes = List.copyOf(slotIndexes);
        }

        public ItemStack first() {
            return alternatives.isEmpty() ? ItemStack.EMPTY : copyStack(alternatives.get(0));
        }
    }

    public record RecipeSnapshot(
            RecipeRef ref,
            List<RecipeInput> inputs,
            int inputSlotCount,
            List<ItemStack> outputs) {
        public boolean canExpand() {
            return !inputs.isEmpty();
        }

        public long outputAmount(String ingredientKey) {
            long amount = 0;
            for (ItemStack output : outputs) {
                if (ingredientKey(output).equals(ingredientKey)) {
                    amount += output.getCount();
                }
            }
            return Math.max(1, amount);
        }

        public boolean produces(String ingredientKey) {
            return outputs.stream().anyMatch(output -> ingredientKey(output).equals(ingredientKey));
        }
    }

    public static final class Node {
        private final ItemStack stack;
        private final String ingredientKey;
        private final RecipeSnapshot recipe;
        private final List<ItemStack> alternatives;
        private final String choiceKey;
        private final String path;
        private final List<Integer> inputSlotIndexes;
        private final boolean explicitChoice;
        private final List<Node> children = new ArrayList<>();
        private boolean expanded = true;
        private boolean cycle;
        private long amount = 1;
        private long crafts;
        private long remaining;
        private Progress progress = Progress.UNSTARTED;
        private int x;
        private int y;

        private Node(ItemStack stack, RecipeSnapshot recipe) {
            this(stack, recipe, List.of(stack), "", "root", List.of(), false);
        }

        private Node(
                ItemStack stack,
                RecipeSnapshot recipe,
                List<ItemStack> alternatives,
                String choiceKey,
                String path,
                List<Integer> inputSlotIndexes,
                boolean explicitChoice) {
            this.stack = copyStack(stack);
            this.ingredientKey = RecipeTreeData.ingredientKey(stack);
            this.recipe = recipe;
            this.alternatives = alternatives.stream().map(RecipeTreeData::copyStack).toList();
            this.choiceKey = choiceKey;
            this.path = path;
            this.inputSlotIndexes = List.copyOf(inputSlotIndexes);
            this.explicitChoice = explicitChoice;
        }

        public ItemStack stack() {
            return stack;
        }

        public String ingredientKey() {
            return ingredientKey;
        }

        public RecipeSnapshot recipe() {
            return recipe;
        }

        public List<ItemStack> alternatives() {
            return alternatives;
        }

        public boolean hasAlternatives() {
            return alternatives.size() > 1;
        }

        public boolean isOutputChoice() {
            return recipe != null && "root".equals(path) && hasAlternatives();
        }

        public boolean explicitChoice() {
            return explicitChoice;
        }

        public List<Integer> inputSlotIndexes() {
            return inputSlotIndexes;
        }

        public List<Node> children() {
            return children;
        }

        public boolean expanded() {
            return expanded;
        }

        public void setExpanded(boolean expanded) {
            this.expanded = expanded;
        }

        public boolean cycle() {
            return cycle;
        }

        public long amount() {
            return amount;
        }

        public long crafts() {
            return crafts;
        }

        public long remaining() {
            return remaining;
        }

        public Progress progress() {
            return progress;
        }

        int x() {
            return x;
        }

        int y() {
            return y;
        }

        void setPosition(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public record Cost(ItemStack stack, List<ItemStack> alternatives, long required, long supplied) {
        public Cost {
            stack = copyStack(stack);
            alternatives = alternatives.stream().map(RecipeTreeData::copyStack).toList();
        }
    }

    public record Analysis(List<Cost> costs, List<Cost> leftovers) {
    }

    public record CraftStep(
            RecipeRef recipe,
            ItemStack stack,
            List<ItemStack> alternatives,
            List<String> selectedInputs,
            long batches,
            long remaining,
            long total,
            Progress progress,
            List<String> nodePaths) {
        public CraftStep {
            stack = copyStack(stack);
            alternatives = alternatives.stream().map(RecipeTreeData::copyStack).toList();
            selectedInputs = List.copyOf(selectedInputs);
            nodePaths = List.copyOf(nodePaths);
        }
    }

    /** A live tree keeps per-tree recipe resolutions separate from global defaults. */
    public static final class Tree {
        private static final String ROOT_OUTPUT_CHOICE = "root-output";
        private final RecipeSnapshot rootRecipe;
        private ItemStack rootStack;
        private boolean rootOutputExplicit;
        private final Map<String, RecipeRef> resolutions = new HashMap<>();
        private final Map<String, String> inputSelections = new HashMap<>();
        private Node root;
        private long batches = 1;
        private boolean craftingMode;
        private Analysis cachedAnalysis;
        private List<CraftStep> cachedCraftingSteps;
        private long cachedAnalysisTick = Long.MIN_VALUE;
        private long cachedAnalysisStorageRevision = Long.MIN_VALUE;
        private int analysisRevision;
        private int cachedAnalysisRevision = -1;
        private long cachedCraftingStepsTick = Long.MIN_VALUE;
        private int cachedCraftingStepsRevision = -1;

        private Tree(RecipeSnapshot rootRecipe, ItemStack rootStack) {
            this.rootRecipe = rootRecipe;
            this.rootStack = copyStack(rootStack);
            rebuild();
        }

        public Node root() {
            return root;
        }

        public long batches() {
            return batches;
        }

        public void setBatches(long batches) {
            long clamped = Math.max(1, Math.min(1_000_000L, batches));
            if (this.batches != clamped) {
                this.batches = clamped;
                invalidateAnalysis();
            }
        }

        public boolean craftingMode() {
            return craftingMode;
        }

        public void setCraftingMode(boolean craftingMode) {
            if (this.craftingMode != craftingMode) {
                this.craftingMode = craftingMode;
                invalidateAnalysis();
            }
        }

        public void resolve(String ingredientKey, RecipeRef recipe) {
            resolutions.put(ingredientKey, recipe);
            rebuild();
        }

        public void clearResolution(String ingredientKey) {
            // A present null value intentionally suppresses the global default,
            // matching EMI's per-tree resolution behavior.
            resolutions.put(ingredientKey, null);
            rebuild();
        }

        public void selectInput(Node node, ItemStack selected) {
            if (node == null || node.choiceKey.isEmpty() || selected == null || selected.isEmpty()) {
                return;
            }
            String selectedKey = ingredientKey(selected);
            boolean valid = node.alternatives.stream()
                    .anyMatch(stack -> ingredientKey(stack).equals(selectedKey));
            if (valid) {
                if (ROOT_OUTPUT_CHOICE.equals(node.choiceKey)) {
                    rootStack = copyStack(selected);
                    rootOutputExplicit = true;
                    rebuild();
                    return;
                }
                inputSelections.put(node.choiceKey, selectedKey);
                rebuild();
            }
        }

        public void clearInputSelection(Node node) {
            if (node != null && !node.choiceKey.isEmpty()) {
                if (ROOT_OUTPUT_CHOICE.equals(node.choiceKey)) {
                    rootStack = rootRecipe.outputs().isEmpty()
                            ? ItemStack.EMPTY
                            : copyStack(rootRecipe.outputs().get(0));
                    rootOutputExplicit = false;
                    rebuild();
                    return;
                }
                inputSelections.remove(node.choiceKey);
                rebuild();
            }
        }

        public void rebuild() {
            invalidateAnalysis();
            BuildContext context = new BuildContext();
            root = new Node(
                    rootStack,
                    rootRecipe,
                    rootRecipe.outputs(),
                    ROOT_OUTPUT_CHOICE,
                    "root",
                    List.of(),
                    rootOutputExplicit);
            context.add();
            expand(root, context, new HashSet<>(), 0, "root");
        }

        public long idealBatch() {
            return Math.max(1, idealBatch(root, 1, 1));
        }

        public Analysis analyze() {
            long gameTime = analysisGameTime();
            long storageRevision = StorageNetworkIntegration.snapshotRevision();
            if (cachedAnalysis != null
                    && cachedAnalysisRevision == analysisRevision
                    && cachedAnalysisTick == gameTime
                    && cachedAnalysisStorageRevision == storageRevision) {
                return cachedAnalysis;
            }
            Map<String, MutableCost> totalCosts = new LinkedHashMap<>();
            Map<String, MutableCost> totalRemainders = new LinkedHashMap<>();
            resetProgress(root);
            calculatePlan(root, safeMultiply(rootStack.getCount(), batches), totalCosts, totalRemainders);

            Map<String, MutableCost> missingCosts = new LinkedHashMap<>();
            Map<String, MutableCost> inventory = Map.of();
            if (craftingMode) {
                // Build one inventory/network view for the whole analysis.
                // calculateProgress mutates its working map, so the cached
                // snapshot returns a private copy for this pass.
                inventory = inventorySnapshot();
                calculateProgress(root, safeMultiply(rootStack.getCount(), batches), inventory, missingCosts);
            }

            List<Cost> costs = new ArrayList<>();
            for (Map.Entry<String, MutableCost> entry : totalCosts.entrySet()) {
                MutableCost total = entry.getValue();
                long missing = craftingMode && missingCosts.containsKey(entry.getKey())
                        ? missingCosts.get(entry.getKey()).amount
                        : (craftingMode ? 0 : total.amount);
                // Show the real amount available, even when it exceeds the
                // requirement (for example 1000mB / 250mB or 26 / 3).
                long supplied = craftingMode ? inventoryAmountFromCosts(total.alternatives, inventory) : 0;
                costs.add(new Cost(copyStack(total.stack), total.alternatives, total.amount, supplied));
            }

            List<Cost> leftovers = totalRemainders.values().stream()
                    .filter(cost -> cost.amount > 0)
                    .map(cost -> new Cost(copyStack(cost.stack), cost.alternatives, cost.amount, 0))
                    .toList();
            cachedAnalysis = new Analysis(List.copyOf(costs), leftovers);
            cachedAnalysisRevision = analysisRevision;
            cachedAnalysisTick = gameTime;
            cachedAnalysisStorageRevision = storageRevision;
            cachedCraftingSteps = null;
            return cachedAnalysis;
        }

        public List<CraftStep> craftingSteps() {
            analyze();
            if (cachedCraftingSteps != null
                    && cachedCraftingStepsRevision == cachedAnalysisRevision
                    && cachedCraftingStepsTick == cachedAnalysisTick) {
                return cachedCraftingSteps;
            }
            Map<String, MutableCraftStep> steps = new LinkedHashMap<>();
            collectCraftSteps(root, steps, false);
            cachedCraftingSteps = steps.values().stream()
                    .map(MutableCraftStep::freeze)
                    .toList();
            cachedCraftingStepsRevision = cachedAnalysisRevision;
            cachedCraftingStepsTick = cachedAnalysisTick;
            return cachedCraftingSteps;
        }

        /**
         * Creates the direct JEI transfer description for a visible recipe
         * node. This is intentionally independent of crafting mode: the
         * recipe-tree plus button is a direct-input transfer, not a recursive
         * crafting request.
         */
        public Optional<CraftStep> directTransferStep(Node node) {
            if (node == null || node.recipe == null) {
                return Optional.empty();
            }
            analyze();
            // Match JEI's own "+" button: transfer one recipe operation,
            // independent of the recipe-tree batch target.
            return Optional.of(createCraftStep(node, 1));
        }

        /** Missing craftable dependencies first, followed by the clicked product. */
        public List<CraftStep> recursiveCraftingSteps(CraftStep target) {
            if (target == null) {
                return List.of();
            }
            analyze();
            Set<String> targets = Set.copyOf(target.nodePaths());
            // A Ctrl-click is an explicit request to craft the clicked
            // product again. The normal progress pass consumes an already
            // owned product before looking at its recipe, which is correct
            // for the inventory summary but would make this action produce
            // no transfer steps. Re-run each clicked node with its own
            // output temporarily reserved so only its missing dependencies
            // are planned recursively.
            for (String path : targets) {
                Node node = findNode(root, path);
                if (node != null) {
                    forceTargetProgress(node);
                }
            }
            List<CraftStep> steps = new ArrayList<>();
            collectTargetSubtrees(root, targets, false, steps);
            List<CraftStep> result = List.copyOf(steps);
            invalidateAnalysis();
            return result;
        }

        private void invalidateAnalysis() {
            analysisRevision++;
            cachedAnalysis = null;
            cachedCraftingSteps = null;
            cachedAnalysisTick = Long.MIN_VALUE;
            cachedAnalysisStorageRevision = Long.MIN_VALUE;
            cachedCraftingStepsTick = Long.MIN_VALUE;
            cachedAnalysisRevision = -1;
            cachedCraftingStepsRevision = -1;
        }

        private static long analysisGameTime() {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            return level == null ? Long.MIN_VALUE : level.getGameTime();
        }

        private void forceTargetProgress(Node target) {
            if (target == null || target.amount <= 0) {
                return;
            }
            // Reuse the same per-tick player/network snapshot as the normal
            // analysis pass. A Ctrl-click may target several nodes; rescanning
            // AE2/RS/Beyond/Integrated storage for every target was the main
            // source of the long pause on large recipe trees.
            Map<String, MutableCost> available = inventorySnapshot();
            // Reserve every equivalent candidate for the clicked output. This
            // prevents an existing output stack from satisfying the target,
            // while keeping all other inventory available to its recipe tree.
            if (target.explicitChoice) {
                available.remove(target.ingredientKey);
            } else {
                available.keySet().removeIf(key -> target.alternatives.stream()
                        .anyMatch(alternative -> ingredientKey(alternative).equals(key)));
            }
            resetProgress(target);
            calculateProgress(target, target.amount, available, new LinkedHashMap<>());
        }

        private Node findNode(Node node, String path) {
            if (node == null || path == null) {
                return null;
            }
            if (path.equals(node.path)) {
                return node;
            }
            for (Node child : node.children) {
                Node found = findNode(child, path);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private void expand(Node node, BuildContext context, Set<String> activeRecipes, int depth, String path) {
            RecipeSnapshot snapshot = node.recipe();
            if (snapshot == null || !snapshot.canExpand() || depth >= MAX_DEPTH || context.count >= MAX_NODES) {
                return;
            }

            String recipeKey = snapshot.ref().key();
            if (!activeRecipes.add(recipeKey)) {
                node.cycle = true;
                node.expanded = false;
                return;
            }

            for (int inputIndex = 0; inputIndex < snapshot.inputs().size(); inputIndex++) {
                if (context.count >= MAX_NODES) {
                    break;
                }
                RecipeInput input = snapshot.inputs().get(inputIndex);
                if (input.alternatives().isEmpty()) {
                    continue;
                }
                String choiceKey = path + "/" + snapshot.ref().key() + "/" + inputIndex;
                SelectedInput selected = selectInput(input, choiceKey, context);
                ItemStack selectedStack = selected.stack();
                RecipeSnapshot childRecipe = preferredRecipe(selectedStack);
                String childPath = choiceKey + "/" + ingredientKey(selectedStack);
                Node child = new Node(
                        selectedStack,
                        childRecipe,
                        input.alternatives(),
                        choiceKey,
                        childPath,
                        input.slotIndexes(),
                        selected.explicit());
                node.children.add(child);
                context.add();
                expand(child, context, activeRecipes, depth + 1, childPath);
            }
            activeRecipes.remove(recipeKey);
        }

        private SelectedInput selectInput(
                RecipeInput input,
                String choiceKey,
                BuildContext context) {
            String selectedKey = inputSelections.get(choiceKey);
            if (selectedKey != null) {
                for (ItemStack alternative : input.alternatives()) {
                    if (ingredientKey(alternative).equals(selectedKey)) {
                        return new SelectedInput(copyStack(alternative), true);
                    }
                }
                inputSelections.remove(choiceKey);
            }
            // Recursive candidate resolution is intentionally limited to the
            // active recipe-tree build. Ordinary JEI lookups must not trigger
            // a whole-modpack dependency search. A bookmarked ingredient is
            // treated like another available inventory candidate: it does not
            // override a real inventory/network match (or a recursively
            // craftable alternative), but remains the fallback when no supply
            // can be found. This keeps bookmark matching consistent with the
            // player's inventory matching logic.
            Optional<ItemStack> supplied = findCandidateWithSupply(
                    input.alternatives(),
                    true,
                    context.candidateContext,
                    context.candidateBudget,
                    true);
            if (supplied.isPresent()) {
                return new SelectedInput(supplied.get(), false);
            }
            return RecipeTreeFavorites.bookmarkedCandidate(input.alternatives())
                    .map(stack -> new SelectedInput(stack, false))
                    .orElseGet(() -> new SelectedInput(input.first(), false));
        }

        private RecipeSnapshot preferredRecipe(ItemStack target) {
            String key = ingredientKey(target);
            List<RecipeRef> candidates = candidates(target);
            boolean explicitResolution = resolutions.containsKey(key);
            RecipeRef preferred = explicitResolution
                    ? resolutions.get(key)
                    : RecipeTreeFavorites.bookmarkedRecipe(target, candidates)
                            .orElseGet(() -> RecipeTreeDefaults.getPreferredRecipe(target, candidates));
            if (preferred != null) {
                RecipeSnapshot snapshot = snapshot(preferred);
                if (snapshot != null && snapshot.produces(key)) {
                    return snapshot;
                }
            }
            // A cleared per-tree resolution intentionally suppresses the
            // global default. Without an explicit or configured default, the
            // ingredient remains unexpanded until the player chooses a recipe.
            return null;
        }

        private long idealBatch(Node node, long total, long amount) {
            if (node == null || node.recipe == null) {
                return total;
            }
            long divisor = node.recipe.outputAmount(node.ingredientKey);
            if (divisor > 0) {
                long multiple = divisor / gcd(divisor, amount);
                total = safeMultiply(total, multiple / gcd(total, multiple));
            }
            for (Node child : node.children) {
                total = idealBatch(child, total, safeMultiply(amount, child.stack.getCount()));
            }
            return total;
        }

        private void calculatePlan(
                Node node,
                long desired,
                Map<String, MutableCost> costs,
                Map<String, MutableCost> remainders) {
            node.amount = desired;
            long remaining = consumeForNode(remainders, node, desired);
            RecipeSnapshot recipe = node.recipe;
            if (remaining <= 0) {
                node.crafts = 0;
                return;
            }
            if (recipe == null || node.children.isEmpty()) {
                addCost(costs, node, remaining);
                return;
            }

            long outputAmount = recipe.outputAmount(node.ingredientKey);
            long crafts = ceilDiv(remaining, outputAmount);
            node.crafts = crafts;
            for (Node child : node.children) {
                calculatePlan(child, safeMultiply(child.stack.getCount(), crafts), costs, remainders);
            }

            long surplus = safeMultiply(crafts, outputAmount) - remaining;
            add(remainders, node.stack, surplus);
            for (ItemStack output : recipe.outputs) {
                if (!ingredientKey(output).equals(node.ingredientKey)) {
                    add(remainders, output, safeMultiply(output.getCount(), crafts));
                }
            }
        }

        private void calculateProgress(
                Node node,
                long desired,
                Map<String, MutableCost> available,
                Map<String, MutableCost> missingCosts) {
            long remaining = consumeForNode(available, node, desired);
            node.remaining = remaining;
            long supplied = desired - remaining;
            if (remaining <= 0) {
                complete(node);
                return;
            }
            node.progress = supplied > 0 ? Progress.PARTIAL : Progress.UNSTARTED;
            RecipeSnapshot recipe = node.recipe;
            if (recipe == null || node.children.isEmpty()) {
                addCost(missingCosts, node, remaining);
                return;
            }

            long crafts = ceilDiv(remaining, recipe.outputAmount(node.ingredientKey));
            boolean childProgress = false;
            for (Node child : node.children) {
                calculateProgress(child, safeMultiply(child.stack.getCount(), crafts), available, missingCosts);
                childProgress |= child.progress != Progress.UNSTARTED;
            }
            if (node.progress == Progress.UNSTARTED && childProgress) {
                node.progress = Progress.PARTIAL;
            }
            for (ItemStack output : recipe.outputs) {
                long produced = safeMultiply(output.getCount(), crafts);
                if (ingredientKey(output).equals(node.ingredientKey)) {
                    produced -= remaining;
                }
                addSupply(available, output, produced);
            }
        }

        private void collectCraftSteps(Node node, Map<String, MutableCraftStep> steps, boolean missingOnly) {
            if (node.recipe != null && node.crafts > 0 && (!missingOnly || node.remaining > 0)) {
                long craftBatches = node.remaining > 0
                        ? ceilDiv(node.remaining, node.recipe.outputAmount(node.ingredientKey))
                        : node.crafts;
                addCraftStep(node, craftBatches, steps);
            }
            for (Node child : node.children) {
                collectCraftSteps(child, steps, missingOnly);
            }
        }

        private void collectTargetSubtrees(
                Node node,
                Set<String> targets,
                boolean forceFullTree,
                List<CraftStep> steps) {
            if (targets.contains(node.path)) {
                collectSubtreePostOrder(node, forceFullTree, steps);
                return;
            }
            for (Node child : node.children) {
                collectTargetSubtrees(child, targets, forceFullTree, steps);
            }
        }

        private void collectSubtreePostOrder(
                Node node,
                boolean forceFullTree,
                List<CraftStep> steps) {
            for (Node child : node.children) {
                if (forceFullTree || child.remaining > 0) {
                    collectSubtreePostOrder(child, forceFullTree, steps);
                }
            }
            if (node.recipe == null
                    || node.remaining <= 0
                    || (forceFullTree && node.crafts <= 0)) {
                return;
            }
            long craftBatches = forceFullTree
                    ? node.crafts
                    : ceilDiv(node.remaining, node.recipe.outputAmount(node.ingredientKey));
            steps.add(createCraftStep(node, craftBatches));
        }

        private void addCraftStep(Node node, long craftBatches, Map<String, MutableCraftStep> steps) {
            CraftStep candidate = createCraftStep(node, craftBatches);
            String signature = candidate.recipe().key() + "|" + ingredientKey(candidate.stack()) + "|"
                    + String.join(",", candidate.selectedInputs());
            MutableCraftStep step = steps.get(signature);
            if (step == null) {
                steps.put(signature, new MutableCraftStep(candidate));
            } else {
                step.mergeAlternatives(candidate.alternatives());
                step.batches = safeAdd(step.batches, candidate.batches());
                step.remaining = safeAdd(step.remaining, candidate.remaining());
                step.total = safeAdd(step.total, candidate.total());
                step.nodePaths.addAll(candidate.nodePaths());
                if (step.progress != candidate.progress()) {
                    step.progress = Progress.PARTIAL;
                }
            }
        }

        private CraftStep createCraftStep(Node node, long craftBatches) {
            List<String> selectedInputs = new ArrayList<>(java.util.Collections.nCopies(
                    node.recipe.inputSlotCount(),
                    ""));
            for (Node child : node.children) {
                if (!child.explicitChoice) {
                    continue;
                }
                for (int slotIndex : child.inputSlotIndexes) {
                    if (slotIndex >= 0 && slotIndex < selectedInputs.size()) {
                        selectedInputs.set(slotIndex, child.ingredientKey);
                    }
                }
            }
            return new CraftStep(
                    node.recipe.ref(),
                    node.stack,
                    node.explicitChoice ? List.of(node.stack) : node.alternatives,
                    selectedInputs,
                    craftBatches,
                    node.remaining,
                    node.amount,
                    node.progress,
                    List.of(node.path));
        }
    }

    private static final class BuildContext {
        private int count;
        private final CandidateSearchBudget candidateBudget = new CandidateSearchBudget();
        private final CandidateContext candidateContext = candidateContext();

        private void add() {
            count++;
        }
    }

    private static final class MutableCost {
        private final ItemStack stack;
        private final List<ItemStack> alternatives;
        private long amount;

        private MutableCost(ItemStack stack, long amount) {
            this(stack, List.of(stack), amount);
        }

        private MutableCost(ItemStack stack, List<ItemStack> alternatives, long amount) {
            this.stack = copyStack(stack);
            this.stack.setCount(1);
            this.alternatives = alternatives.stream()
                    .map(alternative -> {
                        ItemStack copy = copyStack(alternative);
                        copy.setCount(1);
                        return copy;
                    })
                    .toList();
            this.amount = amount;
        }
    }

    private record SelectedInput(ItemStack stack, boolean explicit) {
    }

    /** One inventory/network snapshot shared by candidate checks in a tick. */
    private static final class CandidateContext {
        private final Map<String, Long> available;
        private final Map<String, List<RecipeSnapshot>> recipeCache = new HashMap<>();

        private CandidateContext(Map<String, Long> available) {
            this.available = available;
        }
    }

    private static final class MutableCraftStep {
        private final RecipeRef recipe;
        private final ItemStack stack;
        private final List<ItemStack> alternatives = new ArrayList<>();
        private final List<String> selectedInputs;
        private long batches;
        private long remaining;
        private long total;
        private Progress progress;
        private final List<String> nodePaths = new ArrayList<>();

        private MutableCraftStep(CraftStep source) {
            this.recipe = source.recipe();
            this.stack = copyStack(source.stack());
            mergeAlternatives(source.alternatives());
            this.selectedInputs = source.selectedInputs();
            this.batches = source.batches();
            this.remaining = source.remaining();
            this.total = source.total();
            this.progress = source.progress();
            this.nodePaths.addAll(source.nodePaths());
        }

        private CraftStep freeze() {
            return new CraftStep(recipe, stack, alternatives, selectedInputs, batches, remaining, total, progress,
                    nodePaths);
        }

        private void mergeAlternatives(Collection<ItemStack> candidates) {
            Set<String> existing = alternatives.stream()
                    .map(RecipeTreeData::ingredientKey)
                    .collect(java.util.stream.Collectors.toSet());
            for (ItemStack candidate : candidates) {
                if (existing.add(ingredientKey(candidate))) {
                    alternatives.add(copyStack(candidate));
                }
            }
        }
    }

    public static Optional<Tree> build(IRecipeLayoutDrawable<?> rootLayout) {
        return build(rootLayout, null);
    }

    public static Optional<Tree> build(IRecipeLayoutDrawable<?> rootLayout, ItemStack selectedOutput) {
        Optional<RecipeSnapshot> root = snapshot(rootLayout);
        if (root.isEmpty() || root.get().outputs.isEmpty()) {
            return Optional.empty();
        }
        RecipeSnapshot rootSnapshot = root.get();
        ItemStack output = rootSnapshot.outputs.get(0);
        if (selectedOutput != null && !selectedOutput.isEmpty()) {
            String selectedKey = ingredientKey(selectedOutput);
            output = rootSnapshot.outputs.stream()
                    .filter(candidate -> ingredientKey(candidate).equals(selectedKey))
                    .findFirst()
                    .orElse(output);
        }
        // The recipe layout that opened the tree is an explicit user choice.
        // A bookmarked recipe remains the preferred resolution for child
        // ingredients, but must not replace a different root recipe the
        // player is currently viewing.
        return Optional.of(new Tree(rootSnapshot, output));
    }

    /** Recreates a persisted crafting tree after JEI has rebuilt its runtime. */
    static Optional<Tree> restore(
            RecipeRef rootRef,
            String outputKey,
            int outputCount,
            long batches,
            boolean craftingMode) {
        if (rootRef == null) {
            return Optional.empty();
        }
        RecipeSnapshot rootSnapshot = snapshot(rootRef);
        if (rootSnapshot == null || rootSnapshot.outputs().isEmpty()) {
            return Optional.empty();
        }
        ItemStack output = rootSnapshot.outputs().stream()
                .filter(candidate -> ingredientKey(candidate).equals(outputKey))
                .findFirst()
                .orElse(rootSnapshot.outputs().get(0))
                .copy();
        if (outputCount > 0) {
            output.setCount(Math.min(output.getMaxStackSize(), outputCount));
        }
        Tree restored = new Tree(rootSnapshot, output);
        restored.batches = Math.max(1, Math.min(1_000_000L, batches));
        restored.craftingMode = craftingMode;
        restored.invalidateAnalysis();
        return Optional.of(restored);
    }

    public static boolean isSupported(IRecipeLayoutDrawable<?> layout) {
        return layout != null && isSupportedCategory(layout.getRecipeCategory()) && firstOutput(layout).isPresent();
    }

    public static boolean isSupportedCategory(IRecipeCategory<?> category) {
        if (category == null) {
            return false;
        }
        ResourceLocation recipeType = category.getRecipeType().getUid();
        return !recipeType.getPath().startsWith("tag_recipes/")
                && !JeiPlusPlusConfig.isRecipeTypeDisabled(recipeType);
    }

    public static Optional<ItemStack> firstOutput(IRecipeLayoutDrawable<?> layout) {
        if (layout == null || !isSupportedCategory(layout.getRecipeCategory())) {
            return Optional.empty();
        }
        return layout.getRecipeSlotsView().getSlotViews().stream()
                .filter(slot -> slot.getRole() == RecipeIngredientRole.OUTPUT)
                .flatMap(slot -> displayedStacks(slot, false).stream())
                .filter(stack -> !stack.isEmpty())
                .findFirst()
                .map(RecipeTreeData::copyStack);
    }

    public static Optional<RecipeSnapshot> snapshot(IRecipeLayoutDrawable<?> layout) {
        if (layout == null || !isSupportedCategory(layout.getRecipeCategory())) {
            return Optional.empty();
        }
        RecipeRef ref = ref(layout.getRecipeCategory(), layout.getRecipe());
        RecipeSnapshot cached = RECIPE_SNAPSHOT_CACHE.get(ref.key());
        if (cached != null) {
            return Optional.of(cached);
        }
        if (firstOutput(layout).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshot(ref, layout));
    }

    public static RecipeSnapshot snapshot(RecipeRef ref) {
        if (ref == null) {
            return null;
        }
        RecipeSnapshot cached = RECIPE_SNAPSHOT_CACHE.get(ref.key());
        if (cached != null) {
            return cached;
        }
        return createLayout(ref)
                .map(layout -> snapshot(ref, layout))
                .orElse(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Optional<IRecipeLayoutDrawable<?>> createLayout(RecipeRef ref) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || ref == null || !isSupportedCategory(ref.category())) {
            return Optional.empty();
        }
        IRecipeLayoutDrawable<?> cached = LAYOUT_CACHE.get(ref.key());
        if (cached != null) {
            return Optional.of(cached);
        }
        IFocusGroup emptyFocus = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        Optional<IRecipeLayoutDrawable<Object>> layout = runtime.getRecipeManager().createRecipeLayoutDrawable(
                (IRecipeCategory) ref.category(),
                ref.recipe(),
                emptyFocus);
        layout.ifPresent(value -> LAYOUT_CACHE.put(ref.key(), value));
        return (Optional) layout;
    }

    public static List<RecipeRef> candidates(ItemStack target) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || target == null || target.isEmpty()) {
            return List.of();
        }

        String cacheKey = ingredientKey(target);
        List<RecipeRef> cached = CANDIDATE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        IRecipeManager manager = runtime.getRecipeManager();
        Optional<IFocus<net.minecraftforge.fluids.FluidStack>> fluidFocus = FluidRecipeCompat.createOutputFocus(runtime,
                target);
        Optional<IFocus<ItemStack>> itemFocus = fluidFocus.isPresent()
                ? Optional.empty()
                : createOutputFocus(runtime, target);
        IFocus<?> focus = fluidFocus.isPresent() ? fluidFocus.get() : itemFocus.orElse(null);
        if (focus == null) {
            // Some third-party recipes expose placeholder or otherwise
            // invalid ItemStacks (for example Chisel's generated BlockItems).
            // JEI rejects those values when a focus is created. Treat them as
            // having no discoverable recipes instead of letting a tree rebuild
            // crash the client.
            CANDIDATE_CACHE.put(cacheKey, List.of());
            return List.of();
        }

        List<RecipeRef> result = new ArrayList<>();
        manager.createRecipeCategoryLookup()
                .limitFocus(List.of(focus))
                .get()
                .filter(RecipeTreeData::isSupportedCategory)
                .forEach(category -> addCandidates(manager, category, focus, result));
        List<RecipeRef> immutable = List.copyOf(result);
        CANDIDATE_CACHE.put(cacheKey, immutable);
        return immutable;
    }

    /**
     * Creates an output focus for a tree target while tolerating invalid
     * ItemStacks supplied by third-party recipe categories. JEI's public focus
     * API throws IllegalArgumentException for those values.
     */
    public static Optional<IFocus<ItemStack>> createOutputFocus(IJeiRuntime runtime, ItemStack target) {
        if (runtime == null || target == null || target.isEmpty()) {
            return Optional.empty();
        }
        ItemStack focusStack = target.copy();
        focusStack.setCount(1);
        try {
            return Optional.of(runtime.getJeiHelpers().getFocusFactory().createFocus(
                    RecipeIngredientRole.OUTPUT,
                    VanillaTypes.ITEM_STACK,
                    focusStack));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static List<RecipeSnapshot> candidateSnapshots(ItemStack target) {
        String key = ingredientKey(target);
        List<RecipeSnapshot> cached = SNAPSHOT_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<RecipeSnapshot> snapshots = candidates(target).stream()
                .map(RecipeTreeData::snapshot)
                .filter(snapshot -> snapshot != null && snapshot.produces(key))
                .toList();
        // Recipe layouts are expensive to create. Reuse them for all inputs
        // that ask for the same ingredient during one or more tree builds.
        // The cache is cleared whenever JEI rebuilds its runtime.
        SNAPSHOT_CACHE.put(key, snapshots);
        return snapshots;
    }

    public static String ingredientKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        Optional<String> fluidKey = FluidRecipeCompat.fluidKey(stack);
        if (fluidKey.isPresent()) {
            return fluidKey.get();
        }
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime != null) {
            try {
                return runtime.getIngredientManager()
                        .getIngredientHelper(VanillaTypes.ITEM_STACK)
                        .getUniqueId(stack, UidContext.Ingredient);
            } catch (RuntimeException ignored) {
                // Registry fallback keeps startup/reload paths safe.
            }
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    static String displayIngredientKey(ItemStack stack) {
        return FluidRecipeCompat.fluidKey(stack).orElseGet(() -> ingredientKey(stack));
    }

    public static void clearCaches() {
        CANDIDATE_CACHE.clear();
        SNAPSHOT_CACHE.clear();
        RECIPE_SNAPSHOT_CACHE.clear();
        LAYOUT_CACHE.clear();
        FluidRecipeCompat.clear();
        cachedCandidateContext = null;
        cachedCandidateMenu = null;
        cachedCandidateGameTime = Long.MIN_VALUE;
        cachedCandidateStorageRevision = Long.MIN_VALUE;
        cachedInventory = Map.of();
        cachedInventoryAmounts = Map.of();
        cachedInventoryMenu = null;
        cachedInventoryGameTime = Long.MIN_VALUE;
        cachedInventoryStorageRevision = Long.MIN_VALUE;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addCandidates(
            IRecipeManager manager,
            IRecipeCategory<?> category,
            IFocus<?> focus,
            List<RecipeRef> result) {
        RecipeType type = category.getRecipeType();
        manager.createRecipeLookup(type)
                .limitFocus((Collection) List.of(focus))
                .get()
                .forEach(recipe -> result.add(ref(category, recipe)));
    }

    private static RecipeSnapshot snapshot(RecipeRef ref, IRecipeLayoutDrawable<?> layout) {
        RecipeSnapshot cached = RECIPE_SNAPSHOT_CACHE.get(ref.key());
        if (cached != null) {
            return cached;
        }
        Map<String, MutableRecipeInput> groupedInputs = new LinkedHashMap<>();
        Map<String, ItemStack> outputs = new LinkedHashMap<>();
        int inputSlotCount = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            RecipeIngredientRole role = slot.getRole();
            // JEI catalysts are machines/tools and are not consumed materials.
            if (role != RecipeIngredientRole.INPUT && role != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            int inputSlotIndex = role == RecipeIngredientRole.INPUT ? inputSlotCount++ : -1;
            List<ItemStack> alternatives = displayedStacks(slot, role == RecipeIngredientRole.INPUT);
            if (alternatives.isEmpty()) {
                continue;
            }
            if (role == RecipeIngredientRole.INPUT) {
                addRecipeInput(groupedInputs, alternatives, inputSlotIndex);
            } else {
                for (ItemStack alternative : alternatives) {
                    merge(outputs, alternative);
                }
            }
        }
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime != null) {
            for (List<ITypedIngredient<?>> ingredients : SequencedAssemblyCompat.repeatedInputSlots(
                    ref.recipe(), runtime.getIngredientManager())) {
                List<ItemStack> alternatives = displayedStacks(ingredients, true);
                addRecipeInput(groupedInputs, alternatives, inputSlotCount++);
            }
        }
        List<RecipeInput> inputs = groupedInputs.values().stream()
                .map(MutableRecipeInput::freeze)
                .toList();
        RecipeSnapshot snapshot = new RecipeSnapshot(ref, inputs, inputSlotCount, List.copyOf(outputs.values()));
        RECIPE_SNAPSHOT_CACHE.put(ref.key(), snapshot);
        return snapshot;
    }

    private static void addRecipeInput(
            Map<String, MutableRecipeInput> groupedInputs,
            List<ItemStack> alternatives,
            int inputSlotIndex) {
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        for (ItemStack alternative : alternatives) {
            if (alternative != null && !alternative.isEmpty()) {
                unique.putIfAbsent(ingredientKey(alternative), alternative);
            }
        }
        List<ItemStack> uniqueAlternatives = List.copyOf(unique.values());
        if (uniqueAlternatives.isEmpty()) {
            return;
        }
        String signature = uniqueAlternatives.stream()
                .map(stack -> ingredientKey(stack) + "=" + stack.getCount())
                .sorted()
                .reduce((left, right) -> left + "\u001F" + right)
                .orElse("");
        MutableRecipeInput group = groupedInputs.get(signature);
        if (group == null) {
            groupedInputs.put(signature, new MutableRecipeInput(uniqueAlternatives, inputSlotIndex));
        } else {
            group.merge(uniqueAlternatives, inputSlotIndex);
        }
    }

    private static List<ItemStack> displayedStacks(IRecipeSlotView slot, boolean input) {
        return displayedStacks(slot.getAllIngredients().toList(), input);
    }

    private static List<ItemStack> displayedStacks(
            List<? extends ITypedIngredient<?>> ingredients,
            boolean input) {
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        for (ITypedIngredient<?> ingredient : ingredients) {
            if (ingredient == null) {
                continue;
            }
            ingredient.getItemStack()
                    .filter(item -> !item.isEmpty())
                    .map(ItemStack::copy)
                    .ifPresent(stack -> unique.putIfAbsent(ingredientKey(stack), stack));
            ingredient.getIngredient(VanillaTypes.ITEM_STACK)
                    .filter(item -> !item.isEmpty())
                    .map(ItemStack::copy)
                    .ifPresent(stack -> unique.putIfAbsent(ingredientKey(stack), stack));
            FluidRecipeCompat.representativeContainer(ingredient)
                    .map(FluidRecipeCompat::copyWithDisplay)
                    .ifPresent(stack -> unique.putIfAbsent(ingredientKey(stack), stack));
            if (input) {
                FluidRecipeCompat.containerCandidate(ingredient)
                        .ifPresent(stack -> unique.putIfAbsent(ingredientKey(stack), stack));
            }
        }
        if (input && unique.isEmpty()) {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                for (ITypedIngredient<?> ingredient : ingredients) {
                    if (ingredient == null) {
                        continue;
                    }
                    for (ItemStack stack : FluidRecipeCompat.matchingContainers(player, ingredient)) {
                        ItemStack displayed = FluidRecipeCompat.copyWithDisplay(stack);
                        unique.putIfAbsent(ingredientKey(displayed), displayed);
                    }
                }
            }
        }
        return List.copyOf(unique.values());
    }

    /**
     * Returns every concrete item candidate exposed by a recipe input slot.
     * Transfer code must use the same expansion as tree construction; using
     * only {@link IRecipeSlotView#getItemStacks()} can leave a tag's first
     * (usually oak) entry as the accidental default.
     */
    static List<ItemStack> candidateStacks(IRecipeSlotView slot) {
        return slot == null ? List.of() : displayedStacks(slot, true);
    }

    private static final class MutableRecipeInput {
        private final Map<String, ItemStack> alternatives = new LinkedHashMap<>();
        private final List<Integer> slotIndexes = new ArrayList<>();

        private MutableRecipeInput(List<ItemStack> alternatives, int slotIndex) {
            merge(alternatives, slotIndex);
        }

        private void merge(List<ItemStack> additions, int slotIndex) {
            slotIndexes.add(slotIndex);
            for (ItemStack addition : additions) {
                String key = ingredientKey(addition);
                ItemStack current = alternatives.get(key);
                if (current == null) {
                    alternatives.put(key, copyStack(addition));
                } else {
                    current.setCount(current.getCount() + addition.getCount());
                }
            }
        }

        private RecipeInput freeze() {
            return new RecipeInput(List.copyOf(alternatives.values()), slotIndexes);
        }
    }

    private static void merge(Map<String, ItemStack> destination, ItemStack stack) {
        String key = ingredientKey(stack);
        ItemStack existing = destination.get(key);
        if (existing == null) {
            destination.put(key, copyStack(stack));
        } else {
            existing.setCount(existing.getCount() + stack.getCount());
        }
    }

    private static RecipeRef ref(IRecipeCategory<?> category, Object recipe) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        ResourceLocation id = ((IRecipeCategory) category).getRegistryName(recipe);
        String registryId = id == null ? "" : id.toString();
        String identity = registryId.isEmpty() ? "runtime/" + System.identityHashCode(recipe) : registryId;
        String key = category.getRecipeType().getUid() + "|" + identity;
        return new RecipeRef(category, recipe, key, registryId);
    }

    private static Map<String, MutableCost> playerInventory() {
        Map<String, MutableCost> result = new LinkedHashMap<>();
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            return result;
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                addSupply(result, stack, stack.getCount());
            }
        }
        // Items in the currently open container are also available to the
        // recipe tree. Exclude the player's own inventory slots because they
        // are already included above (and appear in most container menus).
        var menu = player.containerMenu;
        if (menu != null && menu != player.inventoryMenu) {
            for (var slot : menu.slots) {
                if (slot.container == inventory) {
                    continue;
                }
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    addSupply(result, stack, stack.getCount());
                }
            }
        }
        // Optional network storage is an additional source of supply for
        // recipe-tree planning and candidate/highlight resolution.
        StorageNetworkIntegration.StorageSnapshot network = StorageNetworkIntegration.snapshot();
        for (StorageNetworkIntegration.StoredStack stored : network.stacks()) {
            addSupply(result, stored.stack(), stored.amount());
        }
        for (StorageNetworkIntegration.StoredFluid stored : network.fluids()) {
            FluidRecipeCompat.representativeForKey(stored.key(), stored.amount())
                    .ifPresent(stack -> addSupply(result, stack, 1L));
        }
        return result;
    }

    /** Reuses one player/network inventory scan for all consumers in a tick. */
    private static Map<String, MutableCost> inventorySnapshot() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        Object menu = Ae2StorageIntegration.activeMenu();
        long gameTime = minecraft.level == null ? -1L : minecraft.level.getGameTime();
        long storageRevision = StorageNetworkIntegration.snapshotRevision();
        if (menu == cachedInventoryMenu
                && gameTime == cachedInventoryGameTime
                && storageRevision == cachedInventoryStorageRevision) {
            return copyInventory(cachedInventory);
        }
        Map<String, MutableCost> fresh = playerInventory();
        cachedInventoryMenu = menu;
        cachedInventoryGameTime = gameTime;
        cachedInventoryStorageRevision = storageRevision;
        cachedInventory = freezeInventory(fresh);
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (Map.Entry<String, MutableCost> entry : cachedInventory.entrySet()) {
            amounts.put(entry.getKey(), entry.getValue().amount);
        }
        cachedInventoryAmounts = Map.copyOf(amounts);
        return copyInventory(cachedInventory);
    }

    private static Map<String, MutableCost> freezeInventory(Map<String, MutableCost> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<String, MutableCost> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, MutableCost> entry : source.entrySet()) {
            MutableCost value = entry.getValue();
            frozen.put(entry.getKey(), new MutableCost(value.stack, value.alternatives, value.amount));
        }
        return Map.copyOf(frozen);
    }

    private static Map<String, MutableCost> copyInventory(Map<String, MutableCost> source) {
        if (source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, MutableCost> copy = new LinkedHashMap<>();
        for (Map.Entry<String, MutableCost> entry : source.entrySet()) {
            MutableCost value = entry.getValue();
            copy.put(entry.getKey(), new MutableCost(value.stack, value.alternatives, value.amount));
        }
        return copy;
    }

    /** Immutable amount view used by bookmark refreshes without rescanning storage. */
    public static Map<String, Long> inventoryAmounts() {
        inventorySnapshot();
        return cachedInventoryAmounts;
    }

    public static long inventoryAmount(Collection<ItemStack> alternatives, Map<String, Long> amounts) {
        if (alternatives == null || alternatives.isEmpty() || amounts == null || amounts.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        Set<String> keys = new HashSet<>();
        for (ItemStack alternative : alternatives) {
            if (alternative != null && !alternative.isEmpty()) {
                keys.add(ingredientKey(alternative));
            }
        }
        for (String key : keys) {
            total = safeAdd(total, amounts.getOrDefault(key, 0L));
        }
        return total;
    }

    private static long inventoryAmountFromCosts(Collection<ItemStack> alternatives,
            Map<String, MutableCost> inventory) {
        if (alternatives == null || alternatives.isEmpty() || inventory == null || inventory.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        Set<String> keys = new HashSet<>();
        for (ItemStack alternative : alternatives) {
            if (alternative != null && !alternative.isEmpty()) {
                keys.add(ingredientKey(alternative));
            }
        }
        for (String key : keys) {
            MutableCost value = inventory.get(key);
            if (value != null) {
                total = safeAdd(total, value.amount);
            }
        }
        return total;
    }

    private static Map<String, Long> playerInventoryAmounts() {
        return inventoryAmounts();
    }

    private static CandidateContext candidateContext() {
        Object menu = Ae2StorageIntegration.activeMenu();
        var level = net.minecraft.client.Minecraft.getInstance().level;
        long gameTime = level == null ? -1L : level.getGameTime();
        long storageRevision = StorageNetworkIntegration.snapshotRevision();
        if (cachedCandidateContext != null
                && cachedCandidateMenu == menu
                && cachedCandidateGameTime == gameTime
                && cachedCandidateStorageRevision == storageRevision) {
            return cachedCandidateContext;
        }
        CandidateContext context = new CandidateContext(playerInventoryAmounts());
        cachedCandidateContext = context;
        cachedCandidateMenu = menu;
        cachedCandidateGameTime = gameTime;
        cachedCandidateStorageRevision = storageRevision;
        return context;
    }

    /**
     * Finds the first candidate that is directly in the inventory or can be
     * supplied by recursively crafting its inputs. Direct inventory matches
     * always win over recursively craftable candidates, while candidate order
     * remains stable for ties.
     */
    /**
     * Resolves a candidate using inventory and, when requested, its recursive
     * recipe dependencies. The recursive path is reserved for recipe-tree
     * inputs/default resolutions; callers handling a normal JEI recipe can
     * pass {@code false} and retain the direct-inventory behavior.
     */
    static Optional<ItemStack> findCandidateWithSupply(
            Collection<ItemStack> candidates,
            boolean recursive) {
        return findCandidateWithSupply(
                candidates,
                recursive,
                candidateContext(),
                new CandidateSearchBudget(),
                false);
    }

    private static Optional<ItemStack> findCandidateWithSupply(
            Collection<ItemStack> candidates,
            boolean recursive,
            CandidateContext context,
            CandidateSearchBudget budget,
            boolean includeBookmarkedSupply) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<ItemStack> valid = candidates.stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(RecipeTreeData::copyStack)
                .toList();
        if (valid.isEmpty()) {
            return Optional.empty();
        }

        CandidateContext resolvedContext = context == null ? candidateContext() : context;
        Map<String, Long> available = resolvedContext.available;
        for (ItemStack candidate : valid) {
            if (availableAmount(available, ingredientKey(candidate)) >= requiredMapAmount(candidate)) {
                return Optional.of(copyStack(candidate));
            }
        }
        if (!recursive) {
            return Optional.empty();
        }

        Map<String, List<RecipeSnapshot>> recipeCache = resolvedContext.recipeCache;
        CandidateSearchBudget searchBudget = budget == null ? new CandidateSearchBudget() : budget;
        Set<String> bookmarkedKeys = includeBookmarkedSupply
                ? RecipeTreeFavorites.bookmarkedIngredientKeys()
                : Set.of();
        for (ItemStack candidate : valid) {
            Map<String, Long> trial = new LinkedHashMap<>(available);
            if (canSupplyCandidate(
                    candidate,
                    trial,
                    new HashSet<>(),
                    0,
                    recipeCache,
                    searchBudget,
                    bookmarkedKeys)) {
                return Optional.of(copyStack(candidate));
            }
            if (searchBudget.exhausted()) {
                break;
            }
        }
        return Optional.empty();
    }

    private static final class CandidateSearchBudget {
        private int remaining = MAX_CANDIDATE_SEARCH_VISITS;

        private boolean visit() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        private boolean exhausted() {
            return remaining <= 0;
        }
    }

    /**
     * Test whether a candidate can be supplied from the inventory or by any
     * recipe chain. This is used for unlocked candidate display and transfer;
     * it never changes the tree's recipe defaults or stores a resolution.
     */
    private static boolean canSupplyCandidate(
            ItemStack wanted,
            Map<String, Long> available,
            Set<String> active,
            int depth,
            Map<String, List<RecipeSnapshot>> recipeCache,
            CandidateSearchBudget budget,
            Set<String> bookmarkedKeys) {
        if (budget == null || !budget.visit()) {
            return false;
        }
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        String key = ingredientKey(wanted);
        // Bookmarks participate only in candidate resolution. They are not
        // added to the inventory map, so they still appear as missing costs;
        // this merely lets recursive matching follow a bookmarked log through
        // its plank/stick/etc. recipes in the same way as an owned item.
        if (bookmarkedKeys != null && bookmarkedKeys.contains(key)) {
            return true;
        }
        long required = requiredMapAmount(wanted);
        long present = availableAmount(available, key);
        if (present >= required) {
            setAvailable(available, key, present - required);
            return true;
        }
        if (depth >= MAX_CANDIDATE_SEARCH_DEPTH || !active.add(key)) {
            return false;
        }

        long missing = required - present;
        Map<String, Long> base = new LinkedHashMap<>(available);
        setAvailable(base, key, 0);
        List<RecipeSnapshot> recipes = recipeCache.computeIfAbsent(key, ignored -> candidateSnapshots(wanted));
        for (RecipeSnapshot recipe : recipes) {
            if (!budget.visit()) {
                break;
            }
            if (recipe == null || !recipe.produces(key) || recipe.inputs().isEmpty()) {
                continue;
            }
            long crafts = ceilDiv(missing, recipeOutputSupplyAmount(recipe, key));
            if (crafts <= 0) {
                continue;
            }

            Map<String, Long> branch = new LinkedHashMap<>(base);
            boolean inputsAvailable = true;
            for (RecipeInput input : recipe.inputs()) {
                boolean alternativeAvailable = false;
                for (ItemStack alternative : input.alternatives()) {
                    if (!budget.visit()) {
                        inputsAvailable = false;
                        break;
                    }
                    ItemStack requiredInput = copyWithCount(
                            alternative,
                            safeMultiply(alternative.getCount(), crafts));
                    Map<String, Long> inputBranch = new LinkedHashMap<>(branch);
                    if (canSupplyCandidate(
                            requiredInput,
                            inputBranch,
                            new HashSet<>(active),
                            depth + 1,
                            recipeCache,
                            budget,
                            bookmarkedKeys)) {
                        branch = inputBranch;
                        alternativeAvailable = true;
                        break;
                    }
                }
                if (!alternativeAvailable) {
                    inputsAvailable = false;
                    break;
                }
            }
            if (!inputsAvailable) {
                continue;
            }

            for (ItemStack output : recipe.outputs()) {
                addAvailable(branch, output, safeMultiply(output.getCount(), crafts));
            }
            if (availableAmount(branch, key) < missing) {
                continue;
            }
            consumeAvailable(branch, key, missing);
            available.clear();
            available.putAll(branch);
            active.remove(key);
            return true;
        }
        active.remove(key);
        return false;
    }

    private static ItemStack copyWithCount(ItemStack source, long count) {
        ItemStack copy = copyStack(source);
        copy.setCount((int) Math.min(Integer.MAX_VALUE, Math.max(1L, count)));
        return copy;
    }

    private static long recipeOutputSupplyAmount(RecipeSnapshot recipe, String key) {
        if (recipe == null) {
            return 1L;
        }
        for (ItemStack output : recipe.outputs()) {
            if (ingredientKey(output).equals(key)) {
                return Math.max(1L, mapAmount(output, output.getCount()));
            }
        }
        return 1L;
    }

    private static long availableAmount(Map<String, Long> available, String key) {
        return available.getOrDefault(key, 0L);
    }

    private static void setAvailable(Map<String, Long> available, String key, long amount) {
        if (amount <= 0) {
            available.remove(key);
        } else {
            available.put(key, amount);
        }
    }

    private static void addAvailable(Map<String, Long> available, ItemStack stack, long amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) {
            return;
        }
        String key = ingredientKey(stack);
        setAvailable(available, key, safeAdd(
                availableAmount(available, key),
                mapAmount(stack, amount)));
        if (FluidRecipeCompat.fluidKey(stack).isEmpty()) {
            FluidRecipeCompat.fluidRepresentation(stack).ifPresent(fluid -> {
                String fluidKey = ingredientKey(fluid);
                setAvailable(available, fluidKey, safeAdd(
                        availableAmount(available, fluidKey),
                        mapAmount(fluid, amount)));
            });
        }
    }

    private static void consumeAvailable(Map<String, Long> available, String key, long amount) {
        if (amount <= 0) {
            return;
        }
        setAvailable(available, key, Math.max(0, availableAmount(available, key) - amount));
    }

    private static void resetProgress(Node node) {
        node.progress = Progress.UNSTARTED;
        node.crafts = 0;
        node.remaining = 0;
        for (Node child : node.children) {
            resetProgress(child);
        }
    }

    private static void complete(Node node) {
        node.progress = Progress.COMPLETED;
        for (Node child : node.children) {
            complete(child);
        }
    }

    private static void add(Map<String, MutableCost> map, ItemStack stack, long amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) {
            return;
        }
        String key = ingredientKey(stack);
        MutableCost cost = map.get(key);
        if (cost == null) {
            map.put(key, new MutableCost(stack, amount));
        } else {
            cost.amount = safeAdd(cost.amount, amount);
        }
    }

    /** Adds inventory/planner supply using mB for fluids and units for items. */
    private static void addSupply(Map<String, MutableCost> map, ItemStack stack, long units) {
        add(map, stack, mapAmount(stack, units));
        // A filled container can satisfy a fluid ingredient as well as an
        // explicit bucket ingredient. Keep both keys in the planning map so
        // fluid candidates recurse through inventory/network containers.
        if (FluidRecipeCompat.fluidKey(stack).isEmpty()) {
            FluidRecipeCompat.fluidRepresentation(stack).ifPresent(fluid -> add(map, fluid, mapAmount(fluid, units)));
        }
    }

    private static long mapAmount(ItemStack stack, long units) {
        if (stack == null || stack.isEmpty() || units <= 0) {
            return 0L;
        }
        if (FluidRecipeCompat.treeFluid(stack).isPresent()) {
            return FluidRecipeCompat.amountForUnits(stack, units);
        }
        return units;
    }

    private static long requiredMapAmount(ItemStack stack) {
        return mapAmount(stack, Math.max(1L, stack == null ? 1L : stack.getCount()));
    }

    private static void addCost(Map<String, MutableCost> map, Node node, long amount) {
        if (node == null || amount <= 0) {
            return;
        }
        List<ItemStack> alternatives = node.explicitChoice ? List.of(node.stack) : node.alternatives;
        String key = alternatives.stream()
                .map(RecipeTreeData::ingredientKey)
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "\u001F" + right)
                .orElse(node.ingredientKey);
        MutableCost cost = map.get(key);
        if (cost == null) {
            map.put(key, new MutableCost(node.stack, alternatives, amount));
        } else {
            cost.amount = safeAdd(cost.amount, amount);
        }
    }

    private static long consumeForNode(Map<String, MutableCost> map, Node node, long desired) {
        if (node == null || desired <= 0) {
            return desired;
        }
        if (FluidRecipeCompat.treeFluid(node.stack).isPresent()) {
            long perUnit = Math.max(1L, FluidRecipeCompat.amountForUnits(node.stack, 1));
            long desiredAmount = mapAmount(node.stack, desired);
            long remainingAmount = consume(map, node.ingredientKey, desiredAmount);
            return remainingAmount <= 0 ? 0 : ceilDiv(remainingAmount, perUnit);
        }
        long remaining = consume(map, node.ingredientKey, desired);
        if (!node.explicitChoice && remaining > 0) {
            for (ItemStack alternative : node.alternatives) {
                String key = ingredientKey(alternative);
                if (!key.equals(node.ingredientKey)) {
                    remaining = consume(map, key, remaining);
                    if (remaining <= 0) {
                        break;
                    }
                }
            }
        }
        return remaining;
    }

    private static long consume(Map<String, MutableCost> map, String key, long desired) {
        MutableCost cost = map.get(key);
        if (cost == null || desired <= 0) {
            return desired;
        }
        long used = Math.min(desired, cost.amount);
        cost.amount -= used;
        if (cost.amount <= 0) {
            map.remove(key);
        }
        return desired - used;
    }

    public static long inventoryAmount(ItemStack wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return 0L;
        }
        return inventoryAmounts().getOrDefault(ingredientKey(wanted), 0L);
    }

    public static long inventoryAmount(Collection<ItemStack> alternatives) {
        return inventoryAmount(alternatives, inventoryAmounts());
    }

    private static long ceilDiv(long value, long divisor) {
        if (value <= 0) {
            return 0;
        }
        return 1 + (value - 1) / Math.max(1, divisor);
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return Math.max(1, a);
    }

    private static long safeMultiply(long first, long second) {
        if (first <= 0 || second <= 0) {
            return 0;
        }
        if (first > Long.MAX_VALUE / second) {
            return Long.MAX_VALUE;
        }
        return first * second;
    }

    private static long safeAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }
}
