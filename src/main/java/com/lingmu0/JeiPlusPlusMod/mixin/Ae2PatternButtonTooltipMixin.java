package com.lingmu0.JeiPlusPlusMod.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extend AE2's own encode-button tooltip without replacing its normal text. */
@Pseudo
@Mixin(targets = "appeng.client.gui.WidgetContainer", remap = false)
public abstract class Ae2PatternButtonTooltipMixin {
    @Inject(method = "add(Ljava/lang/String;Lnet/minecraft/client/gui/components/AbstractWidget;)V", at = @At("HEAD"), remap = false, require = 0)
    private void jeiPlusPlus$describePatternModifiers(String id, AbstractWidget widget, CallbackInfo ci) {
        if (!"encodePattern".equals(id))
            return;
        widget.setMessage(widget.getMessage().copy()
                .append(Component.literal("\n"))
                .append(Component.translatable("jei_plus_plus.ae2.shift_hint"))
                .append(Component.literal("\n"))
                .append(Component.translatable("jei_plus_plus.ae2.ctrl_hint")));
    }
}
