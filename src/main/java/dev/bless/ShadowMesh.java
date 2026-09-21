package dev.bless;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * bless sun shadows, part a: a depth-only block mesh of the world around the camera, drawn from the
 * sun's view instead of drawing vanilla's chunks a second time. chunk/BlockState access is
 * render-thread-only in this engine (the trap this shelf has hit before), so every world read here
 * happens on the render thread inside {@link #tick}; the worker thread ({@link #meshOnWorker}) only
 * ever touches the primitive boolean[] shell it was handed, exactly the split LightVolume uses
 * between its render-thread snapshot and its worker-thread flood fill.
 *
 * per-section state (the GpuBuffer, the dirty flag, `sections` itself, `pendingRelease`,
 * `queuedForMesh`) is render-thread-only; only `uploadQueue` crosses threads, and it is a
 * ConcurrentLinkedQueue for exactly that reason.
 *
 * not a general voxel mesher: no entities. a full-cube occluder gets face culling against its
 * neighbours (see {@link #isOpaque}); a stair, slab, wall or fence -- non-occluding by that test,
 * but not empty air either -- gets its own collision-shape boxes collected here in copyShell and
 * drawn whole by ShadowSection.buildMesh (item 1's fix for the wide horizontal stripes those blocks
 * used to leave in the shadow).
 */
final class ShadowMesh implements AutoCloseable {
	// render-thread budget for copying opacity shells into snapshots each frame -- sections are
	// small (5.6k-ish BlockState reads each including the face slabs), so this covers several
	// sections most frames rather than needing LightVolume's multi-frame incremental resumption.
	private static final long SNAPSHOT_BUDGET_NANOS = 1_000_000L; // 1 ms
	private static final int MAX_UPLOADS_PER_FRAME = 4;
	// a section drops once the camera is this far past the radius, so a section right at the edge
	// does not get created and dropped every time the camera drifts a block either way.
	private static final int DROP_MARGIN_BLOCKS = 32;
	// copyShell budget item 3: the most sections the copy budget ever spends in one frame, and the
	// backoff/restore around it -- see tick()'s own comment.
	private static final int MAX_SECTIONS_PER_TICK = 8;
	private static final long TICK_BACKOFF_THRESHOLD_NANOS = 2_000_000L; // 2 ms
	private static final long TICK_CAP_RESTORE_MILLIS = 1000L; // restore the halved cap over a second
	// item 1: a stair's toAabbs() can hand back several boxes (an outer-corner stair is two); most
	// blocks that matter here (stairs, slabs, walls, fences) are one or two. four covers every vanilla
	// shape seen so far with room to spare, without ever letting one weird modded block's shape balloon
	// a section's vertex count.
	// repair 2026-09-21 item 11: raised 4 -> 8 (mod shapes with more boxes were silently truncated);
	// dropped boxes past this cap now count into shapeBoxesDropped instead of vanishing unlogged.
	private static final int MAX_SHAPE_BOXES_PER_BLOCK = 8;
	// item 6: a single-box shape spanning at least this fraction of the cube on every axis (farmland,
	// dirt path, mud, soul sand, snow at 7 layers) is treated as a full cube for the shell -- cheaper,
	// and the sliver gap is below what a shadow map can ever show.
	private static final float NEAR_FULL_SPAN = 0.875f;

	private final int radiusBlocks;

	private final Map<Long, ShadowSection> sections = new HashMap<>(); // render-thread only
	// dirty section keys, kept alongside `sections` so dirtySectionsNearestFirst never walks the
	// whole map -- add on markDirty, remove once a section is handed to the worker (queuedForMesh)
	// or dropped. render-thread only, same as `sections`.
	private final Set<Long> dirtyKeys = new HashSet<>();
	// chunk key (new ChunkPos(x, z).pack()) -> the section keys that chunk owns, kept so chunkTracked and
	// markDirtyIfChunkTracked are a map lookup plus a walk of just that chunk's own sections (up to
	// its height in 16-block slices) instead of every section this mesh tracks. render-thread only.
	private final Map<Long, List<Long>> chunkSectionKeys = new HashMap<>();
	private final Set<Long> queuedForMesh = ConcurrentHashMap.newKeySet();
	private final ConcurrentLinkedQueue<UploadResult> uploadQueue = new ConcurrentLinkedQueue<>();
	// buffers swapped out this frame, closed at the top of the next tick() -- same deferred-release
	// shape as FarTerrainPass's pendingRelease, so a buffer still bound to this frame's draw is never
	// closed out from under it.
	private final List<GpuBuffer> pendingRelease = new ArrayList<>();

	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "bless-shadow-mesh");
		thread.setDaemon(true);
		return thread;
	});

	private volatile float lastMeshMillis;
	private boolean closed;

	// running totals, kept up to date at every point a section's buffer or vertex count changes
	// (drainUploads, dropFarSections, chunkUnloaded, close) so sectionsReady()/verticesTotal() are a
	// field read, not a walk of every section every frame.
	private int sectionsReadyCount;
	private long verticesTotalCount;
	// bumped whenever drawAll's set of drawn geometry could have changed -- a section uploaded a new
	// buffer, or a section dropped. DepthEffects compares this instead of verticesTotal() so a
	// same-vertex-count swap (one section's gain offsetting another's loss) still triggers a redraw.
	private long meshRevisionCount;

	// dropFarSections throttle: the scan itself still walks every section (distance is not indexed),
	// but only runs once a second and only once the camera has actually moved -- see the brief.
	private long nextDropMillis;
	private boolean hasDropped;
	private int lastDropCameraX, lastDropCameraY, lastDropCameraZ;

	// copyShell budget backoff (item 3): -1 means the cap sits at MAX_SECTIONS_PER_TICK; once a tick's
	// own copy loop runs past TICK_BACKOFF_THRESHOLD_NANOS this holds the millis the halving started,
	// and effectiveSectionCap() ramps back up to the max over TICK_CAP_RESTORE_MILLIS.
	private long capRestoreStartMillis = -1;

	private record UploadResult(long key, ShadowSection.MeshResult mesh, long meshNanos) {}

	private static final ShadowSection[] EMPTY_SECTIONS = new ShadowSection[0];

	ShadowMesh(int radiusBlocks) {
		this.radiusBlocks = radiusBlocks;
	}

	// item 2's other half: a chunk-load burst while flying can fire this event path dozens of times
	// in one frame, each one walking a whole chunk's sections synchronously. the first
	// MAX_EVENT_CHUNK_LOADS_PER_FRAME still adopt immediately (a lone chunk load is cheap and should
	// stay instant); the rest of that frame's arrivals get queued and picked up by adoptLoadedChunks'
	// own per-tick cap instead, same as a chunk this mesh only discovered by polling the radius.
	private static final int MAX_EVENT_CHUNK_LOADS_PER_FRAME = 8;
	private int chunkLoadsThisFrame;
	private final Set<Long> adoptQueue = new LinkedHashSet<>(); // chunk keys, render-thread only

	/** ClientChunkEvents.CHUNK_LOAD, registered by the pass seat. own-chunk sections are (re)created
	 * dirty; the four neighbour chunks' sections change their exposed faces too, but only when they
	 * already have a mesh -- a neighbour that has never been meshed picks this chunk up naturally the
	 * first time it is meshed itself. */
	void chunkLoaded(LevelChunk chunk) {
		// leak-repairs item 6: a chunk this far outside the drop radius would only be dropped again by
		// dropFarSections' next pass -- never adopt it in the first place. ticks==0 guards the handful
		// of chunk loads that can land before tick() ever runs and populates lastCameraX/Z.
		if (ticks > 0 && tooFarToAdopt(chunk.getPos().x(), chunk.getPos().z())) return;
		if (++chunkLoadsThisFrame > MAX_EVENT_CHUNK_LOADS_PER_FRAME) {
			adoptQueue.add(new ChunkPos(chunk.getPos().x(), chunk.getPos().z()).pack());
			return;
		}
		adoptChunkNow(chunk);
	}

	private boolean tooFarToAdopt(int chunkX, int chunkZ) {
		double dropRadius = radiusBlocks + DROP_MARGIN_BLOCKS;
		double dx = (chunkX * 16 + 8) - lastCameraX;
		double dz = (chunkZ * 16 + 8) - lastCameraZ;
		return dx * dx + dz * dz > dropRadius * dropRadius;
	}

	/** the actual per-chunk adoption work chunkLoaded and adoptLoadedChunks share. */
	private void adoptChunkNow(LevelChunk chunk) {
		int chunkX = chunk.getPos().x(), chunkZ = chunk.getPos().z();
		for (int sectionY = chunk.getMinSectionY(); sectionY <= chunk.getMaxSectionY(); sectionY++) {
			markDirty(chunkX, sectionY, chunkZ);
		}
		markDirtyIfChunkTracked(chunkX - 1, chunkZ);
		markDirtyIfChunkTracked(chunkX + 1, chunkZ);
		markDirtyIfChunkTracked(chunkX, chunkZ - 1);
		markDirtyIfChunkTracked(chunkX, chunkZ + 1);
	}

	/** ClientChunkEvents.CHUNK_UNLOAD: drop every section this chunk owns outright. the chunk index
	 * hands back exactly this chunk's own section keys, so this no longer walks every section this
	 * mesh tracks. */
	void chunkUnloaded(LevelChunk chunk) {
		int chunkX = chunk.getPos().x(), chunkZ = chunk.getPos().z();
		long chunkKey = new ChunkPos(chunkX, chunkZ).pack();
		adoptQueue.remove(chunkKey); // never adopt a chunk that left again before its deferred turn
		List<Long> keys = chunkSectionKeys.remove(chunkKey);
		if (keys == null) return;
		for (long key : keys) {
			ShadowSection section = sections.remove(key);
			if (section == null) continue;
			dropsUnload++;
			queuedForMesh.remove(key);
			dirtyKeys.remove(key);
			releaseCounts(section);
			section.close();
		}
	}

	/** the pass seat's block-change hook. marks this block's own section dirty, and whichever
	 * face-adjacent section (at most one per axis, three at a shared corner) is already tracked --
	 * same "only if it already has a mesh to fix" rule as chunkLoaded's neighbours. */
	void blockChanged(BlockPos pos) {
		int sectionX = SectionPos.blockToSectionCoord(pos.getX());
		int sectionY = SectionPos.blockToSectionCoord(pos.getY());
		int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
		markDirty(sectionX, sectionY, sectionZ);
		int localX = SectionPos.sectionRelative(pos.getX());
		int localY = SectionPos.sectionRelative(pos.getY());
		int localZ = SectionPos.sectionRelative(pos.getZ());
		if (localX == 0) markDirtyIfTracked(sectionX - 1, sectionY, sectionZ);
		if (localX == ShadowSection.SIZE - 1) markDirtyIfTracked(sectionX + 1, sectionY, sectionZ);
		if (localY == 0) markDirtyIfTracked(sectionX, sectionY - 1, sectionZ);
		if (localY == ShadowSection.SIZE - 1) markDirtyIfTracked(sectionX, sectionY + 1, sectionZ);
		if (localZ == 0) markDirtyIfTracked(sectionX, sectionY, sectionZ - 1);
		if (localZ == ShadowSection.SIZE - 1) markDirtyIfTracked(sectionX, sectionY, sectionZ + 1);
	}

	// chunks already loaded before this mesh existed never fire CHUNK_LOAD for it (the first proof had a
	// tower in view and zero sections). the event path stays the fast lane for chunks arriving later;
	// this is the slow lane for everything else, plus the deferred backlog from chunkLoaded's own
	// per-frame cap above.
	private static final int MAX_ADOPTS_PER_TICK = 4;
	// diagnosis counters for the status json: the first proofs showed zero sections with a tower in view.
	volatile int chunksAdopted, ticks, shellsCopied, meshesBuilt, meshesEmpty, uploads, dropsFar, dropsUnload, created;
	// item 1: how many partial-shape boxes (stairs, slabs, walls, fences) copyShell has collected in
	// total -- a section-stats counter beside the vertex totals, for the status json.
	volatile int shapeBoxesEmitted;
	// repair 2026-09-21 item 11: boxes past MAX_SHAPE_BOXES_PER_BLOCK a copyShell had to drop.
	volatile int shapeBoxesDropped;
	volatile int lastCameraX, lastCameraY, lastCameraZ;
	volatile int peakReady; volatile long peakVertices;

	/** item 2: used to adopt every untracked chunk in the radius in one tick -- hundreds of chunks and
	 * thousands of freshly-created ShadowSection objects in a single frame, once a second, worst right
	 * when a chunk-load burst also fires the event path. now capped at MAX_ADOPTS_PER_TICK per call and
	 * run every tick (no timer gate) so a backlog drains steadily instead of arriving all at once. the
	 * deferred adoptQueue (chunkLoaded's overflow) gets first claim, nearest to the camera first; only
	 * once it is empty does this fall back to scanning the radius for chunks that never fired an event
	 * at all (the original "tower in view, zero sections" case). */
	private void adoptLoadedChunks(ClientLevel level, BlockPos camera) {
		int adopted = drainAdoptQueue(level, camera);
		if (adopted >= MAX_ADOPTS_PER_TICK) return;
		int chunkRadius = (radiusBlocks >> 4) + 1;
		int centreX = camera.getX() >> 4, centreZ = camera.getZ() >> 4;
		List<int[]> untracked = new ArrayList<>();
		for (int cx = centreX - chunkRadius; cx <= centreX + chunkRadius; cx++) {
			for (int cz = centreZ - chunkRadius; cz <= centreZ + chunkRadius; cz++) {
				if (!chunkTracked(cx, cz)) untracked.add(new int[]{cx, cz});
			}
		}
		if (untracked.isEmpty()) return;
		untracked.sort(Comparator.comparingLong(c -> {
			long dx = c[0] - centreX, dz = c[1] - centreZ;
			return dx * dx + dz * dz;
		}));
		for (int[] pos : untracked) {
			if (adopted >= MAX_ADOPTS_PER_TICK) break;
			ChunkAccess chunk = level.getChunkSource().getChunk(pos[0], pos[1], ChunkStatus.FULL, false);
			if (chunk instanceof LevelChunk levelChunk) { adoptChunkNow(levelChunk); chunksAdopted++; adopted++; }
		}
	}

	private int drainAdoptQueue(ClientLevel level, BlockPos camera) {
		int adopted = 0;
		if (adoptQueue.isEmpty()) return adopted;
		int centreX = camera.getX() >> 4, centreZ = camera.getZ() >> 4;
		List<Long> ordered = new ArrayList<>(adoptQueue);
		ordered.sort(Comparator.comparingLong(key -> {
			ChunkPos pos = ChunkPos.unpack(key);
			long dx = pos.x() - centreX, dz = pos.z() - centreZ;
			return dx * dx + dz * dz;
		}));
		for (long key : ordered) {
			if (adopted >= MAX_ADOPTS_PER_TICK) break;
			adoptQueue.remove(key);
			ChunkPos pos = ChunkPos.unpack(key);
			if (chunkTracked(pos.x(), pos.z())) continue; // the event path already caught it since queuing
			ChunkAccess chunk = level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
			if (chunk instanceof LevelChunk levelChunk) { adoptChunkNow(levelChunk); chunksAdopted++; adopted++; }
		}
		return adopted;
	}

	/** render thread, once per frame: drains finished uploads, drops sections the camera has left
	 * behind, then spends up to SNAPSHOT_BUDGET_NANOS copying dirty sections' opacity shells (nearest
	 * first) and handing each one to the worker thread. */
	void tick(ClientLevel level, BlockPos camera) {
		RenderSystem.assertOnRenderThread();
		if (closed) return;
		ticks++;
		chunkLoadsThisFrame = 0; // item 2: chunkLoaded's per-frame burst cap resets once per tick
		lastCameraX = camera.getX(); lastCameraY = camera.getY(); lastCameraZ = camera.getZ();
		drainUploads();
		peakReady = Math.max(peakReady, sectionsReady()); peakVertices = Math.max(peakVertices, verticesTotal());
		dropFarSections(camera);
		adoptLoadedChunks(level, camera);

		int sectionCap = effectiveSectionCap();
		long deadline = System.nanoTime() + SNAPSHOT_BUDGET_NANOS;
		long copyLoopStart = System.nanoTime();
		for (ShadowSection section : nearestDirtySections(camera, sectionCap)) {
			if (System.nanoTime() >= deadline) break;
			if (!queuedForMesh.add(section.key)) continue;
			section.dirty = false;
			dirtyKeys.remove(section.key);
			ShellSnapshot snapshot = copyShell(level, section);
			shellsCopied++;
			worker.submit(() -> meshOnWorker(section.key, snapshot.shell(), snapshot.shapeBoxes(),
				section.originX(), section.originY(), section.originZ()));
		}
		recordCopyLoopNanos(System.nanoTime() - copyLoopStart);
	}

	/** the cap for how many sections this tick's copy loop will touch -- MAX_SECTIONS_PER_TICK
	 * normally, ramping back up from half over TICK_CAP_RESTORE_MILLIS after a backoff (see
	 * recordCopyLoopNanos). */
	private int effectiveSectionCap() {
		if (capRestoreStartMillis < 0) return MAX_SECTIONS_PER_TICK;
		long elapsed = System.currentTimeMillis() - capRestoreStartMillis;
		if (elapsed >= TICK_CAP_RESTORE_MILLIS) {
			capRestoreStartMillis = -1;
			return MAX_SECTIONS_PER_TICK;
		}
		int halved = Math.max(1, MAX_SECTIONS_PER_TICK / 2);
		return halved + (int) ((MAX_SECTIONS_PER_TICK - halved) * elapsed / (double) TICK_CAP_RESTORE_MILLIS);
	}

	/** item 3's backoff: a copy loop that ran past the 2 ms threshold halves the cap for the next
	 * tick, then effectiveSectionCap() ramps it back up over a second. */
	private void recordCopyLoopNanos(long copyLoopNanos) {
		if (copyLoopNanos > TICK_BACKOFF_THRESHOLD_NANOS) capRestoreStartMillis = System.currentTimeMillis();
	}

	/** binds each ready section's vertex buffer and issues its draw. the pass seat sets the pipeline
	 * and uniforms before calling this. */
	int drawAll(RenderPass pass) {
		int verticesDrawn = 0;
		for (ShadowSection section : sections.values()) {
			if (section.buffer == null || section.vertexCount == 0) continue;
			pass.setVertexBuffer(0, section.buffer.slice());
			pass.draw(section.vertexCount, 1, 0, 0);
			verticesDrawn += section.vertexCount;
		}
		return verticesDrawn;
	}

	int sectionsTotal() { return sections.size(); }

	int sectionsReady() { return sectionsReadyCount; }

	int sectionsPending() { return dirtyKeys.size(); }

	/** item 4: the dirty set's raw size, exposed under its own status name. */
	int dirtySectionsCount() { return dirtyKeys.size(); }

	long verticesTotal() { return verticesTotalCount; }

	/** bumped on every upload or drop -- DepthEffects' redraw test compares this instead of
	 * verticesTotal() so a same-total swap between two sections still counts as a change. */
	long meshRevision() { return meshRevisionCount; }

	float lastMeshMillis() { return lastMeshMillis; }

	@Override
	public void close() {
		closed = true;
		worker.shutdownNow();
		drainStaleUploads();
		for (ShadowSection section : sections.values()) { releaseCounts(section); section.close(); }
		sections.clear();
		dirtyKeys.clear();
		chunkSectionKeys.clear();
		for (GpuBuffer buffer : pendingRelease) buffer.close();
		pendingRelease.clear();
	}

	// leak-repairs item 1: RmlsClient's level-change hook. every section here belongs to the level
	// that just closed -- CHUNK_UNLOAD never fires for a respawn/portal trip, so without this the
	// section map, its indices and every buffer it held kept growing across every trip.
	void levelChanged() {
		drainStaleUploads();
		for (ShadowSection section : sections.values()) { releaseCounts(section); section.close(); }
		sections.clear();
		dirtyKeys.clear();
		chunkSectionKeys.clear();
		queuedForMesh.clear();
		adoptQueue.clear();
		for (GpuBuffer buffer : pendingRelease) buffer.close();
		pendingRelease.clear();
	}

	/** discards whatever the worker already finished but the render thread never drained -- item 7's
	 * memAlloc buffers are native memory, not cleaner-tracked allocateDirect ones, so they must be
	 * freed by hand here too or close()/levelChanged() would leak exactly what drainUploads fixed. */
	private void drainStaleUploads() {
		UploadResult stale;
		while ((stale = uploadQueue.poll()) != null) freeMeshBuffer(stale.mesh());
	}

	// ---- dirty tracking (render thread only) ----

	private void markDirty(int sectionX, int sectionY, int sectionZ) {
		long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
		ShadowSection section = sections.get(key);
		if (section == null) {
			section = new ShadowSection(key, sectionX, sectionY, sectionZ);
			sections.put(key, section);
			created++;
			chunkSectionKeys.computeIfAbsent(new ChunkPos(sectionX, sectionZ).pack(), ignored -> new ArrayList<>()).add(key);
		}
		section.dirty = true;
		dirtyKeys.add(key);
	}

	private void markDirtyIfTracked(int sectionX, int sectionY, int sectionZ) {
		long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
		ShadowSection section = sections.get(key);
		if (section != null) { section.dirty = true; dirtyKeys.add(key); }
	}

	/** a map lookup for the chunk's own section keys (bounded by its height in 16-block slices),
	 * instead of a walk of every section this mesh tracks. */
	private void markDirtyIfChunkTracked(int chunkX, int chunkZ) {
		List<Long> keys = chunkSectionKeys.get(new ChunkPos(chunkX, chunkZ).pack());
		if (keys == null) return;
		for (long key : keys) {
			ShadowSection section = sections.get(key);
			if (section != null) { section.dirty = true; dirtyKeys.add(key); }
		}
	}

	/** a plain map lookup, no section walk -- see chunkSectionKeys' doc comment. */
	private boolean chunkTracked(int chunkX, int chunkZ) {
		List<Long> keys = chunkSectionKeys.get(new ChunkPos(chunkX, chunkZ).pack());
		return keys != null && !keys.isEmpty();
	}

	// throttled per the brief: the distance scan itself is still a full walk of `sections` (distance
	// is not indexed), but it now runs at most once a second and only once the camera has actually
	// moved more than DROP_MARGIN's own scale (16 blocks) since the last run -- a stationary or
	// slow-moving camera no longer pays this walk every frame.
	private void dropFarSections(BlockPos camera) {
		long now = System.currentTimeMillis();
		if (now < nextDropMillis) return;
		nextDropMillis = now + 1000;
		if (hasDropped) {
			long dx = (long) camera.getX() - lastDropCameraX;
			long dy = (long) camera.getY() - lastDropCameraY;
			long dz = (long) camera.getZ() - lastDropCameraZ;
			if (dx * dx + dz * dz <= 16L * 16L && dy * dy <= 16L * 16L) return;
		}
		hasDropped = true;
		lastDropCameraX = camera.getX();
		lastDropCameraY = camera.getY();
		lastDropCameraZ = camera.getZ();

		double dropRadius = radiusBlocks + DROP_MARGIN_BLOCKS;
		double dropRadiusSq = dropRadius * dropRadius;
		Iterator<Map.Entry<Long, ShadowSection>> iterator = sections.entrySet().iterator();
		while (iterator.hasNext()) {
			ShadowSection section = iterator.next().getValue();
			// leak-repairs item 6: dropFarSections used to cull by planar x/z only, so a section way
			// above or below the camera (another dimension's y range, or a player who tunnelled deep)
			// never left `sections` -- bound the vertical distance by radiusBlocks, same scale the
			// planar radius already uses (the DROP_MARGIN slack stays x/z-only, matching the existing
			// hysteresis against the planar edge).
			double verticalDist = Math.abs((section.originY() + ShadowSection.SIZE / 2.0) - camera.getY());
			if (planarDistanceSq(section, camera) > dropRadiusSq || verticalDist > radiusBlocks) {
				dropsFar++;
				queuedForMesh.remove(section.key);
				dirtyKeys.remove(section.key);
				removeFromChunkIndex(section);
				releaseCounts(section);
				section.close();
				iterator.remove();
			}
		}
	}

	private void removeFromChunkIndex(ShadowSection section) {
		long chunkKey = new ChunkPos(section.sectionX, section.sectionZ).pack();
		List<Long> keys = chunkSectionKeys.get(chunkKey);
		if (keys == null) return;
		keys.remove(Long.valueOf(section.key));
		if (keys.isEmpty()) chunkSectionKeys.remove(chunkKey);
	}

	/** call before a tracked section is dropped, while its buffer/vertexCount are still live -- keeps
	 * sectionsReadyCount/verticesTotalCount/meshRevisionCount correct without a re-scan. */
	private void releaseCounts(ShadowSection section) {
		if (section.buffer != null) sectionsReadyCount--;
		verticesTotalCount -= section.vertexCount;
		meshRevisionCount++;
	}

	/** walks only dirtyKeys, not every section this mesh tracks (see that field's doc comment), and
	 * never sorts the whole dirty set -- a chunk-load burst can dirty thousands of sections at once,
	 * and full sorts of that were 9 of 13 flight-recorder samples on this mesh. one pass keeps only
	 * the nearest `limit` candidates in a small fixed array via insertion (O(limit) per element,
	 * O(limit) total array size, never a list of every dirty section); a section outside the radius
	 * is skipped before anything else is computed. */
	private ShadowSection[] nearestDirtySections(BlockPos camera, int limit) {
		if (dirtyKeys.isEmpty() || limit <= 0) return EMPTY_SECTIONS;
		double radiusSq = (double) radiusBlocks * radiusBlocks;
		ShadowSection[] nearest = new ShadowSection[limit];
		double[] nearestDistSq = new double[limit];
		int count = 0;
		for (long key : dirtyKeys) {
			ShadowSection section = sections.get(key);
			if (section == null || !section.dirty || queuedForMesh.contains(key)) continue;
			double distSq = planarDistanceSq(section, camera);
			if (distSq > radiusSq) continue;
			if (count < limit) {
				int insertAt = count;
				while (insertAt > 0 && nearestDistSq[insertAt - 1] > distSq) {
					nearestDistSq[insertAt] = nearestDistSq[insertAt - 1];
					nearest[insertAt] = nearest[insertAt - 1];
					insertAt--;
				}
				nearestDistSq[insertAt] = distSq;
				nearest[insertAt] = section;
				count++;
			} else if (distSq < nearestDistSq[limit - 1]) {
				int insertAt = limit - 1;
				while (insertAt > 0 && nearestDistSq[insertAt - 1] > distSq) {
					nearestDistSq[insertAt] = nearestDistSq[insertAt - 1];
					nearest[insertAt] = nearest[insertAt - 1];
					insertAt--;
				}
				nearestDistSq[insertAt] = distSq;
				nearest[insertAt] = section;
			}
		}
		return count == limit ? nearest : Arrays.copyOf(nearest, count);
	}

	private static double planarDistanceSq(ShadowSection section, BlockPos camera) {
		double dx = (section.originX() + ShadowSection.SIZE / 2.0) - camera.getX();
		double dz = (section.originZ() + ShadowSection.SIZE / 2.0) - camera.getZ();
		return dx * dx + dz * dz;
	}

	// ---- the snapshot: render-thread state, primitives out. ----

	/** copyShell's own return: the 18x18x18 opacity shell plus whatever partial-shape boxes it found
	 * in this section's own 16x16x16 interior (see {@link ShadowSection.ShapeBox}). both are handed
	 * to the worker unchanged -- see buildMesh. */
	private record ShellSnapshot(boolean[] shell, List<ShadowSection.ShapeBox> shapeBoxes) {}

	/** copies one section's 18x18x18 opacity shell. the interior 16x16x16 plus its y-neighbour slabs
	 * (one block above and below -- always the same chunk column) come from the section's own chunk;
	 * the x/z boundary slabs come from the four side-neighbour chunks. an unloaded neighbour chunk
	 * leaves its slab at the array's default false -- the face draws as exposed until that chunk
	 * loads and remeshes this section via chunkLoaded's border-neighbour dirty.
	 *
	 * item 1: while walking the interior, a block that isn't opaque (so it won't get a face-culled
	 * full-cube quad) but also isn't a fluid and carries a non-empty, non-full-cube collision shape
	 * (a stair, slab, wall, fence...) has its own shape boxes read off getCollisionShape and stashed,
	 * section-local, for buildMesh to draw whole. only the interior (ly 0..15) collects these -- the
	 * halo cells above/below/across a chunk boundary belong to a neighbour section, which collects
	 * its own copy of the same block when it is that section's turn to be meshed. */
	private ShellSnapshot copyShell(ClientLevel level, ShadowSection section) {
		boolean[] shell = new boolean[ShadowSection.SHELL_VOLUME];
		ChunkAccess own = level.getChunkSource().getChunk(section.sectionX, section.sectionZ, ChunkStatus.FULL, false);
		if (own == null) return new ShellSnapshot(shell, List.of()); // chunk unloaded mid-flight; comes back empty, remeshed on its next dirty
		int originX = section.originX(), originY = section.originY(), originZ = section.originZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		List<ShadowSection.ShapeBox> shapeBoxes = null;
		for (int lx = 0; lx < ShadowSection.SIZE; lx++) {
			for (int lz = 0; lz < ShadowSection.SIZE; lz++) {
				for (int ly = -1; ly <= ShadowSection.SIZE; ly++) {
					int worldY = originY + ly;
					if (level.isOutsideBuildHeight(worldY)) continue;
					pos.set(originX + lx, worldY, originZ + lz);
					BlockState state = own.getBlockState(pos);
					// stairs and slabs canOcclude() in vanilla, so they used to enter the map as full cubes
					// and their own treads then sat inside that cube's shadow: the wide horizontal stripes.
					// the collision shape decides now: a full cube (or an occluder with no collision, like
					// leaves) fills the shell; anything partial goes in as its own boxes, opaque or not.
					boolean opaque = isOpaque(state);
					boolean interior = ly >= 0 && ly < ShadowSection.SIZE;
					// item 5: the cheap cached boolean decides full-vs-partial first, so the common case
					// (a plain full-cube block) never pays for a collision-shape fetch at all.
					if (opaque && state.isCollisionShapeFullBlock(level, pos)) {
						shell[ShadowSection.shellIndex(lx, ly, lz)] = true;
						continue;
					}
					if (!opaque && !(interior && !state.isAir() && state.getFluidState().isEmpty())) continue;
					// only the uncommon branch (opaque-but-partial, or a non-opaque interior solid) fetches
					// the real shape.
					VoxelShape shape = state.getCollisionShape(level, pos);
					if (shape.isEmpty()) {
						if (opaque) shell[ShadowSection.shellIndex(lx, ly, lz)] = true; // an occluder with no collision at all, like leaves
					} else if (interior) {
						List<AABB> boxes = shape.toAabbs();
						// item 6: one box spanning almost the whole cube (farmland, dirt path, mud, soul
						// sand, snow at 7 layers) is a shell fill, not worth its own boxes -- the 1/16 gap
						// never shows in a shadow map. only a genuinely partial shape goes to boxes.
						if (boxes.size() == 1 && isNearFullBox(boxes.get(0))) {
							shell[ShadowSection.shellIndex(lx, ly, lz)] = true;
						} else {
							if (shapeBoxes == null) shapeBoxes = new ArrayList<>();
							int count = Math.min(boxes.size(), MAX_SHAPE_BOXES_PER_BLOCK);
							for (int i = 0; i < count; i++) {
								AABB box = boxes.get(i);
								shapeBoxes.add(new ShadowSection.ShapeBox(
									lx + (float) box.minX, ly + (float) box.minY, lz + (float) box.minZ,
									lx + (float) box.maxX, ly + (float) box.maxY, lz + (float) box.maxZ));
							}
							shapeBoxesEmitted += count;
							if (boxes.size() > count) shapeBoxesDropped += boxes.size() - count;
						}
					}
				}
			}
		}
		fillSideSlab(level, shell, section.sectionX - 1, section.sectionZ, -1, originX, originY, originZ, true, pos);
		fillSideSlab(level, shell, section.sectionX + 1, section.sectionZ, ShadowSection.SIZE, originX, originY, originZ, true, pos);
		fillSideSlab(level, shell, section.sectionX, section.sectionZ - 1, -1, originX, originY, originZ, false, pos);
		fillSideSlab(level, shell, section.sectionX, section.sectionZ + 1, ShadowSection.SIZE, originX, originY, originZ, false, pos);
		return new ShellSnapshot(shell, shapeBoxes == null ? List.of() : shapeBoxes);
	}

	// one 16x16 face slab from a side-neighbour chunk. xAxis picks whether `edge` is the fixed local
	// x (a west/east neighbour) or local z (a north/south neighbour); the other in-plane coordinate
	// and ly both range 0..15 -- face checks only ever move one axis, so the shell's diagonal corners
	// (needed for neither) are never read and stay unfilled.
	private void fillSideSlab(ClientLevel level, boolean[] shell, int chunkX, int chunkZ, int edge,
			int originX, int originY, int originZ, boolean xAxis, BlockPos.MutableBlockPos pos) {
		ChunkAccess neighbour = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (neighbour == null) return;
		for (int across = 0; across < ShadowSection.SIZE; across++) {
			for (int ly = 0; ly < ShadowSection.SIZE; ly++) {
				int worldY = originY + ly;
				if (level.isOutsideBuildHeight(worldY)) continue;
				int lx = xAxis ? edge : across;
				int lz = xAxis ? across : edge;
				pos.set(originX + lx, worldY, originZ + lz);
				// item 4: an opaque-but-partial neighbour (a stair, slab, wall, fence across the section
				// border) used to fill this shell cell by isOpaque() alone, culling a real face against a
				// block that only fills part of its own cube -- a shadow leak at every chunk seam it sat
				// on. same full-or-empty test copyShell's own interior uses: only a real full cube, or an
				// occluder with no collision at all (leaves), blocks a neighbour's face.
				BlockState neighbourState = neighbour.getBlockState(pos);
				if (occludesShell(level, pos, neighbourState)) shell[ShadowSection.shellIndex(lx, ly, lz)] = true;
			}
		}
	}

	/** a block occludes a shadow when it can occlude at all (canOcclude(), same test vanilla's own
	 * face culling uses) or is a LeavesBlock (a tree canopy is not a full block by that test, but
	 * rori wants a tree to cast a shadow). water and every other fluid never occludes, regardless of
	 * canOcclude -- checked first so a waterlogged occluding block still only reads as its solid
	 * half here, never the water. */
	private static boolean isOpaque(BlockState state) {
		if (!state.getFluidState().isEmpty()) return false;
		return state.canOcclude() || state.getBlock() instanceof LeavesBlock;
	}

	// item 4: whether this block, at this exact position, fills a shell cell outright -- opaque and
	// either collision-full (a real full cube) or with no collision at all (leaves' own case).
	// anything opaque but partial (a stair, slab, wall, fence at this boundary) belongs to whichever
	// section owns its interior and collects its own boxes there; it never blocks a neighbour's cell.
	private static boolean occludesShell(ClientLevel level, BlockPos pos, BlockState state) {
		if (!isOpaque(state)) return false;
		if (state.isCollisionShapeFullBlock(level, pos)) return true;
		return state.getCollisionShape(level, pos).isEmpty();
	}

	// item 6: a single box spanning at least NEAR_FULL_SPAN of the cube on every axis.
	private static boolean isNearFullBox(AABB box) {
		return (box.maxX - box.minX) >= NEAR_FULL_SPAN
			&& (box.maxY - box.minY) >= NEAR_FULL_SPAN
			&& (box.maxZ - box.minZ) >= NEAR_FULL_SPAN;
	}

	// ---- the mesh: worker thread only, primitives in, primitives out. ----

	private void meshOnWorker(long key, boolean[] shell, List<ShadowSection.ShapeBox> shapeBoxes,
			int originX, int originY, int originZ) {
		long start = System.nanoTime();
		ShadowSection.MeshResult result = ShadowSection.buildMesh(shell, shapeBoxes, originX, originY, originZ);
		meshesBuilt++;
		if (result == null) meshesEmpty++;
		long elapsed = System.nanoTime() - start;
		uploadQueue.add(new UploadResult(key, result, elapsed));
	}

	private void drainUploads() {
		for (GpuBuffer old : pendingRelease) old.close();
		pendingRelease.clear();

		int uploaded = 0;
		UploadResult result;
		while (uploaded < MAX_UPLOADS_PER_FRAME && (result = uploadQueue.poll()) != null) {
			queuedForMesh.remove(result.key());
			lastMeshMillis = result.meshNanos() / 1_000_000f;
			ShadowSection section = sections.get(result.key());
			if (section == null) { freeMeshBuffer(result.mesh()); continue; } // dropped or unloaded while meshing on the worker -- discard
			GpuBuffer old = section.buffer;
			boolean wasReady = old != null;
			long oldVertexCount = section.vertexCount;
			ShadowSection.MeshResult mesh = result.mesh();
			try {
				if (mesh != null) {
					GpuBuffer buffer = RenderSystem.getDevice()
						.createBuffer(() -> "bless shadow section", GpuBuffer.USAGE_VERTEX, mesh.data());
					section.buffer = buffer;
					section.vertexCount = mesh.vertexCount();
				} else {
					section.buffer = null;
					section.vertexCount = 0;
				}
			} finally {
				// leak-repairs item 7: buildMesh's data buffer is now MemoryUtil.memAlloc, not a
				// cleaner-tracked allocateDirect -- free it the moment the gpu upload has read it,
				// success or failure, the way DepthEffects.rebuildMetalVertexBuffer already does.
				freeMeshBuffer(mesh);
			}
			verticesTotalCount += section.vertexCount - oldVertexCount;
			boolean isReady = section.buffer != null;
			if (isReady != wasReady) sectionsReadyCount += isReady ? 1 : -1;
			meshRevisionCount++;
			if (old != null) pendingRelease.add(old);
			uploaded++;
		}
	}

	private static void freeMeshBuffer(ShadowSection.MeshResult mesh) {
		if (mesh != null) MemoryUtil.memFree(mesh.data());
	}
}
