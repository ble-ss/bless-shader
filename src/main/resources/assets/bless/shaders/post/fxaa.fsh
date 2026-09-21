#version 330

// FXAA 3.11-style edge search (quality preset 12 step schedule), the chain's last colour pass.
// strength scales the edge threshold: 1.0 catches every edge (0.0625), 0.0 only the hardest
// (0.25). a negative FxaaSettings.x (fxaa off) is a plain copy -- the early-out that keeps this
// one shader in every chain instead of a separate fxaa/no-fxaa chain variant.
uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo {
	vec2 OutSize;
	vec2 InSize;
};
layout(std140) uniform FxaaConfig {
	vec4 FxaaSettings;
};

in vec2 texCoord;
out vec4 fragColor;

const float FXAA_EDGE_THRESHOLD_MIN = 0.0312;
const float FXAA_SUBPIX_QUALITY = 0.75;
const float FXAA_STEPS[12] = float[12](1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 2.0, 2.0, 4.0, 8.0);

float blessFxaaLuma(vec3 rgb) {
	return dot(rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
	float strength = FxaaSettings.x;
	if (strength < 0.0) {
		fragColor = texture(InSampler, texCoord);
		return;
	}

	vec2 texel = 1.0 / InSize;
	float edgeThreshold = mix(0.25, 0.0625, clamp(strength, 0.0, 1.0));

	vec3 colorCenter = texture(InSampler, texCoord).rgb;
	float lumaCenter = blessFxaaLuma(colorCenter);

	float lumaDown = blessFxaaLuma(texture(InSampler, texCoord + vec2(0.0, -texel.y)).rgb);
	float lumaUp = blessFxaaLuma(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb);
	float lumaLeft = blessFxaaLuma(texture(InSampler, texCoord + vec2(-texel.x, 0.0)).rgb);
	float lumaRight = blessFxaaLuma(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb);

	float lumaMin = min(lumaCenter, min(min(lumaDown, lumaUp), min(lumaLeft, lumaRight)));
	float lumaMax = max(lumaCenter, max(max(lumaDown, lumaUp), max(lumaLeft, lumaRight)));
	float lumaRange = lumaMax - lumaMin;

	// below threshold: no visible edge here, skip the search entirely.
	if (lumaRange < max(FXAA_EDGE_THRESHOLD_MIN, lumaMax * edgeThreshold)) {
		fragColor = vec4(colorCenter, 1.0);
		return;
	}

	float lumaDownLeft = blessFxaaLuma(texture(InSampler, texCoord + vec2(-texel.x, -texel.y)).rgb);
	float lumaUpRight = blessFxaaLuma(texture(InSampler, texCoord + vec2(texel.x, texel.y)).rgb);
	float lumaUpLeft = blessFxaaLuma(texture(InSampler, texCoord + vec2(-texel.x, texel.y)).rgb);
	float lumaDownRight = blessFxaaLuma(texture(InSampler, texCoord + vec2(texel.x, -texel.y)).rgb);

	float lumaDownUp = lumaDown + lumaUp;
	float lumaLeftRight = lumaLeft + lumaRight;
	float lumaLeftCorners = lumaDownLeft + lumaUpLeft;
	float lumaDownCorners = lumaDownLeft + lumaDownRight;
	float lumaRightCorners = lumaDownRight + lumaUpRight;
	float lumaUpCorners = lumaUpRight + lumaUpLeft;

	// horizontal vs vertical edge: whichever axis' second derivative of luma is larger.
	float edgeHorizontal = abs(-2.0 * lumaLeft + lumaLeftCorners)
		+ abs(-2.0 * lumaCenter + lumaDownUp) * 2.0
		+ abs(-2.0 * lumaRight + lumaRightCorners);
	float edgeVertical = abs(-2.0 * lumaUp + lumaUpCorners)
		+ abs(-2.0 * lumaCenter + lumaLeftRight) * 2.0
		+ abs(-2.0 * lumaDown + lumaDownCorners);
	bool isHorizontal = edgeHorizontal >= edgeVertical;

	float luma1 = isHorizontal ? lumaDown : lumaLeft;
	float luma2 = isHorizontal ? lumaUp : lumaRight;
	float gradient1 = luma1 - lumaCenter;
	float gradient2 = luma2 - lumaCenter;
	bool is1Steepest = abs(gradient1) >= abs(gradient2);
	float gradientScaled = 0.25 * max(abs(gradient1), abs(gradient2));

	float stepLength = isHorizontal ? texel.y : texel.x;
	float lumaLocalAverage;
	if (is1Steepest) {
		stepLength = -stepLength;
		lumaLocalAverage = 0.5 * (luma1 + lumaCenter);
	} else {
		lumaLocalAverage = 0.5 * (luma2 + lumaCenter);
	}

	vec2 currentUv = texCoord;
	if (isHorizontal) currentUv.y += stepLength * 0.5;
	else currentUv.x += stepLength * 0.5;

	// walk both ways along the edge until the local luma diverges from the average again.
	vec2 offset = isHorizontal ? vec2(texel.x, 0.0) : vec2(0.0, texel.y);
	vec2 uv1 = currentUv - offset;
	vec2 uv2 = currentUv + offset;

	float lumaEnd1 = blessFxaaLuma(texture(InSampler, uv1).rgb) - lumaLocalAverage;
	float lumaEnd2 = blessFxaaLuma(texture(InSampler, uv2).rgb) - lumaLocalAverage;
	bool reached1 = abs(lumaEnd1) >= gradientScaled;
	bool reached2 = abs(lumaEnd2) >= gradientScaled;
	bool reachedBoth = reached1 && reached2;
	if (!reached1) uv1 -= offset;
	if (!reached2) uv2 += offset;

	if (!reachedBoth) {
		for (int i = 0; i < 12; i++) {
			if (!reached1) lumaEnd1 = blessFxaaLuma(texture(InSampler, uv1).rgb) - lumaLocalAverage;
			if (!reached2) lumaEnd2 = blessFxaaLuma(texture(InSampler, uv2).rgb) - lumaLocalAverage;
			reached1 = abs(lumaEnd1) >= gradientScaled;
			reached2 = abs(lumaEnd2) >= gradientScaled;
			reachedBoth = reached1 && reached2;
			if (!reached1) uv1 -= offset * FXAA_STEPS[i];
			if (!reached2) uv2 += offset * FXAA_STEPS[i];
			if (reachedBoth) break;
		}
	}

	float distance1 = isHorizontal ? (texCoord.x - uv1.x) : (texCoord.y - uv1.y);
	float distance2 = isHorizontal ? (uv2.x - texCoord.x) : (uv2.y - texCoord.y);
	bool isDirection1 = distance1 < distance2;
	float distanceFinal = min(distance1, distance2);
	float edgeThicknessInv = 1.0 / (distance1 + distance2);

	float pixelOffset = -distanceFinal * edgeThicknessInv + 0.5;
	bool isLumaCenterSmaller = lumaCenter < lumaLocalAverage;
	bool correctVariation = ((isDirection1 ? lumaEnd1 : lumaEnd2) < 0.0) != isLumaCenterSmaller;
	float finalOffset = correctVariation ? pixelOffset : 0.0;

	// subpixel aliasing term: a flat-ish neighbourhood (checkerboards, thin lines) still gets blended.
	float lumaAverage = (1.0 / 12.0) * (2.0 * (lumaDownUp + lumaLeftRight) + lumaLeftCorners + lumaRightCorners);
	float subPixelOffset1 = clamp(abs(lumaAverage - lumaCenter) / max(lumaRange, 0.0001), 0.0, 1.0);
	float subPixelOffset2 = (-2.0 * subPixelOffset1 + 3.0) * subPixelOffset1 * subPixelOffset1;
	float subPixelOffsetFinal = subPixelOffset2 * subPixelOffset2 * FXAA_SUBPIX_QUALITY;

	finalOffset = max(finalOffset, subPixelOffsetFinal);

	// the final tap lands off-texel (a fractional pixelOffset), so it needs the bilinear
	// sampler this pass's input is bound with -- see the post_effect json's "bilinear": true.
	vec2 finalUv = texCoord;
	if (isHorizontal) finalUv.y += finalOffset * stepLength;
	else finalUv.x += finalOffset * stepLength;

	fragColor = vec4(texture(InSampler, finalUv).rgb, 1.0);
}
