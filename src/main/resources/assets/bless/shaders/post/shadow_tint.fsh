#version 330
#moj_import <bless:depth_common.glsl>

// glass-light brief item 3: writes the block's own tint, lerped toward white by
// (1 - glass_tint_strength) so the strength slider fades the whole pass toward "no tint" without
// rebuilding the vertex buffer. MetalSettings.z is glass_tint_strength (depth_common.glsl's doc
// comment). blended into shadowTint with DepthPipelines.SHADOW_TINT's own multiplicative blend
// (src DST_COLOR, dst ZERO), so alpha here never matters -- written 1.0 for clarity only.
in vec4 tintColor;
out vec4 fragColor;

void main() {
    vec3 strength = vec3(clamp(MetalSettings.z, 0.0, 1.0));
    fragColor = vec4(mix(vec3(1.0), tintColor.rgb, strength), 1.0);
}
