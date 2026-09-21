package dev.bless;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static dev.bless.ClientConfig.require;

/** optional world-depth owner. the late color chain and its resources remain separate. */
final class DepthEffects {
	private final ClientConfig config;
	private final DepthFrameInputs inputs = new DepthFrameInputs();
	private volatile RmlsDepthTuning tuning;
	private final Map<String, Long> skips = new LinkedHashMap<>();
	private final AtomicReference<Throwable> diagnosticFailure = new AtomicReference<>();
	private CrossFrameResourcePool pool;
	private RenderTarget diagnosticTarget;
	private DepthFrameInputs.Frame lastFrame;
	private Map<String, Object> firstExecution, diagnosticCapture;
	private List<Map<String, Object>> targets = List.of();
	private String state, backend, lastError;
	private Integer width, height;
	private long begun, captures, earlyFrames, contactFrames, raysFrames, hazeFrames, aoFrames, reflectionsFrames, lightFrames, shadowFrames, giFrames, volumetricFrames, sunRaysFrames, diagnosticFrames, executedFrames;
	private long resizeCount, reloadCount, shaderEpoch, errorCount;
	// leak-repairs item 1: bumped every AFTER_CLIENT_LEVEL_CHANGE, so the status json can show whether
	// a heap climb tracks portal trips.
	private long levelChanges;
	private long lastInputFrame, lastInputShaderEpoch;
	// wetness (bless-wet brief item 2): a 0..1 float this class alone advances, from the rain level
	// and this frame's own dt -- lastWetNanos is 0 until the first frame runs, so that first frame
	// contributes no rise/fall (an unknown dt is not a free jump).
	private float wetValue;
	private long lastWetNanos;
	// the pre-effect main colour copy a reflections resolve pass reads instead of the target it
	// writes. owned here, sized to the main target, rebuilt whenever that size changes -- never
	// pooled through CrossFrameResourcePool since a resolve pass's own executes() lambda writes it
	// (see runReflectionsResolve's doc comment on why the copy rides inside that lambda).
	// grass-glint brief (2026-09-20): this used to sit alongside a second copy, the pre-translucent
	// depth captureOpaqueDepth kept from LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN, so
	// water_mask.fsh could guess "this pixel is translucent" from a depth gap against it. that copy
	// disagreed with the frame often enough on rori's modded instance to put a sun glint on grass,
	// so it is gone -- water_mask.fsh now reads MetalMaskScan's own water-surface geometry instead
	// of guessing. opaqueDepthCopies stays as a field-less constant in snapshot() (item 2 of that
	// brief: the bench reads the key, nothing reads it as positive).
	private GpuTexture reflectionScratchTexture;
	private GpuTextureView reflectionScratchView;
	private int reflectionScratchWidth, reflectionScratchHeight;
	// light-volume item 5: its own pre-effect main colour scratch, same shape as reflectionScratchTexture
	// above but copied every frame the light stage runs rather than only when reflections are enabled --
	// the light pass sits earlier in the chain (after ao, before reflections) so it cannot share that one.
	private GpuTexture lightScratchTexture;
	private GpuTextureView lightScratchView;
	private int lightScratchWidth, lightScratchHeight;
	private VoxelVolume volume;
	private int diagnosticSettledFrames;
	private boolean closed, dirty = true, levelOpen, diagnosticRequested, diagnosticNotified;
	private volatile boolean diagnosticSaved;
	private volatile String diagnosticHash;
	private volatile Thread diagnosticWriter;
	// the material mask (brief item 2/3): per-chunk tag scan, and the vertex buffer built from its
	// nearest-4096 query. the scan's own round-robin resume (metalScan.refresh) still runs every
	// frame metal reflections are on -- that is its own 0.5 ms budget, see MetalMaskScan's doc
	// comment -- but the nearest-4096 query and the buffer rebuild it feeds now run only when
	// metalScan.revision() changed (a chunk's reflective positions changed) or the camera moved more
	// than 16 blocks since the buffer now held was built (lastMetalScanRevision/lastMetalCameraPos
	// below); the perf pass that closed this out is in updateMetalGeometry's own doc comment.
	private final MetalMaskScan metalScan = new MetalMaskScan();
	private GpuBuffer metalVertexBuffer;
	private int metalVertexCount;
	private long metalExecutedFrames, metalBlocksDrawnTotal;
	// grass-glint brief item 1/4: the same scan/rebuild machinery, second buffer -- flat quads
	// (WATER_QUAD_OFFSETS) instead of cubes, gated on config.waterReflections() rather than
	// metal_reflections. rebuilt on the same cache trigger as the metal buffer (updateMetalGeometry
	// shares lastMetalScanRevision/lastMetalCameraPos across both -- one scan feeds both masks).
	private GpuBuffer waterMaskVertexBuffer;
	private int waterMaskVertexCount;
	private long waterBlocksDrawnTotal;
	// glass brief item 4: a third buffer, same cache trigger, cubes (reused CUBE_OFFSETS) like metal's
	// rather than flat quads like water's -- gated on config.glassReflections() rather than either.
	private GpuBuffer glassMaskVertexBuffer;
	private int glassMaskVertexCount;
	private long glassBlocksDrawnTotal;
	// glass-light brief item 3: a fourth buffer off the same scan/query, coloured (position + rgba per
	// vertex, DepthPipelines.SHADOW_TINT's own format) rather than plain cubes -- feeds the shadow-tint
	// draw, not the material mask, so it is built and closed independently of the three above.
	private GpuBuffer glassTintVertexBuffer;
	private int glassTintVertexCount;
	// panes brief item 3: a fifth and sixth buffer, same cache trigger as the glass buffers above --
	// a pane mask buffer (drawn into the same metal_mask target as glass's own cubes, gated on
	// glass_reflections) and a pane tint buffer (drawn after the block tint cubes, gated on
	// glass_light) -- both built from a pane's own collision-shape boxes rather than a full cube,
	// since a pane is thin geometry (see rebuildPaneVertexBuffers' own doc comment).
	private GpuBuffer paneMaskVertexBuffer;
	private int paneMaskVertexCount;
	private int paneMaskBlockCount;
	private long paneBlocksDrawnTotal;
	private GpuBuffer paneTintVertexBuffer;
	private int paneTintVertexCount;
	// repair 2026-09-21 item 11: boxes past PANE_BOX_CAP a rebuild had to drop, so an undercounted
	// pane never reads as a silent zero in status.
	private volatile int paneBoxesDropped;
	// item 3's cache: the scan's own revision plus the camera position last used to build
	// metalVertexBuffer -- a rebuild only happens when the scan changed a chunk's positions or the
	// camera moved more than 16 blocks, not every frame reflections run.
	private long lastMetalScanRevision = -1;
	private net.minecraft.world.phys.Vec3 lastMetalCameraPos = net.minecraft.world.phys.Vec3.ZERO;
	private boolean metalGeometryValid;
	// item 2: a village scan bumps metalScan's revision every rescan, which used to force a rebuild
	// every time it changed -- floor rebuilds to at most one per METAL_REBUILD_MIN_INTERVAL_MILLIS
	// regardless of how often the revision or camera trigger fires; the very first build (before
	// metalGeometryValid) is exempt so startup is not delayed by this floor.
	private static final long METAL_REBUILD_MIN_INTERVAL_MILLIS = 250L;
	private long lastMetalRebuildMillis = -1;
	// item 4: wall-clock cost of the per-frame metal path (the scan's own budgeted resume plus,
	// on a cache miss, the nearest-4096 query and vertex buffer rebuild) -- last and peak.
	private volatile long lastMetalTickMicros, maxMetalTickMicros;
	// item 4 (flight-recorder follow-up): the *_max counters are honest but a single chunk-load-burst
	// frame dominates them forever after -- p95 over a rolling window says whether that spike was one
	// join-time frame or the steady state. a plain ring buffer, sorted fresh at snapshot() time
	// (snapshot is a diagnostics read, not a hot path) rather than kept sorted on every tick.
	private static final int TICK_RING_SIZE = 256;
	private final long[] metalTickRing = new long[TICK_RING_SIZE];
	private int metalTickRingPos, metalTickRingFilled;
	// sun shadows (bless-shadow-pass brief): ShadowMesh owns the block geometry (another seat's own
	// file, this seat only calls its frozen api); the persistent shadow map is owned here, same
	// shape as reflectionScratchTexture/lightScratchTexture above -- a cross-frame resource the framegraph
	// never allocates. redrawn only per item 3's threshold (sun moved, camera moved, or a new
	// section landed -- verticesTotal() changing stands in for "a new section uploaded" since
	// ShadowMesh's api exposes no dirty flag of its own).
	private ShadowMesh shadowMesh;
	private GpuTexture shadowMapTexture;
	private GpuTextureView shadowMapView;
	// a colour attachment the pass never writes: the common encoder validates the first colour
	// attachment's view before opening any pass, and an unused slot is null there (first proof: npe).
	private GpuTexture shadowColorTexture;
	private GpuTextureView shadowColorView;
	private int shadowMapResolution;
	private boolean shadowMapDrawn;
	private boolean shadowRedrawRequested;
	// glass-light brief item 3: a second persistent texture beside the shadow map, same resolution,
	// RGBA8 -- the sun's own colour after every stained window it passed through on the way to a given
	// map texel. created/closed alongside the map above but gated on config.glassLight() separately
	// (ensureShadowTint), since it can turn on/off at a reload without the map itself resizing.
	private GpuTexture shadowTintTexture;
	private GpuTextureView shadowTintView;
	private int shadowTintResolution;
	// a 1x1 white fallback, same shape as fallbackShadowTexture below -- bound when glass_light is off
	// so ShadowResolve/GiTrace/VolumeMarch's ShadowTint sampler always has something to read and every
	// sample multiplies in as a no-op.
	private GpuTexture fallbackShadowTintTexture;
	private GpuTextureView fallbackShadowTintView;
	private long shadowTintDrawsTotal, shadowTintBlocksTotal;
	private float lastShadowSunAngle;
	private net.minecraft.world.phys.Vec3 lastShadowCameraPos = net.minecraft.world.phys.Vec3.ZERO;
	// meshRevision(), not verticesTotal(): a section gaining N vertices while another loses N would
	// leave the total unchanged but still needs a redraw (ShadowMesh's own doc comment on the field).
	private long lastShadowMeshRevision = -1;
	private long shadowMapDraws;
	// item 4: wall-clock cost of ShadowMesh.tick() itself, not the whole render() hook -- last and
	// peak, same shape as light_volume_fill_ms above.
	private volatile long lastShadowTickMicros, maxShadowTickMicros;
	private final long[] shadowTickRing = new long[TICK_RING_SIZE];
	private int shadowTickRingPos, shadowTickRingFilled;
	// vulkan-client: the bounce's temporal history -- two half-res pairs (rgba16f radiance, r32f camera
	// distance) the accumulate pass ping-pongs between, persistent across frames so the framegraph never
	// allocates them (same manual accounting as the shadow map); rebuilt on resize, invalidated on reload.
	private final GpuTexture[] giHistory = new GpuTexture[2], giHistoryDistance = new GpuTexture[2];
	private final GpuTextureView[] giHistoryView = new GpuTextureView[2], giHistoryDistanceView = new GpuTextureView[2];
	private int giHistoryWidth, giHistoryHeight, giHistoryIndex;
	private boolean giHistoryValid;
	// a 1x1 "far" depth texture bound as the shadow map when sun_shadows is off -- the shaders gate on
	// ShadowSettings.w and never read it, but a vulkan descriptor still has to point somewhere.
	private GpuTexture fallbackShadowTexture;
	private GpuTextureView fallbackShadowView;
	// wetness (bless-wet brief item 3): water_mask.fsh's own VoxelAtlas sampler needs a texture whether
	// or not the voxel volume exists (colored_light/voxel_gi/volumetric_light might all be off) -- a
	// 1x1 stand-in, same shape as the shadow fallbacks above. its content never matters: the wet
	// branch gates on VolumeOrigin.w < 0.5 (invalid volume) before ever sampling it, treating that as
	// covered, so an untouched fallback texel is never actually read into the sky-exposure decision.
	private GpuTexture fallbackVoxelAtlasTexture;
	private GpuTextureView fallbackVoxelAtlasView;

