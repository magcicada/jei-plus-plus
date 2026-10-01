package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.client.CreativeTabOrder;
import com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat;
import com.lingmu0.JeiPlusPlusMod.client.IngredientListFeatureSource;
import com.lingmu0.JeiPlusPlusMod.client.IngredientListFeatures;
import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.overlay.elements.IElement;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Adds creative-tab filtering and Reliable-EMI-style expandable groups. */
@Mixin(value = IngredientFilter.class, remap = false)
public abstract class IngredientFilterMixin
        implements
            IngredientListFeatureSource,
            IngredientListFeatures.IngredientListExpansionState {
    @Unique
    private volatile int jeiPlusPlus$selectedCreativeTab;
    @Unique
    private volatile int jeiPlusPlus$creativeTabPage;
    @Unique
    private volatile boolean jeiPlusPlus$creativeTabSelectorOpen;
    @Unique
    private volatile int jeiPlusPlus$creativeTabSelectorPage;
    @Unique
    private final Set<String> jeiPlusPlus$expandedGroups = ConcurrentHashMap.newKeySet();
    @Unique
    private volatile List<IElement<?>> jeiPlusPlus$sourceCache;
    @Unique
    private volatile List<IElement<?>> jeiPlusPlus$transformedCache;
    @Unique
    private volatile boolean jeiPlusPlus$cachedCreativeEnabled;
    @Unique
    private volatile boolean jeiPlusPlus$cachedGroupingEnabled;
    @Unique
    private volatile int jeiPlusPlus$cachedCreativeTab = -1;

    @Shadow
    public abstract void invalidateCache();

    @Shadow
    protected abstract void notifyListenersOfChange();

    @Inject(method = "invalidateCache", at = @At("HEAD"), remap = false)
    private void jeiPlusPlus$invalidateTransformCache(CallbackInfo ci) {
        jeiPlusPlus$sourceCache = null;
        jeiPlusPlus$transformedCache = null;
    }

    @Inject(method = "getElements", at = @At("RETURN"), cancellable = true, remap = false)
    private void jeiPlusPlus$transformElements(CallbackInfoReturnable<List<IElement<?>>> cir) {
        boolean creativeEnabled = JeiPlusPlusConfig.CREATIVE_TAB_BAR_ENABLED.get();
        boolean groupingEnabled = JeiPlusPlusConfig.isStackGroupingEnabled();
        if (!creativeEnabled && !groupingEnabled) {
            return;
        }
        List<IElement<?>> source = cir.getReturnValue();
        int selectedTab = creativeEnabled ? jeiPlusPlus$getSelectedCreativeTab() : 0;
        List<IElement<?>> cachedSource = jeiPlusPlus$sourceCache;
        List<IElement<?>> cachedResult = jeiPlusPlus$transformedCache;
        if (source == cachedSource
                && cachedResult != null
                && creativeEnabled == jeiPlusPlus$cachedCreativeEnabled
                && groupingEnabled == jeiPlusPlus$cachedGroupingEnabled
                && selectedTab == jeiPlusPlus$cachedCreativeTab) {
            cir.setReturnValue(cachedResult);
            return;
        }
        List<IElement<?>> transformed = IngredientListFeatures.transform(this, source);
        jeiPlusPlus$cachedCreativeEnabled = creativeEnabled;
        jeiPlusPlus$cachedGroupingEnabled = groupingEnabled;
        jeiPlusPlus$cachedCreativeTab = selectedTab;
        jeiPlusPlus$sourceCache = source;
        jeiPlusPlus$transformedCache = transformed;
        cir.setReturnValue(transformed);
    }

    @Override
    public List<CreativeModeTab> jeiPlusPlus$getCreativeTabs() {
        if (!JeiPlusPlusConfig.CREATIVE_TAB_BAR_ENABLED.get()) {
            return List.of();
        }
        List<CreativeModeTab> result = new ArrayList<>();
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() == CreativeModeTab.Type.CATEGORY && tab.shouldDisplay()) {
                result.add(tab);
            }
        }
        return CreativeTabOrder.order(result);
    }

    @Override
    public int jeiPlusPlus$getSelectedCreativeTab() {
        int maximum = jeiPlusPlus$getCreativeTabs().size();
        if (jeiPlusPlus$selectedCreativeTab < 0 || jeiPlusPlus$selectedCreativeTab > maximum) {
            jeiPlusPlus$selectedCreativeTab = 0;
        }
        return jeiPlusPlus$selectedCreativeTab;
    }

    @Override
    public void jeiPlusPlus$selectCreativeTab(int index) {
        int maximum = jeiPlusPlus$getCreativeTabs().size();
        if (maximum <= 0) {
            index = 0;
        } else {
            index = Math.max(0, Math.min(index, maximum));
        }
        if (jeiPlusPlus$selectedCreativeTab != index) {
            jeiPlusPlus$selectedCreativeTab = index;
            jeiPlusPlus$refresh();
        }
    }

    @Override
    public int jeiPlusPlus$getCreativeTabPage() {
        return Math.max(0, jeiPlusPlus$creativeTabPage);
    }

    @Override
    public void jeiPlusPlus$setCreativeTabPage(int page) {
        jeiPlusPlus$creativeTabPage = Math.max(0, page);
    }

    @Override
    public boolean jeiPlusPlus$isCreativeTabSelectorOpen() {
        return jeiPlusPlus$creativeTabSelectorOpen;
    }

    @Override
    public void jeiPlusPlus$setCreativeTabSelectorOpen(boolean open) {
        jeiPlusPlus$creativeTabSelectorOpen = open;
        CreativeTabGridCompat.setSelectorOpen(open);
    }

    @Override
    public int jeiPlusPlus$getCreativeTabSelectorPage() {
        return Math.max(0, jeiPlusPlus$creativeTabSelectorPage);
    }

    @Override
    public void jeiPlusPlus$setCreativeTabSelectorPage(int page) {
        jeiPlusPlus$creativeTabSelectorPage = Math.max(0, page);
    }

    @Override
    public void jeiPlusPlus$toggleGroup(String groupKey) {
        if (!jeiPlusPlus$expandedGroups.add(groupKey)) {
            jeiPlusPlus$expandedGroups.remove(groupKey);
        }
        jeiPlusPlus$refresh();
    }

    @Override
    public boolean jeiPlusPlus$isGroupExpanded(String key) {
        return jeiPlusPlus$expandedGroups.contains(key);
    }

    @Override
    public List<IElement<?>> jeiPlusPlus$transformElements(List<IElement<?>> elements) {
        return IngredientListFeatures.transform(this, elements);
    }

    @Unique
    private void jeiPlusPlus$refresh() {
        invalidateCache();
        notifyListenersOfChange();
    }
}
