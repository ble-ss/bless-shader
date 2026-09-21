#version 330
#moj_import <bless:depth_common.glsl>
#moj_import <bless:voxel_common.glsl>

// the bounce: one diffuse bounce of light, path-traced through the voxel copy of the world at half
// resolution. every pixel throws GiSettings.y cosine-weighted rays off its surface; a ray that hits an
// occluding (or glowing) voxel returns that voxel's albedo lit by the sun (through the shadow map),
// the flood-filled block light beside it and a flat sky term, plus its own emission; a ray that leaves
// into the air returns the sky. the average is the pixel's indirect radiance, in the same units as
// vanilla's own colour (a sunlit white block reads about 1.0), and alpha carries how many rays saw
// the sky -- the bounce's own ambient occlusion. noisy on purpose: gi_accumulate and gi_blur clean it.
uniform sampler2D WorldDepth;
uniform sampler2D VoxelAtlas;
uniform sampler2D LightAtlas;
uniform sampler2D ShadowMap;
// glass-light brief item 3: the sun's own colour after every stained window on the way to a given
// shadow-map texel -- white (no-op) when glass_light is off (DepthEffects.shadowTintOrFallback).
uniform sampler2D ShadowTint;
in vec2 texCoord;
out vec4 fragColor;
out vec4 fragGeometry; // xyz view-space normal, w view depth -- for gi_blur's edge stops

// pcg-style integer hash; stable per pixel, spun per frame by GiFrame.x and per ray by the index.
uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}
float hashFloat(uint seed) { return float(pcg(seed)) / 4294967296.0; }

