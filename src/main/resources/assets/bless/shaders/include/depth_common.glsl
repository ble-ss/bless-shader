// scratch include: proposed assets/bless/shaders/include/depth_common.glsl.
layout(std140) uniform DepthScene {
    mat4 WorldProjection;
    mat4 InverseWorldProjection;
    vec4 ViewSun;       // xyz normalized toward actual sun, w daylight/weather gate weight
    vec4 SunScreen;     // xy projected sun center, zw full-resolution width/height
    vec4 DepthSettings; // x zero-to-one NDC, y depth packing limit (128), z sun elevation (cos sun angle),
                        // w shadow map world-space texel size (shadow_span / shadow_resolution, blocks --
                        // shadow_resolve's normal-offset bias; not ShadowSettings.y, which is uv-space)
    vec4 EffectTuning;  // x contact_strength, y contact_reach, z contact_steps, w rays_strength
    vec4 HazeSettings;  // x haze_distance (already resolved -- auto picked its number on the java side), y haze_strength, z haze_night, w unused
    vec4 HazeColor;     // rgb config haze tint, w unused
    vec4 SceneFogColor; // rgb the sky's own colour this frame (or HazeColor again if it could not be read), a haze_tint blend factor
    vec4 AoSettings;    // x ao_samples, y ao_radius (blocks), z ao_strength, w unused
    vec4 ViewUp;        // xyz world up (0,1,0) rotated into view space, w unused -- the water reflection plane normal
    vec4 ReflectionSettings; // x reflection_strength, y ray march step size (blocks), z glint_strength,
                         // w wet_strength (bless-wet brief item 5)
    vec4 CameraPosition; // xyz world-space camera position, w camera yaw (radians) -- keys the ripple hash on world xz
    vec4 VolumeOrigin;   // xyz light volume world origin (min corner, blocks), w = 1.0 when the volume is valid
    mat4 ViewToWorld;    // inverse view rotation -- world = CameraPosition.xyz + (ViewToWorld * vec4(viewPos, 0)).xyz
    vec4 LightSettings;  // x light_strength, y light_tint, zw unused
    // appended for the metal material mask (brief item 3/4): existing offsets above never move.
    mat4 ViewRotation; // camera.viewRotationMatrix -- the metal_mask vertex shader's only way to turn a
                        // world-space block position into the same view space every other effect already
                        // reconstructs from clip space; direction-only (no translation), camera position
                        // is subtracted from the world position before this multiplies it.
    vec4 MetalSettings; // x metal_strength, y glass_strength (glass brief item 1),
                         // z glass_tint_strength (glass-light brief item 3, was unused),
                         // w wet_value (bless-wet brief item 2): 0..1, DepthEffects's own per-frame
                         // rain/dry state, not a config knob -- see DepthPass.wetValue
    // appended for sun shadows (bless-shadow-pass brief item 1): existing offsets above never move.
    mat4 ShadowMatrix;   // the sun's own view-projection, world space in, shadow clip space out --
                          // ShadowMatrix * vec4(worldPos, 1.0), no camera subtraction (see
                          // core/shadow_depth.vsh's doc comment for why).
    vec4 ShadowSettings; // x shadow_strength, y shadow map texel size (uv units, 1/resolution --
                          // what the resolve's PCF loop actually samples with), z depth bias
                          // (shadow-map ndc-z units), w 1.0 when the stage is actually running this
                          // frame (daylight and requested), 0.0 otherwise
    // appended for the voxel path trace and the volumetric light (vulkan-client): offsets above never move.
    vec4 GiSettings;     // x gi_strength, y gi_rays, z gi_distance (blocks), w gi_sky (sky radiance scale)
    vec4 GiFrame;        // x frame index (per-frame jitter), y history blend weight, z 1.0 when the history
                          // textures hold a previous frame worth reprojecting, w unused
    mat4 PrevViewProjection; // the previous frame's projection * view rotation: clip = PrevViewProjection *
                          // vec4(world - PrevCameraPosition.xyz, 1.0) -- reprojection for the temporal pass
    vec4 PrevCameraPosition; // xyz the previous frame's camera world position, w unused
    vec4 VolumeSettings; // x volume_strength, y volume_density, z volume_steps, w volume_distance (blocks)
    vec4 SunColor;       // rgb the sun's light colour this frame (warm at the horizon, white overhead,
                          // already scaled by the daylight gate), a sky ambient scale for bounce hits
    vec4 SkyColor;       // rgb the sky's own colour at the zenith this frame, a the horizon blend
    vec4 GiExtra;        // x gi_bounces (1 or 2), y gi_checkerboard (1.0 traces only alternate
                          // texels this frame by (x+y+frame)&1, 0.0 traces every texel),
                          // z gi_emissive (emitter brightness scale, gi_trace's emissive()),
                          // w volume_glow (block-light in-scatter scale, volume_march's density term)
};

