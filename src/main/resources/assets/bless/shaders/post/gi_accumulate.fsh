#version 330
#moj_import <bless:depth_common.glsl>

// temporal accumulation of the bounce: reprojects this pixel's world position into the previous
// frame (PrevViewProjection, PrevCameraPosition), reads the history there, and blends toward the
// fresh trace when the history describes the same surface -- judged by camera distance, which is
// rotation-invariant, so the history depth texture stores |world - camera| rather than a view depth.
// two outputs: the accumulated radiance (rgba16f) and this frame's own distance (r32f) for next frame.
uniform sampler2D WorldDepth;
uniform sampler2D GiCurrent;
uniform sampler2D GiHistory;
uniform sampler2D GiHistoryDepth;
in vec2 texCoord;
out vec4 fragColor;
out vec4 fragDepth;

void main() {
    vec2 uv = pixelUv(texCoord);
    float depth = texture(WorldDepth, uv).r;
    vec4 current = texture(GiCurrent, texCoord);
    if (current.a < 0.0) {
        // checkerboard skipped this texel this frame -- its four edge-adjacent texels have the
        // other parity and were traced, so rebuild an estimate from their mean before anything else.
        ivec2 size = textureSize(GiCurrent, 0);
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        ivec2 offsets[4] = ivec2[4](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1));
        vec4 sum = vec4(0.0);
        float count = 0.0;
        for (int i = 0; i < 4; i++) {
            vec4 tap = texelFetch(GiCurrent, clamp(pixel + offsets[i], ivec2(0), size - 1), 0);
            if (tap.a < 0.0) continue;
            sum += tap; count += 1.0;
        }
        current = count > 0.0 ? sum / count : vec4(0.0, 0.0, 0.0, 1.0);
    }
    if (depth <= 0.0) { fragColor = current; fragDepth = vec4(0.0); return; }
    vec3 world = worldPosition(uv, depth);
    float distanceNow = length(world - CameraPosition.xyz);
    fragDepth = vec4(distanceNow, 0.0, 0.0, 1.0);
    if (GiFrame.z < 0.5) { fragColor = current; return; }

    vec4 prevClip = PrevViewProjection * vec4(world - PrevCameraPosition.xyz, 1.0);
    if (prevClip.w <= 0.0001) { fragColor = current; return; }
    vec2 prevUv = prevClip.xy / prevClip.w * 0.5 + 0.5;
    if (any(lessThan(prevUv, vec2(0.001))) || any(greaterThan(prevUv, vec2(0.999)))) { fragColor = current; return; }
    float expected = length(world - PrevCameraPosition.xyz);
    float stored = texture(GiHistoryDepth, prevUv).r;
    // a tolerance that grows with distance: a half-res texel covers more world far away.
    float tolerance = 0.08 + expected * 0.02;
    if (stored <= 0.0 || abs(stored - expected) > tolerance) { fragColor = current; return; }
    vec4 history = texture(GiHistory, prevUv);
    // clamp the history to the neighbourhood of the fresh sample so a lit surface stops trailing
    // its own ghost when a light turns off or the sun moves.
    vec4 minBox = current, maxBox = current;
    vec2 texel = 1.0 / vec2(textureSize(GiCurrent, 0));
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        vec4 tap = texture(GiCurrent, texCoord + vec2(x, y) * texel);
        if (tap.a < 0.0) continue;
        minBox = min(minBox, tap); maxBox = max(maxBox, tap);
    }
    vec4 pad = (maxBox - minBox) * 0.75 + 0.02;
    history = clamp(history, minBox - pad, maxBox + pad);
    fragColor = mix(current, history, GiFrame.y);
}
