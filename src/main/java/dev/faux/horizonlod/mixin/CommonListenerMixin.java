package dev.faux.horizonlod.mixin;

import dev.faux.horizonlod.HorizonLod;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client's brand while it configures (vanilla reads and drops it). */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class CommonListenerMixin {
	@Inject(method = "handleCustomPayload", at = @At("HEAD"))
	private void horizonLod$brand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		if ((Object) this instanceof ServerConfigurationPacketListenerImpl && packet.payload() instanceof BrandPayload brand) {
			HorizonLod.clientBrand(this, brand.brand());
		}
	}
}
