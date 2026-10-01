package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat;
import mezz.jei.common.input.IUserInputHandler;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.input.IDraggableIngredientInternal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/**
 * Adds JEI++ creative-tab integration to JEI's ingredient grid.
 */
@Pseudo
@Mixin(
        targets = "mezz.jei.gui.overlay.ingredients.IngredientGridWithNavigation",
        remap = false
)
public abstract class IngredientGridWithNavigationMixin {
    @ModifyVariable(
            method = "updateBounds",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false
    )
    private ImmutableRect2i jeiPlusPlus$reserveCreativeTabRow(
            ImmutableRect2i availableArea
    ) {
        return CreativeTabGridCompat.reserveRow(this, availableArea);
    }

    @Inject(
            method = "draw",
            at = @At("TAIL"),
            remap = false
    )
    private void jeiPlusPlus$drawCreativeTabs(
            Minecraft minecraft,
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTicks,
            CallbackInfo ci
    ) {
        CreativeTabGridCompat.draw(this, graphics, mouseX, mouseY);
    }

    @Inject(
            method = "drawTooltips",
            at = @At("TAIL"),
            remap = false
    )
    private void jeiPlusPlus$drawCreativeTabTooltip(
            Minecraft minecraft,
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        CreativeTabGridCompat.drawTooltip(this, graphics, mouseX, mouseY);
    }

    @Inject(
            method = "drawTooltips",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void jeiPlusPlus$hideUnderlyingTooltipsWhileSelectorOpen(
            Minecraft minecraft,
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        if (CreativeTabGridCompat.isSelectorOpen(this)) {
            CreativeTabGridCompat.drawTooltip(this, graphics, mouseX, mouseY);
            ci.cancel();
        }
    }

    @Inject(
            method = "createInputHandler",
            at = @At("RETURN"),
            cancellable = true,
            remap = false
    )
    private void jeiPlusPlus$wrapCreativeTabInput(
            CallbackInfoReturnable<IUserInputHandler> cir
    ) {
        cir.setReturnValue(
                CreativeTabGridCompat.wrapInput(this, cir.getReturnValue())
        );
    }

    @Inject(
            method = "getIngredientUnderMouse",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void jeiPlusPlus$blockIngredientLookupWhileSelectorOpen(
            double mouseX,
            double mouseY,
            CallbackInfoReturnable<Stream<IClickableIngredientInternal<?>>> cir
    ) {
        if (CreativeTabGridCompat.isSelectorOpen(this)) {
            cir.setReturnValue(Stream.empty());
        }
    }

    @Inject(
            method = "getDraggableIngredientUnderMouse",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void jeiPlusPlus$blockIngredientDragWhileSelectorOpen(
            double mouseX,
            double mouseY,
            CallbackInfoReturnable<Stream<IDraggableIngredientInternal<?>>> cir
    ) {
        if (CreativeTabGridCompat.isSelectorOpen(this)) {
            cir.setReturnValue(Stream.empty());
        }
    }
}
