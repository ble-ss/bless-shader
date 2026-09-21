#version 330
#moj_import <bless:depth_common.glsl>

// bless-shadow-pass brief item 4: full-res, composited after contact and before ao. reconstructs
// the pixel's world position from WorldDepth (worldPosition(), the same helper light_resolve
// uses), reprojects it through the sun's own ShadowMatrix, and 3x3-PCFs the persistent shadow map.
uniform sampler2D WorldDepth;
uniform sampler2D ShadowMap;
// glass-light brief item 3: the accumulated tint of every stained window between the map and a given
// shadow-map texel (DepthPass.drawShadowTint) -- white when glass_light is off or nothing sat in the
// way, so this pass reduces to its pre-brief scalar darkening exactly.
uniform sampler2D ShadowTint;
out vec4 fragColor;

void main() {
    vec2 uv = pixelUv(gl_FragCoord.xy / SunScreen.zw);
    float depth = texture(WorldDepth, uv).r;
    // sky (cleared depth) and "not actually running this frame" (ShadowSettings.w, set by
    // DepthFrameInputs' daylight/underwater gate) both leave main colour untouched -- rgb 0 is a
    // no-op through the ZERO/ONE_MINUS_SRC_COLOR blend (glass-light brief item 3 changed this from
    // ONE_MINUS_SRC_ALPHA so the pass can write a full rgb multiplier, not only a darkening scalar --
    // vec4(0.0) is a no-op under both, so every early return below still needs no other change).
    if (depth <= 0.0 || ShadowSettings.w < 0.5) { fragColor = vec4(0.0); return; }
    vec3 world = worldPosition(uv, depth);
    // faces turned away from the sun are dark whatever the map says, and faces at a grazing angle
    // get a bias that grows with the slope: without both, the tower's front wore stripes of acne
    // where its own depth and the map's disagreed by less than a texel.
    vec3 normal = viewNormal(WorldDepth, uv);
    float ndotl = dot(normal, ViewSun.xyz);
    // grazing-angle self-shadow stripes: a texel's own footprint on a near-flat surface stretches
    // long and thin along the sun's projection at low sun elevation, and the slope bias above alone
    // still leaves a diagonal band where the surface's depth and the map's disagree by less than that
    // stretched footprint. push the sampled point along the surface's own WORLD normal (not the sun
    // direction -- that would leak light under overhangs) by the shadow map's world-space texel size
    // (DepthSettings.w -- see DepthPass.uploadScene; ShadowSettings.y is uv-space, 1/resolution, and
    // samples the map itself, not a world offset) before it is ever projected into the map, scaled by
    // how grazing the angle is so a face looking straight at the sun is not pushed at all.
    vec3 worldNormal = normalize((ViewToWorld * vec4(normal, 0.0)).xyz);
    float normalOffset = DepthSettings.w * clamp(1.5 * (1.0 - clamp(ndotl, 0.0, 1.0)), 0.0, 1.5);
    world += worldNormal * normalOffset;
    vec4 shadowClip = ShadowMatrix * vec4(world, 1.0);
    vec3 shadowNdc = shadowClip.xyz / shadowClip.w;
    vec2 shadowUv = shadowNdc.xy * 0.5 + 0.5;
    // outside the light's own box (DepthSettings.x reused: same device-wide zero-to-one convention
    // WorldDepth's own ndc uses, not a WorldDepth-specific flag) -- nothing to darken out there.
    bool debug = ShadowSettings.w > 1.5;
    // debug's 0.6 marker used to ride alpha under the old ONE_MINUS_SRC_ALPHA blend; the new
    // ONE_MINUS_SRC_COLOR blend reads rgb instead, so the same darkening amount now sits there.
    if (shadowUv.x < 0.0 || shadowUv.x > 1.0 || shadowUv.y < 0.0 || shadowUv.y > 1.0) { fragColor = vec4(vec3(debug ? 0.6 : 0.0), 1.0); return; }
    float shadowDepth = DepthSettings.x > 0.5 ? shadowNdc.z : shadowNdc.z * 0.5 + 0.5;
    float slope = clamp(tan(acos(clamp(ndotl, 0.0, 1.0))), 0.0, 4.0);
    float bias = ShadowSettings.z * (1.0 + 2.0 * slope);
    float texel = ShadowSettings.y;
    float occluded = 0.0;
    for (int dx = -1; dx <= 1; dx++)
        for (int dy = -1; dy <= 1; dy++) {
            float stored = texture(ShadowMap, shadowUv + vec2(float(dx), float(dy)) * texel).r;
            if (shadowDepth - bias > stored) occluded += 1.0;
        }
    // rori's striped wall (2026-09-21): a wall the sun skims along has an n.l near zero, and there
    // the map's texels stretch along the wall into bands no bias cures. the old rule went dark below
    // 0.05 and trusted the map above it, a cliff right where the stripes live. ease into the dark
    // over 0.05 to 0.25 instead: a wall lit that obliquely is dim by any measure, and the bands go.
    float terminator = smoothstep(0.05, 0.25, ndotl);
    occluded = mix(9.0, occluded, terminator);
    occluded /= 9.0;
    if (debug) {
        float mode = ShadowSettings.w - 1.0;
        float stored0 = texture(ShadowMap, shadowUv).r;
        float shown = mode > 3.5 ? 1.0 - clamp(ndotl, 0.0, 1.0) : (mode > 2.5 ? shadowDepth : (mode > 1.5 ? stored0 : occluded));
        // same rgb-instead-of-alpha move as the two returns above.
        fragColor = vec4(vec3(clamp(shown, 0.0, 1.0)), 1.0);
        return;
    }
    // the brief's own shape: main *= 1 - s * strength * ViewSun.w * skyLightGate, skyLightGate
    // fixed at 1 -- the lightmap is not available in this post stage (brief item 4's own note).
    // glass-light brief item 3: the old "occluded <= 0" early return skipped exactly the case this
    // brief needs -- a fully lit pixel still passes through here now, tint sampled at the same
    // shadowUv the PCF loop above used, so a sunny floor under a stained window comes out coloured
    // and not only a shadowed one. shadowFactor 0 and tint white both fall out to the old vec4(0.0).
    float shadowFactor = clamp(occluded * ShadowSettings.x * ViewSun.w, 0.0, 1.0);
    vec3 tint = texture(ShadowTint, shadowUv).rgb;
    fragColor = vec4(1.0 - (1.0 - shadowFactor) * tint, 1.0);
}
