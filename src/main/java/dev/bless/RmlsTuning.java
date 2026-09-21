package dev.bless;

/**
 * the fourteen F3+T knobs, re-read from disk on every shader-chain reload.
 * public so the shader-manager mixin (a different package) can carry it.
 */
public record RmlsTuning(float bloomThreshold, float bloomStrength, float gradeStrength, float grainStrength,
	boolean fxaa, float fxaaStrength,
	boolean autoExposure, float exposureTarget, float exposureMin, float exposureMax, float exposureSpeed,
	float spillThreshold, float spillStrength, float spillRadius) {
}
