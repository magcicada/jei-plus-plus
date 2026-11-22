package com.lingmu0.JeiPlusPlusMod;

import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/** Optional bridge to Applied Mekanistics' registered AE2 key type. */
public final class Ae2ChemicalCompat {
    private static final String KEY = "me.ramidzkh.mekae2.ae2.MekanismKey";
    private static final String GENERIC = "appeng.api.stacks.GenericStack";

    private Ae2ChemicalCompat() {}

    public static boolean isChemicalKey(Object key) {
        return key != null && key.getClass().getName().equals(KEY);
    }

    public static ItemStack wrap(Object chemical, long amount) {
        if (amount <= 0) return ItemStack.EMPTY;
        try {
            Class<?> keyClass = Class.forName(KEY);
            for (Method factory : keyClass.getMethods()) {
                if (!factory.getName().equals("of") || factory.getParameterCount() != 1
                    || !factory.getParameterTypes()[0].isInstance(chemical)) continue;
                Object key = factory.invoke(null, chemical);
                if (key == null) return ItemStack.EMPTY;
                Class<?> aeKey = Class.forName("appeng.api.stacks.AEKey");
                return (ItemStack) Class.forName(GENERIC).getMethod("wrapInItemStack", aeKey, long.class)
                    .invoke(null, key, amount);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The recipe tree also works without AE2 or its optional addon.
        }
        return ItemStack.EMPTY;
    }

    public static boolean isWrapped(ItemStack stack) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(Class.forName(GENERIC).getMethod("isWrapped", ItemStack.class)
            .invoke(null, stack));
    }

    static Object unwrapInput(ItemStack stack) throws ReflectiveOperationException {
        Object generic = Class.forName(GENERIC).getMethod("unwrapItemStack", ItemStack.class).invoke(null, stack);
        if (generic == null) return null;
        long amount = (long) generic.getClass().getMethod("amount").invoke(generic);
        return amount > 0 ? generic : null;
    }
}
