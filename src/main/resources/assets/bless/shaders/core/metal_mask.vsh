#version 330
#moj_import <bless:depth_common.glsl>

// brief item 3: a world-space unit cube per block, streamed as absolute world positions (position
// only, 12 bytes) -- this shader's only job is to turn that world position into the same view space
// every other effect already reconstructs from clip space (viewPosition() in depth_common.glsl), so
// the fragment shader's manual depth test compares like against like.
in vec3 Position;

out vec3 viewPos;

void main() {
    vec3 offset = Position - CameraPosition.xyz;
    viewPos = (ViewRotation * vec4(offset, 1.0)).xyz;
    gl_Position = WorldProjection * vec4(viewPos, 1.0);
}
