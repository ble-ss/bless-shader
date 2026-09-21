package dev.bless.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * light-volume item 4: SpriteContents.originalImage is private with no getter -- the raw, unstitched
 * NativeImage is the only place EmitterColors can average a sprite's actual pixels (the atlas texture
 * itself is packed and mipped, not addressable per sprite). same technique offing's own accessor uses
 * (dev.offing.mixin.SpriteContentsAccessor) against the same target class, kept as our own copy since
 * mixins never cross mod packages. accessor only, no method body needed.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
	@Accessor("originalImage")
	NativeImage bless$originalImage();
}
