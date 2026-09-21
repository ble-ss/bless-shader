#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
in vec2 texCoord;
out vec4 fragColor;

// the house dither hash (blessHash in color_grade.glsl), duplicated rather than imported so this
// pass does not drag the colour stage's own uniforms in for one function.
float hazeHash(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// full-screen depth-graded veil; no mask pass, composited straight onto main color with a
// premultiplied-alpha "over" blend (pipeline is ONE, ONE_MINUS_SRC_ALPHA), so fragColor.rgb
// must already carry the veil's own weight and fragColor.a is that same weight.
void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    // sky (clear depth) stays untouched. viewDepth() maps a clear sample to the far clamp (128,
    // a real distance other passes rely on), not to "nothing there", so it cannot stand in for
    // infinity here -- the sky would otherwise pick up a strong, wrong veil.
    if (depth <= 0.0) { fragColor = vec4(0.0); return; }
    // unclamped, and euclidean: viewDepth() saturates at the 128-block packing limit every mask pass
    // needs, but the veil has to keep thickening over offing's far terrain a thousand blocks out.
    float distance = length(viewPosition(uv, depth));

    // v2: the veil is a tint over the sky's own colour, not a fixed paint -- SceneFogColor.rgb is
    // that colour (or the config colour again if the engine's could not be read this frame) and
    // SceneFogColor.a is how much of the config colour rides on top of it.
    vec3 veilColor = mix(SceneFogColor.rgb, HazeColor.rgb, SceneFogColor.a);
    // strength follows daylight: full above elevation 0.3, HazeSettings.z (haze_night) at and below
    // the horizon, smoothstepped between -- this alone is what keeps the veil from brightening into
    // a milky band over dark terrain at midnight.
    float nightFactor = mix(HazeSettings.z, 1.0, smoothstep(0.0, 0.3, DepthSettings.z));
    float veil = HazeSettings.y * nightFactor * (1.0 - exp(-distance / HazeSettings.x));

    // plus or minus half an 8-bit level, stable per pixel and with no time term, so the smooth
    // gradient does not band across dark terrain in an 8-bit target.
    float dither = (hazeHash(gl_FragCoord.xy) - 0.5) / 255.0;
    veil = clamp(veil + dither, 0.0, 1.0);

    fragColor = vec4(veilColor * veil, veil);
}