	DepthEffects(ClientConfig config) {
		this.config = config;
		this.tuning = new RmlsDepthTuning(config.contactStrength(), config.contactReach(), config.contactSteps(), config.raysStrength(),
			config.hazeDistanceResolved(), config.hazeStrength(), config.hazeColorR(), config.hazeColorG(), config.hazeColorB(),
			config.hazeTint(), config.hazeNight(), config.aoSamples(), config.aoRadius(), config.aoStrength(),
			config.reflectionStrength(), config.glintStrength(), config.lightStrength(), config.lightTint(), config.metalStrength(), config.glassStrength(),
			config.shadowStrength(), config.shadowSpan(), config.shadowResolution(), config.glassTintStrength(),
				config.giStrength(), config.giRays(), config.giDistance(), config.giSky(), config.giBounces(),
				config.giScale(), config.giCheckerboard(), config.giEmissive(),
				config.volumeStrength(), config.volumeDensity(), config.volumeSteps(), config.volumeDistance(), config.volumeGlow(), config.sunRaysStrength(),
				config.wetStrength());
		state = config.depthEnabled() ? "waiting" : "disabled";
		if (config.depthEnabled()) DepthPipelines.register();
		if (config.voxelVolumeNeeded()) volume = new VoxelVolume();
		if (config.sunShadows()) shadowMesh = new ShadowMesh(96);
	}

	// ClientChunkEvents.CHUNK_LOAD/CHUNK_UNLOAD (brief item 2), registered from RmlsClient only when
	// metal_reflections is on -- own-chunk reads only, see MetalMaskScan's doc comment. grass-glint
	// brief item 1: water_reflections now also drives this scan (it tracks water surfaces too, water
	// reflections' own material mask, not just metal's), so either config flag keeps it running.
	// glass-light brief item 3: glass_light needs the scan's own glassChunkSections independent of any
	// reflections toggle -- the sun-through-glass tint reuses MetalMaskScan.glassPositionsNear() as
	// the brief asks, but a player can want coloured sun through a window with every reflection off.
	private boolean wantsMaterialScan() { return config.metalReflections() || config.waterReflections() || config.glassReflections() || config.glassLight(); }
	void chunkLoaded(LevelChunk chunk) {
		if (wantsMaterialScan()) metalScan.onLoad(chunk);
		if (config.sunShadows() && shadowMesh != null) shadowMesh.chunkLoaded(chunk);
	}
	void chunkUnloaded(LevelChunk chunk) {
		if (wantsMaterialScan()) metalScan.onUnload(chunk);
		if (config.sunShadows() && shadowMesh != null) shadowMesh.chunkUnloaded(chunk);
	}

	// leak-repairs item 1: RmlsClient's AFTER_CLIENT_LEVEL_CHANGE hook. everything tracked below
	// belongs to the level the player just left -- CHUNK_UNLOAD never fires for a respawn/portal
	// trip, so without this the voxel volume, shadow mesh and material scan all kept stale positions
	// (and, for the voxel volume, a stale ClientLevel reference) across every trip.
	void levelChanged() {
		if (volume != null) volume.levelChanged();
		if (shadowMesh != null) shadowMesh.levelChanged();
		metalScan.levelChanged();
		levelChanges++;
		// a portal trip is not a minute of drying -- the new level starts bone dry, and its own
		// rain level (if any) rebuilds wetValue from a clean nanoTime baseline.
		wetValue = 0f;
		lastWetNanos = 0L;
	}

	// the client-side block-update hook (bless-shadow-pass brief item 5), fired from a mixin on
	// ClientLevel.setBlocksDirty -- verified against mcapi/javap: ClientLevel.setBlock's own
	// prediction path and ClientLevel.sendBlockUpdated (the server-update path) both flow through
	// Level.setBlock's generic markAndNotifyBlock chain, which is the only caller of setBlocksDirty
	// in this class (it forwards straight to LevelExtractor.setBlockDirty, this engine's 26.2
	// render-extraction replacement for the old LevelRenderer.setBlockDirty/setSectionDirty api) --
	// so this one hook covers both player edits and server updates, as the brief asks.
	void blockChanged(BlockPos pos) { if (config.sunShadows() && shadowMesh != null) shadowMesh.blockChanged(pos); }

	// one unit cube, 12 triangles (36 vertices), CCW winding viewed from outside each face -- matches
	// DepthPipelines.METAL_MASK's withCull(true) (brief item 3: cull back faces).
	private static final float[] CUBE_OFFSETS = {
		// +Z (front)
		0,0,1, 1,0,1, 1,1,1,  0,0,1, 1,1,1, 0,1,1,
		// -Z (back)
		1,0,0, 0,0,0, 0,1,0,  1,0,0, 0,1,0, 1,1,0,
		// +X (right)
		1,0,1, 1,0,0, 1,1,0,  1,0,1, 1,1,0, 1,1,1,
		// -X (left)
		0,0,0, 0,0,1, 0,1,1,  0,0,0, 0,1,1, 0,1,0,
		// +Y (top)
		0,1,1, 1,1,1, 1,1,0,  0,1,1, 1,1,0, 0,1,0,
		// -Y (bottom)
		0,0,0, 1,0,0, 1,0,1,  0,0,0, 1,0,1, 0,0,1,
	};

	// grass-glint brief item 1: one flat quad (2 triangles, 6 vertices) at y + 0.889 (8/9, a full source block) -- vanilla's
	// water surface sits a little under the full block height, and this is the "no depth-gap guess"
	// replacement for detecting a water pixel at all. same winding as CUBE_OFFSETS' own +Y face
	// (viewed from above), drawn with DepthPipelines.METAL_MASK's withCull(true) same as the cubes.
	private static final float[] WATER_QUAD_OFFSETS = {
		0,0.889f,1, 1,0.889f,1, 1,0.889f,0,  0,0.889f,1, 1,0.889f,0, 0,0.889f,0,
	};

	// item 3: the scan's cursor always resumes (its own budgeted work), but the nearest-4096 query
	// and the cube/quad vertex buffers it feeds are rebuilt only on a cache miss -- the scan reported
	// a changed chunk (revision) or the camera moved more than 16 blocks since the buffers now held
	// were built. a hit is a field read plus two cheap comparisons; a miss pays what this used to pay
	// every frame. one scan feeds both masks (MetalMaskScan.refresh walks metal and water surfaces
	// together), so metal and water buffers share this one cache trigger.
	private void updateMetalGeometry(DepthFrameInputs.Frame frame) {
		// glass-light brief item 3: frame.reflections() means "water_reflections on and not
		// underwater" (DepthFrameInputs), unrelated to glass_light -- a glassLight-only player must not
		// have the whole scan (and this buffer) shut off by that gate. the tradeoff: if BOTH glass_light
		// and a reflection toggle are on, the metal/water/glass-reflection buffers below now also keep
		// rebuilding while underwater (each still no-ops via its own config check, so nothing wrong
		// draws) instead of being force-closed the way they were before this brief -- a small wasted
		// rebuild underwater, not a visible regression.
		if (!wantsMaterialScan() || (!frame.reflections() && !config.glassLight())) {
			closeMetalVertexBuffer();
			closeWaterVertexBuffer();
			closeGlassVertexBuffer();
			closeGlassTintVertexBuffer();
			closePaneMaskVertexBuffer();
			closePaneTintVertexBuffer();
			metalGeometryValid = false;
			return;
		}
		var level = Minecraft.getInstance().level;
		if (level == null) return;
		var camera = frame.cameraPosition();
		long tickStart = System.nanoTime();
		metalScan.refresh(level, BlockPos.containing(camera), frame.gameTime());
		long revision = metalScan.revision();
		boolean cameraMoved = !metalGeometryValid || camera.distanceTo(lastMetalCameraPos) > 16.0;
		boolean revisionChanged = revision != lastMetalScanRevision;
		if (metalGeometryValid && !cameraMoved && !revisionChanged) {
			recordMetalTickMicros(tickStart);
			return; // cached buffers (or cached "nothing to draw") still good
		}
		long now = System.currentTimeMillis();
		if (metalGeometryValid && now - lastMetalRebuildMillis < METAL_REBUILD_MIN_INTERVAL_MILLIS) {
			recordMetalTickMicros(tickStart);
			return; // rate-limited: a revision/camera trigger landed inside the 250 ms floor
		}
		lastMetalRebuildMillis = now;
		lastMetalScanRevision = revision;
		lastMetalCameraPos = camera;
		metalGeometryValid = true;
		rebuildMetalVertexBuffer(camera);
		rebuildWaterVertexBuffer(camera);
		rebuildGlassVertexBuffer(camera);
		rebuildGlassTintVertexBuffer(level, camera);
		rebuildPaneVertexBuffers(level, camera);
		// repair 2026-09-21 item 8: a newly placed (or broken) window's tint geometry just changed, but
		// the shadow map itself is cached against sun angle -- without this it would not redraw, and
		// so not re-tint, until the sun next moved. ask for one redraw on the very next shadow pass.
		// a request, not a cleared drawn flag: the resolve gates on shadowMapDrawn, and clearing it
		// here skipped the resolve for a frame on every geometry rebuild (66 dark frames in a run).
		shadowRedrawRequested = true;
		recordMetalTickMicros(tickStart);
	}

	private void closeMetalVertexBuffer() {
		if (metalVertexBuffer != null) { metalVertexBuffer.close(); metalVertexBuffer = null; }
		metalVertexCount = 0;
	}

	private void closeWaterVertexBuffer() {
		if (waterMaskVertexBuffer != null) { waterMaskVertexBuffer.close(); waterMaskVertexBuffer = null; }
		waterMaskVertexCount = 0;
	}

	private void closeGlassVertexBuffer() {
		if (glassMaskVertexBuffer != null) { glassMaskVertexBuffer.close(); glassMaskVertexBuffer = null; }
		glassMaskVertexCount = 0;
	}

	private void closeGlassTintVertexBuffer() {
		if (glassTintVertexBuffer != null) { glassTintVertexBuffer.close(); glassTintVertexBuffer = null; }
		glassTintVertexCount = 0;
	}

	private void closePaneMaskVertexBuffer() {
		if (paneMaskVertexBuffer != null) { paneMaskVertexBuffer.close(); paneMaskVertexBuffer = null; }
		paneMaskVertexCount = 0;
		paneMaskBlockCount = 0;
	}

	private void closePaneTintVertexBuffer() {
		if (paneTintVertexBuffer != null) { paneTintVertexBuffer.close(); paneTintVertexBuffer = null; }
		paneTintVertexCount = 0;
	}

