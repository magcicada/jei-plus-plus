package com.lingmu0.JeiPlusPlusMod.client;
import java.util.*;

/** Runs without loading Minecraft, JEI, registries or graphics. */
public final class RecipeTreeOutputChoicesTest {
    record Output(String key, long amount) {}
    public static void main(String[] args) {
        Output iron = new Output("cell:iron", 1), gold = new Output("cell:gold", 1), slag = new Output("slag", 1);
        var slots = List.of(List.of(iron, gold), List.of(slag));
        check(RecipeTreeOutputChoices.select(slots, "cell:iron", Output::key).equals(List.of(iron, slag)), "one output per slot");
        check(RecipeTreeOutputChoices.select(slots, "cell:gold", Output::key).equals(List.of(gold, slag)), "selected output retained");
        check(RecipeTreeOutputChoices.select(List.<List<Output>>of(), "none", Output::key).isEmpty(), "empty slots");
        check(RecipeTreeOutputChoices.select(List.of(List.<Output>of(), List.of(iron)), "cell:iron", Output::key).equals(List.of(iron)), "empty slot ignored");
        check(RecipeTreeOutputChoices.select(List.of(List.of(iron), List.of(iron)), "cell:iron", Output::key).size() == 2, "real repeated output slots retained");
        check(RecipeTreeOutputChoices.amount(slots, "cell:iron", Output::key, Output::amount) == 1, "alternative output count");
        check(RecipeTreeOutputChoices.amount(List.of(List.of(iron), List.of(new Output("cell:iron", 3))), "cell:iron", Output::key, Output::amount) == 4, "sum real repeated outputs");
        check(RecipeTreeOutputChoices.amount(List.of(List.of(new Output("cell:iron", Long.MAX_VALUE)), List.of(iron)), "cell:iron", Output::key, Output::amount) == Long.MAX_VALUE, "saturating output count");
        long first = nestedCost(slots, List.of("cell:iron", "cell:gold"));
        long reversed = nestedCost(slots, List.of("cell:gold", "cell:iron"));
        check(first == 36 && reversed == 36, "nested recipe cannot consume a phantom alternative output");
        for (int i = 0; i < 100; i++) check(nestedCost(slots, List.of("cell:iron", "cell:gold")) == first, "repeatable nested material cost");
        try {
            RecipeTreeOutputChoices.select(slots, "cell:iron", Output::key).clear();
            throw new AssertionError("mutable result");
        } catch (UnsupportedOperationException expected) { }
        System.out.println("Recipe output-choice regression checks passed: nested variants, real byproducts, order and 100 rebuilds.");
    }
    static long nestedCost(List<List<Output>> slots, List<String> targets) {
        Map<String, Long> available = new HashMap<>();
        long ingots = 0;
        for (String target : targets) {
            long needed = Math.max(0, 2 - available.getOrDefault(target, 0L));
            ingots += needed * 9;
            for (Output output : RecipeTreeOutputChoices.select(slots, target, Output::key))
                available.merge(output.key(), output.amount() * needed, Long::sum);
            available.merge(target, -2L, Long::sum);
        }
        return ingots;
    }
    static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
