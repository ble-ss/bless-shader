#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <bless:color_grade.glsl>

uniform sampler2D InSampler;
uniform sampler2D BloomSampler;
uniform sampler2D SpillSampler;
uniform sampler2D ExposureSampler;

layout(std140) uniform GrainConfig {
    float Strength;
};
// bloom_strength: the bloom mix multiplier.
layout(std140) uniform BloomGradeConfig {
    float BloomStrength;
};
// spill_strength: the coloured light-spill mix multiplier. additive, so it keeps its hue
// instead of screening toward white the way the luminance bloom does.
layout(std140) uniform SpillGradeConfig {
    float SpillStrength;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 source = texture(InSampler, texCoord);
    vec3 color = source.rgb;
    vec3 bloom = clamp(texture(BloomSampler, texCoord).rgb * BloomStrength, 0.0, 1.0);
    color = 1.0 - (1.0 - color) * (1.0 - bloom);
    vec3 spill = texture(SpillSampler, texCoord).rgb;
    color += spill * SpillStrength;
    color *= texture(ExposureSampler, texCoord).r * 4.0;
    color = blessGrain(color, gl_FragCoord.xy, GameTime, Strength);
    fragColor = vec4(blessGrade(color), source.a);
}