	private void rebuildMetalVertexBuffer(net.minecraft.world.phys.Vec3 camera) {
		closeMetalVertexBuffer();
		if (!config.metalReflections()) return;
		var positions = metalScan.positionsNear(camera, 64.0, 4096);
		if (positions.isEmpty()) return;
		ByteBuffer data = MemoryUtil.memAlloc(positions.size() * CUBE_OFFSETS.length * 4);
		try {
			for (BlockPos pos : positions)
				for (int i = 0; i < CUBE_OFFSETS.length; i += 3) {
					data.putFloat(pos.getX() + CUBE_OFFSETS[i]);
					data.putFloat(pos.getY() + CUBE_OFFSETS[i + 1]);
					data.putFloat(pos.getZ() + CUBE_OFFSETS[i + 2]);
				}
			data.flip();
			metalVertexBuffer = RenderSystem.getDevice().createBuffer(() -> "bless metal mask vertices", GpuBuffer.USAGE_VERTEX, data);
			metalVertexCount = positions.size() * 12 * 3;
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	// grass-glint brief item 1: the water-surface twin of rebuildMetalVertexBuffer above -- same
	// cache, same 64-block/4096-cap query, one flat quad per surface block instead of a cube.
	private void rebuildWaterVertexBuffer(net.minecraft.world.phys.Vec3 camera) {
		closeWaterVertexBuffer();
		if (!config.waterReflections()) return;
		// water covers whole lakes and seas: 64 blocks and 4096 quads cut an ocean off in a visible ring, so the
		// water side reaches 96 blocks and keeps 24576 quads (2.6 mb of vertices, rebuilt only on a cache miss).
		var positions = metalScan.waterPositionsNear(camera, 96.0, 24576);
		if (positions.isEmpty()) return;
		ByteBuffer data = MemoryUtil.memAlloc(positions.size() * WATER_QUAD_OFFSETS.length * 4);
		try {
			for (BlockPos pos : positions)
				for (int i = 0; i < WATER_QUAD_OFFSETS.length; i += 3) {
					data.putFloat(pos.getX() + WATER_QUAD_OFFSETS[i]);
					data.putFloat(pos.getY() + WATER_QUAD_OFFSETS[i + 1]);
					data.putFloat(pos.getZ() + WATER_QUAD_OFFSETS[i + 2]);
				}
			data.flip();
			waterMaskVertexBuffer = RenderSystem.getDevice().createBuffer(() -> "bless water mask vertices", GpuBuffer.USAGE_VERTEX, data);
			waterMaskVertexCount = positions.size() * 2 * 3; // 2 triangles, 3 vertices each
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	// glass brief item 4: the glass twin of rebuildMetalVertexBuffer -- same cache, same 64-block/4096
	// cap query, cubes (CUBE_OFFSETS reused) since a windowpane keeps its full block shape unlike a
	// water surface's flat quad.
	private void rebuildGlassVertexBuffer(net.minecraft.world.phys.Vec3 camera) {
		closeGlassVertexBuffer();
		if (!config.glassReflections()) return;
		var positions = metalScan.glassPositionsNear(camera, 64.0, 4096);
		if (positions.isEmpty()) return;
		ByteBuffer data = MemoryUtil.memAlloc(positions.size() * CUBE_OFFSETS.length * 4);
		try {
			for (BlockPos pos : positions)
				for (int i = 0; i < CUBE_OFFSETS.length; i += 3) {
					data.putFloat(pos.getX() + CUBE_OFFSETS[i]);
					data.putFloat(pos.getY() + CUBE_OFFSETS[i + 1]);
					data.putFloat(pos.getZ() + CUBE_OFFSETS[i + 2]);
				}
			data.flip();
			glassMaskVertexBuffer = RenderSystem.getDevice().createBuffer(() -> "bless glass mask vertices", GpuBuffer.USAGE_VERTEX, data);
			glassMaskVertexCount = positions.size() * 12 * 3;
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	// glass-light brief item 3: the coloured twin of rebuildGlassVertexBuffer above -- same cache, same
	// query, cubes again (a windowpane keeps its full block shape for the tint draw too), but each
	// vertex carries the block's own tint (DepthPipelines.SHADOW_TINT_VERTEX_FORMAT: position float3 +
	// colour rgba8_unorm, 16 bytes) instead of plain position. tinted_glass is excluded the same way
	// VoxelVolume.record() excludes it from FLAG_FILTER -- vanilla already blocks light through it, so
	// it draws nothing here rather than tinting with whatever grey its own MapColor happens to be.
	// gated on config.glassLight(), independent of glass_reflections.
	private void rebuildGlassTintVertexBuffer(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.phys.Vec3 camera) {
		closeGlassTintVertexBuffer();
		if (!config.glassLight()) return;
		var positions = metalScan.glassPositionsNear(camera, 64.0, 4096);
		if (positions.isEmpty()) return;
		ByteBuffer data = MemoryUtil.memAlloc(positions.size() * (CUBE_OFFSETS.length / 3) * 16);
		int written = 0;
		try {
			var cursor = new BlockPos.MutableBlockPos();
			for (BlockPos pos : positions) {
				net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
				if (!GlassBlocks.isGlass(state) || GlassBlocks.isTinted(state)) continue;
				int color;
				if (state.is(net.minecraft.world.level.block.Blocks.GLASS)) color = 0xFFFFFF;
				else {
					var map = state.getMapColor(level, cursor.set(pos));
					color = map == net.minecraft.world.level.material.MapColor.NONE ? 0xFFFFFF : map.col;
				}
				byte r = (byte) ((color >> 16) & 0xFF), g = (byte) ((color >> 8) & 0xFF), b = (byte) (color & 0xFF);
				for (int i = 0; i < CUBE_OFFSETS.length; i += 3) {
					data.putFloat(pos.getX() + CUBE_OFFSETS[i]);
					data.putFloat(pos.getY() + CUBE_OFFSETS[i + 1]);
					data.putFloat(pos.getZ() + CUBE_OFFSETS[i + 2]);
					data.put(r).put(g).put(b).put((byte) 0xFF);
					written++;
				}
			}
			if (written == 0) return;
			data.flip();
			glassTintVertexBuffer = RenderSystem.getDevice().createBuffer(() -> "bless glass tint vertices", GpuBuffer.USAGE_VERTEX, data);
			glassTintVertexCount = written;
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	// panes brief item 3: same generalized-cube face winding CUBE_OFFSETS uses (six faces, CCW viewed
	// from outside, matching DepthPipelines.METAL_MASK's withCull(true)), parameterized per box's own
	// min/max instead of the fixed 0/1 -- a pane's collision shape is not the unit cube. shared by the
	// mask and tint buffers below since both draw the same boxes.
	private static float[] paneBoxVertices(net.minecraft.world.phys.AABB box) {
		float lx = (float) box.minX, ly = (float) box.minY, lz = (float) box.minZ;
		float hx = (float) box.maxX, hy = (float) box.maxY, hz = (float) box.maxZ;
		return new float[] {
			// +Z (front)
			lx,ly,hz, hx,ly,hz, hx,hy,hz,  lx,ly,hz, hx,hy,hz, lx,hy,hz,
			// -Z (back)
			hx,ly,lz, lx,ly,lz, lx,hy,lz,  hx,ly,lz, lx,hy,lz, hx,hy,lz,
			// +X (right)
			hx,ly,hz, hx,ly,lz, hx,hy,lz,  hx,ly,hz, hx,hy,lz, hx,hy,hz,
			// -X (left)
			lx,ly,lz, lx,ly,hz, lx,hy,hz,  lx,ly,lz, lx,hy,hz, lx,hy,lz,
			// +Y (top)
			lx,hy,hz, hx,hy,hz, hx,hy,lz,  lx,hy,hz, hx,hy,lz, lx,hy,lz,
			// -Y (bottom)
			lx,ly,lz, hx,ly,lz, hx,ly,hz,  lx,ly,lz, hx,ly,hz, lx,ly,hz,
		};
	}

	// panes brief item 3: up to 6 boxes tracked per pane (the post plus up to 4 connected arms --
	// vanilla's own CrossCollisionBlock shape never exceeds this; the cap is a safety floor, not an
	// observed ceiling) -- read once here from the real BlockState.getCollisionShape/toAabbs, shared
	// by the mask build (glass_reflections) and the tint build (glass_light) below so the shape query
	// and paneTint colour lookup happen only once per position per rebuild.
	private static final int PANE_BOX_CAP = 8;

	// panes brief item 3: the pane twin of rebuildGlassVertexBuffer/rebuildGlassTintVertexBuffer --
	// same cache trigger (called only from updateMetalGeometry's cache-miss branch), same
	// 64-block/4096-position query, but geometry comes from each pane's own collision-shape boxes
	// (up to PANE_BOX_CAP) instead of a full cube, since a pane is thin geometry that a cube mask
	// would draw wrong (this brief's whole reason for existing, over the "pane get an isGlass cube"
	// shortcut).
	private void rebuildPaneVertexBuffers(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.phys.Vec3 camera) {
		closePaneMaskVertexBuffer();
		closePaneTintVertexBuffer();
		paneBoxesDropped = 0;
		if (!config.glassReflections() && !config.glassLight()) return;
		var positions = metalScan.panePositionsNear(camera, 64.0, 4096);
		if (positions.isEmpty()) return;
		var cursor = new BlockPos.MutableBlockPos();
		List<net.minecraft.world.phys.AABB> boxes = new ArrayList<>();
		List<BlockPos> owners = new ArrayList<>();
		it.unimi.dsi.fastutil.ints.IntArrayList colors = new it.unimi.dsi.fastutil.ints.IntArrayList();
		int blocksWithGeometry = 0;
		for (BlockPos pos : positions) {
			net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
			var shape = state.getCollisionShape(level, pos);
			var shapeBoxes = shape.toAabbs();
			if (shapeBoxes.isEmpty()) continue;
			int color = GlassBlocks.paneTint(state, level, cursor.set(pos));
			int taken = Math.min(shapeBoxes.size(), PANE_BOX_CAP);
			paneBoxesDropped += shapeBoxes.size() - taken;
			for (int i = 0; i < taken; i++) {
				boxes.add(shapeBoxes.get(i));
				owners.add(pos);
				colors.add(color);
			}
			blocksWithGeometry++;
		}
		if (boxes.isEmpty()) return;
		if (config.glassReflections()) buildPaneMaskBuffer(boxes, owners, blocksWithGeometry);
		if (config.glassLight()) buildPaneTintBuffer(boxes, owners, colors);
	}

	private void buildPaneMaskBuffer(List<net.minecraft.world.phys.AABB> boxes, List<BlockPos> owners, int blockCount) {
		ByteBuffer data = MemoryUtil.memAlloc(boxes.size() * 36 * 12);
		try {
			for (int i = 0; i < boxes.size(); i++) {
				float[] verts = paneBoxVertices(boxes.get(i));
				BlockPos pos = owners.get(i);
				for (int k = 0; k < verts.length; k += 3) {
					data.putFloat(pos.getX() + verts[k]);
					data.putFloat(pos.getY() + verts[k + 1]);
					data.putFloat(pos.getZ() + verts[k + 2]);
				}
			}
			data.flip();
			paneMaskVertexBuffer = RenderSystem.getDevice().createBuffer(() -> "bless pane mask vertices", GpuBuffer.USAGE_VERTEX, data);
			paneMaskVertexCount = boxes.size() * 36;
			paneMaskBlockCount = blockCount;
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private void buildPaneTintBuffer(List<net.minecraft.world.phys.AABB> boxes, List<BlockPos> owners, it.unimi.dsi.fastutil.ints.IntArrayList colors) {
		ByteBuffer data = MemoryUtil.memAlloc(boxes.size() * 36 * 16);
		try {
			for (int i = 0; i < boxes.size(); i++) {
				float[] verts = paneBoxVertices(boxes.get(i));
				BlockPos pos = owners.get(i);
				int color = colors.getInt(i);
				byte r = (byte) ((color >> 16) & 0xFF), g = (byte) ((color >> 8) & 0xFF), b = (byte) (color & 0xFF);
				for (int k = 0; k < verts.length; k += 3) {
					data.putFloat(pos.getX() + verts[k]);
					data.putFloat(pos.getY() + verts[k + 1]);
					data.putFloat(pos.getZ() + verts[k + 2]);
					data.put(r).put(g).put(b).put((byte) 0xFF);
				}
			}
			data.flip();
			paneTintVertexBuffer = RenderSystem.getDevice().createBuffer(() -> "bless pane tint vertices", GpuBuffer.USAGE_VERTEX, data);
			paneTintVertexCount = boxes.size() * 36;
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private void recordMetalTickMicros(long tickStartNanos) {
		long tickMicros = (System.nanoTime() - tickStartNanos) / 1000L;
		lastMetalTickMicros = tickMicros;
		if (tickMicros > maxMetalTickMicros) maxMetalTickMicros = tickMicros;
		metalTickRing[metalTickRingPos] = tickMicros;
		metalTickRingPos = (metalTickRingPos + 1) % TICK_RING_SIZE;
		if (metalTickRingFilled < TICK_RING_SIZE) metalTickRingFilled++;
	}

	/** copies and sorts up to TICK_RING_SIZE ints -- called only from snapshot(), a diagnostics read,
	 * never from the render loop itself. */
	private static long percentile95(long[] ring, int filled) {
		if (filled == 0) return 0;
		long[] sorted = Arrays.copyOf(ring, filled);
		Arrays.sort(sorted);
		int index = Math.min(filled - 1, (int) (filled * 0.95));
		return sorted[index];
	}

	void beginLevel() {
		if (!active()) return;
		guard(() -> {
			require(!levelOpen, "previous early depth hook was not reached");
			levelOpen = true;
			begun++;
			inputs.beginLevel();
		});
	}

	Matrix4f captureProjection(Matrix4f matrix) {
		if (active()) guard(() -> {
			require(levelOpen, "projection captured outside renderLevel");
			inputs.captureProjection(matrix);
			if (inputs.projectionValid()) captures++;
			else dirty = true;
		});
		return matrix;
	}

	void render(GameRenderer renderer) {
		if (!active()) return;
		guard(() -> {
			RenderSystem.assertOnRenderThread();
			require(levelOpen, "early depth hook outside renderLevel");
			levelOpen = false;
			// a frame whose projection could not be trusted is not an error -- the depth stage
			// just sits it out; render_level_frames still counts it via beginLevel's `begun`.
			if (!inputs.projectionValid()) return;
			earlyFrames++;
			require(captures == earlyFrames, "early depth capture/frame attribution differs");
			require(shaderEpoch > 0, "registered depth pipelines have no observed successful shader reload");
			if (volume != null) {
				var client = Minecraft.getInstance();
				var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
				// glass-light brief: cheap volatile writes read back at snapshot-start/fill time (see
				// VoxelVolume's own doc comment) -- no need to gate this on anything, it costs nothing
				// when glass_light is off (record() only reads glassLight, never glassTintStrength alone).
				volume.glassLight = config.glassLight();
				volume.glassTintStrength = config.glassTintStrength();
				if (client.level != null) volume.tick(client.level, camera.blockPos);
			}
			// sun shadows: budgets meshing work and uploads finished sections every frame the
			// feature is on, whether or not the map is actually redrawn or resolved this frame --
			// same shape as the light volume's own tick above (ShadowMesh's own doc comment).
			if (config.sunShadows() && shadowMesh != null) {
				var client = Minecraft.getInstance();
				if (client.level != null) {
					var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
					long tickStart = System.nanoTime();
					shadowMesh.tick(client.level, camera.blockPos);
					long tickMicros = (System.nanoTime() - tickStart) / 1000L;
					lastShadowTickMicros = tickMicros;
					if (tickMicros > maxShadowTickMicros) maxShadowTickMicros = tickMicros;
					shadowTickRing[shadowTickRingPos] = tickMicros;
					shadowTickRingPos = (shadowTickRingPos + 1) % TICK_RING_SIZE;
					if (shadowTickRingFilled < TICK_RING_SIZE) shadowTickRingFilled++;
				}
			}
			lastFrame = inputs.take(renderer, config.contactShadows(), config.underwaterRays(), config.haze(),
				config.ambientOcclusion(), config.waterReflections(), config.coloredLight(), config.sunShadows(),
				config.voxelGi(), config.volumetricLight(), config.sunRays());
			lastInputFrame = earlyFrames;
			lastInputShaderEpoch = shaderEpoch;
			// wetness (bless-wet brief item 2): once a frame, from this frame's rain level and its own
			// dt -- rise capped at 1/8 per second so a downpour doesn't flash the ground wet instantly,
			// fall drained over wet_dry_seconds so it takes the configured minute to dry back out.
			// rainBrightness is 1 - rainLevel (DepthFrameInputs.take); wetness off collapses the target
			// to 0 the same way "the level is null" would, since take() never runs without one.
			long wetNow = System.nanoTime();
			float wetDt = lastWetNanos == 0L ? 0f : Math.min((wetNow - lastWetNanos) / 1_000_000_000f, 1f);
			lastWetNanos = wetNow;
			float wetTarget = config.wetness() ? Math.clamp(1f - lastFrame.rainBrightness(), 0f, 1f) : 0f;
			wetValue = wetValue < wetTarget ? Math.min(wetTarget, wetValue + wetDt * 0.125f)
				: Math.max(wetTarget, wetValue - wetDt / config.wetDrySeconds());
			DepthPass.wetValue = wetValue;
			RenderTarget main = renderer.mainRenderTarget();
			observeTarget(main);
			if (config.contactShadows()) recordSkip("contact", lastFrame.contactSkip());
			if (config.underwaterRays()) recordSkip("rays", lastFrame.raysSkip());
			if (config.haze()) recordSkip("haze", lastFrame.hazeSkip());
			if (config.ambientOcclusion()) recordSkip("ao", lastFrame.aoSkip());
			if (config.waterReflections()) recordSkip("reflections", lastFrame.reflectionsSkip());
			if (config.coloredLight()) recordSkip("light", lastFrame.lightSkip());
			if (config.sunShadows()) recordSkip("shadow", lastFrame.sunShadowsSkip());
			if (config.voxelGi()) recordSkip("gi", lastFrame.giSkip());
			if (config.volumetricLight()) recordSkip("volumetric", lastFrame.volumetricSkip());
			if (config.sunRays()) recordSkip("sun_rays", lastFrame.sunRaysSkip());
			// the shadow map itself is updated ahead of the "any effect executed" gate below, since
			// shadowRunnable() (read by that gate) depends on shadowMapDrawn having already observed
			// this frame's own state -- otherwise the very first sun-shadows-only frame would never
			// draw the map at all.
			updateShadowMap(lastFrame);
			if (!config.diagnosticOnly() && !lastFrame.contact() && !lastFrame.rays() && !lastFrame.haze()
				&& !lastFrame.ao() && !lastFrame.reflections() && !lightRunnable(lastFrame) && !shadowRunnable(lastFrame)
				&& !giRunnable(lastFrame) && !volumetricRunnable(lastFrame) && !lastFrame.sunRays()) return;
			updateMetalGeometry(lastFrame);
			execute(main, lastFrame);
			executedFrames++;
			if (lastFrame.contact()) { if (contactFrames++ == 0) dirty = true; }
			if (lastFrame.rays()) { if (raysFrames++ == 0) dirty = true; }
			if (lastFrame.haze()) { if (hazeFrames++ == 0) dirty = true; }
			if (lastFrame.ao()) { if (aoFrames++ == 0) dirty = true; }
			if (lastFrame.reflections()) { if (reflectionsFrames++ == 0) dirty = true; }
			if (lightRunnable(lastFrame)) { if (lightFrames++ == 0) dirty = true; }
			if (shadowRunnable(lastFrame)) { if (shadowFrames++ == 0) dirty = true; }
			if (giRunnable(lastFrame)) { if (giFrames++ == 0) dirty = true; }
			if (volumetricRunnable(lastFrame)) { if (volumetricFrames++ == 0) dirty = true; }
			if (lastFrame.sunRays()) { if (sunRaysFrames++ == 0) dirty = true; }
			if (config.diagnosticOnly()) diagnosticFrames++;
			state = config.diagnosticOnly() ? "diagnostic" : "ready";
			if (firstExecution == null) { firstExecution = frameEvidence(lastFrame); dirty = true; }
			if (config.diagnosticOnly() && !diagnosticRequested) {
				diagnosticSettledFrames = Minecraft.getInstance().levelRenderer.hasRenderedAllSections() ? diagnosticSettledFrames + 1 : 0;
				if (diagnosticSettledFrames >= 120) captureDiagnostic(main);
			}
		});
	}

	// item 3's third rebuild trigger: a chunk inside the volume loaded or unloaded. cheap either way --
	// this only flips a flag VoxelVolume checks on its own next tick(), never touches world state here.
	void lightChunkChanged() { if (volume != null) volume.chunkChanged(); }

	// the light stage is requested and gate-open (frame.light()) but still has nothing to sample
	// until the volume's first flood fill has landed -- VoxelVolume.tick() runs every frame from the
	// moment colored_light turns on, so this is "not yet" on startup/teleport, not a steady skip.
	private boolean lightRunnable(DepthFrameInputs.Frame frame) {
		return frame.light() && volume != null && volume.sample().valid();
	}

	// the bounce and the air both sample the voxel volume, so like the light stage they wait for its
	// first fill ("not yet", not a steady skip).
	private boolean giRunnable(DepthFrameInputs.Frame frame) { return frame.gi() && volume != null && volume.sample().valid(); }
	private boolean volumetricRunnable(DepthFrameInputs.Frame frame) { return frame.volumetric() && volume != null && volume.sample().valid(); }

	// sun shadows: requested and gate-open (frame.sunShadows(), daylight/not-underwater), but still
	// has nothing to resolve against until the map has drawn at least once -- same "not yet on
	// startup" shape as lightRunnable above.
	private boolean shadowRunnable(DepthFrameInputs.Frame frame) {
		return frame.sunShadows() && shadowMesh != null && shadowMapDrawn;
	}

	// item 3: the persistent shadow map, sized off config.shadowResolution() (rebuilt if a reload
	// changed it), and redrawn only when the sun moved more than 0.25 degrees, the camera moved more
	// than 8 blocks, or ShadowMesh reports a new total vertex count (its api exposes no dirty flag
	// of its own, so a vertex-count change stands in for "a new section uploaded" -- flagged as an
	// assumed proxy in the handback). a frame with no daylight (frame.sunShadows() false) leaves
	// whatever the map already holds untouched; it is never cleared just because the sun set.
	private void updateShadowMap(DepthFrameInputs.Frame frame) {
		if (!config.sunShadows() || shadowMesh == null) return;
		int resolution = config.shadowResolution();
		if (shadowMapTexture == null || shadowMapResolution != resolution) {
			if (shadowMapTexture != null) { shadowMapView.close(); shadowMapTexture.close(); shadowColorView.close(); shadowColorTexture.close(); }
			shadowMapTexture = RenderSystem.getDevice().createTexture("bless shadow map",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, resolution, resolution, 1, 1);
			shadowMapView = RenderSystem.getDevice().createTextureView(shadowMapTexture);
			shadowColorTexture = RenderSystem.getDevice().createTexture("bless shadow colour",
				GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, resolution, resolution, 1, 1);
			shadowColorView = RenderSystem.getDevice().createTextureView(shadowColorTexture);
			shadowMapResolution = resolution;
			shadowMapDrawn = false;
			// the frozen matrix was built against the old resolution's texel snap; drop it so a
			// resolve landing before the next redraw rebuilds one rather than reprojecting through it.
			DepthPass.activeShadowMatrix = null;
			dirty = true;
		}
		ensureShadowTint(resolution);
		if (!frame.sunShadows()) return;
		double sunAngleDeltaDegrees = Math.toDegrees(Math.abs(frame.sunAngle() - lastShadowSunAngle));
		boolean redraw = !shadowMapDrawn || shadowRedrawRequested || sunAngleDeltaDegrees > 0.25
			|| frame.cameraPosition().distanceTo(lastShadowCameraPos) > 8.0
			|| shadowMesh.meshRevision() != lastShadowMeshRevision;
		if (!redraw) return;
		// drawShadowDepth freezes DepthPass.activeShadowMatrix to this frame before it draws: the map
		// and every later resolve, bounce and volume sample share that one matrix until the next redraw.
		DepthPass.drawShadowDepth(RenderSystem.getDevice().createCommandEncoder(), frame, tuning, shadowMapView, shadowColorView, resolution, shadowMesh);
		shadowMapDrawn = true;
		shadowRedrawRequested = false;
		lastShadowSunAngle = frame.sunAngle();
		lastShadowCameraPos = frame.cameraPosition();
		lastShadowMeshRevision = shadowMesh.meshRevision();
		if (shadowMapDraws++ == 0) dirty = true;
		// glass-light brief item 3: the tint draw happens right here, in the same direct-call sequence
		// as the depth draw (neither is wrapped in a framegraph pass -- see drawShadowDepth's own doc
		// comment above), reading the depth values drawShadowDepth just wrote as this pass's own
		// read-only depth-test attachment (LEQUAL, writes off -- see DepthPipelines.SHADOW_TINT). one
		// glass block at the very front of the mesh, at the same depth the mesh's own front face wrote,
		// is the only case this matters for: honest flag, not proven against a mixed glass/solid wall.
		if (config.glassLight() && shadowTintTexture != null) {
			DepthPass.drawShadowTint(RenderSystem.getDevice().createCommandEncoder(), frame, tuning, shadowTintView, shadowMapView, resolution,
				glassTintVertexBuffer == null ? null : glassTintVertexBuffer.slice(), glassTintVertexCount,
				paneTintVertexBuffer == null ? null : paneTintVertexBuffer.slice(), paneTintVertexCount);
			shadowTintDrawsTotal++;
			// repair 2026-09-21 item 12: a per-redraw count of blocks plus panes -- glass cubes alone
			// used to stand in for the whole tint draw and silently drop every pane from the count.
			shadowTintBlocksTotal = (glassTintVertexCount / 36) + (paneTintVertexCount / 36);
		}
	}

	// glass-light brief item 3: created the first time glass_light turns on, resized alongside the
	// shadow map's own resolution, closed the moment glass_light turns off -- independent of the map's
	// own create/resize block above since the two can each change without the other (a reload can flip
	// glass_light with the resolution untouched, or resize the map with glass_light already off).
	private void ensureShadowTint(int resolution) {
		if (!config.glassLight()) {
			if (shadowTintTexture != null) { shadowTintView.close(); shadowTintTexture.close(); shadowTintTexture = null; shadowTintView = null; }
			return;
		}
		if (shadowTintTexture != null && shadowTintResolution == resolution) return;
		if (shadowTintTexture != null) { shadowTintView.close(); shadowTintTexture.close(); }
		shadowTintTexture = RenderSystem.getDevice().createTexture("bless shadow tint",
			GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, resolution, resolution, 1, 1);
		shadowTintView = RenderSystem.getDevice().createTextureView(shadowTintTexture);
		shadowTintResolution = resolution;
	}

	// the shadow tint texture the resolve, the bounce and the air all sample: the real one when
	// glass_light is on, a 1x1 white stand-in otherwise -- same shape as shadowMapOrFallback below,
	// same reasoning (a vulkan descriptor always needs somewhere to point, and white multiplies as a
	// no-op through every one of the three "multiply the sun's contribution" sites).
	private GpuTextureView shadowTintOrFallback() {
		if (config.glassLight() && shadowTintTexture != null) return shadowTintView;
		if (fallbackShadowTintTexture == null) {
			// RENDER_ATTACHMENT: the engine clears a colour texture through a render pass and refuses one without it.
			fallbackShadowTintTexture = RenderSystem.getDevice().createTexture("bless shadow tint fallback",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			fallbackShadowTintView = RenderSystem.getDevice().createTextureView(fallbackShadowTintTexture);
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(fallbackShadowTintTexture, new Vector4f(1, 1, 1, 1));
		}
		return fallbackShadowTintView;
	}

	// the real voxel atlas when the volume is up (light.voxels() non-null), the 1x1 fallback
	// otherwise -- same reasoning as shadowTintOrFallback above.
	private GpuTextureView voxelAtlasOrFallback(VoxelVolume.Sample light) {
		if (light.voxels() != null) return light.voxels();
		if (fallbackVoxelAtlasTexture == null) {
			fallbackVoxelAtlasTexture = RenderSystem.getDevice().createTexture("bless voxel atlas fallback",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			fallbackVoxelAtlasView = RenderSystem.getDevice().createTextureView(fallbackVoxelAtlasTexture);
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(fallbackVoxelAtlasTexture, new Vector4f());
		}
		return fallbackVoxelAtlasView;
	}

	// the resolve (brief item 4): full-res, composited after contact and before ao (see execute()'s
	// own ordering comment). the shadow map itself is not a framegraph resource -- same "manual
	// accounting" precedent as runLightResolve's light_volume role below, for a persistent texture
	// the graph never allocates.
	private ResourceHandle<RenderTarget> runShadowResolve(FrameGraphBuilder graph, ResourceHandle<RenderTarget> input,
		ResourceHandle<RenderTarget> current, DepthFrameInputs.Frame frame, RmlsDepthTuning currentTuning, List<Map<String, Object>> newTargets) {
		GpuTextureView shadowMap = shadowMapView;
		GpuTextureView shadowTint = shadowTintOrFallback();
		int resolution = shadowMapResolution;
		var pass = graph.addPass("bless shadow_resolve");
		// only `current`: a read of the original handle too orders this pass before the contact resolve
		// while the read of `current` orders it after, and the graph reports a cycle (the gate found it).
		var output = pass.readsAndWrites(current);
		pass.executes(() -> DepthPass.drawShadowResolve(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning,
			output.get().getColorTextureView(), input.get().getDepthTextureView(), shadowMap, shadowTint));
		newTargets.add(Map.of("role", "shadow_map", "width", resolution, "height", resolution, "format", "D32_FLOAT", "depth", true));
		return output;
	}

	private void observeTarget(RenderTarget main) {
		require(main.width > 0 && main.height > 0 && main.getColorTexture() != null && main.getDepthTexture() != null,
			"early world target unavailable");
		require(main.getColorTexture().getFormat() == GpuFormat.RGBA8_UNORM && main.getDepthTexture().getFormat() == GpuFormat.D32_FLOAT,
			"unverified early color/depth format");
		boolean changed = width == null || width != main.width || height != main.height;
		if (changed) {
			if (width != null) resizeCount++;
			width = main.width;
			height = main.height;
			clearPool();
			dirty = true;
		}
		String observed = RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT);
		if (observed.contains("opengl")) observed = "opengl";
		else if (observed.contains("vulkan")) observed = "vulkan";
		require(backend == null || backend.equals(observed), "depth backend changed");
		backend = observed;
	}

	private void execute(RenderTarget main, DepthFrameInputs.Frame frame) {
		if (pool == null) pool = new CrossFrameResourcePool(3);
		RmlsDepthTuning currentTuning = tuning;
		VoxelVolume.Sample light = volume != null ? volume.sample() : VoxelVolume.Sample.NONE;
		// the temporal pass's frame-to-frame state, published for uploadScene before any pass records.
		DepthPass.frameIndex = earlyFrames;
		DepthPass.historyValid = giHistoryValid;
		var graph = new FrameGraphBuilder();
		var input = graph.importExternal("bless early world", main);
		if (config.diagnosticOnly()) {
			var pass = graph.addPass("bless DIAGNOSTIC view position");
			var output = pass.readsAndWrites(input);
			pass.executes(() -> DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light, DepthPipelines.DIAGNOSTIC,
				output.get().getColorTextureView(), output.get().getDepthTextureView(), null, false));
			targets = List.of();
		} else {
			// order is contact, then sun shadows, then ao, then light, then reflections, then haze,
			// then rays: the two local occlusion effects (contact's hard shadow line, ao's soft
			// corner darkening) both multiply the ground first, sun shadows' own long-range darkening
			// slotting between them (brief item 4: "after contact and before ao"); the light volume's
			// spill/tint reads that shaded ground next (brief item 5: "after ao and before
			// reflections"); reflections then lay a water surface's mirrored sky and glint on top
			// before the haze veil grades everything by distance, and the rays (underwater only,
			// where haze and reflections both sit out) draw on top of that.
			var newTargets = new ArrayList<Map<String, Object>>();
			var current = input;
			if (frame.contact()) current = runMaskResolve(graph, input, current, frame, currentTuning, light, 0, newTargets);
			if (shadowRunnable(frame)) current = runShadowResolve(graph, input, current, frame, currentTuning, newTargets);
			if (frame.ao()) current = runAoResolve(graph, input, current, frame, currentTuning, light, newTargets);
			// the bounce reads the shaded ground (contact, sun shadows, ao already multiplied in) and
			// rewrites the whole colour; the flood spill then adds the emitters' own direct light on top.
			if (giRunnable(frame)) current = runGiChain(graph, input, current, frame, currentTuning, light, newTargets);
			if (lightRunnable(frame)) current = runLightResolve(graph, input, current, frame, currentTuning, light, newTargets);
			if (frame.reflections()) {
				var metalMask = runMetalMaskDraw(graph, input, frame, currentTuning, newTargets);
				current = runReflectionsResolve(graph, input, current, frame, currentTuning, light, newTargets, metalMask);
			}
			if (frame.haze()) {
				var hazePass = graph.addPass("bless haze resolve");
				var output = hazePass.readsAndWrites(current);
				var worldDepth = input;
				hazePass.executes(() -> DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
					DepthPipelines.HAZE, output.get().getColorTextureView(), worldDepth.get().getDepthTextureView(), null, false));
				current = output;
			}
			// the air goes over the haze (its shafts read through the veil, not under it) and before the
			// underwater rays, which own the frames the air sits out anyway.
			if (volumetricRunnable(frame)) current = runVolumeChain(graph, input, current, frame, currentTuning, light, newTargets);
			// the sun rays go on after the air so they read over the fog, like the underwater rays do over the water.
			if (frame.sunRays()) current = runMaskResolve(graph, input, current, frame, currentTuning, light, 2, newTargets);
			if (frame.rays()) current = runMaskResolve(graph, input, current, frame, currentTuning, light, 1, newTargets);
			String previousRole = targets.isEmpty() ? "" : (String) targets.getFirst().get("role");
			String newRole = newTargets.isEmpty() ? "" : (String) newTargets.getFirst().get("role");
			if (!previousRole.equals(newRole)) dirty = true;
			targets = newTargets;
		}
		graph.execute(pool);
		// next frame reprojects into this one: projection * view rotation, and the camera it was taken from.
		DepthPass.prevViewProjection = new Matrix4f(frame.projection()).mul(frame.viewRotation());
		DepthPass.prevCameraPosition = frame.cameraPosition();
		if (giRunnable(frame)) { giHistoryValid = true; giHistoryIndex ^= 1; }
	}

	// ---- vulkan-client: the bounce ----

	private void ensureGiHistory(int width, int height) {
		if (giHistory[0] != null && giHistoryWidth == width && giHistoryHeight == height) return;
		closeGiHistory();
		for (int i = 0; i < 2; i++) {
			giHistory[i] = RenderSystem.getDevice().createTexture("bless gi history " + i,
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
			giHistoryView[i] = RenderSystem.getDevice().createTextureView(giHistory[i]);
			giHistoryDistance[i] = RenderSystem.getDevice().createTexture("bless gi history distance " + i,
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R32_FLOAT, width, height, 1, 1);
			giHistoryDistanceView[i] = RenderSystem.getDevice().createTextureView(giHistoryDistance[i]);
		}
		giHistoryWidth = width;
		giHistoryHeight = height;
		giHistoryValid = false;
	}

	private void closeGiHistory() {
		for (int i = 0; i < 2; i++) {
			if (giHistory[i] != null) { giHistoryView[i].close(); giHistory[i].close(); giHistory[i] = null; }
			if (giHistoryDistance[i] != null) { giHistoryDistanceView[i].close(); giHistoryDistance[i].close(); giHistoryDistance[i] = null; }
		}
		giHistoryValid = false;
	}

	// the shadow map the bounce and the air sample: the real one when sun shadows have drawn it, a 1x1
	// far-depth stand-in otherwise (ShadowSettings.w already tells the shaders which).
	private GpuTextureView shadowMapOrFallback() {
		if (shadowMapView != null && shadowMapDrawn) return shadowMapView;
		if (fallbackShadowTexture == null) {
			fallbackShadowTexture = RenderSystem.getDevice().createTexture("bless shadow fallback",
				// COPY_DST: the engine files clearDepthTexture under copies and refuses a depth texture without it.
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, 1, 1, 1, 1);
			fallbackShadowView = RenderSystem.getDevice().createTextureView(fallbackShadowTexture);
			RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(fallbackShadowTexture, 1.0);
		}
		return fallbackShadowView;
	}

	private GpuTextureView mainScratch(int width, int height) {
		if (lightScratchTexture == null || lightScratchWidth != width || lightScratchHeight != height) {
			if (lightScratchTexture != null) { lightScratchView.close(); lightScratchTexture.close(); }
			lightScratchTexture = RenderSystem.getDevice().createTexture("bless light scratch",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			lightScratchView = RenderSystem.getDevice().createTextureView(lightScratchTexture);
			lightScratchWidth = width;
			lightScratchHeight = height;
		}
		return lightScratchView;
	}

	// trace (half res) -> accumulate (two targets, copied out to the persistent history pair inside the
	// same executes() so ordering is by construction) -> three a-trous steps (item 2: a third step
	// widens the denoise's own reach at low light, where the trace's noise floor is otherwise a bigger
	// share of the signal than GI_HISTORY_BLEND's extra history alone can absorb) -> full-res compose
	// over a fresh copy of the current colour. every inter-pass hand-off is a tracked graph resource.
	private ResourceHandle<RenderTarget> runGiChain(FrameGraphBuilder graph, ResourceHandle<RenderTarget> input,
		ResourceHandle<RenderTarget> current, DepthFrameInputs.Frame frame, RmlsDepthTuning currentTuning, VoxelVolume.Sample light,
		List<Map<String, Object>> newTargets) {
		int width = frame.width(), height = frame.height();
		int scale = currentTuning.giScale();
		int halfWidth = (width + scale - 1) / scale, halfHeight = (height + scale - 1) / scale;
		ensureGiHistory(halfWidth, halfHeight);
		int read = giHistoryIndex, write = giHistoryIndex ^ 1;
		GpuTextureView historyIn = giHistoryView[read], historyDistanceIn = giHistoryDistanceView[read];
		GpuTexture historyOut = giHistory[write], historyDistanceOut = giHistoryDistance[write];
		GpuTextureView shadowMap = shadowMapOrFallback();
		GpuTextureView shadowTint = shadowTintOrFallback();
		GpuTextureView scratch = mainScratch(width, height);
		GpuTexture scratchTexture = lightScratchTexture;
		var hdr = new RenderTargetDescriptor(halfWidth, halfHeight, false, new Vector4f(), GpuFormat.RGBA16_FLOAT);
		var distanceDescriptor = new RenderTargetDescriptor(halfWidth, halfHeight, false, new Vector4f(), GpuFormat.R32_FLOAT);

		var tracePass = graph.addPass("bless gi_trace");
		tracePass.reads(current);
		var trace = tracePass.createsInternal("bless gi_trace", hdr);
		var geometry = tracePass.createsInternal("bless gi_geometry", hdr);
		tracePass.executes(() -> DepthPass.drawGiTrace(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			trace.get().getColorTextureView(), geometry.get().getColorTextureView(), input.get().getDepthTextureView(), shadowMap, shadowTint));

		var accumulatePass = graph.addPass("bless gi_accumulate");
		accumulatePass.reads(trace);
		var accumulated = accumulatePass.createsInternal("bless gi_accumulated", hdr);
		var distance = accumulatePass.createsInternal("bless gi_distance", distanceDescriptor);
		accumulatePass.executes(() -> {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			DepthPass.drawGiAccumulate(encoder, frame, currentTuning, light, accumulated.get().getColorTextureView(),
				distance.get().getColorTextureView(), input.get().getDepthTextureView(), trace.get().getColorTextureView(),
				historyIn, historyDistanceIn);
			encoder.copyTextureToTexture(accumulated.get().getColorTexture(), historyOut, 0, 0, 0, 0, 0, halfWidth, halfHeight);
			encoder.copyTextureToTexture(distance.get().getColorTexture(), historyDistanceOut, 0, 0, 0, 0, 0, halfWidth, halfHeight);
		});

		var blurAPass = graph.addPass("bless gi_blur_1");
		blurAPass.reads(accumulated);
		blurAPass.reads(geometry);
		var blurA = blurAPass.createsInternal("bless gi_blur_1", hdr);
		blurAPass.executes(() -> DepthPass.drawGiBlur(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			blurA.get().getColorTextureView(), input.get().getDepthTextureView(), accumulated.get().getColorTextureView(), geometry.get().getColorTextureView(), 1f));
		var blurBPass = graph.addPass("bless gi_blur_2");
		blurBPass.reads(blurA);
		blurBPass.reads(geometry);
		var blurB = blurBPass.createsInternal("bless gi_blur_2", hdr);
		blurBPass.executes(() -> DepthPass.drawGiBlur(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			blurB.get().getColorTextureView(), input.get().getDepthTextureView(), blurA.get().getColorTextureView(), geometry.get().getColorTextureView(), 2f));
		// item 2's third a-trous step: same widening pattern as blur_1 -> blur_2 (step scale doubles
		// again), reading only blur_2's own output -- every hand-off here is a tracked graph resource,
		// never a stale handle from an earlier pass.
		var blurCPass = graph.addPass("bless gi_blur_3");
		blurCPass.reads(blurB);
		blurCPass.reads(geometry);
		var blurC = blurCPass.createsInternal("bless gi_blur_3", hdr);
		blurCPass.executes(() -> DepthPass.drawGiBlur(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			blurC.get().getColorTextureView(), input.get().getDepthTextureView(), blurB.get().getColorTextureView(), geometry.get().getColorTextureView(), 4f));

		var resolvePass = graph.addPass("bless gi_resolve");
		resolvePass.reads(blurC);
		var output = resolvePass.readsAndWrites(current);
		resolvePass.executes(() -> {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(current.get().getColorTexture(), scratchTexture, 0, 0, 0, 0, 0, width, height);
			DepthPass.drawGiResolve(encoder, frame, currentTuning, light, output.get().getColorTextureView(),
				input.get().getDepthTextureView(), blurC.get().getColorTextureView(), scratch);
		});
		newTargets.add(Map.of("role", "gi_trace", "width", halfWidth, "height", halfHeight, "format", "RGBA16_FLOAT", "depth", false));
		newTargets.add(Map.of("role", "gi_history", "width", halfWidth, "height", halfHeight, "format", "RGBA16_FLOAT", "depth", false));
		return output;
	}

	// ---- vulkan-client: the air ----

	private ResourceHandle<RenderTarget> runVolumeChain(FrameGraphBuilder graph, ResourceHandle<RenderTarget> input,
		ResourceHandle<RenderTarget> current, DepthFrameInputs.Frame frame, RmlsDepthTuning currentTuning, VoxelVolume.Sample light,
		List<Map<String, Object>> newTargets) {
		int halfWidth = (frame.width() + 1) / 2, halfHeight = (frame.height() + 1) / 2;
		GpuTextureView shadowMap = shadowMapOrFallback();
		GpuTextureView shadowTint = shadowTintOrFallback();
		var marchPass = graph.addPass("bless volume_march");
		marchPass.reads(current);
		var march = marchPass.createsInternal("bless volume_march",
			new RenderTargetDescriptor(halfWidth, halfHeight, false, new Vector4f(), GpuFormat.RGBA16_FLOAT));
		marchPass.executes(() -> DepthPass.drawVolumeMarch(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			march.get().getColorTextureView(), input.get().getDepthTextureView(), shadowMap, shadowTint));
		var resolvePass = graph.addPass("bless volume_resolve");
		resolvePass.reads(march);
		var output = resolvePass.readsAndWrites(current);
		resolvePass.executes(() -> DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			DepthPipelines.VOLUME_RESOLVE, output.get().getColorTextureView(), input.get().getDepthTextureView(),
			march.get().getColorTextureView(), false));
		newTargets.add(Map.of("role", "volume_march", "width", halfWidth, "height", halfHeight, "format", "RGBA16_FLOAT", "depth", false));
		return output;
	}

	// shared by contact and rays: half/quarter-resolution mask pass then a bilinear resolve pass
	// composited onto whatever `current` already holds (haze may have run before this, for rays).
	private static ResourceHandle<RenderTarget> runMaskResolve(FrameGraphBuilder graph,
		ResourceHandle<RenderTarget> input, ResourceHandle<RenderTarget> current,
		DepthFrameInputs.Frame frame, RmlsDepthTuning currentTuning, VoxelVolume.Sample light, int mode, List<Map<String, Object>> newTargets) {
		// mode: 0 contact (half res), 1 underwater rays, 2 sun rays (both quarter res, both additive).
		boolean rays = mode != 0;
		int divisor = rays ? 4 : 2;
		int maskWidth = (frame.width() + divisor - 1) / divisor, maskHeight = (frame.height() + divisor - 1) / divisor;
		String role = mode == 0 ? "contact_mask" : mode == 1 ? "rays_mask" : "sun_rays_mask";
		var maskPass = graph.addPass("bless " + role);
		maskPass.reads(input);
		var mask = maskPass.createsInternal("bless " + role,
			new RenderTargetDescriptor(maskWidth, maskHeight, false, new Vector4f(), GpuFormat.RGBA8_UNORM));
		maskPass.executes(() -> {
			require(mask.get().width == maskWidth && mask.get().height == maskHeight && !mask.get().useDepth, "depth mask dimensions/attachment differ");
			DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
				mode == 0 ? DepthPipelines.CONTACT_MASK : mode == 1 ? DepthPipelines.RAYS_MASK : DepthPipelines.SUN_RAYS_MASK,
				mask.get().getColorTextureView(), input.get().getDepthTextureView(), null, mode);
		});
		var resolve = graph.addPass("bless depth resolve");
		resolve.reads(mask);
		var output = resolve.readsAndWrites(current);
		resolve.executes(() -> DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			rays ? DepthPipelines.RAYS_RESOLVE : DepthPipelines.CONTACT_RESOLVE,
			output.get().getColorTextureView(), input.get().getDepthTextureView(), mask.get().getColorTextureView(), mode));
		newTargets.add(Map.of("role", role, "width", maskWidth, "height", maskHeight, "format", "RGBA8_UNORM", "depth", false));
		return output;
	}

	// ao's own chain: half-resolution mask, then a separable depth-aware blur (horizontal, then
	// vertical, ping-ponging between two half-resolution targets since raw ssao is noise), then a
	// resolve pass that multiplies the composited colour by (1 - occlusion * ao_strength).
	private static ResourceHandle<RenderTarget> runAoResolve(FrameGraphBuilder graph,
		ResourceHandle<RenderTarget> input, ResourceHandle<RenderTarget> current,
		DepthFrameInputs.Frame frame, RmlsDepthTuning currentTuning, VoxelVolume.Sample light, List<Map<String, Object>> newTargets) {
		int maskWidth = (frame.width() + 1) / 2, maskHeight = (frame.height() + 1) / 2;
		var descriptor = new RenderTargetDescriptor(maskWidth, maskHeight, false, new Vector4f(), GpuFormat.RGBA8_UNORM);
		var maskPass = graph.addPass("bless ao_mask");
		maskPass.reads(input);
		var mask = maskPass.createsInternal("bless ao_mask", descriptor);
		maskPass.executes(() -> {
			require(mask.get().width == maskWidth && mask.get().height == maskHeight && !mask.get().useDepth, "ao mask dimensions/attachment differ");
			DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light, DepthPipelines.AO_MASK,
				mask.get().getColorTextureView(), input.get().getDepthTextureView(), null, false);
		});
		var blurHPass = graph.addPass("bless ao_blur_h");
		blurHPass.reads(mask);
		var blurH = blurHPass.createsInternal("bless ao_blur_h", descriptor);
		blurHPass.executes(() -> DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light, DepthPipelines.AO_BLUR_H,
			blurH.get().getColorTextureView(), input.get().getDepthTextureView(), mask.get().getColorTextureView(), false));
		var blurVPass = graph.addPass("bless ao_blur_v");
		blurVPass.reads(blurH);
		var blurV = blurVPass.createsInternal("bless ao_blur_v", descriptor);
		blurVPass.executes(() -> DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light, DepthPipelines.AO_BLUR_V,
			blurV.get().getColorTextureView(), input.get().getDepthTextureView(), blurH.get().getColorTextureView(), true));
		var resolvePass = graph.addPass("bless ao_resolve");
		resolvePass.reads(blurV);
		var output = resolvePass.readsAndWrites(current);
		resolvePass.executes(() -> DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			DepthPipelines.AO_RESOLVE, output.get().getColorTextureView(), input.get().getDepthTextureView(), blurV.get().getColorTextureView(), false));
		newTargets.add(Map.of("role", "ao_mask", "width", maskWidth, "height", maskHeight, "format", "RGBA8_UNORM", "depth", false));
		return output;
	}

	// light-volume item 5: a full-res pass, no mask stage -- reads the cpu-filled volume texture and
	// a fresh scratch copy of the pre-light main colour (composited onto `current` after contact/ao),
	// same "copy inside the one pass's executes()" trick runReflectionsResolve below uses so ordering
	// is by construction. registers its own status role since the volume texture is a persistent
	// resource the framegraph never allocates (item 6: target role light_volume, 768x576).
	private ResourceHandle<RenderTarget> runLightResolve(FrameGraphBuilder graph,
		ResourceHandle<RenderTarget> input, ResourceHandle<RenderTarget> current,
		DepthFrameInputs.Frame frame, RmlsDepthTuning currentTuning, VoxelVolume.Sample light, List<Map<String, Object>> newTargets) {
		int width = frame.width(), height = frame.height();
		GpuTextureView scratchView = mainScratch(width, height);
		GpuTexture scratch = lightScratchTexture;
		var pass = graph.addPass("bless light_resolve");
		var output = pass.readsAndWrites(current);
		pass.executes(() -> {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(current.get().getColorTexture(), scratch, 0, 0, 0, 0, 0, width, height);
			DepthPass.drawLight(encoder, frame, currentTuning, light, output.get().getColorTextureView(),
				input.get().getDepthTextureView(), light.view(), scratchView);
		});
		newTargets.add(Map.of("role", "light_volume", "width", VoxelVolume.TEXTURE_WIDTH, "height", VoxelVolume.TEXTURE_HEIGHT,
			"format", "RGBA8_UNORM", "depth", false));
		return output;
	}

	// water reflections' own chain: a half-resolution mask (screen-space raymarch against WorldDepth,
	// gated on nothing now -- see water_mask.fsh's own doc comment for why the pre-translucent depth
	// gap this used to gate on is gone) then a full-resolution resolve. instance method, not static
	// like the two chains above -- it owns reflectionScratchTexture,
	// the pre-effect colour copy a resolve pass cannot read `current` to get for itself. that copy is
	// folded into the MASK pass's own executes() lambda (not a separate framegraph pass) precisely so
	// its ordering relative to contact/ao (already composited onto `current` by this point) and the
	// resolve pass that reads it is by construction, not by hoping the graph schedules two sibling
	// passes that share no tracked resource in declaration order.
	private ResourceHandle<RenderTarget> runReflectionsResolve(FrameGraphBuilder graph,
		ResourceHandle<RenderTarget> input, ResourceHandle<RenderTarget> current,
		DepthFrameInputs.Frame frame, RmlsDepthTuning currentTuning, VoxelVolume.Sample light, List<Map<String, Object>> newTargets,
		ResourceHandle<RenderTarget> metalMask) {
		int width = frame.width(), height = frame.height();
		if (reflectionScratchTexture == null || reflectionScratchWidth != width || reflectionScratchHeight != height) {
			if (reflectionScratchTexture != null) { reflectionScratchView.close(); reflectionScratchTexture.close(); }
			reflectionScratchTexture = RenderSystem.getDevice().createTexture("bless reflection scratch",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			reflectionScratchView = RenderSystem.getDevice().createTextureView(reflectionScratchTexture);
			reflectionScratchWidth = width;
			reflectionScratchHeight = height;
		}
		GpuTexture scratch = reflectionScratchTexture;
		GpuTextureView scratchView = reflectionScratchView;
		// wetness (bless-wet brief item 3): resolved once, outside the lambda, same shape as scratch above.
		GpuTextureView voxelAtlas = voxelAtlasOrFallback(light);
		int maskWidth = (width + 1) / 2, maskHeight = (height + 1) / 2;
		var maskPass = graph.addPass("bless reflection_mask");
		// only `current`: the original `input` handle is written by the contact and ao resolves, so
		// declaring a read of it here would order this pass before them while the read of `current`
		// orders it after, and the graph reports a cycle (the final gate found it with everything on).
		// the depth texture is the same physical target either way. metalMask is also only ever read
		// here, ordering this pass after the metal_mask draw (brief item 4).
		maskPass.reads(current);
		maskPass.reads(metalMask);
		var mask = maskPass.createsInternal("bless reflection_mask",
			new RenderTargetDescriptor(maskWidth, maskHeight, false, new Vector4f(), GpuFormat.RGBA8_UNORM));
		// second target (brief item 4, "the second target, cleanest"): written by the same water_mask
		// draw call alongside reflection_mask, so it needs no ordering of its own beyond mask's.
		var kind = maskPass.createsInternal("bless reflection_kind",
			new RenderTargetDescriptor(maskWidth, maskHeight, false, new Vector4f(), GpuFormat.RGBA8_UNORM));
		maskPass.executes(() -> {
			require(mask.get().width == maskWidth && mask.get().height == maskHeight && !mask.get().useDepth, "reflection mask dimensions/attachment differ");
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			// pre-effect colour snapshot, taken here (not as its own pass) so it happens after
			// contact/ao and before this mask's own draw, guaranteed by both living in this one
			// pass's executes() lambda -- see this method's doc comment.
			encoder.copyTextureToTexture(current.get().getColorTexture(), scratch, 0, 0, 0, 0, 0, width, height);
			DepthPass.drawWaterMask(encoder, frame, currentTuning, light, mask.get().getColorTextureView(), kind.get().getColorTextureView(),
				input.get().getDepthTextureView(), scratchView, metalMask.get().getColorTextureView(), voxelAtlas);
		});
		var resolve = graph.addPass("bless reflection resolve");
		resolve.reads(mask);
		resolve.reads(kind);
		var output = resolve.readsAndWrites(current);
		resolve.executes(() -> DepthPass.drawWaterResolve(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning, light,
			output.get().getColorTextureView(), input.get().getDepthTextureView(), mask.get().getColorTextureView(),
			kind.get().getColorTextureView(), scratchView));
		newTargets.add(Map.of("role", "reflection_mask", "width", maskWidth, "height", maskHeight, "format", "RGBA8_UNORM", "depth", false));
		return output;
	}

	// the material mask draw (brief item 3, grass-glint brief item 1): full resolution, ahead of the
	// reflection mask so water_mask.fsh can read it this same frame. two channels now, drawn in the
	// same pass -- r=1 for metal (cubes), g=1 for water surfaces (flat quads) -- see
	// DepthPass.drawMetalMask's own doc comment for how the two draws share one pipeline via the
	// MaskKind uniform.
	private ResourceHandle<RenderTarget> runMetalMaskDraw(FrameGraphBuilder graph, ResourceHandle<RenderTarget> input,
		DepthFrameInputs.Frame frame, RmlsDepthTuning currentTuning, List<Map<String, Object>> newTargets) {
		int width = frame.width(), height = frame.height();
		GpuBuffer metalBuffer = metalVertexBuffer;
		int metalCount = metalVertexCount;
		GpuBuffer waterBuffer = waterMaskVertexBuffer;
		int waterCount = waterMaskVertexCount;
		GpuBuffer glassBuffer = glassMaskVertexBuffer;
		int glassCount = glassMaskVertexCount;
		GpuBuffer paneBuffer = paneMaskVertexBuffer;
		int paneCount = paneMaskVertexCount;
		int paneBlockCount = paneMaskBlockCount;
		var pass = graph.addPass("bless metal_mask");
		pass.reads(input);
		var target = pass.createsInternal("bless metal_mask",
			new RenderTargetDescriptor(width, height, false, new Vector4f(), GpuFormat.RGBA8_UNORM));
		pass.executes(() -> {
			require(target.get().width == width && target.get().height == height && !target.get().useDepth, "metal mask dimensions/attachment differ");
			DepthPass.drawMetalMask(RenderSystem.getDevice().createCommandEncoder(), frame, currentTuning,
				target.get().getColorTextureView(), input.get().getDepthTextureView(),
				metalBuffer == null ? null : metalBuffer.slice(), metalCount,
				waterBuffer == null ? null : waterBuffer.slice(), waterCount,
				glassBuffer == null ? null : glassBuffer.slice(), glassCount,
				paneBuffer == null ? null : paneBuffer.slice(), paneCount);
			// this pass itself always runs whenever reflections do (see this method's doc comment --
			// water_mask.fsh's MetalMask sampler is bound unconditionally), so the counters only
			// advance when their own feature is actually configured, matching every other depth
			// effect's "unrequested effect executed" bench contract (executed_frames stays 0 when off).
			if (config.metalReflections()) {
				if (metalExecutedFrames++ == 0) dirty = true;
				metalBlocksDrawnTotal = metalCount / 36;
			}
			if (config.waterReflections()) waterBlocksDrawnTotal = waterCount / 6;
			if (config.glassReflections()) {
				glassBlocksDrawnTotal = glassCount / 36;
				paneBlocksDrawnTotal = paneBlockCount;
			}
		});
		// gated the same as the executed-frame counter above: this pass runs (and this internal
		// target exists) whenever reflections do, but it is only reported as an active depth target
		// when metal_reflections is actually configured -- otherwise a shelf with plain water
		// reflections would show a metal_mask role it never asked for.
		if (config.metalReflections())
			newTargets.add(Map.of("role", "metal_mask", "width", width, "height", height, "format", "RGBA8_UNORM", "depth", false));
		return target;
	}

	void reloaded() {
		if (!active()) return;
		guard(() -> {
			RenderSystem.assertOnRenderThread();
			shaderEpoch++;
			if (executedFrames > 0) reloadCount++;
			clearPool();
			// same event the post-chain uniforms mixin rides for the colour knobs (ShaderManager.apply,
			// F3+T included); the depth stage has no pass to substitute into, so it re-reads here and
			// carries the result into every DepthPass.draw upload until the next reload.
			tuning = RmlsClient.currentDepthTuning();
			giHistoryValid = false;
			dirty = true;
		});
	}

	void resize() { if (active()) guard(() -> { clearPool(); giHistoryValid = false; }); }

	void endFrame() {
		if (closed || !config.depthEnabled()) return;
		guard(() -> {
			require(!levelOpen, "renderLevel did not reach the early depth hook");
			if (pool != null) pool.endFrame();
			Throwable failure = diagnosticFailure.getAndSet(null);
			if (failure != null) throw new IllegalStateException("depth diagnostic capture failed", failure);
			if (diagnosticSaved && !diagnosticNotified) { diagnosticNotified = true; dirty = true; }
		});
	}

	private void captureDiagnostic(RenderTarget main) {
		diagnosticRequested = true;
		diagnosticCapture = frameEvidence(lastFrame);
		dirty = true;
		// immutable capture target prevents any later hand/UI draw or delayed readback from changing this image.
		diagnosticTarget = new TextureTarget("bless diagnostic capture", main.width, main.height, false, GpuFormat.RGBA8_UNORM);
		DepthPass.draw(RenderSystem.getDevice().createCommandEncoder(), lastFrame, tuning, VoxelVolume.Sample.NONE, DepthPipelines.DIAGNOSTIC,
			diagnosticTarget.getColorTextureView(), main.getDepthTextureView(), null, false);
		Screenshot.takeScreenshot(diagnosticTarget, image -> {
			Thread worker = new Thread(() -> {
				try (image) {
					Path path = diagnosticPath();
					Files.createDirectories(path.getParent());
					image.writeToFile(path);
					diagnosticHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
					diagnosticSaved = true;
				} catch (Throwable failure) { diagnosticFailure.compareAndSet(null, failure); }
			}, "bless-depth-diagnostic-writer");
			diagnosticWriter = worker;
			worker.start();
		});
	}

	private Path diagnosticPath() {
		return config.diagnosticsPath().resolveSibling(config.diagnosticsPath().getFileName() + ".depth-diagnostic.png");
	}

	void close() {
		if (closed) return;
		guard(() -> {
			// every frame sat out is not a diagnostic curiosity, it is a run that measured nothing.
			if (begun > 0 && inputs.skippedInvalidProjectionFrames() == begun)
				throw new IllegalStateException("world projection was never finite");
			if (pool != null) pool.close();
			Thread writer = diagnosticWriter;
			if (writer != null) {
				try { writer.join(5000); }
				catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
				require(!writer.isAlive(), "depth diagnostic writer did not close");
			}
			if (config.diagnosticOnly()) require(diagnosticSaved && diagnosticFailure.get() == null, "diagnostic image was not completed before shutdown");
		});
		// a failed pending readback is left to device shutdown instead of destroying its source early or forcing a GPU wait.
		if (diagnosticTarget != null && diagnosticSaved) guard(diagnosticTarget::destroyBuffers);
		if (reflectionScratchTexture != null) guard(() -> { reflectionScratchView.close(); reflectionScratchTexture.close(); });
		if (lightScratchTexture != null) guard(() -> { lightScratchView.close(); lightScratchTexture.close(); });
		if (volume != null) guard(volume::close);
		if (metalVertexBuffer != null) guard(metalVertexBuffer::close);
		if (waterMaskVertexBuffer != null) guard(waterMaskVertexBuffer::close);
		if (glassMaskVertexBuffer != null) guard(glassMaskVertexBuffer::close);
		if (glassTintVertexBuffer != null) guard(glassTintVertexBuffer::close);
		if (paneMaskVertexBuffer != null) guard(paneMaskVertexBuffer::close);
		if (paneTintVertexBuffer != null) guard(paneTintVertexBuffer::close);
		if (shadowMapTexture != null) guard(() -> { shadowMapView.close(); shadowMapTexture.close(); shadowColorView.close(); shadowColorTexture.close(); });
		if (shadowTintTexture != null) guard(() -> { shadowTintView.close(); shadowTintTexture.close(); });
		if (shadowMesh != null) guard(shadowMesh::close);
		guard(this::closeGiHistory);
		if (fallbackShadowTexture != null) guard(() -> { fallbackShadowView.close(); fallbackShadowTexture.close(); });
		if (fallbackShadowTintTexture != null) guard(() -> { fallbackShadowTintView.close(); fallbackShadowTintTexture.close(); });
		if (fallbackVoxelAtlasTexture != null) guard(() -> { fallbackVoxelAtlasView.close(); fallbackVoxelAtlasTexture.close(); });
		pool = null;
		closed = true;
		dirty = true;
	}

	private void clearPool() {
		if (pool != null) pool.clear();
		targets = List.of();
	}

	private boolean active() { return config.depthEnabled() && !closed && errorCount == 0; }
	boolean failed() { return errorCount != 0; }
	long errorCount() { return errorCount; }
	String lastError() { return lastError; }
	boolean takeDirty() { boolean changed = dirty; dirty = false; return changed; }

	private void guard(Runnable action) {
		try { action.run(); }
		catch (Throwable failure) {
			if (errorCount == 0) {
				errorCount++;
				lastError = failure.toString();
				state = "failed";
				dirty = true;
				RmlsClient.LOGGER.error("bless early depth stage failed", failure);
			}
		}
	}

	// mirrors haze.fsh's own mix(HazeSettings.z, 1.0, smoothstep(0.0, 0.3, elevation)) exactly, so
	// the reported number is what the shader actually applied, not a second guess at it.
	private static float daylightFactor(float elevation, float nightFloor) {
		float t = Math.clamp((elevation - 0.0f) / 0.3f, 0.0f, 1.0f);
		t = t * t * (3.0f - 2.0f * t);
		return nightFloor + (1.0f - nightFloor) * t;
	}

	private void recordSkip(String effect, String reason) {
		if (reason.equals("none")) return;
		String key = effect + ":" + reason;
		if (!skips.containsKey(key)) dirty = true;
		skips.merge(key, 1L, Long::sum);
	}

	private Map<String, Object> frameEvidence(DepthFrameInputs.Frame frame) {
		var result = new LinkedHashMap<String, Object>();
		result.put("early_world_frame", lastInputFrame);
		result.put("game_time", frame.gameTime());
		result.put("shader_epoch", lastInputShaderEpoch);
		result.put("projection_column_major", frame.projection().get(new float[16]));
		result.put("inverse_projection_column_major", frame.inverseProjection().get(new float[16]));
		result.put("view_rotation_column_major", frame.viewRotation().get(new float[16]));
		result.put("camera_position", Map.of("x", frame.cameraPosition().x(), "y", frame.cameraPosition().y(), "z", frame.cameraPosition().z()));
		result.put("framebuffer", List.of(frame.width(), frame.height()));
		result.put("clip_z_zero_to_one", frame.zeroToOne());
		result.put("sun_angle_radians", frame.sunAngle());
		result.put("view_sun_direction", List.of(frame.viewSun().x, frame.viewSun().y, frame.viewSun().z));
		result.put("projected_sun_uv", List.of(frame.sunU(), frame.sunV()));
		result.put("rain_brightness", frame.rainBrightness());
		result.put("skybox", frame.skybox());
		result.put("fog_type", frame.fogType());
		result.put("camera_sky_column_open", frame.skyOpen());
		result.put("contact_skip", frame.contactSkip());
		result.put("rays_skip", frame.raysSkip());
		result.put("haze_skip", frame.hazeSkip());
		result.put("ao_skip", frame.aoSkip());
		result.put("reflections_skip", frame.reflectionsSkip());
		result.put("light_skip", frame.lightSkip());
		result.put("shadow_skip", frame.sunShadowsSkip());
		result.put("gi_skip", frame.giSkip());
		result.put("volumetric_skip", frame.volumetricSkip());
		result.put("sun_rays_skip", frame.sunRaysSkip());
		result.put("does_mob_effect_block_sky_raw", frame.doesMobEffectBlockSkyRaw());
		result.put("camera_entity_initialized", frame.cameraEntityInitialized());
		result.put("player_has_blindness", frame.playerHasBlindness());
		result.put("player_has_darkness", frame.playerHasDarkness());
		result.put("player_effects", frame.playerEffects());
		result.put("sky_block_source", frame.skyBlockSource());
		return result;
	}

	Map<String, Object> snapshot() {
		var result = new LinkedHashMap<String, Object>();
		result.put("state", state);
		result.put("closed", closed);
		result.put("level_changes", levelChanges);
		result.put("contact_shadows", config.contactShadows());
		result.put("underwater_rays", config.underwaterRays());
		result.put("haze", config.haze());
		result.put("haze_distance", config.hazeDistance());
		result.put("haze_distance_resolved", config.hazeDistanceResolved());
		result.put("haze_strength", config.hazeStrength());
		result.put("haze_color", config.hazeColor());
		result.put("haze_tint", config.hazeTint());
		result.put("haze_night", config.hazeNight());
		result.put("haze_daylight_factor", lastFrame == null ? null : daylightFactor(lastFrame.elevation(), config.hazeNight()));
		result.put("haze_color_source", lastFrame == null ? null : (lastFrame.skyColorAvailable() ? "sky" : "config"));
		result.put("ambient_occlusion", config.ambientOcclusion());
		result.put("ao_samples", config.aoSamples());
		result.put("ao_radius", config.aoRadius());
		result.put("ao_strength", config.aoStrength());
		result.put("water_reflections", config.waterReflections());
		result.put("reflection_strength", config.reflectionStrength());
		result.put("glint_strength", config.glintStrength());
		result.put("reflections_executed_frames", reflectionsFrames);
		// grass-glint brief item 2: the pre-translucent depth copy this counted is gone (the water
		// mask reads MetalMaskScan's own geometry now, not a depth gap against a copy that disagreed
		// with the frame often enough to glint grass) -- kept at 0 so a bench reading this key still
		// gets an int, not a missing one.
		result.put("opaque_depth_copies", 0L);
		result.put("colored_light", config.coloredLight());
		result.put("light_strength", config.lightStrength());
		result.put("light_tint", config.lightTint());
		result.put("light_executed_frames", lightFrames);
		result.put("light_volume_rebuilds", volume == null ? 0 : volume.rebuilds());
		result.put("light_volume_emitters", volume == null ? 0 : volume.lastEmitters());
		result.put("light_volume_fill_ms", volume == null ? null : volume.lastFillMillis());
		result.put("metal_reflections", config.metalReflections());
		result.put("metal_strength", config.metalStrength());
		result.put("metal_blocks_tracked", metalScan.trackedBlocks());
		result.put("metal_blocks_drawn", metalBlocksDrawnTotal);
		result.put("metal_executed_frames", metalExecutedFrames);
		result.put("metal_tick_micros", lastMetalTickMicros);
		result.put("metal_tick_micros_max", maxMetalTickMicros);
		result.put("metal_tick_micros_p95", percentile95(metalTickRing, metalTickRingFilled));
		// grass-glint brief item 4: water surfaces tracked by the same scan, next to metal's own.
		result.put("water_blocks_tracked", metalScan.trackedWaterBlocks());
		result.put("water_blocks_drawn", waterBlocksDrawnTotal);
		// glass brief item 3/4: glass blocks tracked by the same scan, next to metal and water's own.
		result.put("glass_reflections", config.glassReflections());
		result.put("glass_strength", config.glassStrength());
		result.put("glass_blocks_tracked", metalScan.trackedGlassBlocks());
		result.put("glass_blocks_drawn", glassBlocksDrawnTotal);
		result.put("pane_blocks_tracked", metalScan.trackedPaneBlocks());
		result.put("pane_blocks_drawn", paneBlocksDrawnTotal);
		result.put("sun_shadows", config.sunShadows());
		result.put("shadow_strength", config.shadowStrength());
		result.put("shadow_span", config.shadowSpan());
		result.put("shadow_resolution", config.shadowResolution());
		result.put("shadow_executed_frames", shadowFrames);
		result.put("shadow_map_draws", shadowMapDraws);
		result.put("glass_light", config.glassLight());
		result.put("glass_tint_strength", config.glassTintStrength());
		result.put("shadow_tint_draws", shadowTintDrawsTotal);
		result.put("shadow_tint_blocks", shadowTintBlocksTotal);
		result.put("shadow_sections_ready", shadowMesh == null ? 0 : shadowMesh.sectionsReady());
		result.put("shadow_sections_total", shadowMesh == null ? 0 : shadowMesh.sectionsTotal());
		result.put("shadow_chunks_adopted", shadowMesh == null ? 0 : shadowMesh.chunksAdopted);
		result.put("shadow_ticks", shadowMesh == null ? 0 : shadowMesh.ticks);
		result.put("shadow_shells_copied", shadowMesh == null ? 0 : shadowMesh.shellsCopied);
		result.put("shadow_meshes_built", shadowMesh == null ? 0 : shadowMesh.meshesBuilt);
		result.put("shadow_meshes_empty", shadowMesh == null ? 0 : shadowMesh.meshesEmpty);
		result.put("shadow_drops_far", shadowMesh == null ? 0 : shadowMesh.dropsFar);
		result.put("shadow_drops_unload", shadowMesh == null ? 0 : shadowMesh.dropsUnload);
		result.put("shadow_sections_created", shadowMesh == null ? 0 : shadowMesh.created);
		result.put("shadow_last_map_vertices", DepthPass.lastMapVertices);
		result.put("shadow_peak_sections_ready", shadowMesh == null ? 0 : shadowMesh.peakReady);
		result.put("shadow_peak_vertices", shadowMesh == null ? 0L : shadowMesh.peakVertices);
		result.put("shadow_camera_x", shadowMesh == null ? 0 : shadowMesh.lastCameraX);
		result.put("shadow_camera_y", shadowMesh == null ? 0 : shadowMesh.lastCameraY);
		result.put("shadow_camera_z", shadowMesh == null ? 0 : shadowMesh.lastCameraZ);
		result.put("shadow_sections_pending", shadowMesh == null ? 0 : shadowMesh.sectionsPending());
		// item 4: the dirty set's own size (the same count sections_pending already reports), named
		// for what the flight-recorder tour actually asked for alongside the two tick_micros_max counters.
		result.put("shadow_dirty_sections", shadowMesh == null ? 0 : shadowMesh.dirtySectionsCount());
		result.put("shadow_vertices", shadowMesh == null ? 0L : shadowMesh.verticesTotal());
		// item 1: how many partial-shape boxes (stairs, slabs, walls, fences) copyShell has collected
		// across every section meshed this session, beside the vertex totals it already reports.
		result.put("shadow_shape_boxes", shadowMesh == null ? 0 : shadowMesh.shapeBoxesEmitted);
		// item 11: boxes past the per-block cap that copyShell/rebuildPaneVertexBuffers had to drop,
		// so a raised cap that is still too low reads as a nonzero count instead of a silent shortfall.
		result.put("shadow_shape_boxes_dropped", shadowMesh == null ? 0 : shadowMesh.shapeBoxesDropped);
		result.put("pane_boxes_dropped", paneBoxesDropped);
		result.put("shadow_tick_micros", lastShadowTickMicros);
		result.put("shadow_tick_micros_max", maxShadowTickMicros);
		result.put("shadow_tick_micros_p95", percentile95(shadowTickRing, shadowTickRingFilled));
		result.put("voxel_gi", config.voxelGi());
		result.put("gi_strength", config.giStrength());
		result.put("gi_rays", config.giRays());
		result.put("gi_distance", config.giDistance());
		result.put("gi_sky", config.giSky());
		result.put("gi_bounces", config.giBounces());
		result.put("gi_scale", config.giScale());
		result.put("gi_checkerboard", config.giCheckerboard());
		result.put("gi_emissive", config.giEmissive());
		result.put("gi_executed_frames", giFrames);
		result.put("volumetric_light", config.volumetricLight());
		result.put("volume_strength", config.volumeStrength());
		result.put("volume_density", config.volumeDensity());
		result.put("volume_steps", config.volumeSteps());
		result.put("volume_distance", config.volumeDistance());
		result.put("volume_glow", config.volumeGlow());
		result.put("volumetric_executed_frames", volumetricFrames);
		result.put("sun_rays", config.sunRays());
		result.put("sun_rays_strength", config.sunRaysStrength());
		result.put("sun_rays_executed_frames", sunRaysFrames);
		result.put("wetness", config.wetness());
		result.put("wet_strength", config.wetStrength());
		result.put("wet_dry_seconds", config.wetDrySeconds());
		result.put("wet_value", wetValue);
		result.put("voxel_volume_size", volume == null ? null : List.of(VoxelVolume.SIZE_X, VoxelVolume.SIZE_Y, VoxelVolume.SIZE_Z));
		result.put("voxel_volume_occluders", volume == null ? 0 : volume.lastOccluders());
		result.put("voxel_filters", volume == null ? 0 : volume.lastFilters());
		result.put("voxel_volume_snapshot_ms", volume == null ? null : volume.lastSnapshotMillis());
		result.put("voxel_fill_skips", volume == null ? 0 : volume.voxelFillSkips());
		result.put("voxel_emitters_dropped", volume == null ? 0 : volume.emittersDropped());
		result.put("diagnostic_only", config.diagnosticOnly());
		result.put("depth_diagnostic", config.depthDiagnostic());
		result.put("backend", backend);
		result.put("render_level_frames", begun);
		result.put("projection_captures", captures);
		result.put("early_world_hook_frames", earlyFrames);
		result.put("skipped_invalid_projection_frames", inputs.skippedInvalidProjectionFrames());
		result.put("executed_frames", executedFrames);
		result.put("contact_executed_frames", contactFrames);
		result.put("rays_executed_frames", raysFrames);
		result.put("haze_executed_frames", hazeFrames);
		result.put("ao_executed_frames", aoFrames);
		result.put("diagnostic_executed_frames", diagnosticFrames);
		result.put("skip_reasons", new LinkedHashMap<>(skips));
		result.put("active_targets", targets);
		result.put("framebuffer_width", width);
		result.put("framebuffer_height", height);
		result.put("resize_count", resizeCount);
		result.put("reload_count", reloadCount);
		result.put("shader_epoch", shaderEpoch);
		result.put("error_count", errorCount);
		result.put("last_error", lastError);
		result.put("projection_capture_failure", inputs.projectionFailureEvidence());
		result.put("depth_input", "early world D32_FLOAT before hand depth clear; reversed depth, clear 0");
		result.put("color_input", "none; color-only blend attachment; destination alpha preserved outside diagnostic mode");
		result.put("counter_scope", "successful graph execution/recording calls, not GPU completion or presentation");
		result.put("first_execution_inputs", firstExecution);
		result.put("last_inputs", lastFrame == null ? null : frameEvidence(lastFrame));
		result.put("diagnostic_requested", diagnosticRequested);
		result.put("diagnostic_saved", diagnosticSaved);
		result.put("diagnostic_path", config.diagnosticOnly() ? diagnosticPath().toString() : null);
		result.put("diagnostic_sha256", diagnosticHash);
		result.put("diagnostic_capture_inputs", diagnosticCapture);
		result.put("diagnostic_target_retained_for_readback", diagnosticTarget != null && !diagnosticSaved);
		result.put("diagnostic_encoding", config.diagnosticOnly()
			? "raw early image: R=(view.x+16)/32, G=(view.y+16)/32, B=-view.z/32, clamped; clear depth white; 12px magenta/yellow border; not a performance result" : null);
		return result;
	}
}
