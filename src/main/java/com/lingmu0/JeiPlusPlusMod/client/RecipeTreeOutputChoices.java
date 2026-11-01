package com.lingmu0.JeiPlusPlusMod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** A cycling slot yields one alternative, not every alternative as a byproduct. */
final class RecipeTreeOutputChoices {
    private RecipeTreeOutputChoices() {}

    static <T> long amount(List<? extends List<T>> slots, String wanted, Function<T, String> key, ToLongFunction<T> count) {
        long total = 0;
        for (T output : select(slots, wanted, key)) {
            if (!key.apply(output).equals(wanted)) continue;
            long addition = Math.max(0, count.applyAsLong(output));
            total = total > Long.MAX_VALUE - addition ? Long.MAX_VALUE : total + addition;
        }
        return Math.max(1, total);
    }

    static <T> List<T> select(List<? extends List<T>> slots, String wanted, Function<T, String> key) {
        List<T> selected = new ArrayList<>();
        for (List<T> alternatives : slots) {
            if (alternatives.isEmpty()) continue;
            T output = alternatives.get(0);
            for (T candidate : alternatives) {
                if (key.apply(candidate).equals(wanted)) {
                    output = candidate;
                    break;
                }
            }
            selected.add(output);
        }
        return List.copyOf(selected);
    }
}
