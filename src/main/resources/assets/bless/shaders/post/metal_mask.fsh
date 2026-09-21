#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
// grass-glint brief item 1, glass brief item 4: which of the three draws this is -- x: 0 metal
// (cubes), 1 water (flat quads at y + 0.875), 2 glass (cubes, reused CUBE_OFFSETS same as metal).
// picks the write channel and the depth-test tolerance below.
layout(std140) uniform MaskKind { vec4 MaskSettings; };
in vec3 viewPos;
out vec4 fragColor;

void main() {
    // brief item 3: the manual depth test -- no depth attachment on this pass (see DepthPipelines.METAL_MASK),
    // so a fragment only survives when it sits within tolerance of the real scene surface at this pixel.
    // geometry further from the camera than the surface (occluded) and geometry nearer (poking through
    // open air in front of the surface) both discard. metal's tolerance stays tight (0.05: cube faces sit
    // exactly on the block's surface); water's is looser (0.15) since vanilla's water surface height
    // varies with flow, not a fixed 0.875 the way this quad assumes. glass shares metal's tight 0.05:
    // 26.2's translucent terrain still writes real depth, so a glass cube face sits on the real surface
    // exactly like a metal one (glass brief item 4).
    bool isWater = MaskSettings.x > 0.5 && MaskSettings.x < 1.5;
    bool isGlass = MaskSettings.x > 1.5;
    float tolerance = isWater ? 0.15 : 0.05;
    vec2 uv = pixelUv(gl_FragCoord.xy / SunScreen.zw);
    float sceneDepth = viewDepth(uv, texture(WorldDepth, uv).r);
    if (abs(-viewPos.z - sceneDepth) > tolerance) discard;
    // r: metal. g: water surface. b: glass. water_mask.fsh reads these instead of guessing from a depth gap.
    if (isGlass) fragColor = vec4(0.0, 0.0, 1.0, 1.0);
    else fragColor = isWater ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
}
