package dev.bless;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.bless.ClientConfig.require;

/** color-only stage after world/hand rendering; main depth is deliberately not an input. */
final class WorldEffects {
	private static final String PRESET_REVISION = "cozy-color-1";
	private static final Identifier SCRATCH = Identifier.fromNamespaceAndPath("bless", "scratch");
	private static final Identifier BLOOM_A = Identifier.fromNamespaceAndPath("bless", "bloom_a");
	private static final Identifier BLOOM_B = Identifier.fromNamespaceAndPath("bless", "bloom_b");
	private static final Identifier SPILL_A = Identifier.fromNamespaceAndPath("bless", "spill_a");
	private static final Identifier SPILL_B = Identifier.fromNamespaceAndPath("bless", "spill_b");
	private final Identifier chainId;
	private final Set<Identifier> targetIds;
	private final ClientConfig config;
	private final DepthEffects depth;
	private final String version;
	private final EffectsStatus diagnostics;
	private CrossFrameResourcePool pool;
	private PostChain previousChain;
	private String state, backend, lastError;
	private Integer width, height;
	private List<Map<String, Object>> targets = List.of();
	private long executedFrames, worldHookFrames, resizeCount, reloadCount, errorCount;
	private boolean closed, writerFailureReported;

	WorldEffects(ClientConfig config, String version, DepthEffects depth) {
		this.config = config;
		this.depth = depth;
		this.version = version;
		chainId = Identifier.fromNamespaceAndPath("bless", config.mode() + (config.grain() ? "_grain" : ""));
		targetIds = config.mode().equals("bloom") ? Set.of(PostChain.MAIN_TARGET_ID, SCRATCH, BLOOM_A, BLOOM_B, SPILL_A, SPILL_B)
			: Set.of(PostChain.MAIN_TARGET_ID, SCRATCH);
		state = config.mode().equals("off") ? "disabled" : "waiting";
		diagnostics = new EffectsStatus(config.diagnosticsPath());
		diagnostics.publish(snapshot());
	}

