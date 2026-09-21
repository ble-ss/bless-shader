#version 330

uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};
// spill_radius: quarter-res pixel step between taps. Direction is baked per pass
// (horizontal then vertical), reusing this one shader the way vanilla's box_blur reuses BlurDir.
layout(std140) uniform SpillBlurConfig {
    vec2 Direction;
    float Radius;
};
in vec2 texCoord;
out vec4 fragColor;

void main() {
    // same 9-tap gaussian weights as bloom_vertical, spread by Radius instead of a fixed 3.5.
    vec2 offset = Direction * Radius / InSize;
    vec3 sum = texture(InSampler, texCoord).rgb * 0.227027;
    sum += (texture(InSampler, texCoord + offset).rgb + texture(InSampler, texCoord - offset).rgb) * 0.194594;
    sum += (texture(InSampler, texCoord + offset * 2.0).rgb + texture(InSampler, texCoord - offset * 2.0).rgb) * 0.121621;
    sum += (texture(InSampler, texCoord + offset * 3.0).rgb + texture(InSampler, texCoord - offset * 3.0).rgb) * 0.054054;
    sum += (texture(InSampler, texCoord + offset * 4.0).rgb + texture(InSampler, texCoord - offset * 4.0).rgb) * 0.016216;
    fragColor = vec4(sum, 1.0);
}
