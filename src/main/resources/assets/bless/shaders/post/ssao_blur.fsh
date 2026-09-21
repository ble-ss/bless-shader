#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
uniform sampler2D EffectMask;
layout(std140) uniform DepthResolve { vec4 ResolveSettings; }; // x: 0 horizontal pass, 1 vertical pass
in vec2 texCoord;
out vec4 fragColor;

const float WEIGHTS[5] = float[5](0.227027, 0.194594, 0.121621, 0.054054, 0.016216);

// separable depth-aware blur over the half-resolution ao mask; run twice (horizontal then
// vertical) since ssao is noise before this. WorldDepth is bound but unused -- the mask already
// carries its own packed view depth in gb, which is what this pass weights against.
void main() {
    ivec2 size = textureSize(EffectMask, 0);
    ivec2 base = ivec2(floor(texCoord * vec2(size)));
    ivec2 step = ResolveSettings.x > 0.5 ? ivec2(0, 1) : ivec2(1, 0);
    vec4 center = texelFetch(EffectMask, clamp(base, ivec2(0), size - 1), 0);
    float centerDepth = unpackViewDepth(center.gb);
    float sum = center.r * center.a * WEIGHTS[0];
    float weightSum = center.a * WEIGHTS[0];
    for (int i = 1; i <= 4; ++i) {
        for (int side = -1; side <= 1; side += 2) {
            ivec2 coord = clamp(base + step * i * side, ivec2(0), size - 1);
            vec4 tap = texelFetch(EffectMask, coord, 0);
            float tapDepth = unpackViewDepth(tap.gb);
            float depthWeight = 1.0 - smoothstep(0.02, max(0.08, centerDepth * 0.02), abs(tapDepth - centerDepth));
            float weight = tap.a * depthWeight * WEIGHTS[i];
            sum += tap.r * weight;
            weightSum += weight;
        }
    }
    float occlusion = weightSum > 0.0001 ? sum / weightSum : center.r;
    fragColor = vec4(occlusion, center.gb, center.a);
}
