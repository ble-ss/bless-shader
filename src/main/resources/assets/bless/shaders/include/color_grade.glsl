#ifndef RMLS_CLIENT_COLOR_GRADE
#define RMLS_CLIENT_COLOR_GRADE

const vec3 RMLS_LUMA = vec3(0.2126, 0.7152, 0.0722);

// grade_strength: 0.0 leaves the source untouched, 1.0 is the full cozy grade (today's fixed look).
layout(std140) uniform RmlsGradeTuning {
    float GradeStrength;
};

vec3 blessGrade(vec3 source) {
    vec3 c = source;
    c *= 1.00;
    c = c / (1.0 + max(c - 0.78, 0.0) * 0.80);
    c *= vec3(1.0 + 0.055 * 0.35, 1.0 + 0.012 * 0.35, 1.0 - 0.045 * 0.35);
    float l = dot(c, RMLS_LUMA);
    c = mix(c, mix(c, vec3(l) * 1.08 + 0.02, 0.5), 0.22 * (1.0 - smoothstep(0.0, 0.55, l)));
    vec3 shadowTint = vec3(0.94, 0.96, 1.06);
    vec3 highTint = vec3(1.05, 1.00, 0.93);
    c *= mix(vec3(1.0), mix(shadowTint, highTint, smoothstep(0.12, 0.72, l)), 0.60);
    c = mix(vec3(l), c, 1.06);
    c = (c - 0.5) * 0.92 + 0.5;
    c += 0.035 * (1.0 - c);
    c = clamp(c, 0.0, 1.0);
    return mix(source, c, GradeStrength);
}

// polynomial hash avoids the large sine arguments that produced stripes on apple.
float blessHash(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 blessGrain(vec3 color, vec2 pixel, float gameTime, float strength) {
    if (strength > 0.0) {
        float t = floor(gameTime * 1200.0 * 24.0);
        float n = blessHash(pixel * 0.60 + t) - 0.5;
        float l = dot(color, RMLS_LUMA);
        color += n * strength * (4.0 * l * (1.0 - l));
    }
    return color;
}

#endif
