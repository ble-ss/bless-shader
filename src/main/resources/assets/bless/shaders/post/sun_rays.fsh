#version 330
#moj_import <bless:depth_common.glsl>

// light rays in the air: a quarter-resolution screen-space march from every pixel toward the sun's
// projected centre, counting how much open sky lies along the way, weighted toward the sun. the
// resolve adds it as warm light, so a tree or a hill in front of a low sun throws rays past its
// edges. the underwater rays are this same idea read against the water surface; here the sky is the
// open depth itself. scheduled only with daylight, ordinary air and the sun on screen.
uniform sampler2D WorldDepth;
in vec2 texCoord;
out vec4 fragColor;

float rayHash(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    float distance = viewDepth(uv, depth);
    vec2 towardSun = SunScreen.xy - uv;
    // a per-pixel offset along the march breaks the 16 samples' banding into grain.
    float jitter = rayHash(gl_FragCoord.xy) * 0.9;
    float visibility = 0.0;
    float weights = 0.0;
    for (int i = 0; i < 16; ++i) {
        float t = (float(i) + 0.5 + jitter) / 16.0;
        vec2 sampleUv = uv + towardSun * t;
        if (any(lessThan(sampleUv, vec2(0.0))) || any(greaterThan(sampleUv, vec2(1.0)))) break;
        float weight = 1.0 - 0.6 * t;
        float sampleDepth = texture(WorldDepth, pixelUv(sampleUv)).r;
        float nearSun = 1.0 - smoothstep(0.05, 0.5, length(sampleUv - SunScreen.xy));
        float open = sampleDepth <= 0.0 ? 1.0 : 0.0;
        visibility += open * nearSun * weight;
        weights += weight;
    }
    float shaft = weights > 0.0 ? visibility / weights : 0.0;
    // the pixel's own proximity to the sun scales the whole thing, so rays do not wash the far sky.
    float glow = 1.0 - smoothstep(0.05, 0.4, length(uv - SunScreen.xy));
    // the open sky itself only gets half: the rays are for what stands in front of the sun, and the
    // sky near it is already the brightest thing in the frame.
    float onSky = depth <= 0.0 ? 0.5 : 1.0;
    shaft *= (0.3 + 0.7 * glow) * onSky * ViewSun.w * GiFrame.w;
    fragColor = vec4(clamp(shaft, 0.0, 1.0), packViewDepth(distance), 1.0);
}
