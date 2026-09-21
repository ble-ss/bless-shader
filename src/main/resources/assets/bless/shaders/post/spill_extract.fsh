#version 330

uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};
// spill_threshold: the low edge of the saturation-weighted bright-pass smoothstep; the high edge stays +0.25 above it.
layout(std140) uniform SpillExtractConfig {
    float Threshold;
};
in vec2 texCoord;
out vec4 fragColor;

// keys on saturation and brightness, not luminance alone -- a grey wall at full brightness
// stays out, a warm torch or lava glow passes and keeps its hue. the (0.15 + sat) weight (item
// 11) still lets a neutral bright surface through at 0.15 of its brightness rather than half of
// it -- 0.5 let a genuinely neutral-bright wall bloom nearly as hard as a saturated torch glow.
vec3 spillPass(vec2 uv) {
    vec3 c = texture(InSampler, uv).rgb;
    float m = max(c.r, max(c.g, c.b));
    float sat = m - min(c.r, min(c.g, c.b));
    return c * smoothstep(Threshold, Threshold + 0.25, m) * (0.15 + sat);
}

void main() {
    // 4-tap box (item 10): quarter-res spill extract otherwise lets a torch-sized bright spot
    // fall between texel centres and strobe as the camera moves a fraction of a pixel.
    vec2 texel = 1.0 / InSize;
    vec3 sum = spillPass(texCoord + vec2(-1.0, -1.0) * texel)
        + spillPass(texCoord + vec2(1.0, -1.0) * texel)
        + spillPass(texCoord + vec2(-1.0, 1.0) * texel)
        + spillPass(texCoord + vec2(1.0, 1.0) * texel);
    fragColor = vec4(sum * 0.25, 1.0);
}
