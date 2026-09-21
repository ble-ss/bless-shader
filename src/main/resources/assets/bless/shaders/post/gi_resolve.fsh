#version 330
#moj_import <bless:depth_common.glsl>

// the compose: upsample the half-resolution bounce with a depth-aware bilinear and lay it into the
// frame. vanilla's colour is albedo already multiplied by vanilla's light, so it stands in for the
// albedo the bounce should multiply (a small flat term keeps deep shadow from staying pitch black
// where that proxy is dark), and the sky-visibility alpha darkens what the sky cannot see -- the
// bounce's own ambient occlusion, gentler at night when vanilla has already dimmed the world.
// reads the pre-effect colour from LightMain and writes the whole colour (no blend, alpha kept).
uniform sampler2D WorldDepth;
uniform sampler2D GiSampler;
uniform sampler2D LightMain;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = pixelUv(texCoord);
    vec4 source = texture(LightMain, uv);
    float depth = texture(WorldDepth, uv).r;
    if (depth <= 0.0) { fragColor = source; return; }
    float distance = viewDepth(uv, depth);
    ivec2 size = textureSize(GiSampler, 0);
    vec2 position = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(position));
    vec2 f = fract(position);
    vec4 gi = vec4(0.0);
    float weightSum = 0.0;
    for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x) {
        ivec2 coord = clamp(base + ivec2(x, y), ivec2(0), size - 1);
        // the half-res texel's own depth: the full-res pixel at its centre.
        vec2 tapUv = (vec2(coord) + 0.5) / vec2(size);
        float tapDepth = texture(WorldDepth, pixelUv(tapUv)).r;
        float tapDistance = tapDepth <= 0.0 ? 1e9 : viewDepth(pixelUv(tapUv), tapDepth);
        float depthWeight = 1.0 - smoothstep(0.02, max(0.08, distance * 0.02), abs(tapDistance - distance));
        float bilinear = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
        float weight = depthWeight * bilinear + 0.0001;
        gi += texelFetch(GiSampler, coord, 0) * weight;
        weightSum += weight;
    }
    gi /= weightSum;
    vec3 bounce = max(gi.rgb, vec3(0.0)) * GiSettings.x;
    float skyVisibility = clamp(gi.a, 0.0, 1.0);
    // sky occlusion: strongest in daylight, a quarter of that at night.
    float occlusionStrength = clamp(mix(0.2, 0.55, ViewSun.w) * GiSettings.w, 0.0, 0.9);
    float ambient = mix(1.0 - occlusionStrength, 1.0, skyVisibility);
    vec3 lit = source.rgb * ambient + bounce * (source.rgb * 1.1 + 0.16);
    fragColor = vec4(lit, source.a);
}
