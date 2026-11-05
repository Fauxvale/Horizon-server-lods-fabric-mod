package dev.faux.horizonlod.mixin;

import dev.faux.horizonlod.HorizonLod;
import dev.faux.horizonlod.LodPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Horizon's handshake (Horizon's lan/conn.rs): the client answers the server's {@code "horizon"}
 * brand with its own {@code "horizon"} brand ({@link CommonListenerMixin} keeps it); once it has
 * replied to {@code SelectKnownPacks}, which it sends after that brand, a client whose last brand
 * is {@code "horizon"} gets the far-terrain Offer on {@code horizon:lod}.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
abstract class ConfigurationMixin {
	@Unique
	private volatile boolean horizonLod$offered;

	@Inject(method = "handleSelectKnownPacks", at = @At("HEAD"))
	private void horizonLod$offer(ServerboundSelectKnownPacks packet, CallbackInfo ci) {
		if (horizonLod$offered || !HorizonLod.isHorizon(this)) return;
		byte[] offer = HorizonLod.offer();
		if (offer == null) return;
		horizonLod$offered = true;
		((ServerCommonPacketListenerImpl) (Object) this).send(new ClientboundCustomPayloadPacket(new LodPayload(offer)));
	}
}
