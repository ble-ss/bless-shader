#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <bless:color_grade.glsl>

uniform sampler2D InSampler;
uniform sampler2D ExposureSampler;

layout(std140) uniform GrainConfig {
    float Strength;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 source = texture(InSampler, texCoord);
    vec3 color = source.rgb;
    color *= texture(ExposureSampler, texCoord).r * 4.0;
    color = blessGrain(color, gl_FragCoord.xy, GameTime, Strength);
    fragColor = vec4(blessGrade(color), source.a);
}
