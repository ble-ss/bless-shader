#version 330
#moj_import <bless:depth_common.glsl>

// bless-shadow-pass brief item 2: ShadowMesh's own vertices are absolute world-space floats (its
// own doc comment). ShadowMatrix is already the sun's view-projection built directly in world
// space, snapped to the camera on the java side (DepthPass.buildShadowMatrix) -- unlike every
// screen-quad effect here, this shader never touches WorldProjection or CameraPosition at all.
in vec3 Position;

void main() {
    gl_Position = ShadowMatrix * vec4(Position, 1.0);
}
