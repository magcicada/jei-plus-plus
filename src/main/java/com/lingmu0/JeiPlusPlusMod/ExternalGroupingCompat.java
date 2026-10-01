package com.lingmu0.JeiPlusPlusMod;

import net.minecraftforge.fml.ModList;

import java.util.Locale;
import java.util.Set;

/** Detects client-side JEI grouping addons that would conflict with JEI++. */
final class ExternalGroupingCompat {
    private static final Set<String> KNOWN_MOD_IDS = Set.of(
            "collapsible_groups",
            "jei_tag_groups",
            "jeitaggroups",
            "jei_groups",
            "jeigroups");

    private static final boolean GROUPING_MOD_LOADED = detectGroupingMod();

    private ExternalGroupingCompat() {
    }

    static boolean isGroupingModLoaded() {
        return GROUPING_MOD_LOADED;
    }

    private static boolean detectGroupingMod() {
        ModList modList = ModList.get();
        for (String modId : KNOWN_MOD_IDS) {
            if (modList.isLoaded(modId)) {
                return true;
            }
        }
        // Keep compatibility with renamed builds whose visible name remains
        // JEI Tag Groups, JEI Groups, or Collapsible Groups.
        return modList.getMods().stream()
                .map(info -> normalize(info.getDisplayName()))
                .anyMatch(name -> name.equals("jeitaggroups")
                        || name.equals("jeigroups")
                        || name.equals("collapsiblegroups")
                        || name.equals("collapsiblegroupsjei"));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
