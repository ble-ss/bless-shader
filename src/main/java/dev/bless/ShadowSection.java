package dev.bless;

import com.mojang.blaze3d.buffers.GpuBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * one 16x16x16 section's opaque-face shadow mesh: a GpuBuffer of position-only vertices (3 floats,
 * 12 bytes each, absolute world-space coordinates -- the pass subtracts the camera in-shader via
 * DepthScene's CameraPosition), two triangles per exposed face. {@link #buildMesh} is a pure
 * function of an 18x18x18 opacity shell (plus a short list of partial-shape boxes, see
 * {@link ShapeBox}) with no world access, so ShadowMesh runs it on its single worker thread;
 * everything else here (the GpuBuffer, the dirty flag, the section's own position) is
 * render-thread-only state ShadowMesh owns and mutates directly.
 *
 * not a general voxel mesher: no entities. a full-cube occluder gets face culling against its
 * neighbours (see ShadowMesh.isOpaque); a stair, slab, wall or fence -- non-occluding by that test,
 * but not empty air either -- gets its own {@code getCollisionShape} boxes drawn whole instead (item
 * 1's fix for the wide horizontal stripes those blocks used to leave in the shadow).
 */
final class ShadowSection {
	static final int SIZE = 16;
	// the opacity shell carries one block of neighbour context on every side, so a face exactly on
	// the section boundary still knows whether the block across it occludes: SIZE + 2 per axis.
	static final int SHELL = SIZE + 2;
	static final int SHELL_VOLUME = SHELL * SHELL * SHELL;

	private static final int BYTES_PER_VERTEX = 12; // 3 floats, position-only, no normal or uv
	private static final int VERTICES_PER_FACE = 6; // two triangles, no index buffer

	final long key;
	final int sectionX, sectionY, sectionZ; // section coordinates: worldBlockPos >> 4 on each axis

	GpuBuffer buffer;
	int vertexCount;
	boolean dirty = true;

	ShadowSection(long key, int sectionX, int sectionY, int sectionZ) {
		this.key = key;
		this.sectionX = sectionX;
		this.sectionY = sectionY;
		this.sectionZ = sectionZ;
	}

	int originX() { return sectionX * SIZE; }
	int originY() { return sectionY * SIZE; }
	int originZ() { return sectionZ * SIZE; }

	void close() {
		if (buffer != null) { buffer.close(); buffer = null; }
		vertexCount = 0;
	}

	/** index into the 18x18x18 opacity shell for local block (lx, ly, lz), each ranging -1..16 --
	 * shifted by one so the backing array itself stays 0-based. */
	static int shellIndex(int lx, int ly, int lz) {
		return (lx + 1) + (ly + 1) * SHELL + (lz + 1) * SHELL * SHELL;
	}

	/** a finished mesh's raw vertex data plus the vertex count it decodes to (data.remaining() / 12,
	 * kept alongside rather than recomputed so ShadowMesh never has to touch buffer position math). */
	record MeshResult(ByteBuffer data, int vertexCount) {}

	/** one partial-shape block's own collision box (stair, slab, wall, fence, ...), in coordinates
	 * local to this section (0..16 on each axis, same frame as a full block's own lx/ly/lz) --
	 * ShadowMesh.copyShell reads it off state.getCollisionShape(...).toAabbs() on the render thread,
	 * one section-local offset per box, up to its own per-block cap. buildMesh only ever adds this
	 * section's originX/Y/Z, the same as it already does for a full block's own local coordinate. */
	record ShapeBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}

	/**
	 * worker thread, pure arithmetic: builds this section's mesh from an 18x18x18 opacity shell (see
	 * shellIndex) plus shapeBoxes, the partial-shape boxes copyShell collected on the render thread.
	 * a full-block face is written whenever the centre cell occludes and its neighbour across that
	 * face does not; each shapeBox instead draws all six of its own faces unconditionally -- there
	 * are few of them (stairs, slabs, walls, fences), and working out which of a stair's faces touch
	 * a solid neighbour costs more than just drawing all six ever would. returns null when the
	 * section has no exposed faces and no shape boxes at all, so ShadowMesh never uploads an empty
	 * buffer.
	 */
	static MeshResult buildMesh(boolean[] shell, List<ShapeBox> shapeBoxes, int originX, int originY, int originZ) {
		// two passes: count faces first so the direct buffer is allocated exactly once at its final
		// size -- sections are small and this runs once per dirty section, never once per frame, so
		// the extra walk costs nothing next to a growable-buffer resize copy.
		int faces = 0;
		for (int lx = 0; lx < SIZE; lx++)
			for (int ly = 0; ly < SIZE; ly++)
				for (int lz = 0; lz < SIZE; lz++) {
					if (!shell[shellIndex(lx, ly, lz)]) continue;
					for (int face = 0; face < 6; face++) {
						if (!shell[shellIndex(lx + FACE_DX[face], ly + FACE_DY[face], lz + FACE_DZ[face])]) faces++;
					}
				}
		int shapeFaces = shapeBoxes.size() * 6;
		if (faces == 0 && shapeFaces == 0) return null;

		// the vulkan encoder reads the upload through its native address, so a heap buffer segfaults
		// inside Unsafe.copyMemory (LightVolume hit this first) -- direct, native order, same as there.
		// leak-repairs item 7: memAlloc instead of allocateDirect -- an allocateDirect buffer only
		// frees when the cleaner gets to it (unbounded delay under gc pressure, which is exactly when
		// this shelf can least afford it); ShadowMesh.drainUploads now frees this one by hand right
		// after the gpu upload reads it.
		ByteBuffer data = MemoryUtil.memAlloc((faces + shapeFaces) * VERTICES_PER_FACE * BYTES_PER_VERTEX);
		for (int lx = 0; lx < SIZE; lx++)
			for (int ly = 0; ly < SIZE; ly++)
				for (int lz = 0; lz < SIZE; lz++) {
					if (!shell[shellIndex(lx, ly, lz)]) continue;
					float wx = originX + lx, wy = originY + ly, wz = originZ + lz;
					for (int face = 0; face < 6; face++) {
						if (shell[shellIndex(lx + FACE_DX[face], ly + FACE_DY[face], lz + FACE_DZ[face])]) continue;
						writeFace(data, wx, wy, wz, face);
					}
				}
		for (ShapeBox box : shapeBoxes) {
			writeBox(data, originX + box.minX(), originY + box.minY(), originZ + box.minZ(),
				originX + box.maxX(), originY + box.maxY(), originZ + box.maxZ());
		}
		int vertexCount = (faces + shapeFaces) * VERTICES_PER_FACE;
		data.flip();
		return new MeshResult(data, vertexCount);
	}

	// face order: +x, -x, +y, -y, +z, -z.
	private static final int[] FACE_DX = {1, -1, 0, 0, 0, 0};
	private static final int[] FACE_DY = {0, 0, 1, -1, 0, 0};
	private static final int[] FACE_DZ = {0, 0, 0, 0, 1, -1};

	// two triangles covering the unit quad on this face of the block at (x, y, z). winding does not
	// matter for a depth-only pass, but a consistent outward winding costs nothing here.
	private static void writeFace(ByteBuffer out, float x, float y, float z, int face) {
		switch (face) {
			case 0 -> quad(out, x+1,y,z, x+1,y+1,z, x+1,y+1,z+1, x+1,y,z+1);   // +x
			case 1 -> quad(out, x,y,z+1, x,y+1,z+1, x,y+1,z, x,y,z);          // -x
			case 2 -> quad(out, x,y+1,z, x,y+1,z+1, x+1,y+1,z+1, x+1,y+1,z);  // +y
			case 3 -> quad(out, x,y,z+1, x,y,z, x+1,y,z, x+1,y,z+1);          // -y
			case 4 -> quad(out, x+1,y,z+1, x+1,y+1,z+1, x,y+1,z+1, x,y,z+1);  // +z
			default -> quad(out, x,y,z, x,y+1,z, x+1,y+1,z, x+1,y,z);         // -z
		}
	}

	// a shapeBox's own six faces, unconditionally -- no neighbour culling (see buildMesh's own doc on
	// why). same vertex order per face as writeFace, generalised from a unit cube to an arbitrary box.
	private static void writeBox(ByteBuffer out, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		quad(out, maxX,minY,minZ, maxX,maxY,minZ, maxX,maxY,maxZ, maxX,minY,maxZ); // +x
		quad(out, minX,minY,maxZ, minX,maxY,maxZ, minX,maxY,minZ, minX,minY,minZ); // -x
		quad(out, minX,maxY,minZ, minX,maxY,maxZ, maxX,maxY,maxZ, maxX,maxY,minZ); // +y
		quad(out, minX,minY,maxZ, minX,minY,minZ, maxX,minY,minZ, maxX,minY,maxZ); // -y
		quad(out, maxX,minY,maxZ, maxX,maxY,maxZ, minX,maxY,maxZ, minX,minY,maxZ); // +z
		quad(out, minX,minY,minZ, minX,maxY,minZ, maxX,maxY,minZ, maxX,minY,minZ); // -z
	}

	// two triangles, (a,b,c) and (a,c,d), for the quad a-b-c-d.
	private static void quad(ByteBuffer out, float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		vertex(out, ax, ay, az); vertex(out, bx, by, bz); vertex(out, cx, cy, cz);
		vertex(out, ax, ay, az); vertex(out, cx, cy, cz); vertex(out, dx, dy, dz);
	}

	private static void vertex(ByteBuffer out, float x, float y, float z) {
		out.putFloat(x).putFloat(y).putFloat(z);
	}
}
