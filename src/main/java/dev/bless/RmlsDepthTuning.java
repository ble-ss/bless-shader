package dev.bless;

/**
 * the four contact/rays knobs, re-read from disk on every shader-manager reload (the same
 * ShaderManager.apply() event F3+T fires) and carried into the per-frame DepthScene uniform
 * DepthPass builds by hand -- there is no PostChainConfig pass to substitute into here, so
 * this is the depth stage's own analogue of RmlsTuning rather than a rider on it.
 */
record RmlsDepthTuning(float contactStrength, float contactReach, int contactSteps, float raysStrength,
	float hazeDistance, float hazeStrength, float hazeColorR, float hazeColorG, float hazeColorB,
	float hazeTint, float hazeNight, int aoSamples, float aoRadius, float aoStrength,
	float reflectionStrength, float glintStrength, float lightStrength, float lightTint, float metalStrength, float glassStrength,
	float shadowStrength, float shadowSpan, int shadowResolution, float glassTintStrength,
	float giStrength, int giRays, float giDistance, float giSky, int giBounces,
	int giScale, boolean giCheckerboard, float giEmissive,
	float volumeStrength, float volumeDensity, int volumeSteps, float volumeDistance, float volumeGlow, float sunRaysStrength,
	float wetStrength) {
	// the temporal pass keeps this much of its history each frame when the reprojection lands on the
	// same surface; not a config knob -- it trades noise against lag. item 2: 0.88 still left visible
	// denoise smear at low light (few bright samples a frame, so the trace's own noise floor is a
	// bigger share of the signal); 0.94 keeps more history and settles in a little over two seconds
	// instead of about one, a trade worth it once the third a-trous step (see DepthEffects.runGiChain)
	// and gi_trace's firefly clamp are already cutting the noise this used to cover for.
	static final float GI_HISTORY_BLEND = 0.94f;
	// the ray march step size (blocks) is not a config knob -- see ClientConfig's water reflection
	// keys doc comment for why only strength and glint are exposed.
	static final float REFLECTION_STEP = 0.5f;
	// the shadow raster's depth bias (item 1's ShadowSettings.z) is not a config knob either -- a
	// fixed 0.04 (already in shadow-map view-depth units) applied in the resolve's PCF compare.
	// in the map's own depth units: the ortho box spans 511 blocks of depth over 0..1, so one block
	// is about 0.002. the first cut used 0.04, which is twenty blocks, and no tower on earth cast a
	// shadow through it (the debug views showed the map correct and the compare never firing).
	static final float SHADOW_BIAS = 0.0008f;
}
