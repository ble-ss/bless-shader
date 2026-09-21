#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    vec3 p = viewPosition(uv, max(depth, 0.000001));
    // derivatives precede divergent exits. unstable silhouette normals disable this pixel.
    vec3 dx = dFdx(p), dy = dFdy(p);
    vec3 normal = cross(dx, dy);
    float normalLength2 = dot(normal, normal);
    float distance = -p.z;
    fragColor = vec4(0.0, packViewDepth(clamp(distance, 0.0, DepthSettings.y)), 1.0);
    if (depth <= 0.0 || distance <= 0.05 || distance >= 24.0 || normalLength2 < 0.00000001)
        return;
    if (max(length(dx), length(dy)) > max(0.25, distance * 0.05)) return;
    normal *= inversesqrt(normalLength2);
    if (dot(normal, -p) < 0.0) normal = -normal;
    float receivingLight = max(dot(normal, ViewSun.xyz), 0.0);
    if (receivingLight <= 0.05) return;

    float shadow = 0.0;
    // deterministic short steps out to a knob-scaled total reach; no temporal history,
    // randomized march or off-screen fallback. steps=4, reach=1.0 marches the same 0.8-unit
    // total distance the fixed four-step version always did.
    int steps = clamp(int(EffectTuning.z + 0.5), 2, 8);
    float totalReach = 0.8 * EffectTuning.y;
    for (int i = 0; i < steps; ++i) {
        float fraction = (float(i) + 1.0) / float(steps);
        float t = fraction * totalReach;
        vec3 ray = p + normal * 0.035 + ViewSun.xyz * t;
        vec4 clip = WorldProjection * vec4(ray, 1.0);
        if (clip.w <= 0.0001) break;
        vec2 sampleUv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(sampleUv, vec2(0.001))) || any(greaterThan(sampleUv, vec2(0.999)))) break;
        float sceneDepth = texture(WorldDepth, pixelUv(sampleUv)).r;
        if (sceneDepth <= 0.0) continue;
        // unclamped on purpose: viewDepth() saturates at the packing limit and would read every far
        // surface as nearer than the ray (the ao mask drew a ring at the limit from the same call).
        float gap = -ray.z + viewPosition(pixelUv(sampleUv), sceneDepth).z;
        // finite thickness rejects unrelated foreground crossings. blocks are a full unit thick, so the
            // window is 1.5 units: the 0.10 the draft used let every ray pass straight through a slab.
        if (gap > 0.025 && gap < 1.5) {
            // strongest right at the contact line (small fraction), fading to zero at full reach.
            shadow = 1.0 - fraction;
            break;
        }
    }
    fragColor.r = clamp(shadow * receivingLight * ViewSun.w * EffectTuning.x, 0.0, 1.0);
}
