#ifndef RMLS_CLIENT_VOXEL_COMMON
#define RMLS_CLIENT_VOXEL_COMMON
// the voxel volume's layout, in lock step with VoxelVolume.java (change both): SIZE_Y y-slices of
// SIZE_X x SIZE_Z, tiled GRID_W x GRID_H in one 2d texture -- this engine has no 3d textures and no
// compute, so the atlas is the volume. two textures share the layout and VolumeOrigin: the voxel
// atlas (rgb albedo, a flag byte) and the light atlas (rgb flood-filled coloured block light).
const float VOX_SIZE_X = 128.0, VOX_SIZE_Y = 64.0, VOX_SIZE_Z = 128.0;
const float VOX_GRID_W = 8.0, VOX_GRID_H = 8.0;
const vec2 VOX_TEXTURE_SIZE = vec2(VOX_SIZE_X * VOX_GRID_W, VOX_SIZE_Z * VOX_GRID_H); // 1024 x 1024
const vec3 VOX_SIZE = vec3(VOX_SIZE_X, VOX_SIZE_Y, VOX_SIZE_Z);
const float VOX_FLAG_OCCLUDER = 128.0, VOX_FLAG_FLUID = 64.0;
// glass-light brief item 2: a filter voxel (glass) -- march() lets a ray pass through it rather than
// stopping, multiplying its own carried throughput by the voxel's rgb (its tint) first.
const float VOX_FLAG_FILTER = 32.0;

// integer texel of local cell (x, y, z), no filtering -- the trace's own fetch.
ivec2 voxelTexel(ivec3 cell) {
    int tileX = cell.y - (cell.y / int(VOX_GRID_W)) * int(VOX_GRID_W);
    int tileY = cell.y / int(VOX_GRID_W);
    return ivec2(tileX * int(VOX_SIZE_X) + cell.x, tileY * int(VOX_SIZE_Z) + cell.z);
}

bool voxelInside(ivec3 cell) {
    return all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(VOX_SIZE)));
}

// one y-slice's tile, addressed in the grid; xz already clamped half a texel inside the tile so
// bilinear filtering never bleeds into its neighbour.
vec3 voxelFetchSlice(sampler2D atlas, float slice, vec2 xz) {
    float tileX = mod(slice, VOX_GRID_W);
    float tileY = floor(slice / VOX_GRID_W);
    vec2 uv = (vec2(tileX, tileY) * vec2(VOX_SIZE_X, VOX_SIZE_Z) + xz) / VOX_TEXTURE_SIZE;
    return texture(atlas, uv).rgb;
}

// trilinear: hardware bilinear across x/z (within one tile), a manual lerp across the two nearest
// y-slices. fades to zero over the volume's outer 8 blocks so the edge of the volume never shows.
vec3 voxelSampleSmooth(sampler2D atlas, vec3 worldPos) {
    if (VolumeOrigin.w < 0.5) return vec3(0.0);
    vec3 local = worldPos - VolumeOrigin.xyz;
    if (any(lessThan(local, vec3(0.0))) || any(greaterThanEqual(local, VOX_SIZE))) return vec3(0.0);
    float fy = clamp(local.y - 0.5, 0.0, VOX_SIZE_Y - 1.0);
    float y0 = floor(fy);
    float y1 = min(y0 + 1.0, VOX_SIZE_Y - 1.0);
    float ty = fy - y0;
    vec2 xz = clamp(local.xz, vec2(0.5), vec2(VOX_SIZE_X - 0.5, VOX_SIZE_Z - 0.5));
    vec3 value = mix(voxelFetchSlice(atlas, y0, xz), voxelFetchSlice(atlas, y1, xz), ty);
    vec3 edge = min(local, VOX_SIZE - local);
    float fade = clamp(min(edge.x, min(edge.y, edge.z)) / 8.0, 0.0, 1.0);
    return value * fade;
}

// how far a world point sits inside the volume, 0 at its faces and beyond, 1 from 8 blocks in.
float voxelInsideFade(vec3 worldPos) {
    if (VolumeOrigin.w < 0.5) return 0.0;
    vec3 local = worldPos - VolumeOrigin.xyz;
    vec3 edge = min(local, VOX_SIZE - local);
    return clamp(min(edge.x, min(edge.y, edge.z)) / 8.0, 0.0, 1.0);
}
#endif
