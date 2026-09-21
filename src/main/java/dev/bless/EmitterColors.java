package dev.bless;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bless.mixin.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * light-volume item 4: every glowing block's colour, for the cpu flood fill in LightVolume. the same
 * model-quad-sprite-average technique offing's ColumnCapture uses for its own per-BlockState colours
 * (read that file first) -- collectParts, the up-facing quad preferred, sprite average via the raw
 * originalImage (ABGR, alpha-weighted, transparent pixels skipped).
 *
 * torches and lanterns are dominated in their own sprite average by the stick or the frame (mostly
 * grey/brown), so the average alone reads muddy; every emitter's colour gets a uniform saturation
 * push toward the hue that IS there -- sea lantern and glowstone are already saturated enough that
 * the push does not change how they read.
 */
final class EmitterColors {
	private static final long MODEL_SEED = 42L;
	private static final Map<BlockState, Integer> CACHE = new ConcurrentHashMap<>();

	// item 5: fluids and a few textureless emitters have no baked block-model quads at all, so
	// averageModelColor finds nothing for them -- a small fixed table by block stands in instead.
	// these are literal target colours (rori's numbers), so they skip boostSaturation on the way out.
	private static final Map<Block, Integer> FALLBACK_COLORS = Map.ofEntries(
		Map.entry(Blocks.LAVA, rgb(255, 110, 20)),
		Map.entry(Blocks.FIRE, rgb(255, 150, 60)),
		Map.entry(Blocks.SOUL_FIRE, rgb(80, 200, 255)),
		Map.entry(Blocks.MAGMA_BLOCK, rgb(200, 70, 20)),
		Map.entry(Blocks.GLOWSTONE, rgb(255, 200, 120)),
		Map.entry(Blocks.SHROOMLIGHT, rgb(255, 170, 90)),
		Map.entry(Blocks.OCHRE_FROGLIGHT, rgb(255, 200, 90)),
		Map.entry(Blocks.VERDANT_FROGLIGHT, rgb(120, 255, 140)),
		Map.entry(Blocks.PEARLESCENT_FROGLIGHT, rgb(230, 190, 255)),
		Map.entry(Blocks.SEA_LANTERN, rgb(170, 230, 230)));

	private EmitterColors() {}

	private static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }

	/** packed 0xRRGGBB, saturation-boosted; 0x808080 (neutral grey) for a state that fails to resolve. */
	static int colorFor(BlockState state) {
		return CACHE.computeIfAbsent(state, EmitterColors::compute);
	}

	private static int compute(BlockState state) {
		try {
			Integer modelColor = averageModelColor(state);
			if (modelColor != null) return boostSaturation(modelColor);
		} catch (Throwable failure) {
			// fall through to the table below
		}
		Integer fallback = FALLBACK_COLORS.get(state.getBlock());
		return fallback != null ? fallback : 0x808080;
	}

	/** null when the model has no quads at all (fluids, a few textureless states) -- see FALLBACK_COLORS. */
	private static Integer averageModelColor(BlockState state) {
		BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(MODEL_SEED), parts);
		BakedQuad quad = firstQuad(parts, Direction.UP);
		if (quad == null) {
			for (Direction direction : Direction.values()) {
				if (direction == Direction.UP) continue;
				quad = firstQuad(parts, direction);
				if (quad != null) break;
			}
		}
		if (quad == null) quad = firstQuad(parts, null);
		return quad == null ? null : averageSprite(quad.materialInfo().sprite());
	}

	private static BakedQuad firstQuad(List<BlockStateModelPart> parts, Direction direction) {
		for (BlockStateModelPart part : parts) {
			List<BakedQuad> quads = part.getQuads(direction);
			if (!quads.isEmpty()) return quads.get(0);
		}
		return null;
	}

	// alpha-weighted average of a sprite's frame-0 pixels. NativeImage.getPixel calls the raw
	// getPixelABGR then ARGB.fromABGR (bytecode-verified: tools/mcapi NativeImage -c), so the
	// value coming back here is already ARGB (A at bits 24-31, R at 16-23, G at 8-15, B at 0-7)
	// -- the old code read it as if it were still ABGR and had R/B swapped, turning every warm
	// emitter (sea lantern's yellow, torch's orange) teal-and-purple.
	private static int averageSprite(TextureAtlasSprite sprite) {
		SpriteContents contents = sprite.contents();
		NativeImage image = ((SpriteContentsAccessor) (Object) contents).bless$originalImage();
		if (image == null) return 0x808080;
		int width = contents.width(), height = contents.height();
		long sumR = 0, sumG = 0, sumB = 0, sumA = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int argb;
				try {
					argb = image.getPixel(x, y);
				} catch (Throwable failure) {
					continue;
				}
				int a = (argb >>> 24) & 0xFF;
				if (a == 0) continue;
				int r = (argb >>> 16) & 0xFF;
				int g = (argb >>> 8) & 0xFF;
				int b = argb & 0xFF;
				sumR += (long) r * a;
				sumG += (long) g * a;
				sumB += (long) b * a;
				sumA += a;
			}
		}
		if (sumA == 0) return 0x808080;
		int avgR = (int) (sumR / sumA), avgG = (int) (sumG / sumA), avgB = (int) (sumB / sumA);
		return (avgR << 16) | (avgG << 8) | avgB;
	}

	/** push hsv saturation halfway toward 1.0, value/hue unchanged. */
	private static int boostSaturation(int rgb) {
		float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		float value = max;
		float saturation = max <= 0f ? 0f : delta / max;
		float hue;
		if (delta <= 0.00001f) hue = 0f;
		else if (max == r) hue = 60f * (((g - b) / delta) % 6f);
		else if (max == g) hue = 60f * (((b - r) / delta) + 2f);
		else hue = 60f * (((r - g) / delta) + 4f);
		if (hue < 0f) hue += 360f;
		float boosted = saturation + (1f - saturation) * 0.5f;
		float c = value * boosted;
		float x = c * (1f - Math.abs((hue / 60f) % 2f - 1f));
		float m = value - c;
		float r2, g2, b2;
		if (hue < 60f) { r2 = c; g2 = x; b2 = 0f; }
		else if (hue < 120f) { r2 = x; g2 = c; b2 = 0f; }
		else if (hue < 180f) { r2 = 0f; g2 = c; b2 = x; }
		else if (hue < 240f) { r2 = 0f; g2 = x; b2 = c; }
		else if (hue < 300f) { r2 = x; g2 = 0f; b2 = c; }
		else { r2 = c; g2 = 0f; b2 = x; }
		int outR = clamp255(Math.round((r2 + m) * 255f));
		int outG = clamp255(Math.round((g2 + m) * 255f));
		int outB = clamp255(Math.round((b2 + m) * 255f));
		return (outR << 16) | (outG << 8) | outB;
	}

	private static int clamp255(int value) { return Math.max(0, Math.min(255, value)); }
}
