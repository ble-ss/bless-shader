package dev.bless.mixin;

import dev.bless.RmlsClient;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
	// typed overload only; the Object bridge delegates to it and must not double-count reloads.
	@Inject(method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("RETURN"))
	private void blessDepthReloaded(CallbackInfo ci) { RmlsClient.depthReloaded(); }
}
