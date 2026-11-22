package com.lingmu0.JeiPlusPlusMod.mixin;

import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeTransfer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientContainerSyncMixin {
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void jeiPlusPlus$confirmUpgradeSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        RecipeTreeTransfer.updateSophisticatedSlot(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void jeiPlusPlus$confirmUpgradeContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        RecipeTreeTransfer.updateSophisticatedContents(packet.getContainerId());
    }
}
