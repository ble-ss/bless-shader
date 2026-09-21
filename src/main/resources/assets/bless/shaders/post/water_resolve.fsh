#version 330
#moj_import <bless:depth_common.glsl>

uniform sampler2D WorldDepth;
uniform sampler2D EffectMask;
// brief item 4, glass brief item 5: r > 0.5 metal, g > 0.5 water, b > 0.5 glass -- which of the
// three water_mask.fsh's hit at this pixel was.
uniform sampler2D ReflectionKind;
// the pre-effect main colour snapshot water_mask.fsh's own executes() lambda already copies every
// frame (DepthEffects.reflectionScratchTexture) -- sampled here at the pixel's OWN uv (not a
// reflected hit uv) so a metal reflection can tint by the surface's own colour (item 4: gold
// reflects gold), the one input this resolve pass had no other way to read.
uniform sampler2D ReflectionScratch;
in vec2 texCoord;
out vec4 fragColor;

// duplicated from water_mask.fsh on purpose -- same reasoning as ssao_mask.fsh's aoHash doc
// comment. keyed on world xz (item 6, see water_mask.fsh's doc comment), sampled at the same
// pixel centre (pixelUv) so both shaders reconstruct the identical ripple.
float waterHash(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 uv = pixelUv(texCoord);
    // single-target choice (brief's item 2): rgb the reflected colour water_mask.fsh already
    // fetched at full precision, a confidence. this trades away the depth-aware bilinear upsample
    // every other resolve pass uses (contact_mask, ssao_mask both pack a view depth into gb for
    // that) -- a plain hardware-filtered sample of the half-res mask is used instead, so a
    // reflection can bleed a texel or two across a sharp depth edge at the shoreline. acceptable
    // for a first cut; flagged in this seat's handback.
    vec4 mask = texture(EffectMask, uv);
    vec3 reflected = mask.rgb;
    float confidence = mask.a;
    vec4 kind = texture(ReflectionKind, uv);
    bool metal = kind.r > 0.5;
    bool water = kind.g > 0.5;
    bool glass = kind.b > 0.5;
    // wetness (bless-wet brief item 4): a pixel water_mask.fsh called wet ground, not metal/water/glass.
    bool wet = kind.a > 0.5;
    // rori 2026-09-20: the glint below used to be computed for every pixel that was not metal and
    // added even at zero confidence, so any flat ground at the sun's mirror angle sparkled -- the
    // village grass. only a pixel the material mask calls water, metal, glass or wet ground gets
    // anything from here.
    if (!metal && !water && !glass && !wet) { fragColor = vec4(0.0); return; }

    float depth = texture(WorldDepth, uv).r;
    if (depth <= 0.0) { fragColor = vec4(0.0); return; }
    vec3 p = viewPosition(uv, depth);
    vec3 viewDir = -normalize(p);

    if (metal) {
        // item 4: metal reflects itself with its own reconstructed normal, no ripple, no glint --
        // a bare block face has no sun-facet to sparkle. F0 0.5 (brief item 4's literal number):
        // a rough middle ground between water's near-grazing-only fresnel and a mirror's F0 1.
        vec3 n = viewNormal(WorldDepth, uv);
        float fresnel = 0.5 + 0.5 * pow(1.0 - max(dot(viewDir, n), 0.0), 5.0);
        float k = clamp(fresnel * confidence * MetalSettings.x, 0.0, 1.0);
        if (k <= 0.0) { fragColor = vec4(0.0); return; }
        vec3 main = texture(ReflectionScratch, uv).rgb;
        float luma = dot(main, vec3(0.2126, 0.7152, 0.0722));
        vec3 tinted = reflected * main / max(luma, 0.2);
        fragColor = vec4(tinted * k, k);
        return;
    }

    if (glass) {
        // glass brief item 5: a dielectric's fresnel, strong at grazing angles and faint head on.
        // real glass is F0 0.04, which the bench could not see at all (0.2 of 255 on a wall looked at
        // straight); 0.10 keeps the shape of the curve and lets a window read as glass. tinted by the
        // surface's own colour the same way metal is above, plus the sun's own highlight: a flat pane
        // does throw the sun back as one tight spot, narrower than water's and without its ripple.
        vec3 n = viewNormal(WorldDepth, uv);
        float fresnel = 0.10 + 0.90 * pow(1.0 - max(dot(viewDir, n), 0.0), 5.0);
        float k = clamp(fresnel * confidence * MetalSettings.y, 0.0, 1.0);
        vec3 r = reflect(normalize(p), n);
        float glint = pow(max(dot(r, ViewSun.xyz), 0.0), 400.0) * ViewSun.w * 0.5 * MetalSettings.y;
        vec3 glintColor = vec3(1.0, 0.97, 0.9) * glint;
        if (k <= 0.0) { fragColor = vec4(glintColor, 0.0); return; }
        vec3 main = texture(ReflectionScratch, uv).rgb;
        float luma = dot(main, vec3(0.2126, 0.7152, 0.0722));
        vec3 tinted = reflected * main / max(luma, 0.2);
        fragColor = vec4(tinted * k + glintColor, k);
        return;
    }

    if (wet) {
        // wetness (bless-wet brief item 4): fresnel with F0 0.04 (a puddle reads as water at grazing
        // angles), no glint -- the normal water_mask.fsh reconstructed for this pixel already carries
        // no ripple, so there is no sun-facet to sparkle the way water's own ripple gives it one.
        // d darkens the damp look independent of the reflection itself, scaled by both the live wet
        // value (MetalSettings.w) and wet_strength (ReflectionSettings.w) -- a light drizzle darkens
        // less than a downpour, and the strength knob still zeroes it out entirely at 0.
        vec3 wetNormal = viewNormal(WorldDepth, uv);
        float wetFresnel = 0.04 + 0.96 * pow(1.0 - max(dot(viewDir, wetNormal), 0.0), 5.0);
        float k = clamp(wetFresnel * confidence * ReflectionSettings.w, 0.0, 1.0);
        float d = 0.3 * MetalSettings.w * ReflectionSettings.w;
        fragColor = vec4(reflected * k, 1.0 - (1.0 - d) * (1.0 - k));
        return;
    }

    vec3 n = normalize(ViewUp.xyz);
    float yawC = cos(CameraPosition.w), yawS = sin(CameraPosition.w);
    vec2 worldXz = CameraPosition.xz + vec2(yawC * p.x - yawS * p.z, yawS * p.x + yawC * p.z);
    vec3 ripple = vec3(waterHash(worldXz), waterHash(worldXz + 17.0), waterHash(worldXz + 43.0)) * 0.03 - 0.015;
    n = normalize(n + ripple);
    vec3 r = reflect(normalize(p), n);

    // glint_strength (ReflectionSettings.z) is not in the brief's literal glint formula, but the
    // config knob has to do something -- applied here as the natural multiplier. flagged in the
    // handback as an assumption rather than a verified read of intent.
    // metal never reaches this code (the `if (metal)` branch above already returned) -- metal
    // gets no glint, only its own fresnel/tint. water's own glint was far too wide and bright
    // (pow 180, factor 1.5): a diamond floor straight below the camera bled the sun highlight
    // into a blown-out white blob. tightened to pow 600 (a much narrower highlight) and factor
    // 0.8 (dimmer).
    float glint = pow(max(dot(r, ViewSun.xyz), 0.0), 600.0) * ViewSun.w * 0.8 * ReflectionSettings.z;
    vec3 glintColor = vec3(1.0, 0.97, 0.9) * glint;

    // item 4: glint is computed above the confidence gate and survives it -- a water pixel with no
    // mirror hit still sparkles. the blend function's src colour factor is ONE unconditionally, so
    // an alpha of 0 here adds the glint on top of whatever is already there without darkening it.
    if (confidence <= 0.0) { fragColor = vec4(glintColor, 0.0); return; }

    float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(viewDir, n), 0.0), 5.0);
    // item 5: k is hoisted once, clamped, and reused for both the colour and the alpha -- the old
    // code recomputed the same product unclamped for colour and clamped for alpha, so the two
    // disagreed whenever fresnel * confidence * ReflectionSettings.x exceeded 1.
    float k = clamp(fresnel * confidence * ReflectionSettings.x, 0.0, 1.0);
    vec3 color = reflected * k + glintColor;
    fragColor = vec4(color, k);
}
