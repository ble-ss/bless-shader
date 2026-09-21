#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = pixelUv(texCoord);
    vec2 pixels = min(uv, 1.0 - uv) * SunScreen.zw;
    // conspicuous diagnostic border. raw capture is taken before hand/screen effects.
    if (min(pixels.x, pixels.y) < 12.0) {
        float stripe = mod(floor((uv.x * SunScreen.z + uv.y * SunScreen.w) / 12.0), 2.0);
        fragColor = vec4(1.0, stripe, 1.0 - stripe, 1.0);
        return;
    }
    float depth = texture(WorldDepth, uv).r;
    if (depth <= 0.0) {
        fragColor = vec4(1.0);
        return;
    }
    vec3 position = viewPosition(uv, depth);
    fragColor = vec4(clamp(vec3((position.x + 16.0) / 32.0,
        (position.y + 16.0) / 32.0, -position.z / 32.0), 0.0, 1.0), 1.0);
}