	void render(GameRenderer renderer) {
		if (closed) return;
		try {
			RenderSystem.assertOnRenderThread();
			worldHookFrames++;
			checkWriter();
			if (depth.takeDirty()) diagnostics.publish(snapshot());
			if (depth.failed()) return;
			if (state.equals("failed")) return;
			RenderTarget main = renderer.mainRenderTarget();
			require(main.width > 0 && main.height > 0 && main.getColorTexture() != null, "world target is unavailable");
			boolean dimensionsChanged = width == null || width != main.width || height != main.height;
			if (dimensionsChanged) {
				if (width != null) resizeCount++;
				width = main.width;
				height = main.height;
				clearPool();
			}
			String observedBackend = RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT);
			if (observedBackend.contains("opengl")) observedBackend = "opengl";
			else if (observedBackend.contains("vulkan")) observedBackend = "vulkan";
			require(backend == null || backend.equals(observedBackend), "rendering backend changed during client lifetime");
			backend = observedBackend;
			if (config.mode().equals("off")) {
				if (worldHookFrames == 1 || dimensionsChanged) diagnostics.publish(snapshot());
				return;
			}
			require(main.getColorTexture().getFormat() == GpuFormat.RGBA8_UNORM, "color effects require the verified RGBA8_UNORM world target");
			// the shader manager owns reload and close; never reuse a stale chain after reload.
			PostChain chain = Minecraft.getInstance().getShaderManager().getPostChain(chainId, targetIds);
			require(chain != null, "effect post chain failed to compile: " + chainId);
			boolean chainChanged = previousChain != chain;
			if (chainChanged) {
				if (previousChain != null) reloadCount++;
				previousChain = chain;
				clearPool();
			}
			if (pool == null) pool = new CrossFrameResourcePool(3);
			FrameGraphBuilder graph = new FrameGraphBuilder();
			TargetBundle bundle = new TargetBundle();
			bundle.replace(PostChain.MAIN_TARGET_ID, graph.importExternal("bless main", main));
			bundle.replace(SCRATCH, graph.createInternal("bless color scratch", descriptor(width, height)));
			int halfWidth = (width + 1) / 2;
			int halfHeight = (height + 1) / 2;
			int quarterWidth = (width + 3) / 4;
			int quarterHeight = (height + 3) / 4;
			if (config.mode().equals("bloom")) {
				bundle.replace(BLOOM_A, graph.createInternal("bless bloom a", descriptor(halfWidth, halfHeight)));
				bundle.replace(BLOOM_B, graph.createInternal("bless bloom b", descriptor(halfWidth, halfHeight)));
				bundle.replace(SPILL_A, graph.createInternal("bless spill a", descriptor(quarterWidth, quarterHeight)));
				bundle.replace(SPILL_B, graph.createInternal("bless spill b", descriptor(quarterWidth, quarterHeight)));
			}
			chain.addToFrame(graph, width, height, bundle);
			graph.execute(pool);
			executedFrames++;
			state = "ready";
			if (executedFrames == 1 || dimensionsChanged || chainChanged) {
				targets = config.mode().equals("bloom")
					? List.of(target("main", width, height, main.useDepth), target("scratch", width, height, false),
						target("bloom_a", halfWidth, halfHeight, false), target("bloom_b", halfWidth, halfHeight, false),
						target("spill_a", quarterWidth, quarterHeight, false), target("spill_b", quarterWidth, quarterHeight, false))
					: List.of(target("main", width, height, main.useDepth), target("scratch", width, height, false));
				diagnostics.publish(snapshot());
				RmlsClient.LOGGER.info("bless ready: mode={}, backend={}, size={}x{}, executed={}, resizes={}, reloads={}",
					config.mode(), backend, width, height, executedFrames, resizeCount, reloadCount);
			}
		} catch (Throwable failure) {
			fail(failure);
		}
	}

	void endFrame() {
		if (closed) return;
		try {
			checkWriter();
			if (pool != null) pool.endFrame();
			if (depth.takeDirty()) diagnostics.publish(snapshot());
		} catch (Throwable failure) {
			fail(failure);
		}
	}

	void resize() {
		if (closed) return;
		try {
			// dimensions and counters are observed after the engine has resized the actual target.
			clearPool();
		} catch (Throwable failure) {
			fail(failure);
		}
	}

	void close() {
		if (closed) return;
		try {
			checkWriter();
			if (pool != null) pool.close();
		} catch (Throwable failure) {
			fail(failure);
		}
		pool = null;
		previousChain = null;
		closed = true;
		try {
			diagnostics.close(snapshot());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			RmlsClient.LOGGER.error("bless status shutdown interrupted", interrupted);
		} catch (Exception failure) {
			RmlsClient.LOGGER.error("bless status shutdown failed", failure);
		}
		RmlsClient.LOGGER.info("bless closed: mode={}, state={}, executed={}, world_hooks={}, errors={}",
			config.mode(), (depth.failed() || state.equals("failed")) ? "failed" : config.diagnosticOnly() ? "diagnostic" : state,
			executedFrames, worldHookFrames, errorCount + depth.errorCount());
	}

	private void clearPool() {
		if (pool != null) pool.clear();
	}

	private void checkWriter() {
		if (diagnostics.error() != null && !writerFailureReported) {
			writerFailureReported = true;
			throw new IllegalStateException("diagnostics writer failed", diagnostics.error());
		}
	}

	private void fail(Throwable failure) {
		errorCount++;
		state = "failed";
		lastError = failure.toString();
		RmlsClient.LOGGER.error("bless effect failed", failure);
		diagnostics.publish(snapshot());
	}

	private static RenderTargetDescriptor descriptor(int width, int height) {
		return new RenderTargetDescriptor(width, height, false, new Vector4f(), GpuFormat.RGBA8_UNORM);
	}

	private static Map<String, Object> target(String role, int width, int height, boolean depth) {
		return Map.of("role", role, "width", width, "height", height, "format", "RGBA8_UNORM", "depth", depth);
	}

	private Map<String, Object> snapshot() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("schema_version", 1);
		result.put("mod_id", "bless");
		result.put("mod_version", version);
		result.put("state", (depth.failed() || state.equals("failed")) ? "failed" : config.diagnosticOnly() ? "diagnostic" : state);
		result.put("closed", closed);
		result.put("mode", config.mode());
		result.put("grain", config.grain());
		RmlsTuning applied = RmlsClient.appliedTuning();
		result.put("bloom_threshold", applied != null ? applied.bloomThreshold() : config.bloomThreshold());
		result.put("bloom_strength", applied != null ? applied.bloomStrength() : config.bloomStrength());
		result.put("grade_strength", applied != null ? applied.gradeStrength() : config.gradeStrength());
		result.put("grain_strength", applied != null ? applied.grainStrength() : config.grainStrength());
		result.put("fxaa", config.fxaa());
		result.put("fxaa_strength", applied != null ? applied.fxaaStrength() : config.fxaaStrength());
		// mirrors exposure_executed_frames: the readback isn't cheap, so "is it actually running"
		// is answered by the frame count instead of sampling the shader's own output.
		result.put("fxaa_executed_frames",
			config.fxaa() && (config.mode().equals("grade") || config.mode().equals("bloom")) ? executedFrames : 0L);
		result.put("auto_exposure", config.autoExposure());
		result.put("exposure_target", applied != null ? applied.exposureTarget() : config.exposureTarget());
		result.put("exposure_min", applied != null ? applied.exposureMin() : config.exposureMin());
		result.put("exposure_max", applied != null ? applied.exposureMax() : config.exposureMax());
		result.put("exposure_speed", applied != null ? applied.exposureSpeed() : config.exposureSpeed());
		// the last exposure value read back isn't cheap (a GPU->CPU stall this stage otherwise
		// never pays); executed_frames already tells the same "is it actually running" story.
		result.put("exposure_executed_frames",
			config.autoExposure() && (config.mode().equals("grade") || config.mode().equals("bloom")) ? executedFrames : 0L);
		result.put("spill_threshold", applied != null ? applied.spillThreshold() : config.spillThreshold());
		result.put("spill_strength", applied != null ? applied.spillStrength() : config.spillStrength());
		result.put("spill_radius", applied != null ? applied.spillRadius() : config.spillRadius());
		result.put("preset_revision", config.mode().equals("grade") || config.mode().equals("bloom") ? PRESET_REVISION : null);
		result.put("bloom_input", config.mode().equals("bloom") ? "luminance and haze; no emissive mask" : "none");
		result.put("grain_clock", config.grain() ? "engine game time; 24 steps per game second" : "none");
		result.put("backend", backend);
		result.put("executed_frames", executedFrames);
		result.put("world_hook_frames", worldHookFrames);
		result.put("framebuffer_width", width);
		result.put("framebuffer_height", height);
		result.put("targets", targets);
		result.put("resize_count", resizeCount);
		result.put("reload_count", reloadCount);
		result.put("error_count", errorCount + depth.errorCount());
		result.put("last_error", lastError == null ? depth.lastError() : lastError);
		result.put("scope", "world, first-person hand and world screen effects; before ordinary GUI");
		result.put("depth_input", config.depthEnabled() ? "separate early world stage; late color stage has no depth input" : "none");
		result.put("contact_shadows", config.contactShadows());
		result.put("underwater_rays", config.underwaterRays());
		result.put("haze", config.haze());
		result.put("ambient_occlusion", config.ambientOcclusion());
		result.put("water_reflections", config.waterReflections());
		result.put("colored_light", config.coloredLight());
		result.put("sun_shadows", config.sunShadows());
		result.put("voxel_gi", config.voxelGi());
		result.put("volumetric_light", config.volumetricLight());
		result.put("sun_rays", config.sunRays());
		result.put("depth_diagnostic", config.depthDiagnostic());
		result.put("diagnostic_only", config.diagnosticOnly());
		result.put("depth", depth.snapshot());
		return result;
	}

	private static final class TargetBundle implements PostChain.TargetBundle {
		private final Map<Identifier, ResourceHandle<RenderTarget>> handles = new LinkedHashMap<>();

		@Override public void replace(Identifier id, ResourceHandle<RenderTarget> handle) {
			handles.put(id, handle);
		}

		@Override public ResourceHandle<RenderTarget> get(Identifier id) {
			return handles.get(id);
		}
	}
}
