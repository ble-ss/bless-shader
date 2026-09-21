#version 330
#moj_import <bless:depth_common.glsl>

// the bounce's spatial denoise: one a-trous step over the half-resolution accumulated radiance,
// 5x5 taps spread by ResolveSettings.x texels, stopped by depth and by normal so the blur never
// crosses an edge. run twice (step 1, then 2) between gi_accumulate and gi_resolve.
uniform sampler2D WorldDepth;
uniform sampler2D EffectMask;
uniform sampler2D GiGeometry; // gi_trace's second output: xyz view normal, w view depth (0 for sky)
layout(std140) uniform DepthResolve { vec4 ResolveSettings; }; // x: the a-trous step in texels
in vec2 texCoord;
out vec4 fragColor;

const float KERNEL[3] = float[3](0.375, 0.25, 0.0625);

void main() {
    vec4 centre = texture(EffectMask, texCoord);
    vec4 centreGeometry = texture(GiGeometry, texCoord);
    if (centreGeometry.w <= 0.0) { fragColor = centre; return; }
    float centreDist = centreGeometry.w;
    vec3 centreNormal = centreGeometry.xyz;
    vec2 texel = ResolveSettings.x / vec2(textureSize(EffectMask, 0));
    vec4 sum = centre * KERNEL[0] * KERNEL[0];
    float weightSum = KERNEL[0] * KERNEL[0];
    for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++) {
        if (x == 0 && y == 0) continue;
        vec2 tapCoord = texCoord + vec2(x, y) * texel;
        if (any(lessThan(tapCoord, vec2(0.0))) || any(greaterThan(tapCoord, vec2(1.0)))) continue;
        vec4 tapGeometry = texture(GiGeometry, tapCoord);
        if (tapGeometry.w <= 0.0) continue;
        float tapDist = tapGeometry.w;
        vec3 tapNormal = tapGeometry.xyz;
        float depthWeight = exp(-abs(tapDist - centreDist) / max(0.05, centreDist * 0.04));
        float normalWeight = pow(max(dot(centreNormal, tapNormal), 0.0), 16.0);
        float weight = KERNEL[abs(x)] * KERNEL[abs(y)] * depthWeight * normalWeight;
        sum += texture(EffectMask, tapCoord) * weight;
        weightSum += weight;
    }
    fragColor = weightSum > 0.0001 ? sum / weightSum : centre;
}
