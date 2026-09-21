#version 330
#moj_import <bless:depth_common.glsl>

// depth-only: gl_Position's z (written by shadow_depth.vsh) is the whole point of this pass. the
// colour target is declared WRITE_NONE (DepthPipelines.SHADOW_DEPTH), so this value never lands
// anywhere -- it exists only because the pipeline builder always needs at least one colour target.
out vec4 fragColor;

void main() {
    fragColor = vec4(1.0);
}
