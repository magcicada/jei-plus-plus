package com.lingmu0.JeiPlusPlusMod;

import net.minecraftforge.fml.common.Mod;

/**
 * Most JEI++ features remain client-only. The optional AE2 pattern action
 * uses a common play channel but this entry point has no client-only links.
 
 * JEI++ is a client-side JEI extension. All of the actual integration lives
 * in the optional JEI plugin and the client mixins, so the common mod entry
 * point deliberately has no client-only references.
 */
@Mod(JeiPlusPlus.MODID)
public final class JeiPlusPlus {
    public static final String MODID = "jei_plus_plus";

    public JeiPlusPlus() {
        JeiPlusPlusConfig.register();
        Ae2PatternNetwork.register();
    }
}
