package com.lingmu0.JeiPlusPlusMod;

import net.minecraftforge.fml.common.Mod;

/**
 * JEI++ is a client-side JEI extension. All of the actual integration lives
 * in the optional JEI plugin and the client mixins, so the common mod entry
 * point deliberately has no client-only references.
 */
@Mod(JeiPlusPlus.MODID)
public final class JeiPlusPlus {
    public static final String MODID = "jei_plus_plus";

    public JeiPlusPlus() {
        JeiPlusPlusConfig.register();
    }
}
