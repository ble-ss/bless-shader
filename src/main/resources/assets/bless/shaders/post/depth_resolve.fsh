#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
uniform sampler2D EffectMask;
layout(std140) uniform DepthResolve { vec4 ResolveSettings; }; // x: 0 contact, 1 underwater rays, 2 sun rays
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = pixelUv(texCoord);
    float distance = viewDepth(uv, texture(WorldDepth, uv).r);
    ivec2 size = textureSize(EffectMask, 0);
    vec2 position = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(position));
    vec2 f = fract(position);
    float effect = 0.0, weightSum = 0.0;
    for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x) {
        vec4 value = texelFetch(EffectMask, clamp(base + ivec2(x, y), ivec2(0), size - 1), 0);
        float depthDifference = abs(unpackViewDepth(value.gb) - distance);
        float depthWeight = 1.0 - smoothstep(0.02, max(0.08, distance * 0.02), depthDifference);
        float bilinear = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
        float weight = value.a * depthWeight * bilinear;
        effect += value.r * weight;
        weightSum += weight;
    }
    effect = weightSum > 0.0001 ? effect / weightSum : 0.0;
    // contact pipeline: rgb ZERO / ONE_MINUS_SRC_ALPHA; rays pipeline: rgb ONE / ONE.
    // both use WRITE_COLOR only, preserving destination alpha, and have no depth attachment.
    // 0.50: mask r peaks at 0.75 right at the contact line (contact_mask's fraction-based falloff),
    // so the default (contact_strength 1.0) darkens the base of an occluder ~37.5%, fading to zero
    // over the march -- the strength knob multiplies the mask itself, not this composite constant.
    // rays add light the water has already coloured: pale, slightly green-white, strong enough to read as shafts.
    // sun rays (mode 2) add the sun's own colour through the same additive pipeline the water rays use.
    if (ResolveSettings.x > 1.5) { fragColor = vec4(SunColor.rgb * (effect * 0.14), 0.0); return; }
    fragColor = ResolveSettings.x < 0.5 ? vec4(0.0, 0.0, 0.0, effect * 0.50) : vec4(vec3(0.85, 0.95, 0.90) * (effect * 0.30), 0.0);
}
