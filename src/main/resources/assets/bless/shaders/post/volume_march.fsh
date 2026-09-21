#version 330
#moj_import <bless:depth_common.glsl>
#moj_import <bless:voxel_common.glsl>

// the air: volumetric light at half resolution. marches from the camera toward the pixel's surface
// (or VolumeSettings.w blocks out toward the sky) through a thin height fog, gathering sunlight
// where the shadow map says the sun reaches (the shafts), the flood-filled block light in the air
// (a lantern's glow) and a little sky, and attenuating by the fog it passes. rgb is the in-scattered
// light, a the transmittance left for the surface behind -- volume_resolve composites them with a
// ONE / SRC_ALPHA blend, so both are already what the blend needs.
uniform sampler2D WorldDepth;
uniform sampler2D LightAtlas;
uniform sampler2D ShadowMap;
// glass-light brief item 3: same tint texture gi_trace.fsh reads -- white (no-op) when glass_light
// is off (DepthEffects.shadowTintOrFallback).
uniform sampler2D ShadowTint;
in vec2 texCoord;
out vec4 fragColor;

float volHash(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// glass-light brief item 3: returns the sun's colour (tinted by any stained window between the map
// and this point) rather than a bare 0/1 -- see gi_trace.fsh's own copy of this function.
vec3 sunVisibility(vec3 world) {
    if (ShadowSettings.w < 0.5) return vec3(1.0);
    vec4 clip = ShadowMatrix * vec4(world, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    vec2 uv = ndc.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec3(1.0);
    float depth = DepthSettings.x > 0.5 ? ndc.z : ndc.z * 0.5 + 0.5;
    if (depth - ShadowSettings.z * 2.0 > texture(ShadowMap, uv).r) return vec3(0.0);
    return texture(ShadowTint, uv).rgb;
}

// fog density at a world point: a base that thins with height above the camera and thickens a
// little below it, so mist pools in hollows and the air overhead stays clear.
float density(vec3 world) {
    float height = world.y - CameraPosition.y;
    return VolumeSettings.y * (0.35 + 0.65 * exp(-max(height + 6.0, 0.0) / 14.0));
}

void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    // the ray: from the camera through this pixel, in world space.
    vec3 viewFar = viewPosition(uv, max(depth, 0.000001));
    vec3 dirView = normalize(viewFar);
    vec3 dir = normalize((ViewToWorld * vec4(dirView, 0.0)).xyz);
    float surface = depth <= 0.0 ? VolumeSettings.w : length(viewFar);
    float tMax = min(surface, VolumeSettings.w);
    int steps = clamp(int(VolumeSettings.z + 0.5), 4, 64);
    float dt = tMax / float(steps);
    float jitter = volHash(gl_FragCoord.xy + fract(GiFrame.x * 0.61803) * 17.0);
    vec3 worldSun = normalize((ViewToWorld * vec4(ViewSun.xyz, 0.0)).xyz);
    // henyey-greenstein forward scattering toward the sun: the shafts brighten looking sunward.
    float cosSun = dot(dir, worldSun);
    // g 0.4 and a 2.5 gain: over the full 64 blocks at the default density this in-scatters about
    // 0.28 looking straight at the sun and 0.02 looking away -- g 0.55 at a gain of 6 blew the whole
    // sunward sky to white in the golden-hour grove.
    float g = 0.4;
    float phase = (1.0 - g * g) / (4.0 * 3.14159265 * pow(1.0 + g * g - 2.0 * g * cosSun, 1.5));
    vec3 sunLight = SunColor.rgb * phase * 2.5;
    vec3 skyLight = SkyColor.rgb * 0.06;

    vec3 scattered = vec3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < steps; i++) {
        float t = (float(i) + jitter) * dt;
        vec3 point = CameraPosition.xyz + dir * t;
        float rho = density(point);
        vec3 light = sunLight * sunVisibility(point) + skyLight;
        // volume_glow (GiExtra.w, default 1.0): scales only the block-light in-scatter term, not the
        // sun/sky terms above -- a sea-lantern room's fine per-pixel jitter (volume_resolve's tent
        // filter cleans the rest) reads worst on this term specifically, so it's the one a player
        // can turn down without dimming the sun shafts.
        light += voxelSampleSmooth(LightAtlas, point) * (0.9 * LightSettings.x + 0.25) * GiExtra.w;
        float absorb = exp(-rho * dt);
        // energy-conserving integration of a constant source over the step.
        scattered += transmittance * light * (1.0 - absorb);
        transmittance *= absorb;
        if (transmittance < 0.01) break;
    }
    fragColor = vec4(scattered * VolumeSettings.x, transmittance);
}
