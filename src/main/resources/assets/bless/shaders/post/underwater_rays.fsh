#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
in vec2 texCoord;
out vec4 fragColor;

// schedule only for water fog, real open sky, daytime sun and an on-screen projected sun center.
void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    float distance = viewDepth(uv, depth);
    vec2 towardSun = SunScreen.xy - uv;
    // from below, the sky is never in the depth buffer: the water surface is, since translucent water writes
    // depth. so the light enters at the surface, and the surface is what the depth reads in the sun's direction.
    // a sample is lit when nothing sits nearer than that surface, and the surface itself counts as open sky.
    float surfaceDepth = texture(WorldDepth, pixelUv(clamp(SunScreen.xy, 0.001, 0.999))).r;
    float surfaceDistance = surfaceDepth <= 0.0 ? 1000.0 : viewDepth(pixelUv(clamp(SunScreen.xy, 0.001, 0.999)), surfaceDepth);
    float visibility = 0.0;
    float weights = 0.0;
    for (int i = 0; i < 12; ++i) {
        float t = (float(i) + 0.5) / 12.0;
        vec2 sampleUv = uv + towardSun * t;
        float weight = 1.0 - 0.65 * t;
        float sampleDepth = texture(WorldDepth, pixelUv(sampleUv)).r;
        float sampleDistance = sampleDepth <= 0.0 ? 1000.0 : viewDepth(pixelUv(sampleUv), sampleDepth);
        float nearSun = 1.0 - smoothstep(0.08, 0.45, length(sampleUv - SunScreen.xy));
        float open = sampleDistance >= surfaceDistance * 0.85 ? 1.0 : 0.0;
        visibility += open * nearSun * weight;
        weights += weight;
    }
    float pathWeight = 1.0 - exp(-min(distance, 32.0) / 12.0);
    float shaft = visibility / weights * pathWeight * ViewSun.w * EffectTuning.w;
    fragColor = vec4(clamp(shaft, 0.0, 1.0), packViewDepth(distance), 1.0);
}
