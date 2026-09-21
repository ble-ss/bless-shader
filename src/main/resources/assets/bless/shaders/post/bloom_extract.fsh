#version 330

uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};
// bloom_threshold: the low edge of the bright-pass smoothstep; the high edge stays +0.35 above it.
layout(std140) uniform BloomExtractConfig {
    float Threshold;
};
in vec2 texCoord;
out vec4 fragColor;

vec3 brightPass(vec2 uv) {
    vec3 c = texture(InSampler, uv).rgb;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return c * (smoothstep(Threshold, Threshold + 0.35, l) * 0.10 + 0.10);
}

void main() {
    // radius uses full-resolution source dimensions, as in cozy.
    vec2 offset = vec2(3.5 / InSize.x, 0.0);
    vec3 sum = brightPass(texCoord) * 0.227027;
    sum += (brightPass(texCoord + offset) + brightPass(texCoord - offset)) * 0.194594;
    sum += (brightPass(texCoord + offset * 2.0) + brightPass(texCoord - offset * 2.0)) * 0.121621;
    sum += (brightPass(texCoord + offset * 3.0) + brightPass(texCoord - offset * 3.0)) * 0.054054;
    sum += (brightPass(texCoord + offset * 4.0) + brightPass(texCoord - offset * 4.0)) * 0.016216;
    fragColor = vec4(sum, 1.0);
}
