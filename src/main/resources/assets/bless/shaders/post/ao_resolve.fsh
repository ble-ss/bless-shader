#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
uniform sampler2D EffectMask;
layout(std140) uniform DepthResolve { vec4 ResolveSettings; }; // unused; ao has one output shape only
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = pixelUv(texCoord);
    float distance = viewDepth(uv, texture(WorldDepth, uv).r);
    ivec2 size = textureSize(EffectMask, 0);
    vec2 position = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(position));
    vec2 f = fract(position);
    float occlusion = 0.0, weightSum = 0.0;
    for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x) {
        vec4 value = texelFetch(EffectMask, clamp(base + ivec2(x, y), ivec2(0), size - 1), 0);
        float depthDifference = abs(unpackViewDepth(value.gb) - distance);
        float depthWeight = 1.0 - smoothstep(0.02, max(0.08, distance * 0.02), depthDifference);
        float bilinear = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
        float weight = value.a * depthWeight * bilinear;
        occlusion += value.r * weight;
        weightSum += weight;
    }
    occlusion = weightSum > 0.0001 ? occlusion / weightSum : 0.0;
    // multiply-darken via dest-alpha blend, the exact shape contact_resolve already uses: rgb
    // ZERO / ONE_MINUS_SRC_ALPHA leaves colour untouched here and multiplies the destination by
    // (1 - alpha) once bound, so alpha alone carries occlusion * ao_strength.
    fragColor = vec4(0.0, 0.0, 0.0, clamp(occlusion * AoSettings.z, 0.0, 1.0));
}
