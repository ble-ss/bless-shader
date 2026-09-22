package dev.bless;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tracks, per loaded chunk, which blocks are in the reflective tag (brief item 2). CHUNK_LOAD
 * scans the chunk once, own-chunk reads only (chunk.getBlockState with in-chunk coordinates,
 * never Level.getBlockState -- that blocks on neighbours mid-load, the trap this shelf has hit
 * before). CHUNK_UNLOAD drops the entry.
 *
 * item 3a: the round-robin refresh used to rescan one whole chunk (every section, ~98k
 * getBlockState calls) synchronously on the render thread every 20 ticks -- a real hitch. it is
 * now a resumable cursor: at most FRAME_BUDGET_NANOS per call, walking the camera's 9 chunks
 * section by section, skipping any section whose 16-block y-range sits further than
 * Y_WINDOW_BLOCKS from the camera (a reflective tag three floors above the player is not worth
 * the reads).
 *
 * item 1 (the flight-recorder tour, metal_tick_micros_max 33100): onLoad used to scan the whole
 * chunk synchronously too -- several chunks a frame while flying meant several of those scans
 * stacked in one frame. onLoad now only records which sections are pending; {@link #refresh}
 * drains that pending set first, nearest chunk to the camera first and nearest section within it
 * first, off the same FRAME_BUDGET_NANOS the round-robin rescan already spent alone. a section
 * with no entry in a tracked chunk's map is simply "not scanned yet" -- trackedBlocks and
 * positionsNear already only ever walk whatever is actually present.
 *
 * item 1 of the grass-glint brief (2026-09-20): the same per-chunk walk now also tracks water
 * SURFACE blocks (a water fluid state with a non-water block or air directly above it) in a
 * second, parallel set of per-section arrays -- one pass over each column serves both material
 * masks, so this costs no extra budget. water_mask.fsh used to guess "this pixel is water" from
 * a depth gap against a pre-translucent copy that disagreed with the frame often enough to glint
 * on grass; the mask this scan feeds is drawn as real geometry instead, so the shader can just look.
 */
final class MetalMaskScan {
	private static final int Y_WINDOW_BLOCKS = 48;
	private static final int TIME_CHECK_STRIDE = 256; // (x,z) columns (16 y-reads each) between clock checks
	// rmls-cost brief item 2a: the fixed 0.5 ms budget is now a knob, read live from config
	// (DepthEffects.updateMetalGeometry, same volatile-write-before-use shape as VoxelVolume's own
	// glassLight/voxelBudgetMs fields) -- default halved from the old constant (0.5 -> 0.25 ms).
	volatile float materialBudgetMs = 0.25f;
	private long frameBudgetNanos() { return (long) (materialBudgetMs * 1_000_000f); }

	// per chunk, per section (blockY >> 4): the reflective positions found in that section.
	private final Map<Long, Map<Integer, long[]>> chunkSections = new ConcurrentHashMap<>();
	// same shape, for water surface positions (item 1) -- a separate map since a section's metal
	// and water counts are unrelated and DepthEffects rebuilds each side's vertex buffer on its
	// own config gate (metal_reflections vs water_reflections).
	private final Map<Long, Map<Integer, long[]>> waterChunkSections = new ConcurrentHashMap<>();
	// glass brief item 3: a third parallel per-section map, same shape, same one walk -- glass has
	// its own config gate (glass_reflections) and its own cube vertex buffer, so it gets its own map
	// rather than folding into chunkSections the way metal and glass share nothing else.
	private final Map<Long, Map<Integer, long[]>> glassChunkSections = new ConcurrentHashMap<>();
	// panes brief item 2: a fourth parallel per-section map, same shape, same one walk -- panes ride
	// glass's own config gates (glass_reflections/glass_light) but get their own map since their
	// draw geometry (DepthEffects.rebuildPaneVertexBuffers, per-box not per-cube) is its own thing.
	private final Map<Long, Map<Integer, long[]>> paneChunkSections = new ConcurrentHashMap<>();
	// chunks onLoad has seen but whose sections PendingScan has not gotten to yet -- render thread
	// only (onLoad/onUnload/refresh all run there), so a plain HashMap-backed structure would do,
	// but chunkSections is already Concurrent for callers off-thread and this sits next to it.
	private final Map<Long, IntArrayList> pendingByChunk = new ConcurrentHashMap<>();
	private PendingScan pendingScan;
	private Cursor cursor;
	// bumped whenever any chunk's reflective positions change (load, unload, or a rescanned
	// section's positions coming back different from before) -- DepthEffects compares this instead
	// of calling positionsNear every frame (brief item 3).
	private volatile long revision;

	long revision() { return revision; }

	void onLoad(LevelChunk chunk) {
		long chunkKey = chunk.getPos().pack();
		chunkSections.putIfAbsent(chunkKey, new ConcurrentHashMap<>());
		waterChunkSections.putIfAbsent(chunkKey, new ConcurrentHashMap<>());
		glassChunkSections.putIfAbsent(chunkKey, new ConcurrentHashMap<>());
		paneChunkSections.putIfAbsent(chunkKey, new ConcurrentHashMap<>());
		IntArrayList sectionYs = new IntArrayList();
		for (int sectionY = chunk.getMinSectionY(); sectionY <= chunk.getMaxSectionY(); sectionY++) sectionYs.add(sectionY);
		pendingByChunk.put(chunkKey, sectionYs);
		// no revision bump here: the chunk is tracked but empty until PendingScan actually scans a
		// section, and storeSection already bumps revision the moment that first section's positions
		// land (null -> an array is "different" by Arrays.equals).
	}

	void onUnload(LevelChunk chunk) {
		long chunkKey = chunk.getPos().pack();
		chunkSections.remove(chunkKey);
		waterChunkSections.remove(chunkKey);
		glassChunkSections.remove(chunkKey);
		paneChunkSections.remove(chunkKey);
		pendingByChunk.remove(chunkKey);
		if (pendingScan != null && pendingScan.chunkKey == chunkKey) pendingScan = null;
		revision++;
	}

	// leak-repairs item 1: RmlsClient's level-change hook. every tracked chunk belongs to the level
	// that just closed -- CHUNK_UNLOAD never fires for a respawn/portal trip, so without this the
	// three maps below (and the pending scan) kept growing across every trip.
	void levelChanged() {
		chunkSections.clear();
		waterChunkSections.clear();
		glassChunkSections.clear();
		paneChunkSections.clear();
		pendingByChunk.clear();
		pendingScan = null;
		cursor = null;
		revision++;
	}

	int trackedChunks() {
		return chunkSections.size();
	}

	int trackedBlocks() {
		return trackedBlocks(chunkSections);
	}

	int trackedWaterBlocks() {
		return trackedBlocks(waterChunkSections);
	}

	int trackedGlassBlocks() {
		return trackedBlocks(glassChunkSections);
	}

	int trackedPaneBlocks() {
		return trackedBlocks(paneChunkSections);
	}

	private static int trackedBlocks(Map<Long, Map<Integer, long[]>> sectionsMap) {
		int total = 0;
		for (Map<Integer, long[]> sections : sectionsMap.values())
			for (long[] positions : sections.values()) total += positions.length;
		return total;
	}

	/** spends up to FRAME_BUDGET_NANOS, render thread only: pending (just-loaded, never-scanned)
	 * sections first, nearest chunk to the camera first, then whatever budget is left over goes to
	 * the round-robin rescan cursor. a chunk-load burst while flying can queue dozens of chunks in
	 * one frame; this still only ever spends the one shared budget. */
	void refresh(ClientLevel level, BlockPos cameraPos, long gameTime) {
		evictFarChunks(cameraPos);
		long deadline = System.nanoTime() + frameBudgetNanos();
		if (drainPending(level, cameraPos, deadline)) return; // pending work ate the whole budget
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) return;
		ChunkPos center = ChunkPos.containing(cameraPos);
		if (cursor == null || cursor.done() || !cursor.center.equals(center)) cursor = new Cursor(center, cameraPos.getY());
		cursor.resume(level, this, remaining);
	}

	// leak-repairs item 5: onLoad tracks every chunk it is told about at any distance, and only
	// onUnload ever removed one -- a chunk this scan never got an unload event for (missed while the
	// scan was gated off, or a level change some other mod's event ordering slipped past) stayed
	// forever. once a second, drop anything further than EVICT_RADIUS_CHUNKS chebyshev chunks from
	// the camera; a cheap walk of chunkSections' own keys, same map lookups onLoad/onUnload use.
	// CHUNK_GATHER_RADIUS (4, declared further down next to positionsNear) plus 4 chunks of slack.
	private static final int EVICT_RADIUS_CHUNKS = 8;
	private long nextEvictMillis;

	private void evictFarChunks(BlockPos cameraPos) {
		long now = System.currentTimeMillis();
		if (now < nextEvictMillis) return;
		nextEvictMillis = now + 1000;
		int cameraChunkX = cameraPos.getX() >> 4, cameraChunkZ = cameraPos.getZ() >> 4;
		for (Long key : chunkSections.keySet()) {
			int dx = Math.abs(ChunkPos.getX(key) - cameraChunkX);
			int dz = Math.abs(ChunkPos.getZ(key) - cameraChunkZ);
			if (Math.max(dx, dz) <= EVICT_RADIUS_CHUNKS) continue;
			chunkSections.remove(key);
			waterChunkSections.remove(key);
			glassChunkSections.remove(key);
			paneChunkSections.remove(key);
			pendingByChunk.remove(key);
			if (pendingScan != null && pendingScan.chunkKey == key) pendingScan = null;
			revision++;
		}
	}

	/** returns true once the deadline is spent (whether or not pending work remains). picks the
	 * nearest not-yet-scanned chunk fresh each time the current one finishes, so the camera moving
	 * mid-burst still keeps scanning toward wherever it ended up. */
	private boolean drainPending(ClientLevel level, BlockPos cameraPos, long deadline) {
		int[] checked = {0};
		while (System.nanoTime() < deadline) {
			if (pendingScan == null) {
				Long nextKey = nearestPendingChunk(cameraPos);
				if (nextKey == null) return false; // nothing pending; let the round-robin cursor run
				IntArrayList sectionYs = pendingByChunk.get(nextKey);
				pendingScan = new PendingScan(nextKey, nearestSectionsFirst(sectionYs, cameraPos.getY()));
			}
			ChunkPos pos = ChunkPos.unpack(pendingScan.chunkKey);
			if (!(level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
				// unloaded between being queued and being drained -- onUnload should already have
				// cleared this, but a stale chunk reference here should never wedge the drain.
				pendingByChunk.remove(pendingScan.chunkKey);
				pendingScan = null;
				continue;
			}
			boolean outOfBudget = pendingScan.resume(chunk, this, deadline, checked);
			if (pendingScan.done()) {
				pendingByChunk.remove(pendingScan.chunkKey);
				pendingScan = null;
			}
			if (outOfBudget) return true;
		}
		return true;
	}

	/** small set (a frame's own chunk-load burst, not the whole tracked world) -- a plain walk is
	 * cheap, unlike sorting every tracked block the way positionsNear used to. */
	private Long nearestPendingChunk(BlockPos cameraPos) {
		if (pendingByChunk.isEmpty()) return null;
		int cameraChunkX = cameraPos.getX() >> 4, cameraChunkZ = cameraPos.getZ() >> 4;
		Long best = null;
		long bestDistSq = Long.MAX_VALUE;
		for (Long key : pendingByChunk.keySet()) {
			long dx = ChunkPos.getX(key) - cameraChunkX, dz = ChunkPos.getZ(key) - cameraChunkZ;
			long distSq = dx * dx + dz * dz;
			if (distSq < bestDistSq) { bestDistSq = distSq; best = key; }
		}
		return best;
	}

	/** at most ~24 sections a chunk -- insertion sort by distance from the camera's y is plenty. */
	private static int[] nearestSectionsFirst(IntArrayList sectionYs, int cameraY) {
		int[] out = sectionYs.toIntArray();
		for (int i = 1; i < out.length; i++) {
			int value = out[i];
			int valueDist = Math.abs(value * 16 + 8 - cameraY);
			int j = i - 1;
			while (j >= 0 && Math.abs(out[j] * 16 + 8 - cameraY) > valueDist) { out[j + 1] = out[j]; j--; }
			out[j + 1] = value;
		}
		return out;
	}

	// bumps revision only when the rescanned section's positions actually differ from what was there
	// before -- the round-robin cursor revisits every tracked section continuously, and a bump on
	// every visit regardless of change would force DepthEffects to rebuild every frame anyway,
	// defeating the point of the revision check.
	private void storeSection(long chunkKey, int sectionY, long[] positions) {
		storeSection(chunkSections, chunkKey, sectionY, positions);
	}

	private void storeWaterSection(long chunkKey, int sectionY, long[] positions) {
		storeSection(waterChunkSections, chunkKey, sectionY, positions);
	}

	private void storeGlassSection(long chunkKey, int sectionY, long[] positions) {
		storeSection(glassChunkSections, chunkKey, sectionY, positions);
	}

	private void storePaneSection(long chunkKey, int sectionY, long[] positions) {
		storeSection(paneChunkSections, chunkKey, sectionY, positions);
	}

	private void storeSection(Map<Long, Map<Integer, long[]>> sectionsMap, long chunkKey, int sectionY, long[] positions) {
		long[] previous = sectionsMap.computeIfAbsent(chunkKey, key -> new ConcurrentHashMap<>()).put(sectionY, positions);
		if (!Arrays.equals(previous, positions)) revision++;
	}

	/** true when the block at pos is water (source or flowing) and the block directly above it is
	 * not itself water -- the surface of a body of water, not a submerged block mid-column. the
	 * chunk's own top y stands in for "nothing above" at the loaded-column ceiling, since reading
	 * one block past it would leave this chunk's own data (LevelChunk spans its full height). */
	private static boolean isWaterSurface(LevelChunk chunk, BlockPos.MutableBlockPos pos, int chunkTopY) {
		FluidState fluid = chunk.getFluidState(pos);
		if (!isWater(fluid)) return false;
		if (pos.getY() >= chunkTopY) return true;
		return !isWater(chunk.getFluidState(pos.above()));
	}

	private static boolean isWater(FluidState fluid) {
		if (fluid.isEmpty()) return false;
		var type = fluid.getType();
		return type == Fluids.WATER || type == Fluids.FLOWING_WATER;
	}

	// item 2: positionsNear used to sort every tracked position in range, one 30 ms spike traced to
	// it -- gather only from chunks within chebyshev range of the camera first (a plain map lookup per
	// chunk, same key shape onLoad/onUnload already use), and sort only when that gather actually
	// exceeds `limit`; otherwise the gathered set is small enough to take whole.
	// rmls-cost brief item 2b: with 21598 water surfaces in range the gather itself was the cost, not
	// the sort -- a fixed radius-4 square (81 chunks) walked every chunk whether or not it had already
	// found `limit` positions. now walks chunks ring by ring outward from the camera's own chunk and
	// stops the moment `limit` positions have been found within range -- nearest chunks first is nearly
	// nearest-first, good enough for a mask that gets re-sorted below anyway when it overshoots.

	/** nearest-first positions within range of the camera, capped at limit (brief item 3: 4096). */
	List<BlockPos> positionsNear(Vec3 camera, double range, int limit) {
		return positionsNear(chunkSections, camera, range, limit);
	}

	/** same, for water surface positions (grass-glint brief item 1). */
	List<BlockPos> waterPositionsNear(Vec3 camera, double range, int limit) {
		return positionsNear(waterChunkSections, camera, range, limit);
	}

	/** same, for glass positions (glass brief item 2). */
	List<BlockPos> glassPositionsNear(Vec3 camera, double range, int limit) {
		return positionsNear(glassChunkSections, camera, range, limit);
	}

	/** same, for pane positions (panes brief item 2). */
	List<BlockPos> panePositionsNear(Vec3 camera, double range, int limit) {
		return positionsNear(paneChunkSections, camera, range, limit);
	}

	private static List<BlockPos> positionsNear(Map<Long, Map<Integer, long[]>> sectionsMap, Vec3 camera, double range, int limit) {
		double rangeSq = range * range;
		int centerChunkX = ((int) Math.floor(camera.x())) >> 4;
		int centerChunkZ = ((int) Math.floor(camera.z())) >> 4;
		// enough rings to cover `range` fully (range/16 chunks) plus one for the camera's own offset
		// inside its chunk -- a ring walk that stopped short of this would silently under-report.
		int maxRadius = (int) Math.ceil(range / 16.0) + 1;
		List<BlockPos> found = new ArrayList<>();
		ringSearch:
		for (int radius = 0; radius <= maxRadius; radius++) {
			for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
				for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
					// only the ring's own perimeter -- interior chunks at this radius were already
					// walked by an earlier, smaller radius.
					if (Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ)) != radius) continue;
					Map<Integer, long[]> sections = sectionsMap.get(ChunkPos.pack(chunkX, chunkZ));
					if (sections == null) continue;
					for (long[] positions : sections.values())
						for (long packed : positions) {
							BlockPos pos = BlockPos.of(packed);
							double distanceSq = pos.distToCenterSqr(camera.x(), camera.y(), camera.z());
							if (distanceSq <= rangeSq) found.add(pos);
						}
				}
			}
			if (found.size() >= limit) break ringSearch;
		}
		if (found.size() > limit) {
			found.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(camera.x(), camera.y(), camera.z())));
			found = found.subList(0, limit);
		}
		return found;
	}

	/** a chunk's not-yet-scanned sections, resumable one column at a time exactly like Cursor below --
	 * onLoad used to run this whole thing synchronously per chunk; now it is budget-sliced same as
	 * the round-robin rescan, just walking a caller-picked chunk instead of the camera's 3x3. */
	private static final class PendingScan {
		final long chunkKey;
		private final int[] sectionYs; // nearest-camera-y first, fixed for this scan's lifetime
		private int sectionIndex, localX, localZ;
		private LongList pendingFound, pendingWaterFound, pendingGlassFound, pendingPaneFound;
		private final BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();

		PendingScan(long chunkKey, int[] sectionYs) {
			this.chunkKey = chunkKey;
			this.sectionYs = sectionYs;
		}

		boolean done() { return sectionIndex >= sectionYs.length; }

		/** true if it ran out of budget mid-section (caller should stop for this frame). */
		boolean resume(LevelChunk chunk, MetalMaskScan owner, long deadline, int[] checked) {
			int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
			int chunkTopY = chunk.getMinY() + chunk.getHeight() - 1;
			while (sectionIndex < sectionYs.length) {
				int sectionY = sectionYs[sectionIndex];
				int baseY = sectionY * 16;
				if (pendingFound == null) pendingFound = new LongArrayList();
				if (pendingWaterFound == null) pendingWaterFound = new LongArrayList();
				if (pendingGlassFound == null) pendingGlassFound = new LongArrayList();
				if (pendingPaneFound == null) pendingPaneFound = new LongArrayList();
				for (; localX < 16; localX++) {
					for (; localZ < 16; localZ++) {
						for (int y = baseY; y < baseY + 16; y++) {
							cursorPos.set(baseX + localX, y, baseZ + localZ);
							BlockState state = chunk.getBlockState(cursorPos);
							if (MetalBlocks.isReflective(state))
								pendingFound.add(BlockPos.asLong(baseX + localX, y, baseZ + localZ));
							if (isWaterSurface(chunk, cursorPos, chunkTopY))
								pendingWaterFound.add(BlockPos.asLong(baseX + localX, y, baseZ + localZ));
							if (GlassBlocks.isGlass(state))
								pendingGlassFound.add(BlockPos.asLong(baseX + localX, y, baseZ + localZ));
							if (GlassBlocks.isPane(state))
								pendingPaneFound.add(BlockPos.asLong(baseX + localX, y, baseZ + localZ));
						}
						if (++checked[0] % TIME_CHECK_STRIDE == 0 && System.nanoTime() >= deadline) return true;
					}
					localZ = 0;
				}
				localX = 0;
				owner.storeSection(chunkKey, sectionY, pendingFound.toLongArray());
				owner.storeWaterSection(chunkKey, sectionY, pendingWaterFound.toLongArray());
				owner.storeGlassSection(chunkKey, sectionY, pendingGlassFound.toLongArray());
				owner.storePaneSection(chunkKey, sectionY, pendingPaneFound.toLongArray());
				pendingFound = null;
				pendingWaterFound = null;
				pendingGlassFound = null;
				pendingPaneFound = null;
				sectionIndex++;
			}
			return false;
		}
	}

	/** camera-centred 3x3 grid of chunks, each split into y-windowed sections, resumed one budget at a time. */
	private static final class Cursor {
		final ChunkPos center;
		private final int cameraY;
		private int chunkIndex, sectionOrdinal, localX, localZ;
		private int[] pendingSections;
		private LongList pendingFound, pendingWaterFound, pendingGlassFound, pendingPaneFound;
		private boolean finished;
		private final BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();

		Cursor(ChunkPos center, int cameraY) {
			this.center = center;
			this.cameraY = cameraY;
		}

		boolean done() { return finished; }

		void resume(ClientLevel level, MetalMaskScan owner, long budgetNanos) {
			long deadline = System.nanoTime() + budgetNanos;
			int checked = 0;
			while (chunkIndex < 9) {
				int dx = chunkIndex % 3 - 1, dz = chunkIndex / 3 - 1;
				int chunkX = center.x() + dx, chunkZ = center.z() + dz;
				if (!(level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
					advanceChunk();
					continue;
				}
				if (pendingSections == null) pendingSections = sectionsInWindow(chunk);
				if (sectionOrdinal >= pendingSections.length) {
					advanceChunk();
					continue;
				}
				int sectionY = pendingSections[sectionOrdinal];
				if (pendingFound == null) pendingFound = new LongArrayList();
				if (pendingWaterFound == null) pendingWaterFound = new LongArrayList();
				if (pendingGlassFound == null) pendingGlassFound = new LongArrayList();
				if (pendingPaneFound == null) pendingPaneFound = new LongArrayList();
				int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
				int baseY = sectionY * 16;
				int chunkTopY = chunk.getMinY() + chunk.getHeight() - 1;
				for (; localX < 16; localX++) {
					for (; localZ < 16; localZ++) {
						for (int y = baseY; y < baseY + 16; y++) {
							cursorPos.set(baseX + localX, y, baseZ + localZ);
							BlockState state = chunk.getBlockState(cursorPos);
							if (MetalBlocks.isReflective(state))
								pendingFound.add(BlockPos.asLong(baseX + localX, y, baseZ + localZ));
							if (isWaterSurface(chunk, cursorPos, chunkTopY))
								pendingWaterFound.add(BlockPos.asLong(baseX + localX, y, baseZ + localZ));
							if (GlassBlocks.isGlass(state))
								pendingGlassFound.add(BlockPos.asLong(baseX + localX, y, baseZ + localZ));
							if (GlassBlocks.isPane(state))
								pendingPaneFound.add(BlockPos.asLong(baseX + localX, y, baseZ + localZ));
						}
						if (++checked % TIME_CHECK_STRIDE == 0 && System.nanoTime() >= deadline) return;
					}
					localZ = 0;
				}
				localX = 0;
				owner.storeSection(chunk.getPos().pack(), sectionY, pendingFound.toLongArray());
				owner.storeWaterSection(chunk.getPos().pack(), sectionY, pendingWaterFound.toLongArray());
				owner.storeGlassSection(chunk.getPos().pack(), sectionY, pendingGlassFound.toLongArray());
				owner.storePaneSection(chunk.getPos().pack(), sectionY, pendingPaneFound.toLongArray());
				pendingFound = null;
				pendingWaterFound = null;
				pendingGlassFound = null;
				pendingPaneFound = null;
				sectionOrdinal++;
			}
			finished = true;
		}

		private void advanceChunk() {
			chunkIndex++;
			sectionOrdinal = 0;
			pendingSections = null;
		}

		private int[] sectionsInWindow(LevelChunk chunk) {
			List<Integer> sections = new ArrayList<>();
			for (int sectionY = chunk.getMinSectionY(); sectionY <= chunk.getMaxSectionY(); sectionY++) {
				int sectionMinY = sectionY * 16, sectionMaxY = sectionMinY + 15;
				if (cameraY + Y_WINDOW_BLOCKS < sectionMinY || cameraY - Y_WINDOW_BLOCKS > sectionMaxY) continue;
				sections.add(sectionY);
			}
			int[] out = new int[sections.size()];
			for (int i = 0; i < out.length; i++) out[i] = sections.get(i);
			return out;
		}
	}
}
