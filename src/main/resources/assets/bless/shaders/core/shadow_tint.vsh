#version 330
#moj_import <bless:depth_common.glsl>

// glass-light brief item 3: one world-space cube per tracked glass block (DepthEffects.
// rebuildGlassTintVertexBuffer), coloured per vertex by the block's own tint -- white for plain
// glass, its MapColor for stained glass. like shadow_depth.vsh, ShadowMatrix already carries the
// sun's whole view-projection in world space; this shader never touches WorldProjection or
// CameraPosition either.
in vec3 Position;
in vec4 Color;

out vec4 tintColor;

void main() {
    tintColor = Color;
    gl_Position = ShadowMatrix * vec4(Position, 1.0);
}
