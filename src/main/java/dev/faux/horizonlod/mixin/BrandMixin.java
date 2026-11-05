package dev.faux.horizonlod.mixin;

import dev.faux.horizonlod.HorizonLod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The server's brand is exactly {@code "horizon"}: Horizon clients only say they are Horizon (and
 * so only get the far-terrain Offer) to a server with that brand. Every client's F3 shows it.
 */
@Mixin(MinecraftServer.class)
abstract class BrandMixin {
	@Inject(method = "getServerModName", at = @At("RETURN"), cancellable = true)
	private void horizonLod$brand(CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(HorizonLod.BRAND);
	}
}
