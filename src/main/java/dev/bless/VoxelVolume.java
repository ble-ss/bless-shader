package dev.bless;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.MapColor;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * a voxel copy of the world around the camera, one texel per block, in two textures that share one
 * origin and one layout: the voxel atlas (albedo in rgb, a flag byte in a -- see {@link #FLAG_OCCLUDER})
 * that gi_trace.fsh marches its bounce rays through, and the light atlas (flood-filled coloured block
 * light, the old LightVolume's whole job) that light_resolve.fsh and the trace's hit shading read.
 *
 * grown from LightVolume: same render-thread snapshot spread over many frames (chunk/BlockState access
 * is render-thread-only in this engine, the trap this shelf has hit before), same worker-thread flood
 * fill of primitives, same 2d-atlas-of-y-slices layout the shader addresses without a 3d texture (this
 * engine's GpuDevice has none, and no compute either). the albedo is written on the render thread during
 * the snapshot -- a MapColor lookup per opaque block, no atlas sampling -- so the worker never sees a
 * BlockState.
 *
 * layout: slice y lives in tile (y % GRID_W, y / GRID_W) of an 8x8 grid of SIZE_X x SIZE_Z tiles, so
 * texel (tileX*SIZE_X + x, tileY*SIZE_Z + z). voxel_common.glsl carries the same constants; change both.
 */
final class VoxelVolume {
	static final int SIZE_X = 128, SIZE_Y = 64, SIZE_Z = 128;
	static final int GRID_W = 8, GRID_H = 8; // 8x8 tiles of 128x128 = one 2d texture holding 64 y-slices
	static final int TEXTURE_WIDTH = SIZE_X * GRID_W, TEXTURE_HEIGHT = SIZE_Z * GRID_H; // 1024 x 1024

	// the flag byte (voxel atlas alpha). bits 0-3 carry the block's light emission (0..15).
	static final int FLAG_OCCLUDER = 0x80; // rays stop here: canOcclude(), or a leaves block
	static final int FLAG_FLUID = 0x40;    // water or lava: rays pass, the shader may tint
	// glass-light brief item 1: a glass block (GlassBlocks.isGlass, tinted_glass excluded -- vanilla
	// already blocks light through that one) is not an occluder; its albedo bytes hold its tint
	// (white for plain glass, MapColor for stained) instead of a solid surface colour. fill()'s flood
	// multiplies a light path's carried rgb by this cell's tint on the way through, and gi_trace's
	// march() lets a ray pass through rather than stopping.
	static final int FLAG_FILTER = 0x20;
	static final int FLAG_EMITTER_MASK = 0x0F;

	// re-anchor once the camera leaves the middle third of the volume on an axis.
	private static final int MARGIN_XZ = SIZE_X / 6;
	private static final int TIME_CHECK_STRIDE = 512;
	// rmls-cost brief item 1: the two constants above used to be fixed (2 s rebuild floor, 1.5 ms
	// snapshot budget) -- both are now knobs, read live from config each tick (DepthEffects.render's
	// own volume.tick call site), same volatile-field shape as glassLight/glassTintStrength below.
	volatile float voxelBudgetMs = 1.0f;
	volatile float voxelRebuildSeconds = 2f;

	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "bless-voxel-volume");
		thread.setDaemon(true);
		return thread;
	});

	private GpuTexture lightTexture, voxelTexture;
	private GpuTextureView lightView, voxelView;
	private final Vector3f displayOrigin = new Vector3f();
	private boolean valid;
	private boolean closed;

	private Snapshot building;
	private long lastRebuildStart;
	private volatile boolean chunksDirty = true;
	private final AtomicReference<Result> pendingResult = new AtomicReference<>();
	// leak-repairs item 2: set the moment a finished snapshot is handed to the worker, cleared in
	// fill()'s own finally. maybeStartRebuild refuses to start a new snapshot while this is set, so a
	// worker that falls behind (a lava sea's flood fill running long) no longer piles up an unbounded
	// queue of snapshots on the single-thread executor below.
	private final AtomicBoolean fillInFlight = new AtomicBoolean(false);

	// worker-thread scratch for the flood fill, reused across fills (8 MB otherwise churned every 2 s).
	private int[] bestRemaining, visitedEpoch;
	// leak-repairs item 4c: the flood's own frontier, four parallel arrays instead of a new int[4] per
	// push -- allocated once, reused every fill (worker thread only, one fill in flight at a time per
	// item 2 above). a light level is capped at 15, so no single emitter's flood can outgrow this.
	private static final int FLOOD_STACK_CAPACITY = 32_768;
	private int[] stackX, stackY, stackZ, stackRemaining;
	// glass-light brief item 1: the throughput a pushed cell carries -- starts at (1,1,1) for every
	// emitter's own seed, multiplied by a filter cell's own tint (lerped toward white by
	// 1 - glassTintStrength) each time the flood steps INTO one. relaxation below still compares
	// remaining level only, same as before this brief -- a cell popped twice in one epoch just means
	// two slightly different throughputs both got a turn at out[]'s own per-channel max.
	private float[] stackThroughR, stackThroughG, stackThroughB;
	// leak-repairs item 4b: thins lava's own flood seeds -- one seed per 5x5x5 cell of the volume, set
	// index-only (no colour key: two fluid emitters sharing a cell are overwhelmingly the same block,
	// same colour). reused across fills, cleared instead of reallocated.
	private static final int CELL_SIZE = 5;
	// rmls-cost brief item 3: sized for CELL_SIZE_CROWDED (3, the finer grid) since that yields the
	// larger quotient range -- the coarser fluid-only grid (CELL_SIZE, 5) still indexes into the same
	// BitSet fine at those strides, it just never reaches the far end of them.
	private static final int CELLS_X = (SIZE_X + 2) / 3;
	private static final int CELLS_Y = (SIZE_Y + 2) / 3;
	private static final int CELLS_Z = (SIZE_Z + 2) / 3;
	private BitSet thinnedCells;
	// leak-repairs item 4a: a snapshot with an unbounded lava sea in it produced hundreds of thousands
	// of emitters, each running its own flood -- keep only the nearest voxelEmitterCap to the volume
	// centre. rmls-cost brief item 3: this used to be a fixed 4096; now a live knob, default 384
	// (repair 2026-09-21 item 3, down from 1024 -- overwritten from config every tick regardless, this
	// initial value only covers the sliver before the first tick).
	volatile int voxelEmitterCap = 384;
	// rmls-cost brief item 3: the fluid-only 5x5x5 thinning below still applies at any emitter count,
	// but once the raw count exceeds voxelEmitterCap every emitter (not just fluids) also gets thinned,
	// at the finer 3x3x3 grid -- a village of candles is otherwise hundreds of same-brightness floods a
	// block or two apart. CELLS_X/Y/Z below are sized for the finer grid (3) since that yields the
	// larger cell-count; the coarser fluid-only grid (5) still indexes into the same BitSet fine, just
	// leaving most of it unused (a smaller quotient range).
	private static final int CELL_SIZE_FLUID = CELL_SIZE;
	private static final int CELL_SIZE_CROWDED = 3;

	private long rebuilds, lastEmitters, lastFillNanos, lastSnapshotNanos, lastOccluders, lastFilters;
	private long voxelFillSkips, emittersDroppedTotal, voxelRebuildsSkippedStill;
	// rmls-cost brief item 1b: the adaptive interval maybeStartRebuild last computed, for status only.
	private long lastComputedIntervalNanos = 2_000_000_000L;
	// rmls-cost brief item 1c: the camera position (block-grained; cameraBlock is already that coarse)
	// at the start of the last snapshot actually taken -- compared against on every subsequent
	// maybeStartRebuild call to decide the "camera barely moved" half of the still-room skip.
	private double lastSnapshotCameraX, lastSnapshotCameraY, lastSnapshotCameraZ;
	// glass-light brief: set by DepthEffects every tick from config, read when a new Snapshot starts
	// (record() decides FLAG_FILTER at scan time) and by fill() (the flood's own tint lerp) -- plain
	// volatile fields rather than a config reference so this class stays decoupled from ClientConfig,
	// same shape as everything else here.
	volatile boolean glassLight;
	volatile float glassTintStrength = 1f;

	/** what DepthPass needs to sample this frame; never null -- Sample.NONE when nothing is ready yet. */
	record Sample(Vector3f origin, boolean valid, GpuTextureView view, GpuTextureView voxels) {
		static final Sample NONE = new Sample(new Vector3f(), false, null, null);
	}

	private record Result(int originX, int originY, int originZ, ByteBuffer light, ByteBuffer voxels, int emitters, long occluders, long filters, long fillNanos, long snapshotNanos, int emittersDropped) {}

	void chunkChanged() { chunksDirty = true; }

	Sample sample() { return valid && lightView != null ? new Sample(displayOrigin, true, lightView, voxelView) : Sample.NONE; }

	long rebuilds() { return rebuilds; }
	long lastEmitters() { return lastEmitters; }
	long lastOccluders() { return lastOccluders; }
	long lastFilters() { return lastFilters; }
	double lastFillMillis() { return lastFillNanos / 1_000_000.0; }
	double lastSnapshotMillis() { return lastSnapshotNanos / 1_000_000.0; }
	long voxelFillSkips() { return voxelFillSkips; }
	long emittersDropped() { return emittersDroppedTotal; }
	long voxelRebuildsSkippedStill() { return voxelRebuildsSkippedStill; }
	double voxelIntervalMillis() { return lastComputedIntervalNanos / 1_000_000.0; }

	// leak-repairs item 1: RmlsClient's level-change hook. the in-flight snapshot (and any finished-
	// but-undrained result) belong to the level the player just left; drop them and invalidate the
	// display sample so the shaders wait for a fresh fill in the new level instead of showing -- or
	// worse, gpu-uploading -- the old one's voxels.
	void levelChanged() {
		building = null;
		pendingResult.set(null);
		valid = false;
	}

	/** once per frame, render thread only, while colored_light or voxel_gi is on. */
	void tick(ClientLevel level, BlockPos cameraBlock) {
		RenderSystem.assertOnRenderThread();
		if (closed) return;
		ensureTextures();
		drainCompleted();
		if (building == null) maybeStartRebuild(level, cameraBlock);
		else building.resume((long) (voxelBudgetMs * 1_000_000f));
		if (building != null && building.done()) {
			Snapshot finished = building;
			building = null;
			// leak-repairs item 3: the snapshot is about to sit in the worker queue (possibly for a
			// while, per item 2's own throttle) -- null its ClientLevel reference now, on the render
			// thread, once every read of it (the scan above) is done. fill() never touches it.
			finished.level = null;
			fillInFlight.set(true);
			worker.submit(() -> fill(finished));
		}
	}

	private void ensureTextures() {
		if (lightTexture != null) return;
		lightTexture = RenderSystem.getDevice().createTexture("bless light atlas",
			GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, TEXTURE_WIDTH, TEXTURE_HEIGHT, 1, 1);
		lightView = RenderSystem.getDevice().createTextureView(lightTexture);
		voxelTexture = RenderSystem.getDevice().createTexture("bless voxel atlas",
			GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, TEXTURE_WIDTH, TEXTURE_HEIGHT, 1, 1);
		voxelView = RenderSystem.getDevice().createTextureView(voxelTexture);
	}

	private void maybeStartRebuild(ClientLevel level, BlockPos cameraBlock) {
		long now = System.nanoTime();
		// compare the camera's 16-block cell, not the camera itself: the anchor is snapped to 16, so the
		// raw camera sits up to 15 blocks off it and a tight margin read that as stale every frame.
		int cellX = Math.floorDiv(cameraBlock.getX(), 16) * 16, cellY = Math.floorDiv(cameraBlock.getY(), 16) * 16, cellZ = Math.floorDiv(cameraBlock.getZ(), 16) * 16;
		boolean anchorStale = !valid
			|| Math.abs(cellX - (displayOrigin.x() + SIZE_X / 2)) > MARGIN_XZ
			|| Math.abs(cellZ - (displayOrigin.z() + SIZE_Z / 2)) > MARGIN_XZ
			|| Math.abs(cellY - (displayOrigin.y() + SIZE_Y / 2)) > 16;
		// rmls-cost brief item 1b: the fixed 2 s floor used to be REBUILD_INTERVAL_NANOS -- a snapshot
		// costing 250 ms of render-thread time then still rebuilt every 2 s regardless, wasting most of
		// that budget on a big-world scan. the interval now floors at voxelRebuildSeconds but stretches
		// to 3x the last snapshot's own cost plus its fill cost, so a slow world self-throttles.
		long intervalNanos = Math.max((long) (voxelRebuildSeconds * 1_000_000_000L), 3 * lastSnapshotNanos + lastFillNanos);
		lastComputedIntervalNanos = intervalNanos;
		boolean timerDue = now - lastRebuildStart >= intervalNanos;
		// chunksDirty flips on every nearby block change and must not itself force a rebuild -- it only
		// rides the next one once the interval has elapsed. anchorStale still jumps the queue.
		if (!anchorStale && !timerDue) return;
		// leak-repairs item 2: this used to start a new snapshot every REBUILD_INTERVAL_NANOS whether
		// or not the previous fill had finished, on a single-thread executor with an unbounded queue --
		// a fill running long (a lava sea's flood, before item 4) piled up snapshots faster than the
		// worker could drain them. skip and count instead; the next call (still every frame) retries.
		if (fillInFlight.get()) { voxelFillSkips++; return; }
		// rmls-cost brief item 1c: a due timer alone is not reason enough -- if the camera has barely
		// moved (under 4 blocks) since the last snapshot AND no chunk has loaded or unloaded since
		// (chunksDirty), nothing in the volume could plausibly have changed. standing still in a lit
		// room now costs nothing beyond this cheap check. anchorStale still always proceeds: it means
		// the volume itself needs to re-anchor, not merely that the timer elapsed.
		if (!anchorStale) {
			double dx = cameraBlock.getX() - lastSnapshotCameraX, dy = cameraBlock.getY() - lastSnapshotCameraY, dz = cameraBlock.getZ() - lastSnapshotCameraZ;
			if (dx * dx + dy * dy + dz * dz < 16.0 && !chunksDirty) {
				voxelRebuildsSkippedStill++;
				lastRebuildStart = now; // defer the next check by a full interval; nothing here to redo sooner
				return;
			}
		}
		chunksDirty = false;
		lastRebuildStart = now;
		lastSnapshotCameraX = cameraBlock.getX();
		lastSnapshotCameraY = cameraBlock.getY();
		lastSnapshotCameraZ = cameraBlock.getZ();
		int originX = Math.floorDiv(cameraBlock.getX(), 16) * 16 - SIZE_X / 2;
		int originY = Math.floorDiv(cameraBlock.getY(), 16) * 16 - SIZE_Y / 2;
		int originZ = Math.floorDiv(cameraBlock.getZ(), 16) * 16 - SIZE_Z / 2;
		building = new Snapshot(level, originX, originY, originZ, glassLight, glassTintStrength);
	}

	// the vulkan encoder reads the upload through its native address, so a heap buffer from
	// ByteBuffer.wrap segfaults inside Unsafe.copyMemory (the first coloured-light proof died there).
	private static ByteBuffer directCopy(byte[] bytes) {
		ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length).order(java.nio.ByteOrder.nativeOrder());
		direct.put(bytes).flip();
		return direct;
	}

	private void drainCompleted() {
		Result result = pendingResult.getAndSet(null);
		if (result == null) return;
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.writeToTexture(lightTexture, result.light(), 0, 0, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT);
		encoder.writeToTexture(voxelTexture, result.voxels(), 0, 0, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT);
		displayOrigin.set(result.originX(), result.originY(), result.originZ());
		valid = true;
		rebuilds++;
		lastEmitters = result.emitters();
		lastOccluders = result.occluders();
		lastFilters = result.filters();
		lastFillNanos = result.fillNanos();
		lastSnapshotNanos = result.snapshotNanos();
		emittersDroppedTotal += result.emittersDropped();
	}

	void close() {
		closed = true;
		worker.shutdownNow();
		if (lightView != null) lightView.close();
		if (lightTexture != null) lightTexture.close();
		if (voxelView != null) voxelView.close();
		if (voxelTexture != null) voxelTexture.close();
	}

	// ---- the flood fill: worker thread only, primitives in, primitives out. ----

	private void fill(Snapshot snapshot) {
		// leak-repairs item 2: this used to run with no signal back to maybeStartRebuild about whether
		// it was still busy -- the finally clears fillInFlight no matter how this exits.
		try {
			long start = System.nanoTime();
			int cells = SIZE_X * SIZE_Y * SIZE_Z;
			byte[] out = new byte[cells * 4];
			for (int i = 0; i < cells; i++) out[i * 4 + 3] = (byte) 0xFF; // alpha unused by the shader; opaque by convention
			if (bestRemaining == null) {
				bestRemaining = new int[cells]; visitedEpoch = new int[cells];
				stackX = new int[FLOOD_STACK_CAPACITY]; stackY = new int[FLOOD_STACK_CAPACITY];
				stackZ = new int[FLOOD_STACK_CAPACITY]; stackRemaining = new int[FLOOD_STACK_CAPACITY];
				stackThroughR = new float[FLOOD_STACK_CAPACITY]; stackThroughG = new float[FLOOD_STACK_CAPACITY];
				stackThroughB = new float[FLOOD_STACK_CAPACITY];
				thinnedCells = new BitSet(CELLS_X * CELLS_Y * CELLS_Z);
			} else {
				Arrays.fill(visitedEpoch, 0);
				thinnedCells.clear();
			}
			int epoch = 0;
			int emitterTotal = snapshot.emitterLevel.size();
			// leak-repairs item 4a: a lava sea can leave this snapshot with hundreds of thousands of
			// level-15 emitters, each about to run its own flood below -- keep only the nearest
			// voxelEmitterCap to the volume centre.
			int emitterCap = Math.max(1, voxelEmitterCap);
			int[] emitterOrder = selectEmitters(snapshot, emitterTotal, emitterCap);
			int emittersDropped = emitterTotal - emitterOrder.length;
			// rmls-cost brief item 3: over the cap, every emitter (not only fluids) gets thinned, at the
			// finer 3x3x3 grid -- a village of candles is otherwise hundreds of same-brightness floods a
			// block or two apart even after the distance cap above already trimmed the far ones.
			boolean crowded = emitterTotal > emitterCap;
			int thinCellSize = crowded ? CELL_SIZE_CROWDED : CELL_SIZE_FLUID;
			for (int order = 0; order < emitterOrder.length; order++) {
				int e = emitterOrder[order];
				int ex = snapshot.emitterX.getInt(e), ey = snapshot.emitterY.getInt(e), ez = snapshot.emitterZ.getInt(e);
				int startIndex = snapshot.index(ex, ey, ez);
				// leak-repairs item 4b: thin fluid (lava) emitters to one seed per cell -- a lava sea's
				// surface is otherwise thousands of same-colour emitters a block apart, each queuing a
				// flood that mostly re-covers ground its neighbours already lit. rmls-cost brief item 3:
				// once crowded, every emitter is thinned this way, not only fluids.
				boolean fluidEmitter = (snapshot.voxels[startIndex * 4 + 3] & FLAG_FLUID) != 0;
				if (fluidEmitter || crowded) {
					int cellIndex = ((ex / thinCellSize) * CELLS_Y + (ey / thinCellSize)) * CELLS_Z + (ez / thinCellSize);
					if (thinnedCells.get(cellIndex)) continue;
					thinnedCells.set(cellIndex);
				}
				epoch++;
				int level = snapshot.emitterLevel.getInt(e);
				int color = snapshot.emitterColor.getInt(e);
				float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
				// leak-repairs item 4c: a preallocated stack instead of an ArrayDeque<int[]> -- the old
				// code allocated a new int[4] for every push (potentially millions across one fill's
				// worth of floods). a light level is capped at 15, so no single flood can outgrow
				// FLOOD_STACK_CAPACITY; the bounds check below is a safety net, not an expected path.
				int stackSize = 0;
				stackX[0] = ex; stackY[0] = ey; stackZ[0] = ez; stackRemaining[0] = level;
				stackThroughR[0] = 1f; stackThroughG[0] = 1f; stackThroughB[0] = 1f;
				stackSize = 1;
				visitedEpoch[startIndex] = epoch;
				bestRemaining[startIndex] = level;
				while (stackSize > 0) {
					stackSize--;
					int cx = stackX[stackSize], cy = stackY[stackSize], cz = stackZ[stackSize], remaining = stackRemaining[stackSize];
					float throughR = stackThroughR[stackSize], throughG = stackThroughG[stackSize], throughB = stackThroughB[stackSize];
					int index = snapshot.index(cx, cy, cz);
					float scale = (float) remaining / level;
					int channelR = out[index * 4] & 0xFF, channelG = out[index * 4 + 1] & 0xFF, channelB = out[index * 4 + 2] & 0xFF;
					out[index * 4] = clampByte(Math.max(channelR, Math.round(r * throughR * scale * 255f)));
					out[index * 4 + 1] = clampByte(Math.max(channelG, Math.round(g * throughG * scale * 255f)));
					out[index * 4 + 2] = clampByte(Math.max(channelB, Math.round(b * throughB * scale * 255f)));
					if (remaining <= 1) continue;
					for (int face = 0; face < 6; face++) {
						int nx = cx + FACE_DX[face], ny = cy + FACE_DY[face], nz = cz + FACE_DZ[face];
						if (nx < 0 || ny < 0 || nz < 0 || nx >= SIZE_X || ny >= SIZE_Y || nz >= SIZE_Z) continue;
						int neighborIndex = snapshot.index(nx, ny, nz);
						if (snapshot.occluders.get(neighborIndex)) continue;
						int neighborRemaining = remaining - 1;
						if (visitedEpoch[neighborIndex] == epoch && bestRemaining[neighborIndex] >= neighborRemaining) continue;
						visitedEpoch[neighborIndex] = epoch;
						bestRemaining[neighborIndex] = neighborRemaining;
						if (stackSize < FLOOD_STACK_CAPACITY) {
							stackX[stackSize] = nx; stackY[stackSize] = ny; stackZ[stackSize] = nz; stackRemaining[stackSize] = neighborRemaining;
							// stepping INTO a filter cell (glass) multiplies the carried throughput by its own
							// tint, lerped toward white by 1 - glassTintStrength; every other neighbour passes
							// the throughput through unchanged.
							float nr = throughR, ng = throughG, nb = throughB;
							if ((snapshot.voxels[neighborIndex * 4 + 3] & FLAG_FILTER) != 0) {
								float strength = snapshot.glassTintStrength;
								float tr = (snapshot.voxels[neighborIndex * 4] & 0xFF) / 255f;
								float tg = (snapshot.voxels[neighborIndex * 4 + 1] & 0xFF) / 255f;
								float tb = (snapshot.voxels[neighborIndex * 4 + 2] & 0xFF) / 255f;
								nr *= 1f - strength + strength * tr;
								ng *= 1f - strength + strength * tg;
								nb *= 1f - strength + strength * tb;
							}
							stackThroughR[stackSize] = nr; stackThroughG[stackSize] = ng; stackThroughB[stackSize] = nb;
							stackSize++;
						} // else: should never happen at level <= 15 -- drop the push, not the fill.
					}
				}
			}
			long fillNanos = System.nanoTime() - start;
			pendingResult.set(new Result(snapshot.originX, snapshot.originY, snapshot.originZ,
				directCopy(out), directCopy(snapshot.voxels), emitterOrder.length, snapshot.occluders.cardinality(),
				snapshot.filterCells, fillNanos, snapshot.elapsedNanos, emittersDropped));
		} finally {
			fillInFlight.set(false);
		}
	}

	/** leak-repairs item 4a: indices into snapshot's emitter lists, nearest `cap` to the volume centre
	 * when there are more than that many, otherwise every emitter unordered. worker thread only.
	 * rmls-cost brief item 3: cap is now voxelEmitterCap, read once per fill by the caller, not a
	 * fixed constant. */
	private static int[] selectEmitters(Snapshot snapshot, int emitterTotal, int cap) {
		if (emitterTotal <= cap) {
			int[] all = new int[emitterTotal];
			for (int i = 0; i < emitterTotal; i++) all[i] = i;
			return all;
		}
		double centerX = SIZE_X / 2.0, centerY = SIZE_Y / 2.0, centerZ = SIZE_Z / 2.0;
		Integer[] order = new Integer[emitterTotal];
		double[] distSq = new double[emitterTotal];
		for (int i = 0; i < emitterTotal; i++) {
			order[i] = i;
			double dx = snapshot.emitterX.getInt(i) - centerX, dy = snapshot.emitterY.getInt(i) - centerY, dz = snapshot.emitterZ.getInt(i) - centerZ;
			distSq[i] = dx * dx + dy * dy + dz * dz;
		}
		Arrays.sort(order, (a, b) -> Double.compare(distSq[a], distSq[b]));
		int[] kept = new int[cap];
		for (int i = 0; i < cap; i++) kept[i] = order[i];
		return kept;
	}

	private static byte clampByte(int value) { return (byte) Math.max(0, Math.min(255, value)); }

	private static final int[] FACE_DX = {1, -1, 0, 0, 0, 0};
	private static final int[] FACE_DY = {0, 0, 1, -1, 0, 0};
	private static final int[] FACE_DZ = {0, 0, 0, 0, 1, -1};

	// ---- the snapshot: render-thread state, incremental, a budget per frame. ----

	/** built one chunk column at a time; BlockState reads never leave the render thread. */
	private static final class Snapshot {
		// leak-repairs item 3: not final -- VoxelVolume.tick() nulls this once the render-thread scan
		// finishes and before the snapshot is handed to the worker queue, so a fill running long (or
		// piled up behind item 2's own throttle) never pins the old ClientLevel alive. fill() itself
		// never reads this field.
		ClientLevel level;
		final int originX, originY, originZ;
		final BitSet occluders = new BitSet(SIZE_X * SIZE_Y * SIZE_Z);
		// the voxel atlas bytes, written here directly: rgb albedo (MapColor, or the emitter colour for a
		// glowing block), a the flag byte. air stays all-zero.
		final byte[] voxels = new byte[SIZE_X * SIZE_Y * SIZE_Z * 4];
		final it.unimi.dsi.fastutil.ints.IntArrayList emitterX = new it.unimi.dsi.fastutil.ints.IntArrayList();
		final it.unimi.dsi.fastutil.ints.IntArrayList emitterY = new it.unimi.dsi.fastutil.ints.IntArrayList();
		final it.unimi.dsi.fastutil.ints.IntArrayList emitterZ = new it.unimi.dsi.fastutil.ints.IntArrayList();
		final it.unimi.dsi.fastutil.ints.IntArrayList emitterLevel = new it.unimi.dsi.fastutil.ints.IntArrayList();
		final it.unimi.dsi.fastutil.ints.IntArrayList emitterColor = new it.unimi.dsi.fastutil.ints.IntArrayList();
		long elapsedNanos;
		// glass-light brief: captured once at snapshot start, both passed in from the outer instance's
		// current values (Snapshot is a static nested class, no implicit outer reference) rather than
		// read live -- the scan spans many resume() calls and must not change its own rules mid-scan.
		// glassLight decides record()'s own FLAG_FILTER branch; glassTintStrength only matters to fill().
		final boolean glassLight;
		final float glassTintStrength;
		int filterCells;

		private final int chunkMinX, chunkMaxX, chunkMinZ, chunkMaxZ;
		private final int scanMinY, scanMaxY;
		private int cursorChunkIndex, cursorY, cursorLocalX, cursorLocalZ;
		private boolean finished;
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		Snapshot(ClientLevel level, int originX, int originY, int originZ, boolean glassLight, float glassTintStrength) {
			this.level = level;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.glassLight = glassLight;
			this.glassTintStrength = glassTintStrength;
			chunkMinX = Math.floorDiv(originX, 16);
			chunkMaxX = Math.floorDiv(originX + SIZE_X - 1, 16);
			chunkMinZ = Math.floorDiv(originZ, 16);
			chunkMaxZ = Math.floorDiv(originZ + SIZE_Z - 1, 16);
			scanMinY = Math.max(originY, level.getMinY());
			scanMaxY = Math.min(originY + SIZE_Y - 1, level.getMinY() + level.getHeight() - 1);
			cursorY = scanMinY;
		}

		// must land on the exact texel the shader's fetch reads for this (x, y, z): slice ly lives in
		// tile (ly % GRID_W, ly / GRID_W) of the grid. writeToTexture uploads row-major at TEXTURE_WIDTH,
		// so the pixel index is (tileY*SIZE_Z + lz) * TEXTURE_WIDTH + (tileX*SIZE_X + lx).
		int index(int lx, int ly, int lz) {
			int tileX = ly % GRID_W, tileY = ly / GRID_W;
			int px = tileX * SIZE_X + lx, py = tileY * SIZE_Z + lz;
			return py * TEXTURE_WIDTH + px;
		}

		boolean done() { return finished; }

		void resume(long budgetNanos) {
			long began = System.nanoTime();
			long deadline = began + budgetNanos;
			int chunksPerRow = chunkMaxX - chunkMinX + 1;
			int totalChunks = chunksPerRow * (chunkMaxZ - chunkMinZ + 1);
			int checked = 0;
			try {
				while (cursorChunkIndex < totalChunks) {
					int cx = chunkMinX + cursorChunkIndex % chunksPerRow;
					int cz = chunkMinZ + cursorChunkIndex / chunksPerRow;
					ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
					if (chunk == null) {
						cursorChunkIndex++;
						cursorY = scanMinY;
						continue;
					}
					int baseX = cx * 16, baseZ = cz * 16;
					for (; cursorY <= scanMaxY; cursorY++) {
						for (; cursorLocalX < 16; cursorLocalX++) {
							for (; cursorLocalZ < 16; cursorLocalZ++) {
								int worldX = baseX + cursorLocalX, worldZ = baseZ + cursorLocalZ;
								int localX = worldX - originX, localZ = worldZ - originZ;
								if (localX >= 0 && localX < SIZE_X && localZ >= 0 && localZ < SIZE_Z) {
									BlockState state = chunk.getBlockState(cursor.set(worldX, cursorY, worldZ));
									if (!state.isAir()) record(state, localX, cursorY - originY, localZ);
								}
								if (++checked % TIME_CHECK_STRIDE == 0 && System.nanoTime() >= deadline) return;
							}
							cursorLocalZ = 0;
						}
						cursorLocalX = 0;
					}
					cursorY = scanMinY;
					cursorChunkIndex++;
				}
				finished = true;
			} finally {
				elapsedNanos += System.nanoTime() - began;
			}
		}

		private void record(BlockState state, int localX, int localY, int localZ) {
			int index = index(localX, localY, localZ);
			boolean fluid = !state.getFluidState().isEmpty();
			// glass-light brief item 1: a filter cell (glass, tinted_glass excluded -- vanilla already
			// blocks light through that one) is gated on glassLight so the feature off reproduces the
			// exact old behaviour (glass falls through canOcclude()==false, same as before this brief).
			// panes brief item 4: a pane counts as a filter cell too -- a voxel is a whole cell (128^3
			// world blocks packed to this volume's own grid), not a partial shape, so a pane tints its
			// whole cell same as a full glass block would; there is no tinted pane, so no isTinted check.
			boolean isGlassCell = GlassBlocks.isGlass(state) && !GlassBlocks.isTinted(state);
			boolean isPaneCell = GlassBlocks.isPane(state);
			boolean filter = glassLight && (isGlassCell || isPaneCell);
			// same occluder test ShadowMesh uses: canOcclude(), or a leaves block (rori wants a canopy to
			// shade), never a fluid or a filter cell.
			boolean occluder = !fluid && !filter && (state.canOcclude() || state.getBlock() instanceof LeavesBlock);
			int emission = state.getLightEmission();
			int flags = (occluder ? FLAG_OCCLUDER : 0) | (fluid ? FLAG_FLUID : 0) | (filter ? FLAG_FILTER : 0) | Math.min(emission, 15);
			if (flags == 0) return;
			if (occluder) occluders.set(index);
			if (filter) filterCells++;
			int color;
			if (emission > 0) {
				emitterX.add(localX);
				emitterY.add(localY);
				emitterZ.add(localZ);
				emitterLevel.add(emission);
				color = EmitterColors.colorFor(state);
				emitterColor.add(color);
			} else if (isPaneCell) {
				color = GlassBlocks.paneTint(state, level, cursor);
			} else if (filter) {
				// minecraft:glass itself has no MapColor entry worth trusting for a colour (NONE, grey
				// fallback below would tint a plain window grey) -- white is the correct "no tint" value.
				if (state.is(net.minecraft.world.level.block.Blocks.GLASS)) color = 0xFFFFFF;
				else {
					MapColor map = state.getMapColor(level, cursor);
					color = map == MapColor.NONE ? 0xFFFFFF : map.col;
				}
			} else {
				MapColor map = state.getMapColor(level, cursor);
				color = map == MapColor.NONE ? 0x808080 : map.col;
			}
			int base = index * 4;
			voxels[base] = (byte) ((color >> 16) & 0xFF);
			voxels[base + 1] = (byte) ((color >> 8) & 0xFF);
			voxels[base + 2] = (byte) (color & 0xFF);
			voxels[base + 3] = (byte) flags;
		}
	}
}
