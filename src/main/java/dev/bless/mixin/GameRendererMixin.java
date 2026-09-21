package dev.bless.mixin;

import dev.bless.RmlsClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.joml.Matrix4f;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void blessBeginDepthLevel(DeltaTracker deltaTracker, CallbackInfo ci) { RmlsClient.beginDepthLevel(); }

	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), index = 0)
	private Matrix4f blessCaptureDepthProjection(Matrix4f projection) { return RmlsClient.captureDepthProjection(projection); }

	// world depth is cleared for the hand immediately after this boundary.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", shift = At.Shift.AFTER))
	private void blessDepthEffects(DeltaTracker deltaTracker, CallbackInfo ci) { RmlsClient.renderDepth((GameRenderer) (Object) this); }

	// this follows hand, screen effects, entity outlines and vanilla postprocessing, before GUI rendering.
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void blessWorldEffects(DeltaTracker deltaTracker, boolean renderWorld, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.isGameLoadFinished() && renderWorld && client.level != null) {
			RmlsClient.renderWorld((GameRenderer) (Object) this);
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void blessEndFrame(DeltaTracker deltaTracker, boolean renderWorld, CallbackInfo ci) {
		RmlsClient.endFrame();
	}

	@Inject(method = "resize", at = @At("HEAD"))
	private void blessResize(int width, int height, CallbackInfo ci) {
		RmlsClient.resize();
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void blessClose(CallbackInfo ci) {
		RmlsClient.close();
	}
}
