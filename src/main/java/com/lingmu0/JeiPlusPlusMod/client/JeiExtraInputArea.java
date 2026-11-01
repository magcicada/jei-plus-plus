package com.lingmu0.JeiPlusPlusMod.client;

/** Additional controls outside a JEI overlay's native hit box. */
public interface JeiExtraInputArea {
    boolean jeiPlusPlus$containsExtraInput(double mouseX, double mouseY);

    default boolean jeiPlusPlus$isModalInput() {
        return false;
    }
}
