#version 330

// eye adaptation, stage 1 of 3: 16x16 log-luminance downsample.
// each output texel averages an 8x8 grid of taps over its own cell of the full frame,
// storing log2(luminance + 0.0001) -- but remapped into 0..1 first, because the internal
// targets this chain declares are hardcoded RGBA8_UNORM by vanilla's PostChain (verified
// against the deobfuscated jar: PostChain.addToFrame always builds the descriptor with
// GpuFormat.RGBA8_UNORM for both persistent and plain internal targets, so an RGBA16F
// target isn't reachable through the declarative post_effect json this chain uses).
// log2(luma + 0.0001) for an LDR (already-clamped 0..1) source sits in [-13.29, 0.0]; the
// remap below covers [-14, 0] with margin. luma_final and exposure_adapt share the same
// affine constants to decode.
uniform sampler2D InSampler;

const vec3 RMLS_LUMA = vec3(0.2126, 0.7152, 0.0722);
const float RMLS_LOG_LO = -14.0;
const float RMLS_LOG_SPAN = 14.0;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 cellOrigin = floor(gl_FragCoord.xy) / 16.0;
    vec2 cellSize = vec2(1.0 / 16.0);
    float sumLog = 0.0;
    for (int y = 0; y < 8; y++) {
        for (int x = 0; x < 8; x++) {
            vec2 uv = cellOrigin + (vec2(x, y) + 0.5) / 8.0 * cellSize;
            vec3 sampleColor = texture(InSampler, uv).rgb;
            float luma = dot(sampleColor, RMLS_LUMA);
            sumLog += log2(luma + 0.0001);
        }
    }
    float meanLog = sumLog / 64.0;
    float encoded = clamp((meanLog - RMLS_LOG_LO) / RMLS_LOG_SPAN, 0.0, 1.0);
    fragColor = vec4(encoded, 0.0, 0.0, 1.0);
}