vec2 pixelUv(vec2 uv) {
    vec2 pixel = clamp(floor(uv * SunScreen.zw), vec2(0.0), SunScreen.zw - 1.0);
    return (pixel + 0.5) / SunScreen.zw;
}

vec3 viewPosition(vec2 uv, float depth) {
    float z = DepthSettings.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = InverseWorldProjection * vec4(uv * 2.0 - 1.0, z, 1.0);
    return p.xyz / p.w;
}

float viewDepth(vec2 uv, float depth) {
    return depth <= 0.0 ? DepthSettings.y : clamp(-viewPosition(uv, depth).z, 0.0, DepthSettings.y);
}

// light-volume item 2: a pixel's world position from its view-space position (viewPosition above)
// and the camera's own world transform -- ViewToWorld rotates the direction back to world space,
// CameraPosition.xyz supplies the translation viewPosition's own space has none of.
vec3 worldPosition(vec2 uv, float depth) {
    vec3 p = viewPosition(uv, depth);
    return CameraPosition.xyz + (ViewToWorld * vec4(p, 0.0)).xyz;
}

// masks use RGBA8_UNORM: r effect, gb 16-bit view depth, a valid sample.
vec2 packViewDepth(float depth) {
    float code = floor(clamp(depth / DepthSettings.y, 0.0, 1.0) * 65535.0 + 0.5);
    return vec2(floor(code / 256.0), mod(code, 256.0)) / 255.0;
}

float unpackViewDepth(vec2 value) {
    vec2 bytes = floor(value * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0 * DepthSettings.y;
}

// improved 4-tap normal reconstruction: on each axis, pick the neighbour whose depth is closer
// to the centre rather than always averaging both, so a silhouette edge never blends the
// foreground and background sample into one smeared normal. `depthTex` is passed in rather than
// declared here, since depth_common.glsl carries no WorldDepth binding of its own -- every
// importer already declares its own `uniform sampler2D WorldDepth` at its own binding.
vec3 viewNormal(sampler2D depthTex, vec2 uv) {
    vec2 texel = 1.0 / SunScreen.zw;
    float depthC = texture(depthTex, uv).r;
    float depthL = texture(depthTex, pixelUv(uv - vec2(texel.x, 0.0))).r;
    float depthR = texture(depthTex, pixelUv(uv + vec2(texel.x, 0.0))).r;
    float depthD = texture(depthTex, pixelUv(uv - vec2(0.0, texel.y))).r;
    float depthU = texture(depthTex, pixelUv(uv + vec2(0.0, texel.y))).r;
    vec3 pc = viewPosition(uv, max(depthC, 0.000001));
    vec3 pl = viewPosition(pixelUv(uv - vec2(texel.x, 0.0)), max(depthL, 0.000001));
    vec3 pr = viewPosition(pixelUv(uv + vec2(texel.x, 0.0)), max(depthR, 0.000001));
    vec3 pd = viewPosition(pixelUv(uv - vec2(0.0, texel.y)), max(depthD, 0.000001));
    vec3 pu = viewPosition(pixelUv(uv + vec2(0.0, texel.y)), max(depthU, 0.000001));
    vec3 dx = abs(depthL - depthC) < abs(depthR - depthC) ? (pc - pl) : (pr - pc);
    vec3 dy = abs(depthD - depthC) < abs(depthU - depthC) ? (pc - pd) : (pu - pc);
    // dx and dy are one pixel wide, so their lengths shrink with distance and the raw cross
    // product shrinks with the square of it. testing that against a fixed epsilon declared every
    // near surface degenerate: a floor 4.9 units out gave a squared length of 9.6e-9 and fell
    // back to the camera-facing normal, which buried the ao hemisphere in the floor. normalize
    // the two tangents first, so the test measures the angle between them and is scale free.
    float lengthX2 = dot(dx, dx);
    float lengthY2 = dot(dy, dy);
    if (lengthX2 < 1e-20 || lengthY2 < 1e-20) return vec3(0.0, 0.0, 1.0);
    vec3 normal = cross(dx * inversesqrt(lengthX2), dy * inversesqrt(lengthY2));
    float length2 = dot(normal, normal);
    if (length2 < 0.00000001) return vec3(0.0, 0.0, 1.0);
    normal *= inversesqrt(length2);
    if (dot(normal, -pc) < 0.0) normal = -normal;
    return normal;
}
