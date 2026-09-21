#version 330

uniform sampler2D InSampler;
uniform sampler2D MainSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    // the second input supplies actual full dimensions, including odd sizes.
    // it is bound for dimensions only and adds no texture fetch.
    vec2 offset = vec2(0.0, 3.5 / float(textureSize(MainSampler, 0).y));
    vec3 sum = texture(InSampler, texCoord).rgb * 0.227027;
    sum += (texture(InSampler, texCoord + offset).rgb + texture(InSampler, texCoord - offset).rgb) * 0.194594;
    sum += (texture(InSampler, texCoord + offset * 2.0).rgb + texture(InSampler, texCoord - offset * 2.0).rgb) * 0.121621;
    sum += (texture(InSampler, texCoord + offset * 3.0).rgb + texture(InSampler, texCoord - offset * 3.0).rgb) * 0.054054;
    sum += (texture(InSampler, texCoord + offset * 4.0).rgb + texture(InSampler, texCoord - offset * 4.0).rgb) * 0.016216;
    fragColor = vec4(sum, 1.0);
}
