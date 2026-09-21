package dev.bless.mixin;

import dev.bless.RmlsClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// bless-shadow-pass brief item 5: the client-side block-update hook. verified against mcapi/javap --
// ClientLevel.setBlocksDirty forwards straight to LevelExtractor.setBlockDirty (this engine's 26.2
// render-extraction replacement for the old LevelRenderer.setBlockDirty/setSectionDirty api), and it
// is the one call both ClientLevel.setBlock's own prediction path and the server-update path
// (ClientLevel.sendBlockUpdated, via Level.setBlock's generic markAndNotifyBlock chain) flow through --
// so this single hook covers both player edits and server updates, as the brief asks.
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "setBlocksDirty", at = @At("HEAD"))
	private void blessShadowBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		RmlsClient.shadowBlockChanged(pos);
	}
}
