#version 330
#moj_import <bless:depth_common.glsl>

// composites the half-resolution volumetric light over the frame: depth-aware bilinear upsample of
// volume_march's (in-scatter, transmittance), written as premultiplied colour and the transmittance
// in alpha so the ONE / SRC_ALPHA pipeline blend does frame * T + in-scatter in one draw.
uniform sampler2D WorldDepth;
uniform sampler2D EffectMask;
layout(std140) uniform DepthResolve { vec4 ResolveSettings; }; // bound by the shared draw path, unused here
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    float distance = depth <= 0.0 ? 1e9 : viewDepth(uv, depth);
    ivec2 size = textureSize(EffectMask, 0);
    vec2 position = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(position));
    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    // 4x4 depth-aware tent, not the old 2x2 bilinear: a room full of sea lanterns showed volume_march's
    // half-res per-pixel jitter as a fine checkerboard in the block-light in-scatter on dark faces --
    // a 2-tap-per-axis filter is too narrow to average that pattern out before it reaches the frame.
    // fixed (1,3,3,1)/8 weights per axis (not the sub-pixel fract position bilinear used) so this is a
    // wider blur over the same depth-rejected taps, not a different reconstruction.
    float tentWeights[4] = float[4](1.0, 3.0, 3.0, 1.0);
    for (int y = -1; y <= 2; ++y) for (int x = -1; x <= 2; ++x) {
        ivec2 coord = clamp(base + ivec2(x, y), ivec2(0), size - 1);
        vec2 tapUv = (vec2(coord) + 0.5) / vec2(size);
        float tapDepth = texture(WorldDepth, pixelUv(tapUv)).r;
        float tapDistance = tapDepth <= 0.0 ? 1e9 : viewDepth(pixelUv(tapUv), tapDepth);
        // sky and far surfaces both saturate at the depth clamp; only a real near/far mismatch is rejected.
        float mismatch = min(abs(tapDistance - distance), 1e6);
        float depthWeight = 1.0 - smoothstep(0.5, max(2.0, distance * 0.1), mismatch);
        float tent = (tentWeights[x + 1] / 8.0) * (tentWeights[y + 1] / 8.0);
        float weight = depthWeight * tent + 0.0001;
        sum += texelFetch(EffectMask, coord, 0) * weight;
        weightSum += weight;
    }
    vec4 fog = sum / weightSum;
    fragColor = vec4(max(fog.rgb, vec3(0.0)), clamp(fog.a, 0.0, 1.0));
}
