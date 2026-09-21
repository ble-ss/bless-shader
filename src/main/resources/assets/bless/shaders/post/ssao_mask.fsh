#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
in vec2 texCoord;
out vec4 fragColor;

// the house dither hash (blessHash in color_grade.glsl), duplicated rather than imported so this
// pass does not drag the colour stage's own uniform in for one function -- same reasoning haze.fsh
// already gives for hazeHash.
float aoHash(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// an orthonormal basis around `n`, spun by `angle`; the per-pixel rotation is what keeps a low
// sample count from banding into visible rings.
vec3 aoTangent(vec3 n, float angle) {
    vec3 helper = abs(n.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 t = normalize(cross(helper, n));
    vec3 b = cross(n, t);
    return t * cos(angle) + b * sin(angle);
}

void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    vec3 p = viewPosition(uv, max(depth, 0.000001));
    float distance = -p.z;
    fragColor = vec4(0.0, packViewDepth(clamp(distance, 0.0, DepthSettings.y)), 1.0);
    if (depth <= 0.0) return;
    // beyond the packing limit the mask cannot carry a depth the resolve can trust, and the clamped
    // helper below would read every neighbour as nearer than the pixel and call it fully occluded:
    // that drew a dark ring across the far terrain at exactly the limit. no occlusion out there.
    if (distance >= DepthSettings.y) return;

    vec3 normal = viewNormal(WorldDepth, uv);
    int samples = clamp(int(AoSettings.x + 0.5), 4, 32);
    float radius = clamp(AoSettings.y, 0.25, 4.0);
    // rori's close-up smudge (2026-09-21): half a block from a door, a one-block kernel covers most
    // of the screen and twelve taps turn into a blotch. cap the radius at a third of the distance so
    // the kernel never grows past a fixed share of the frame; far away the world radius wins as before.
    radius = min(radius, max(0.08, distance * 0.35));
    float rotation = aoHash(gl_FragCoord.xy) * 6.2831853;
    float occlusion = 0.0;
    float tested = 0.0;
    for (int i = 0; i < samples; ++i) {
        // golden-angle spiral over the hemisphere: even coverage from a handful of taps, no rings,
        // no time term (the rotation is per-pixel, not per-frame, so the mask holds still).
        float fi = float(i) + 0.5;
        float turn = fi * 2.3999632 + rotation;
        float r = sqrt(fi / float(samples));
        float height = sqrt(max(0.0, 1.0 - r * r));
        vec3 sampleDir = normalize(aoTangent(normal, turn) * r + normal * max(height, 0.05));
        vec3 samplePos = p + sampleDir * radius;
        vec4 clip = WorldProjection * vec4(samplePos, 1.0);
        if (clip.w <= 0.0001) continue;
        vec2 sampleUv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(sampleUv, vec2(0.0))) || any(greaterThan(sampleUv, vec2(1.0)))) continue;
        float sceneDepth = texture(WorldDepth, pixelUv(sampleUv)).r;
        if (sceneDepth <= 0.0) continue;
        tested += 1.0;
        // unclamped: viewDepth() saturates at the packing limit, which is a lie this comparison must not hear.
        float sceneDistance = -viewPosition(pixelUv(sampleUv), sceneDepth).z;
        float sampleDistance = -samplePos.z;
        // range check: geometry farther away than the sample radius cannot occlude it, or a
        // distant wall behind a wide-open field would darken the whole frame.
        float rangeCheck = smoothstep(0.0, 1.0, radius / max(0.0001, abs(sceneDistance - sampleDistance)));
        // small bias keeps a flat surface from occluding itself on its own noise.
        occlusion += (sceneDistance <= sampleDistance - 0.02) ? rangeCheck : 0.0;
    }
    // divide by the taps that were actually tested: an off-screen or sky tap says nothing about
    // occlusion, and counting it as open thinned every corner near the frame edge.
    fragColor.r = tested > 0.0 ? clamp(occlusion / tested, 0.0, 1.0) : 0.0;
}