// sun visibility at a world point through the persistent shadow map (ShadowSettings.w says whether
// the map is live this frame); a point outside the map's box, or a frame with no map, counts as lit.
// glass-light brief item 3: returns the sun's own colour rather than a bare 0/1 -- white (no tint) in
// every case but "lit, and one or more stained windows sat between the map and this point", where
// ShadowTint carries the accumulated dye colour (DepthPass.drawShadowTint, multiplicative blend).
vec3 sunVisibility(vec3 world) {
    if (ShadowSettings.w < 0.5) return vec3(1.0);
    vec4 clip = ShadowMatrix * vec4(world, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    vec2 uv = ndc.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec3(1.0);
    float depth = DepthSettings.x > 0.5 ? ndc.z : ndc.z * 0.5 + 0.5;
    float stored = texture(ShadowMap, uv).r;
    if (depth - ShadowSettings.z * 3.0 > stored) return vec3(0.0);
    return texture(ShadowTint, uv).rgb;
}

// the amanatides-woo dda through the atlas, in volume-local block coordinates. returns true on a
// hit (an occluding or glowing voxel) with the hit point, the face normal it entered through and
// the voxel itself; a start inside a solid cell reports `startNormal` as its face. glass-light brief
// item 2: a filter voxel (glass) never stops the ray -- it multiplies `throughput` by the voxel's own
// tint (lerped toward white by MetalSettings.z, glass_tint_strength) and the march keeps going.
bool march(vec3 origin, vec3 dir, float reach, vec3 startNormal, out vec3 hitPoint, out vec3 hitNormal, out vec4 hitVoxel, out vec3 throughput) {
    vec3 local = origin - VolumeOrigin.xyz;
    ivec3 cell = ivec3(floor(local));
    ivec3 stepDir = ivec3(sign(dir));
    vec3 invDir = 1.0 / max(abs(dir), vec3(1e-5));
    vec3 next = (vec3(cell) + max(vec3(stepDir), vec3(0.0)) - local) * sign(dir) * invDir;
    // an axis the ray never crosses must never be the nearest boundary.
    next = mix(next, vec3(1e9), vec3(lessThan(abs(dir), vec3(1e-5))));
    vec3 tDelta = invDir;
    float t = 0.0;
    int axis = 1;
    throughput = vec3(1.0);
    for (int i = 0; i < 96; i++) {
        if (!voxelInside(cell)) return false;
        vec4 voxel = texelFetch(VoxelAtlas, voxelTexel(cell), 0);
        float flags = floor(voxel.a * 255.0 + 0.5);
        bool occluder = flags >= VOX_FLAG_OCCLUDER;
        bool filterCell = !occluder && mod(floor(flags / VOX_FLAG_FILTER), 2.0) > 0.5;
        if (filterCell) {
            throughput *= mix(vec3(1.0), voxel.rgb, clamp(MetalSettings.z, 0.0, 1.0));
        } else if (occluder || mod(flags, 16.0) > 0.0) {
            hitNormal = vec3(0.0);
            if (axis == 0) hitNormal.x = -float(stepDir.x); else if (axis == 1) hitNormal.y = -float(stepDir.y); else hitNormal.z = -float(stepDir.z);
            if (i == 0) hitNormal = startNormal;
            hitPoint = origin + dir * t;
            hitVoxel = voxel;
            return true;
        }
        if (next.x < next.y && next.x < next.z) { t = next.x; next.x += tDelta.x; cell.x += stepDir.x; axis = 0; }
        else if (next.y < next.z) { t = next.y; next.y += tDelta.y; cell.y += stepDir.y; axis = 1; }
        else { t = next.z; next.z += tDelta.z; cell.z += stepDir.z; axis = 2; }
        if (t > reach) return false;
    }
    return false;
}

// the light arriving at a hit: the sun through the shadow map, the flood-filled block light in the
// air beside it, a flat sky term -- what the voxel's albedo then reflects.
vec3 incoming(vec3 hitPoint, vec3 hitNormal, vec3 worldSun) {
    float ndotl = max(dot(hitNormal, worldSun), 0.0);
    vec3 sunVis = ndotl > 0.0 ? sunVisibility(hitPoint + hitNormal * 0.08) : vec3(0.0);
    vec3 sun = SunColor.rgb * sunVis * ndotl;
    vec3 flood = voxelSampleSmooth(LightAtlas, hitPoint + hitNormal * 0.6) * (LightSettings.x * 2.0 + 0.5);
    vec3 sky = SkyColor.rgb * SunColor.a * GiSettings.w * (0.55 + 0.45 * hitNormal.y);
    return sun + flood + sky;
}

// t is the ray length (march()'s own `t`, world blocks) to the hit -- a ray from the block right
// under a torch travels almost no distance before it hits the torch's own glowing voxel, so without
// the fade the full base factor lands on that one surface: a blinding hot square under every torch
// and stake block. fading it in over the first 1.5 blocks lets the emitter still glow on nearby
// walls (t large, fade at 1.0) without burning the block it sits on (t near 0, fade at its floor).
vec3 emissive(vec4 voxel, float t) {
    float flags = floor(voxel.a * 255.0 + 0.5);
    float fade = clamp(t / 1.5, 0.25, 1.0);
    return voxel.rgb * (mod(flags, 16.0) / 15.0) * GiExtra.z * fade;
}

vec3 cosineDirection(vec3 n, float u1, float u2) {
    vec3 tangent = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 bitangent = cross(n, tangent);
    float phi = 6.2831853 * u1;
    float sinTheta = sqrt(u2), cosTheta = sqrt(1.0 - u2);
    return normalize(tangent * (cos(phi) * sinTheta) + bitangent * (sin(phi) * sinTheta) + n * max(cosTheta, 0.02));
}

void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    if (depth <= 0.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); fragGeometry = vec4(0.0); return; }
    vec3 viewPos = viewPosition(uv, depth);
    vec3 viewN = viewNormal(WorldDepth, uv);
    fragGeometry = vec4(viewN, -viewPos.z);
    vec3 normal = normalize((ViewToWorld * vec4(viewN, 0.0)).xyz);
    vec3 world = CameraPosition.xyz + (ViewToWorld * vec4(viewPos, 0.0)).xyz;
    float inside = voxelInsideFade(world);
    if (inside <= 0.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec3 worldSun = normalize((ViewToWorld * vec4(ViewSun.xyz, 0.0)).xyz);

    // start a hair off the surface, along the normal, so the ray's first cell is the air in front of it.
    vec3 origin = world + normal * 0.03;
    int rays = clamp(int(GiSettings.y + 0.5), 1, 8);
    bool twoBounces = GiExtra.x > 1.5;
    float reach = GiSettings.z;
    uint pixelSeed = uint(gl_FragCoord.x) * 1973u + uint(gl_FragCoord.y) * 9277u + uint(GiFrame.x) * 26699u;
    // checkerboard trace: half the texels a frame, alternating by (x+y+frame) parity. fragGeometry
    // above is already written either way (cheap); a skipped texel marks itself unseen with alpha
    // -1 so gi_accumulate can rebuild it from its (traced, opposite-parity) edge neighbours.
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (GiExtra.y > 0.5 && ((pixel.x + pixel.y + int(GiFrame.x)) & 1) != 0) { fragColor = vec4(0.0, 0.0, 0.0, -1.0); return; }
    vec3 radiance = vec3(0.0);
    float skyHits = 0.0;
    for (int r = 0; r < rays; r++) {
        uint seed = pixelSeed + uint(r) * 7919u;
        // cosine-weighted hemisphere: the pdf already carries the n.l term, so hits add plain radiance.
        vec3 dir = cosineDirection(normal, hashFloat(seed), hashFloat(seed + 1u));
        vec3 hitPoint, hitNormal; vec4 voxel; vec3 throughput;
        if (!march(origin, dir, reach, normal, hitPoint, hitNormal, voxel, throughput)) {
            // a miss adds nothing: vanilla's own lightmap already lit this surface by the sky, so the
            // bounce only ever adds what surfaces reflect, and the miss count becomes the sky occlusion.
            skyHits += 1.0;
            continue;
        }
        vec3 albedo = voxel.rgb;
        vec3 light = incoming(hitPoint, hitNormal, worldSun);
        // t: march() doesn't hand its own `t` back out, so the ray length is just the distance from
        // where this ray started to where it landed -- exact, since march() walks a straight line.
        float t = distance(origin, hitPoint);
        // the second bounce: one more cosine ray off the hit, whose own hit's reflected light (and
        // emission) arrives at the first hit scaled by the first albedo -- light creeping round a corner.
        if (twoBounces) {
            vec3 dir2 = cosineDirection(hitNormal, hashFloat(seed + 2u), hashFloat(seed + 3u));
            vec3 secondOrigin = hitPoint + hitNormal * 0.03;
            vec3 hitPoint2, hitNormal2; vec4 voxel2; vec3 throughput2;
            if (march(secondOrigin, dir2, reach * 0.75, hitNormal, hitPoint2, hitNormal2, voxel2, throughput2))
                light += throughput2 * (voxel2.rgb * incoming(hitPoint2, hitNormal2, worldSun) + emissive(voxel2, distance(secondOrigin, hitPoint2)));
        }
        // item 2's firefly clamp: one lucky ray landing close to an emitter (or a very bright bounced
        // surface) can otherwise spike this single texel far past its neighbours; gi_blur's a-trous
        // steps would then spread that one spike into a soft glow patch around it. clamp per channel
        // before the sum so a genuinely bright hit still contributes, just not unboundedly. the glass
        // throughput multiplies after the clamp: a pane can only ever dim what passed it.
        radiance += throughput * min(albedo * light + emissive(voxel, t), vec3(3.0));
    }
    float inv = 1.0 / float(rays);
    // fade the whole result toward "open sky, no bounce" at the volume's edge so the boundary never shows.
    fragColor = vec4(radiance * inv * inside, mix(1.0, skyHits * inv, inside));
}
