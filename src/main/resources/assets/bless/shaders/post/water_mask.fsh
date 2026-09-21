#version 330
#moj_import <bless:depth_common.glsl>
#moj_import <bless:voxel_common.glsl>

uniform sampler2D WorldDepth;
uniform sampler2D ReflectionScratch;
// brief item 4 (grass-glint brief item 1, glass brief item 5): the material mask
// DepthPass.drawMetalMask already drew this frame -- r > 0.5 means this pixel sits on a block from
// the reflective tag, g > 0.5 means it sits on a water surface, b > 0.5 means it sits on a glass
// block. always bound (see DepthPipelines.WATER_MASK's doc comment):
// DepthEffects clears it to zero and skips whichever draw its own feature flag is off for, so
// either branch simply never fires without ever needing a second pipeline variant. this replaces
// the old "guess from a depth gap against a pre-translucent copy" (captureOpaqueDepth, now gone) --
// that gap disagreed with the frame often enough on rori's modded instance to put a sun glint on
// grass, since grass has no fluid state at all and was never meant to read as water.
uniform sampler2D MetalMask;
// wetness (bless-wet brief item 3): the voxel copy of the world, read only for its occluder flag --
// same atlas the light/gi/volumetric stages bind, VoxelAtlasOrFallback's 1x1 stand-in when there is
// no volume at all (colored_light/voxel_gi/volumetric_light all off).
uniform sampler2D VoxelAtlas;
in vec2 texCoord;
out vec4 fragColor;
// second target (brief item 4, "the second target, cleanest"): r > 0.5 tells water_resolve.fsh this
// hit is a metal reflection, not a water one, so it can drop the ripple/fresnel-water math already
// baked into fragColor's confidence and apply metal's own formula instead.
out vec4 kindColor;

