package dev.bless;

import com.mojang.blaze3d.platform.InputConstants;
import dev.bless.ui.RmlsSettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class RmlsClient implements ClientModInitializer {
	static final Logger LOGGER = LoggerFactory.getLogger("bless");
	private static WorldEffects effects;
	private static Path configPath;
	private static volatile RmlsTuning appliedTuning;
	private static DepthEffects depth;

	@Override public void onInitializeClient() {
		try {
			configPath = FabricLoader.getInstance().getConfigDir().resolve("bless.json");
			var config = ClientConfig.read(configPath);
			String version = FabricLoader.getInstance().getModContainer("bless").orElseThrow()
				.getMetadata().getVersion().getFriendlyString();
			depth = new DepthEffects(config);
			effects = new WorldEffects(config, version, depth);
			// leak-repairs item 1: fabric fires no CHUNK_UNLOAD on a respawn/portal trip, so nothing
			// used to reset the voxel volume, shadow mesh or material scan when the player changed
			// level -- each kept growing stale positions (and a stale ClientLevel) across every trip.
			DepthEffects levelWatcher = depth;
			ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> levelWatcher.levelChanged());
			// live-toggle repair 2026-09-21: these three used to gate on the launch-time config alone
			// (voxel volume / material scan / shadow mesh each only registered when that config already
			// wanted them), matching the "no handler nobody asked for" rule the bench manual likes. but
			// DepthEffects.reloaded() can now turn any of these on later from the settings screen, and
			// CHUNK_LOAD/CHUNK_UNLOAD only ever fires for a chunk arriving after a handler is listening
			// -- a handler registered post-launch would silently miss every chunk already loaded before
			// that reload (reloaded()'s own feedLoadedChunks() covers that gap once, but only future
			// chunk churn keeps a tracker current). so both events register unconditionally now, one
			// handler each: DepthEffects.chunkLoaded/chunkUnloaded already no-op per tracker internally
			// (wantsMaterialScan()/config.sunShadows()), and lightChunkChanged() no-ops when volume ==
			// null, so a shelf with every depth flag off still pays nothing but the event dispatch itself.
			// never reads the chunk here beyond that -- CHUNK_LOAD handlers may only read their own
			// chunk (the bench manual's own trap).
			DepthEffects tracked = depth;
			ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> { tracked.lightChunkChanged(); tracked.chunkLoaded(chunk); });
			ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> { tracked.lightChunkChanged(); tracked.chunkUnloaded(chunk); });
			LOGGER.info("bless configured: mode={}, grain={}", config.mode(), config.grain());
			// the settings screen's own way in when mod menu is absent -- unbound by default, so it
			// never steals a key a player already relies on.
			KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bless", "bless"));
			KeyMapping openSettings = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.bless.settings", InputConstants.UNKNOWN.getValue(), category));
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				while (openSettings.consumeClick()) client.setScreenAndShow(new RmlsSettingsScreen(client.gui.screen()));
			});
		} catch (Exception failure) {
			throw new IllegalStateException("bless configuration failed", failure);
		}
	}

	/**
	 * re-read fresh from disk; called from the post-chain uniforms mixin while the shader
	 * manager rebuilds its compilation cache, which happens on every resource reload
	 * (F3+T included). this is how the four knobs apply without a client restart.
	 */
	public static RmlsTuning currentTuning() {
		try {
			ClientConfig config = ClientConfig.read(configPath);
			return new RmlsTuning(config.bloomThreshold(), config.bloomStrength(), config.gradeStrength(), config.grainStrength(),
				config.fxaa(), config.fxaaStrength(),
				config.autoExposure(), config.exposureTarget(), config.exposureMin(), config.exposureMax(), config.exposureSpeed(),
				config.spillThreshold(), config.spillStrength(), config.spillRadius());
		} catch (IOException failure) {
			throw new IllegalStateException("bless configuration failed", failure);
		}
	}

	/**
	 * re-read fresh from disk; called from DepthEffects.reloaded() on the same ShaderManager.apply()
	 * event the post-chain uniforms mixin rides for the colour knobs. the depth stage has no
	 * PostChainConfig pass to substitute into (its passes are hand-built RenderPipelines whose
	 * uniforms DepthPass uploads itself, every frame), so this is read once per reload and cached,
	 * then carried into that per-frame upload -- same trigger, different transport.
	 */
	static RmlsDepthTuning currentDepthTuning() {
		try {
			ClientConfig config = ClientConfig.read(configPath);
			return new RmlsDepthTuning(config.contactStrength(), config.contactReach(), config.contactSteps(), config.raysStrength(),
				config.hazeDistanceResolved(), config.hazeStrength(), config.hazeColorR(), config.hazeColorG(), config.hazeColorB(),
				config.hazeTint(), config.hazeNight(), config.aoSamples(), config.aoRadius(), config.aoStrength(),
				config.reflectionStrength(), config.glintStrength(), config.lightStrength(), config.lightTint(), config.metalStrength(), config.glassStrength(),
				config.shadowStrength(), config.shadowSpan(), config.shadowResolution(), config.glassTintStrength(),
				config.giStrength(), config.giRays(), config.giDistance(), config.giSky(), config.giBounces(),
				config.giScale(), config.giCheckerboard(), config.giEmissive(),
				config.volumeStrength(), config.volumeDensity(), config.volumeSteps(), config.volumeDistance(), config.volumeGlow(), config.sunRaysStrength(),
				config.wetStrength());
		} catch (IOException failure) {
			throw new IllegalStateException("bless configuration failed", failure);
		}
	}

	/** records what the last chain build actually applied, for EffectsStatus diagnostics. */
	public static void recordAppliedTuning(RmlsTuning tuning) {
		appliedTuning = tuning;
	}

	public static RmlsTuning appliedTuning() {
		return appliedTuning;
	}

	/** the settings screen's one way in to the file both the reader and the writer share. */
	public static Path configPath() {
		return configPath;
	}

	public static void renderWorld(GameRenderer renderer) {
		if (effects != null) effects.render(renderer);
	}

	public static void endFrame() {
		if (depth != null) depth.endFrame();
		if (effects != null) effects.endFrame();
	}

	public static void resize() {
		if (depth != null) depth.resize();
		if (effects != null) effects.resize();
	}

	public static void close() {
		if (depth != null) depth.close();
		if (effects != null) effects.close();
	}

	// the client-side block-update hook (bless-shadow-pass brief item 5), fired from a mixin on
	// ClientLevel.setBlocksDirty -- see DepthEffects.blockChanged's own doc comment for why that
	// method covers both player edits and server updates. no-ops internally when sun_shadows is off.
	public static void shadowBlockChanged(BlockPos pos) { if (depth != null) depth.blockChanged(pos); }

	public static void beginDepthLevel() { if (depth != null) depth.beginLevel(); }
	public static Matrix4f captureDepthProjection(Matrix4f matrix) {
		return depth == null ? matrix : depth.captureProjection(matrix);
	}
	public static void renderDepth(GameRenderer renderer) { if (depth != null) depth.render(renderer); }
	public static void depthReloaded() { if (depth != null) depth.reloaded(); }
}
