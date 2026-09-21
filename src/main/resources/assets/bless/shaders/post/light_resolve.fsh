#version 330
#moj_import <bless:depth_common.glsl>
#moj_import <bless:voxel_common.glsl>

// light-volume item 5: the flood-filled coloured block light at the pixel's own surface -- spill and
// tint. the atlas layout moved to voxel_common.glsl when the volume grew to carry albedo too.
uniform sampler2D WorldDepth;
uniform sampler2D LightVolume;
uniform sampler2D LightMain;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = pixelUv(texCoord);
    float depthRaw = texture(WorldDepth, uv).r;
    // sky pixels (reversed depth, clear 0) untouched -- the volume has nothing to say about the sky.
    if (depthRaw <= 0.0) { fragColor = vec4(0.0); return; }
    vec3 worldPos = worldPosition(uv, depthRaw);
    vec3 light = voxelSampleSmooth(LightVolume, worldPos);
    vec3 mainColor = texture(LightMain, uv).rgb;
    vec3 spill = light * LightSettings.x;
    // tint the pixel toward the light's own hue, weighted by how much light reaches it.
    vec3 tint = mix(vec3(0.0), mainColor * normalize(light + 0.001) * length(light), LightSettings.y);
    fragColor = vec4(spill + tint, 0.0);
}