// duplicated house hash (see ssao_mask.fsh's aoHash doc comment for why this is copied rather
// than shared) -- keyed on world xz (item 6), reconstructed from view-space p.xz by rotating
// back through the camera's yaw and offsetting by the camera's world position, so the ripple
// pattern is pinned to the world instead of sliding with the camera.
float waterHash(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// repair 2026-09-21 item 10: a filter cell (VOX_FLAG_FILTER, 32 -- glass, panes) blocks rain the
// same as a real occluder would: rain does not fall through a glass roof. same bit-check pattern
// gi_trace.fsh already uses for the same flag, kept here rather than shared so this file stays
// independent of that stage's own include chain.
bool wetSkyBlocked(float flags) {
    if (flags >= VOX_FLAG_OCCLUDER) return true;
    return mod(floor(flags / VOX_FLAG_FILTER), 2.0) > 0.5;
}

// wetness (bless-wet brief item 3): true when nothing stands between worldPos and the sky, walking
// straight up worldPos's own voxel column from one cell above it to the top of the volume. an
// invalid volume (VolumeOrigin.w < 0.5) is treated as covered -- no voxel data to prove the sky
// open, so no puddle guesses off it -- and so is a column outside the volume's xz footprint.
//
// repair 2026-09-21 item 7: the walk used to fetch every cell one at a time, up to the whole column
// height. it now strides 4 cells at a time and only drops to a one-at-a-time refine over the up to
// 4 cells below the first strided step that came back covered -- same bounds, same first-occluder
// result, a fraction of the texelFetch calls for a typical open-sky column.
bool wetSkyOpen(vec3 worldPos) {
    if (VolumeOrigin.w < 0.5) return false;
    vec3 local = worldPos - VolumeOrigin.xyz;
    if (any(lessThan(local.xz, vec2(0.0))) || any(greaterThanEqual(local.xz, vec2(VOX_SIZE_X, VOX_SIZE_Z)))) return false;
    int x = int(floor(local.x));
    int z = int(floor(local.z));
    int startY = max(int(floor(local.y)) + 1, 0);
    int topY = int(VOX_SIZE_Y);
    int y = startY;
    for (; y < topY; y += 4) {
        vec4 voxel = texelFetch(VoxelAtlas, voxelTexel(ivec3(x, y, z)), 0);
        float flags = floor(voxel.a * 255.0 + 0.5);
        if (wetSkyBlocked(flags)) break;
    }
    if (y >= topY) return true; // strided all the way to the top without finding cover
    int refineStart = max(y - 3, startY);
    for (int ry = refineStart; ry <= y; ++ry) {
        vec4 voxel = texelFetch(VoxelAtlas, voxelTexel(ivec3(x, ry, z)), 0);
        float flags = floor(voxel.a * 255.0 + 0.5);
        if (wetSkyBlocked(flags)) return false;
    }
    return true;
}

void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    // default: no reflection here, of either kind.
    fragColor = vec4(0.0);
    kindColor = vec4(0.0);
    if (depth <= 0.0) return;

    vec4 material = texture(MetalMask, uv);
    bool metal = material.r > 0.5;
    bool water = material.g > 0.5;
    // glass brief item 5: b > 0.5 is a glass block -- marched the same way metal is (view normal
    // from depth, no ripple: a windowpane does not flow), but resolved with its own dielectric
    // fresnel in water_resolve.fsh, so it rides its own kind channel rather than metal's.
    bool glass = material.b > 0.5;
    vec3 p = viewPosition(uv, depth);
    // wetness (bless-wet brief item 3): a pixel none of the three masks claim is still a wet-ground
    // candidate when the wet value is up and it faces the sky -- everything else about it (no ripple,
    // its own reconstructed normal, the same screen-space march) mirrors the metal/glass branch below.
    bool wet = false;
    float wetScale = 1.0;
    vec3 n;
    if (!metal && !water && !glass) {
        if (MetalSettings.w <= 0.01) return; // wetness off (or bone dry) -- no reflection here.
        vec3 viewN = viewNormal(WorldDepth, uv);
        vec3 worldNormal = normalize((ViewToWorld * vec4(viewN, 0.0)).xyz);
        if (worldNormal.y <= 0.7) return; // only faces open toward the sky are candidates.
        vec3 worldPos = CameraPosition.xyz + (ViewToWorld * vec4(p, 0.0)).xyz;
        if (!wetSkyOpen(worldPos)) return; // something stands over this column -- stays dry.
        float puddleNoise = waterHash(floor(worldPos.xz / 3.0));
        float puddle = smoothstep(0.55, 0.7, puddleNoise);
        wet = true;
        wetScale = MetalSettings.w * mix(0.25, 1.0, puddle);
        n = viewN;
    }
    // the kind rides to water_resolve.fsh whether or not the march hits: r metal, g water, b glass,
    // a wet ground (bless-wet brief item 3). the resolve gates its sun glint on g, so a grass pixel
    // (none of the four) never sparkles at the mirror angle.
    kindColor = vec4(metal ? 1.0 : 0.0, water ? 1.0 : 0.0, glass ? 1.0 : 0.0, wet ? 1.0 : 0.0);
    if (metal || glass) {
        // a metal or glass surface reflects itself, not the water plane -- its own reconstructed
        // normal (depth_common's 4-tap normal, already used by ao/contact), no ripple.
        n = viewNormal(WorldDepth, uv);
    } else if (water) {
        // grass-glint brief item 1: water_mask.fsh used to guess a translucent surface from a depth
        // gap against a pre-translucent copy (captureOpaqueDepth) -- gone now, along with the
        // sun-glinting-on-grass false positives it produced. the g channel is a real water-surface
        // mask (MetalMaskScan's own block scan), so this branch is only ever reached over water.
        n = normalize(ViewUp.xyz);
        float yawC = cos(CameraPosition.w), yawS = sin(CameraPosition.w);
        vec2 worldXz = CameraPosition.xz + vec2(yawC * p.x - yawS * p.z, yawS * p.x + yawC * p.z);
        vec3 ripple = vec3(waterHash(worldXz), waterHash(worldXz + 17.0), waterHash(worldXz + 43.0)) * 0.03 - 0.015;
        n = normalize(n + ripple);
    }
    // wet already carries its own normal (viewN, no ripple) set above.
    vec3 r = reflect(normalize(p), n);

    float step = max(ReflectionSettings.y, 0.05);
    vec3 rayPos = p;
    // item 8: two steps of head start before the first test, so a grazing reflection ray never
    // hits the water surface it started from.
    rayPos += r * step;
    step *= 1.05;
    rayPos += r * step;
    step *= 1.05;

    bool behindCamera = false;
    for (int i = 0; i < 24; ++i) {
        rayPos += r * step;
        step *= 1.05;
        vec4 clip = WorldProjection * vec4(rayPos, 1.0);
        if (clip.w <= 0.0001) { behindCamera = true; break; }
        vec2 sampleUv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(sampleUv, vec2(0.0))) || any(greaterThan(sampleUv, vec2(1.0)))) break;
        float sceneRaw = texture(WorldDepth, pixelUv(sampleUv)).r;
        if (sceneRaw <= 0.0) continue;
        float sceneDistance = -viewPosition(pixelUv(sampleUv), sceneRaw).z; // unclamped, see contact_mask
        float rayDistance = -rayPos.z;
        float gap = rayDistance - sceneDistance;
        if (gap > 0.0 && gap < max(0.5, step)) {
            float edgeDist = min(min(sampleUv.x, 1.0 - sampleUv.x), min(sampleUv.y, 1.0 - sampleUv.y));
            float edgeFade = smoothstep(0.0, 0.1, edgeDist);
            float stepFade = 1.0 - float(i) / 24.0;
            // wetness (bless-wet brief item 3): confidence scaled by wet * mix(0.25, 1.0, puddle);
            // wetScale is 1.0 on every non-wet branch, a no-op there.
            fragColor = vec4(texture(ReflectionScratch, sampleUv).rgb, edgeFade * stepFade * wetScale);
            return;
        }
    }
    // ran out of screen or steps without a hit -- a lake under open sky reflects the sky, not
    // nothing (item 4). a ray that went behind the camera is left at the zero default above.
    // metal gets no such sky fallback: an unresolved metal reflection stays unlit rather than
    // borrowing the sky colour a bare block face was never going to show.
    // the sky stands in wherever the march found nothing, metal included and a ray that went
    // behind the camera included: a wall facing you reflects the room behind you, which no
    // screen-space march can see, and a faint sky is closer to that than nothing (the iron wall
    // on the metal scene changed by nothing at all before this). metal takes it a little fainter;
    // glass rides the same fainter fallback (glass brief item 5) -- a windowpane is a bare surface
    // too, not a water plane under open sky.
    fragColor = vec4(SceneFogColor.rgb, ((metal || glass) ? 0.25 : 0.35) * wetScale);
}
