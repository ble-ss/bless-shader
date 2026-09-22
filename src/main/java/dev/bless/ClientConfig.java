package dev.bless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public record ClientConfig(String mode, boolean grain, Path diagnosticsPath,
	float bloomThreshold, float bloomStrength, float gradeStrength, float grainStrength,
	boolean fxaa, float fxaaStrength,
	boolean autoExposure, float exposureTarget, float exposureMin, float exposureMax, float exposureSpeed,
	float spillThreshold, float spillStrength, float spillRadius,
	boolean contactShadows, boolean underwaterRays, String depthDiagnostic,
	float contactStrength, float contactReach, int contactSteps, float raysStrength,
	boolean haze, float hazeDistance, float hazeStrength, String hazeColor, float hazeTint, float hazeNight,
	boolean ambientOcclusion, int aoSamples, float aoRadius, float aoStrength,
	boolean waterReflections, float reflectionStrength, float glintStrength,
	boolean coloredLight, float lightStrength, float lightTint,
	boolean metalReflections, float metalStrength,
	boolean glassReflections, float glassStrength,
	boolean sunShadows, float shadowStrength, float shadowSpan, int shadowResolution,
	boolean glassLight, float glassTintStrength,
	boolean voxelGi, float giStrength, int giRays, float giDistance, float giSky, int giBounces,
	int giScale, boolean giCheckerboard, float giEmissive,
	boolean volumetricLight, float volumeStrength, float volumeDensity, int volumeSteps, float volumeDistance, float volumeGlow,
	boolean sunRays, float sunRaysStrength,
	boolean wetness, float wetStrength, float wetDrySeconds,
	float voxelBudgetMs, float voxelRebuildSeconds, float materialBudgetMs, int voxelEmitterCap) {
	private static final Set<String> KEYS = Set.of("schema_version", "mode", "grain", "diagnostics_path",
		"bloom_threshold", "bloom_strength", "grade_strength", "grain_strength",
		"fxaa", "fxaa_strength",
		"auto_exposure", "exposure_target", "exposure_min", "exposure_max", "exposure_speed",
		"spill_threshold", "spill_strength", "spill_radius",
		"contact_shadows", "underwater_rays", "depth_diagnostic",
		"contact_strength", "contact_reach", "contact_steps", "rays_strength",
		"haze", "haze_distance", "haze_strength", "haze_color", "haze_tint", "haze_night",
		"ambient_occlusion", "ao_samples", "ao_radius", "ao_strength",
		"water_reflections", "reflection_strength", "glint_strength",
		"colored_light", "light_strength", "light_tint",
		"metal_reflections", "metal_strength",
		"glass_reflections", "glass_strength",
		"sun_shadows", "shadow_strength", "shadow_span", "shadow_resolution", "shadow_debug",
		"glass_light", "glass_tint_strength",
		"voxel_gi", "gi_strength", "gi_rays", "gi_distance", "gi_sky", "gi_bounces", "gi_scale", "gi_checkerboard", "gi_emissive",
		"volumetric_light", "volume_strength", "volume_density", "volume_steps", "volume_distance", "volume_glow",
		"sun_rays", "sun_rays_strength",
		"wetness", "wet_strength", "wet_dry_seconds",
		"voxel_budget_ms", "voxel_rebuild_seconds", "material_budget_ms", "voxel_emitter_cap");
	// today's baked numbers; a config missing these keys renders identically to before the knobs existed.
	private static final float DEFAULT_BLOOM_THRESHOLD = 0.80f;
	private static final float DEFAULT_BLOOM_STRENGTH = 0.32f;
	private static final float DEFAULT_GRADE_STRENGTH = 1.0f;
	private static final float DEFAULT_GRAIN_STRENGTH = 0.03f;
	// fxaa off by default; strength is the edge threshold scale -- 1.0 catches every edge
	// (threshold 0.0625), 0.0 only the hardest (threshold 0.25). see fxaa.fsh.
	private static final float DEFAULT_FXAA_STRENGTH = 0.75f;
	// eye adaptation off by default; target is the mean luminance the frame is pulled toward,
	// min/max clamp the exposure multiplier, speed is the per-second ease rate (brighten and
	// darken share it for now).
	private static final float DEFAULT_EXPOSURE_TARGET = 0.3f;
	private static final float DEFAULT_EXPOSURE_MIN = 0.5f;
	private static final float DEFAULT_EXPOSURE_MAX = 2.5f;
	private static final float DEFAULT_EXPOSURE_SPEED = 1.5f;
	// coloured light-spill tier; a config missing these keys renders identically to before spill existed.
	private static final float DEFAULT_SPILL_THRESHOLD = 0.55f;
	private static final float DEFAULT_SPILL_STRENGTH = 0.35f;
	private static final float DEFAULT_SPILL_RADIUS = 6.0f;
	// a config missing these keys renders the shipped default contact/rays look, 1.0 being "the intended look".
	private static final float DEFAULT_CONTACT_STRENGTH = 1.0f;
	private static final float DEFAULT_CONTACT_REACH = 1.0f;
	private static final int DEFAULT_CONTACT_STEPS = 4;
	private static final float DEFAULT_RAYS_STRENGTH = 1.0f;
	// haze off by default; a cool pale blue-grey tint (v2: over the sky's own colour, not standing
	// alone) at the distance a settlement usually reads as "far". 0 means "auto": 0.75 of the depth
	// stage's own far clamp (see DepthPass.FAR_DEPTH_CLAMP / hazeDistanceResolved below).
	private static final float DEFAULT_HAZE_DISTANCE = 0.0f;
	private static final float DEFAULT_HAZE_STRENGTH = 0.22f;
	private static final String DEFAULT_HAZE_COLOR = "#b9c6d4";
	private static final float DEFAULT_HAZE_TINT = 0.25f;
	private static final float DEFAULT_HAZE_NIGHT = 0.35f;
	// ao off by default; 12 samples in a one-block radius is the day-one balance -- priced separately in bench.
	private static final int DEFAULT_AO_SAMPLES = 12;
	private static final float DEFAULT_AO_RADIUS = 1.0f;
	private static final float DEFAULT_AO_STRENGTH = 1.0f;
	// water reflections off by default; the ray march step size is not a config knob -- see
	// RmlsDepthTuning.REFLECTION_STEP. strength and glint retuned alongside the water_resolve.fsh
	// glint fix (pow 180->600, factor 1.5->0.8) so the shipped defaults match the tightened look.
	private static final float DEFAULT_REFLECTION_STRENGTH = 0.45f;
	private static final float DEFAULT_GLINT_STRENGTH = 0.6f;
	// coloured light off by default; a torch's warmth or a sea lantern's cool spilling a little
	// onto what it lights, and tinting it toward the light's own hue.
	private static final float DEFAULT_LIGHT_STRENGTH = 0.35f;
	private static final float DEFAULT_LIGHT_TINT = 0.25f;
	// metal reflections off by default; rides the water reflection chain (see depthEnabled/require
	// below), a middling strength so gold and diamond read as reflective without mirroring the world.
	// rori 2026-09-20: grass was catching a sun glint off the water-mask depth-gap guess (see
	// water_mask.fsh), unrelated to metal at all -- but while in here, metal (iron/diamond etc)
	// read as shinier than she wanted too, so this default is halved alongside the water fix.
	private static final float DEFAULT_METAL_STRENGTH = 0.18f;
	// glass reflections off by default; a dielectric's own fresnel is grazing-only (F0 0.04 in
	// water_resolve.fsh's glass branch), so this strength sits higher than metal's without reading
	// as a mirror -- a windowpane should catch the sky at an angle, not turn into a puddle.
	private static final float DEFAULT_GLASS_STRENGTH = 0.6f; // 0.35 with F0 0.04 was invisible on the bench
	// sun shadows off by default; a block mesh cast from the sun (bless-shadow-pass brief), not a
	// re-draw of sodium's own chunks. span/resolution govern the ortho box the shadow camera covers
	// and the persistent shadow map's side length -- both fixed for a run, no "auto" sentinel.
	private static final float DEFAULT_SHADOW_STRENGTH = 0.55f;
	private static final float DEFAULT_SHADOW_SPAN = 128.0f;
	private static final int DEFAULT_SHADOW_RESOLUTION = 2048;
	// light through glass off by default: a stained window's own colour on the sun shafts it lets
	// through and on the flood-filled block light that crosses it indoors. 1.0 is the full dye colour,
	// 0.0 is clear glass whatever colour the pane actually is.
	private static final float DEFAULT_GLASS_TINT_STRENGTH = 1.0f;
	// the voxel path trace off by default: one diffuse bounce through the voxel copy of the world, four
	// rays a pixel at half resolution, 24 blocks of reach, the sky at its natural brightness.
	private static final float DEFAULT_GI_STRENGTH = 1.0f;
	private static final int DEFAULT_GI_RAYS = 4;
	private static final float DEFAULT_GI_DISTANCE = 24.0f;
	private static final float DEFAULT_GI_SKY = 1.0f;
	// two bounces: light creeps round one corner. 1 is the cheaper single bounce.
	private static final int DEFAULT_GI_BOUNCES = 2;
	// half resolution by default (2); 3 or 4 trims the trace/accumulate/blur/history targets further
	// for the same shader code -- gi_resolve's depth-aware bilinear upsamples whatever size it is handed.
	private static final int DEFAULT_GI_SCALE = 2;
	// off by default: tracing only alternate texels a frame (parity on x+y+frame) halves the march
	// cost again, at the price of a one-frame-old estimate on the untraced half until it fills in.
	private static final boolean DEFAULT_GI_CHECKERBOARD = false;
	// emitter hot-spot fix: 1.0 keeps a torch's own glow at the old un-scaled brightness on distant
	// surfaces (the fade-by-distance in gi_trace's emissive() handles the near-field burn on its own).
	private static final float DEFAULT_GI_EMISSIVE = 1.0f;
	// volumetric light off by default: a thin height fog the sun's shafts and a lantern's glow show in,
	// marched 20 steps a pixel at half resolution out to 64 blocks.
	private static final float DEFAULT_VOLUME_STRENGTH = 1.0f;
	private static final float DEFAULT_VOLUME_DENSITY = 0.006f;
	private static final int DEFAULT_VOLUME_STEPS = 20;
	private static final float DEFAULT_VOLUME_DISTANCE = 64.0f;
	// air-dither fix: 1.0 keeps the block-light in-scatter term at the old brightness; volume_resolve's
	// 4x4 tent already cleans most of the checkerboard, so this is a player's own extra dial down.
	private static final float DEFAULT_VOLUME_GLOW = 1.0f;
	// screen-space light rays off by default: the underwater rays' march, in air, toward an on-screen sun.
	private static final float DEFAULT_SUN_RAYS_STRENGTH = 1.0f;
	// wetness off by default: ground open to the sky darkens and puddles reflect while it rains, drying
	// over wet_dry_seconds after. strength governs how strongly the wet look reads (fresnel/darkening);
	// the seconds knob is how long a dry-out takes once the rain stops.
	private static final float DEFAULT_WET_STRENGTH = 0.7f;
	private static final float DEFAULT_WET_DRY_SECONDS = 60.0f;
	// rmls-cost brief: the render-thread cost knobs -- a big, built-up world (rori's live status:
	// voxel_volume_snapshot_ms 252, light_volume_fill_ms 55 with 552 emitters, metal_tick_micros_p95
	// 1808) needs these tighter than the bench fixture's own numbers ever forced. voxel_budget_ms is
	// VoxelVolume's per-frame render-thread scan budget (was a fixed 1.5 ms); voxel_rebuild_seconds is
	// the floor of its adaptive rebuild interval (was a fixed 2 s); material_budget_ms is
	// MetalMaskScan's per-frame chunk-tag scan budget (was a fixed 0.5 ms); voxel_emitter_cap is how
	// many of the voxel volume's nearest light emitters get their own flood fill (was a fixed 4096).
	private static final float DEFAULT_VOXEL_BUDGET_MS = 1.0f;
	private static final float DEFAULT_VOXEL_REBUILD_SECONDS = 2.0f;
	private static final float DEFAULT_MATERIAL_BUDGET_MS = 0.25f;
	private static final int DEFAULT_VOXEL_EMITTER_CAP = 1024;
	private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

	// the one table every knob's range, default and screen label come from -- the reader below
	// pulls its min/max/default from here instead of its own literals, and RmlsSettingsScreen
	// walks this same list to build its widgets, so the two can never drift apart. "mode" is the
	// one MODE-kind entry; its range is a set of strings (MODE_VALUES), not a float span.
	public enum Kind { MODE, BOOLEAN, FLOAT, INT }
	public record OptionSpec(String key, Kind kind, float min, float max, float defaultValue, String label, String group) {}
	public static final java.util.List<String> MODE_VALUES = java.util.List.of("off", "identity", "grade", "bloom");
	public static final java.util.List<OptionSpec> OPTIONS = java.util.List.of(
		new OptionSpec("mode", Kind.MODE, 0, 0, 0, "render pipeline", "colour"),
		new OptionSpec("grain", Kind.BOOLEAN, 0, 1, 0, "film grain", "colour"),
		new OptionSpec("bloom_threshold", Kind.FLOAT, 0.0f, 1.5f, DEFAULT_BLOOM_THRESHOLD, "bloom threshold", "colour"),
		new OptionSpec("bloom_strength", Kind.FLOAT, 0.0f, 2.0f, DEFAULT_BLOOM_STRENGTH, "bloom strength", "colour"),
		new OptionSpec("grade_strength", Kind.FLOAT, 0.0f, 2.0f, DEFAULT_GRADE_STRENGTH, "colour grade strength", "colour"),
		new OptionSpec("grain_strength", Kind.FLOAT, 0.0f, 2.0f, DEFAULT_GRAIN_STRENGTH, "grain strength", "colour"),
		new OptionSpec("fxaa", Kind.BOOLEAN, 0, 1, 0, "anti-aliasing (fxaa)", "colour"),
		new OptionSpec("fxaa_strength", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_FXAA_STRENGTH, "anti-aliasing strength", "colour"),
		new OptionSpec("auto_exposure", Kind.BOOLEAN, 0, 1, 0, "eye adaptation", "colour"),
		new OptionSpec("exposure_target", Kind.FLOAT, 0.1f, 0.6f, DEFAULT_EXPOSURE_TARGET, "eye adaptation target", "colour"),
		new OptionSpec("exposure_min", Kind.FLOAT, 0.25f, 1.0f, DEFAULT_EXPOSURE_MIN, "eye adaptation min", "colour"),
		new OptionSpec("exposure_max", Kind.FLOAT, 1.0f, 4.0f, DEFAULT_EXPOSURE_MAX, "eye adaptation max", "colour"),
		new OptionSpec("exposure_speed", Kind.FLOAT, 0.2f, 8.0f, DEFAULT_EXPOSURE_SPEED, "eye adaptation speed", "colour"),
		new OptionSpec("spill_threshold", Kind.FLOAT, 0.2f, 0.9f, DEFAULT_SPILL_THRESHOLD, "light spill threshold", "colour"),
		new OptionSpec("spill_strength", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_SPILL_STRENGTH, "light spill strength", "colour"),
		new OptionSpec("spill_radius", Kind.FLOAT, 2.0f, 12.0f, DEFAULT_SPILL_RADIUS, "light spill radius", "colour"),
		new OptionSpec("contact_shadows", Kind.BOOLEAN, 0, 1, 0, "contact shadows", "depth"),
		new OptionSpec("contact_strength", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_CONTACT_STRENGTH, "contact shadow strength", "depth"),
		new OptionSpec("contact_reach", Kind.FLOAT, 0.25f, 4.0f, DEFAULT_CONTACT_REACH, "contact shadow reach", "depth"),
		new OptionSpec("contact_steps", Kind.INT, 2, 8, DEFAULT_CONTACT_STEPS, "contact shadow steps", "depth"),
		new OptionSpec("underwater_rays", Kind.BOOLEAN, 0, 1, 0, "underwater light rays", "depth"),
		new OptionSpec("rays_strength", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_RAYS_STRENGTH, "underwater ray strength", "depth"),
		new OptionSpec("ambient_occlusion", Kind.BOOLEAN, 0, 1, 0, "ambient occlusion", "depth"),
		new OptionSpec("ao_samples", Kind.INT, 4, 32, DEFAULT_AO_SAMPLES, "ambient occlusion samples", "depth"),
		new OptionSpec("ao_radius", Kind.FLOAT, 0.25f, 4.0f, DEFAULT_AO_RADIUS, "ambient occlusion radius", "depth"),
		new OptionSpec("ao_strength", Kind.FLOAT, 0.0f, 2.0f, DEFAULT_AO_STRENGTH, "ambient occlusion strength", "depth"),
		new OptionSpec("haze", Kind.BOOLEAN, 0, 1, 0, "distance haze", "depth"),
		new OptionSpec("haze_distance", Kind.FLOAT, 0.0f, 256.0f, DEFAULT_HAZE_DISTANCE, "haze distance, 0 is auto", "depth"),
		new OptionSpec("haze_strength", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_HAZE_STRENGTH, "haze strength", "depth"),
		new OptionSpec("haze_tint", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_HAZE_TINT, "haze sky tint", "depth"),
		new OptionSpec("haze_night", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_HAZE_NIGHT, "haze night strength", "depth"),
		new OptionSpec("water_reflections", Kind.BOOLEAN, 0, 1, 0, "water reflections", "depth"),
		new OptionSpec("reflection_strength", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_REFLECTION_STRENGTH, "reflection strength", "depth"),
		new OptionSpec("glint_strength", Kind.FLOAT, 0.0f, 5.0f, DEFAULT_GLINT_STRENGTH, "reflection glint strength", "depth"),
		new OptionSpec("colored_light", Kind.BOOLEAN, 0, 1, 0, "coloured light spill", "depth"),
		new OptionSpec("light_strength", Kind.FLOAT, 0.0f, 2.0f, DEFAULT_LIGHT_STRENGTH, "coloured light strength", "depth"),
		new OptionSpec("light_tint", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_LIGHT_TINT, "coloured light tint", "depth"),
		new OptionSpec("metal_reflections", Kind.BOOLEAN, 0, 1, 0, "metal reflections", "depth"),
		new OptionSpec("metal_strength", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_METAL_STRENGTH, "metal reflection strength", "depth"),
		new OptionSpec("glass_reflections", Kind.BOOLEAN, 0, 1, 0, "glass reflections", "depth"),
		new OptionSpec("glass_strength", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_GLASS_STRENGTH, "glass reflection strength", "depth"),
		new OptionSpec("sun_shadows", Kind.BOOLEAN, 0, 1, 0, "sun shadows", "depth"),
		new OptionSpec("shadow_strength", Kind.FLOAT, 0.0f, 2.0f, DEFAULT_SHADOW_STRENGTH, "sun shadow strength", "depth"),
		new OptionSpec("shadow_span", Kind.FLOAT, 32.0f, 512.0f, DEFAULT_SHADOW_SPAN, "sun shadow span", "depth"),
		new OptionSpec("shadow_resolution", Kind.INT, 1024, 4096, DEFAULT_SHADOW_RESOLUTION, "sun shadow map resolution", "depth"),
		new OptionSpec("glass_light", Kind.BOOLEAN, 0, 1, 0, "light through glass", "depth"),
		new OptionSpec("glass_tint_strength", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_GLASS_TINT_STRENGTH, "glass tint strength", "depth"),
		new OptionSpec("voxel_gi", Kind.BOOLEAN, 0, 1, 0, "voxel bounce light", "depth"),
		new OptionSpec("gi_strength", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_GI_STRENGTH, "bounce light strength", "depth"),
		new OptionSpec("gi_rays", Kind.INT, 1, 8, DEFAULT_GI_RAYS, "bounce light rays", "depth"),
		new OptionSpec("gi_distance", Kind.FLOAT, 4.0f, 64.0f, DEFAULT_GI_DISTANCE, "bounce light reach", "depth"),
		new OptionSpec("gi_sky", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_GI_SKY, "bounce sky brightness", "depth"),
		new OptionSpec("gi_bounces", Kind.INT, 1, 2, DEFAULT_GI_BOUNCES, "bounce light bounces", "depth"),
		new OptionSpec("gi_scale", Kind.INT, 2, 4, DEFAULT_GI_SCALE, "bounce light resolution divisor", "depth"),
		new OptionSpec("gi_checkerboard", Kind.BOOLEAN, 0, 1, DEFAULT_GI_CHECKERBOARD ? 1 : 0, "bounce light checkerboard trace", "depth"),
		new OptionSpec("gi_emissive", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_GI_EMISSIVE, "bounce light emitter brightness", "depth"),
		new OptionSpec("volumetric_light", Kind.BOOLEAN, 0, 1, 0, "volumetric light", "depth"),
		new OptionSpec("volume_strength", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_VOLUME_STRENGTH, "volumetric strength", "depth"),
		new OptionSpec("volume_density", Kind.FLOAT, 0.0f, 0.25f, DEFAULT_VOLUME_DENSITY, "volumetric density", "depth"),
		new OptionSpec("volume_steps", Kind.INT, 4, 64, DEFAULT_VOLUME_STEPS, "volumetric steps", "depth"),
		new OptionSpec("volume_distance", Kind.FLOAT, 8.0f, 256.0f, DEFAULT_VOLUME_DISTANCE, "volumetric distance", "depth"),
		new OptionSpec("volume_glow", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_VOLUME_GLOW, "volumetric block-light glow", "depth"),
		new OptionSpec("sun_rays", Kind.BOOLEAN, 0, 1, 0, "sun light rays", "depth"),
		new OptionSpec("sun_rays_strength", Kind.FLOAT, 0.0f, 3.0f, DEFAULT_SUN_RAYS_STRENGTH, "sun ray strength", "depth"),
		new OptionSpec("wetness", Kind.BOOLEAN, 0, 1, 0, "rain wetness", "depth"),
		new OptionSpec("wet_strength", Kind.FLOAT, 0.0f, 1.0f, DEFAULT_WET_STRENGTH, "wetness strength", "depth"),
		new OptionSpec("wet_dry_seconds", Kind.FLOAT, 5.0f, 300.0f, DEFAULT_WET_DRY_SECONDS, "wetness dry-out seconds", "depth"),
		new OptionSpec("voxel_budget_ms", Kind.FLOAT, 0.25f, 4.0f, DEFAULT_VOXEL_BUDGET_MS, "voxel volume frame budget", "depth"),
		new OptionSpec("voxel_rebuild_seconds", Kind.FLOAT, 1.0f, 30.0f, DEFAULT_VOXEL_REBUILD_SECONDS, "voxel volume rebuild floor", "depth"),
		new OptionSpec("material_budget_ms", Kind.FLOAT, 0.1f, 2.0f, DEFAULT_MATERIAL_BUDGET_MS, "material scan frame budget", "depth"),
		new OptionSpec("voxel_emitter_cap", Kind.INT, 256, 4096, DEFAULT_VOXEL_EMITTER_CAP, "voxel volume emitter cap", "depth"));
	private static final java.util.Map<String, OptionSpec> BY_KEY = OPTIONS.stream()
		.collect(java.util.stream.Collectors.toMap(OptionSpec::key, spec -> spec));
	static OptionSpec spec(String key) {
		OptionSpec found = BY_KEY.get(key);
		require(found != null, "no option spec for " + key);
		return found;
	}

	public static ClientConfig read(Path path) throws IOException {
		// a missing file used to mean everything off and no file written; a first launch then showed
		// nothing at all and sent people hunting for a config. the jar carries the tuned config
		// (assets/bless/default-config.json, the m4 candidate with every effect on): write it once,
		// then read it like any other. if the copy fails the old silence stands.
		if (!Files.exists(path)) {
			try (var in = ClientConfig.class.getResourceAsStream("/assets/bless/default-config.json")) {
				if (in != null) {
					Files.createDirectories(path.getParent());
					Files.copy(in, path);
					RmlsClient.LOGGER.info("bless: wrote the default configuration to {}", path);
				}
			} catch (IOException e) {
				RmlsClient.LOGGER.warn("bless: could not write the default configuration to {}: {}", path, e.toString());
			}
		}
		if (!Files.exists(path)) return new ClientConfig("off", false, null,
			DEFAULT_BLOOM_THRESHOLD, DEFAULT_BLOOM_STRENGTH, DEFAULT_GRADE_STRENGTH, DEFAULT_GRAIN_STRENGTH,
			false, DEFAULT_FXAA_STRENGTH,
			false, DEFAULT_EXPOSURE_TARGET, DEFAULT_EXPOSURE_MIN, DEFAULT_EXPOSURE_MAX, DEFAULT_EXPOSURE_SPEED,
			DEFAULT_SPILL_THRESHOLD, DEFAULT_SPILL_STRENGTH, DEFAULT_SPILL_RADIUS,
			false, false, "none",
			DEFAULT_CONTACT_STRENGTH, DEFAULT_CONTACT_REACH, DEFAULT_CONTACT_STEPS, DEFAULT_RAYS_STRENGTH,
			false, DEFAULT_HAZE_DISTANCE, DEFAULT_HAZE_STRENGTH, DEFAULT_HAZE_COLOR, DEFAULT_HAZE_TINT, DEFAULT_HAZE_NIGHT,
			false, DEFAULT_AO_SAMPLES, DEFAULT_AO_RADIUS, DEFAULT_AO_STRENGTH,
			false, DEFAULT_REFLECTION_STRENGTH, DEFAULT_GLINT_STRENGTH,
			false, DEFAULT_LIGHT_STRENGTH, DEFAULT_LIGHT_TINT,
			false, DEFAULT_METAL_STRENGTH,
			false, DEFAULT_GLASS_STRENGTH,
			false, DEFAULT_SHADOW_STRENGTH, DEFAULT_SHADOW_SPAN, DEFAULT_SHADOW_RESOLUTION,
			false, DEFAULT_GLASS_TINT_STRENGTH,
			false, DEFAULT_GI_STRENGTH, DEFAULT_GI_RAYS, DEFAULT_GI_DISTANCE, DEFAULT_GI_SKY, DEFAULT_GI_BOUNCES,
			DEFAULT_GI_SCALE, DEFAULT_GI_CHECKERBOARD, DEFAULT_GI_EMISSIVE,
			false, DEFAULT_VOLUME_STRENGTH, DEFAULT_VOLUME_DENSITY, DEFAULT_VOLUME_STEPS, DEFAULT_VOLUME_DISTANCE, DEFAULT_VOLUME_GLOW,
			false, DEFAULT_SUN_RAYS_STRENGTH,
			false, DEFAULT_WET_STRENGTH, DEFAULT_WET_DRY_SECONDS,
			DEFAULT_VOXEL_BUDGET_MS, DEFAULT_VOXEL_REBUILD_SECONDS, DEFAULT_MATERIAL_BUDGET_MS, DEFAULT_VOXEL_EMITTER_CAP);
		try (var reader = Files.newBufferedReader(path)) {
			var parsed = JsonParser.parseReader(reader);
			require(parsed.isJsonObject(), "configuration must be an object");
			JsonObject json = parsed.getAsJsonObject();
			// an unknown key warns and is ignored: a config from a newer jar (or a stale key from an older
			// one) used to refuse the whole file and kill the client at startup; a knob nobody reads is
			// not worth a crash. the known keys still validate exactly as before.
			for (String key : json.keySet()) if (!KEYS.contains(key)) RmlsClient.LOGGER.warn("bless: ignoring unknown configuration field {}", key);
			require(json.has("schema_version") && json.get("schema_version").isJsonPrimitive()
				&& json.getAsJsonPrimitive("schema_version").isNumber()
				&& json.get("schema_version").getAsDouble() == 1d, "schema_version must be 1");
			require(json.has("mode") && json.get("mode").isJsonPrimitive()
				&& json.getAsJsonPrimitive("mode").isString(), "mode must be a string");
			String mode = json.get("mode").getAsString();
			require(Set.of("off", "identity", "grade", "bloom").contains(mode), "mode must be off, identity, grade or bloom");
			boolean grain = false;
			if (json.has("grain")) {
				require(json.get("grain").isJsonPrimitive() && json.getAsJsonPrimitive("grain").isBoolean(), "grain must be boolean");
				grain = json.get("grain").getAsBoolean();
			}
			require(!grain || mode.equals("grade") || mode.equals("bloom"), "grain requires grade or bloom mode");
			boolean fxaa = optionalBoolean(json, "fxaa");
			require(!fxaa || mode.equals("grade") || mode.equals("bloom"), "fxaa requires grade or bloom mode");
			float fxaaStrength = readFloat(json, spec("fxaa_strength"));
			boolean autoExposure = optionalBoolean(json, "auto_exposure");
			require(!autoExposure || mode.equals("grade") || mode.equals("bloom"), "auto_exposure requires grade or bloom mode");
			Path diagnostics = null;
			if (json.has("diagnostics_path") && !json.get("diagnostics_path").isJsonNull()) {
				require(json.get("diagnostics_path").isJsonPrimitive()
					&& json.getAsJsonPrimitive("diagnostics_path").isString(), "diagnostics_path must be an absolute path or null");
				diagnostics = Path.of(json.get("diagnostics_path").getAsString());
				require(diagnostics.isAbsolute() && diagnostics.getFileName() != null, "diagnostics_path must name an absolute file");
			}
			float bloomThreshold = readFloat(json, spec("bloom_threshold"));
			float bloomStrength = readFloat(json, spec("bloom_strength"));
			float gradeStrength = readFloat(json, spec("grade_strength"));
			float grainStrength = readFloat(json, spec("grain_strength"));
			float exposureTarget = readFloat(json, spec("exposure_target"));
			float exposureMin = readFloat(json, spec("exposure_min"));
			float exposureMax = readFloat(json, spec("exposure_max"));
			float exposureSpeed = readFloat(json, spec("exposure_speed"));
			float spillThreshold = readFloat(json, spec("spill_threshold"));
			float spillStrength = readFloat(json, spec("spill_strength"));
			float spillRadius = readFloat(json, spec("spill_radius"));
			boolean contact = optionalBoolean(json, "contact_shadows");
			boolean rays = optionalBoolean(json, "underwater_rays");
			String diagnostic = "none";
			if (json.has("depth_diagnostic")) {
				require(json.get("depth_diagnostic").isJsonPrimitive() && json.getAsJsonPrimitive("depth_diagnostic").isString(),
					"depth_diagnostic must be a string");
				diagnostic = json.get("depth_diagnostic").getAsString();
			}
			require(Set.of("none", "view").contains(diagnostic), "depth_diagnostic must be none or view");
			boolean haze = optionalBoolean(json, "haze");
			boolean ambientOcclusion = optionalBoolean(json, "ambient_occlusion");
			boolean waterReflections = optionalBoolean(json, "water_reflections");
			boolean coloredLight = optionalBoolean(json, "colored_light");
			boolean metalReflections = optionalBoolean(json, "metal_reflections");
			boolean glassReflections = optionalBoolean(json, "glass_reflections");
			boolean sunShadows = optionalBoolean(json, "sun_shadows");
			boolean glassLight = optionalBoolean(json, "glass_light");
			boolean voxelGi = optionalBoolean(json, "voxel_gi");
			boolean volumetricLight = optionalBoolean(json, "volumetric_light");
			boolean sunRays = optionalBoolean(json, "sun_rays");
			boolean wetness = optionalBoolean(json, "wetness");
			// a diagnosis switch, not a knob: the resolve shows the raw shadow mask instead of shading.
			DepthPass.shadowDebug = json.has("shadow_debug") ? (json.get("shadow_debug").isJsonPrimitive() && json.getAsJsonPrimitive("shadow_debug").isNumber() ? json.get("shadow_debug").getAsInt() : (optionalBoolean(json, "shadow_debug") ? 1 : 0)) : 0;
			require(!metalReflections || waterReflections, "metal_reflections requires water_reflections");
			// glass rides the same water_mask/water_resolve chain metal does (glass brief item 5/6) --
			// same requirement, same reasoning as metal's line above.
			require(!glassReflections || waterReflections, "glass_reflections requires water_reflections");
			// wetness rides the same water_mask/water_resolve chain metal and glass do -- same reasoning.
			require(!wetness || waterReflections, "wetness requires water_reflections");
			// light through glass has no sun to carry without the shadow map that shapes it.
			require(!glassLight || sunShadows, "glass_light requires sun_shadows");
			require(!(contact || rays || haze || ambientOcclusion || waterReflections || coloredLight || sunShadows || glassLight || voxelGi || volumetricLight || sunRays || wetness) || mode.equals("grade") || mode.equals("bloom"),
				"depth effects require grade or bloom mode");
			require(diagnostic.equals("none")
				|| (mode.equals("off") && !grain && !contact && !rays && !haze && !ambientOcclusion && !waterReflections && !coloredLight
					&& !metalReflections && !glassReflections && !sunShadows && !glassLight && !voxelGi && !volumetricLight && !sunRays && !wetness && diagnostics != null),
				"depth diagnostic requires mode off, no effects, and diagnostics_path; diagnostic runs are not performance results");
			float contactStrength = readFloat(json, spec("contact_strength"));
			float contactReach = readFloat(json, spec("contact_reach"));
			int contactSteps = readInt(json, spec("contact_steps"));
			float raysStrength = readFloat(json, spec("rays_strength"));
			// 0 is the "auto" sentinel (see hazeDistanceResolved()); any other value stands on its own.
			float hazeDistance = readFloat(json, spec("haze_distance"));
			float hazeStrength = readFloat(json, spec("haze_strength"));
			String hazeColor = DEFAULT_HAZE_COLOR;
			if (json.has("haze_color")) {
				require(json.get("haze_color").isJsonPrimitive() && json.getAsJsonPrimitive("haze_color").isString(),
					"haze_color must be a string");
				String value = json.get("haze_color").getAsString();
				require(HEX_COLOR.matcher(value).matches(), "haze_color must be a #rrggbb string");
				hazeColor = value;
			}
			float hazeTint = readFloat(json, spec("haze_tint"));
			float hazeNight = readFloat(json, spec("haze_night"));
			int aoSamples = readInt(json, spec("ao_samples"));
			float aoRadius = readFloat(json, spec("ao_radius"));
			float aoStrength = readFloat(json, spec("ao_strength"));
			float reflectionStrength = readFloat(json, spec("reflection_strength"));
			float glintStrength = readFloat(json, spec("glint_strength"));
			float lightStrength = readFloat(json, spec("light_strength"));
			float lightTint = readFloat(json, spec("light_tint"));
			float metalStrength = readFloat(json, spec("metal_strength"));
			float glassStrength = readFloat(json, spec("glass_strength"));
			float shadowStrength = readFloat(json, spec("shadow_strength"));
			float shadowSpan = readFloat(json, spec("shadow_span"));
			int shadowResolution = readInt(json, spec("shadow_resolution"));
			float glassTintStrength = readFloat(json, spec("glass_tint_strength"));
			float giStrength = readFloat(json, spec("gi_strength"));
			int giRays = readInt(json, spec("gi_rays"));
			float giDistance = readFloat(json, spec("gi_distance"));
			float giSky = readFloat(json, spec("gi_sky"));
			int giBounces = readInt(json, spec("gi_bounces"));
			int giScale = readInt(json, spec("gi_scale"));
			boolean giCheckerboard = optionalBoolean(json, "gi_checkerboard");
			float giEmissive = readFloat(json, spec("gi_emissive"));
			float volumeStrength = readFloat(json, spec("volume_strength"));
			float volumeDensity = readFloat(json, spec("volume_density"));
			int volumeSteps = readInt(json, spec("volume_steps"));
			float volumeDistance = readFloat(json, spec("volume_distance"));
			float volumeGlow = readFloat(json, spec("volume_glow"));
			float sunRaysStrength = readFloat(json, spec("sun_rays_strength"));
			float wetStrength = readFloat(json, spec("wet_strength"));
			float wetDrySeconds = readFloat(json, spec("wet_dry_seconds"));
			float voxelBudgetMs = readFloat(json, spec("voxel_budget_ms"));
			float voxelRebuildSeconds = readFloat(json, spec("voxel_rebuild_seconds"));
			float materialBudgetMs = readFloat(json, spec("material_budget_ms"));
			int voxelEmitterCap = readInt(json, spec("voxel_emitter_cap"));
			return new ClientConfig(mode, grain, diagnostics, bloomThreshold, bloomStrength, gradeStrength, grainStrength,
				fxaa, fxaaStrength,
				autoExposure, exposureTarget, exposureMin, exposureMax, exposureSpeed,
				spillThreshold, spillStrength, spillRadius,
				contact, rays, diagnostic, contactStrength, contactReach, contactSteps, raysStrength,
				haze, hazeDistance, hazeStrength, hazeColor, hazeTint, hazeNight,
				ambientOcclusion, aoSamples, aoRadius, aoStrength,
				waterReflections, reflectionStrength, glintStrength,
				coloredLight, lightStrength, lightTint,
				metalReflections, metalStrength,
				glassReflections, glassStrength,
				sunShadows, shadowStrength, shadowSpan, shadowResolution,
				glassLight, glassTintStrength,
				voxelGi, giStrength, giRays, giDistance, giSky, giBounces, giScale, giCheckerboard, giEmissive,
				volumetricLight, volumeStrength, volumeDensity, volumeSteps, volumeDistance, volumeGlow,
				sunRays, sunRaysStrength,
				wetness, wetStrength, wetDrySeconds,
				voxelBudgetMs, voxelRebuildSeconds, materialBudgetMs, voxelEmitterCap);
		}
	}

	boolean depthEnabled() {
		return contactShadows || underwaterRays || haze || ambientOcclusion || waterReflections || coloredLight || metalReflections
			|| glassReflections || sunShadows || glassLight || voxelGi || volumetricLight || sunRays || wetness || !depthDiagnostic.equals("none");
	}
	/** the voxel volume serves four stages: the flood-filled spill, the bounce, the air, and the sky walk wetness reads for cover. */
	boolean voxelVolumeNeeded() { return coloredLight || voxelGi || volumetricLight || wetness; }
	boolean diagnosticOnly() { return !depthDiagnostic.equals("none"); }

	// 0 means "auto": 0.75 of the depth stage's own far clamp, the same 128 DepthSettings.y already
	// carries for every effect's view-depth clamp (DepthPass.FAR_DEPTH_CLAMP). a positive value overrides.
	float hazeDistanceResolved() { return hazeDistance > 0.0f ? hazeDistance : DepthPass.FAR_DEPTH_CLAMP * 0.75f; }

	// #rrggbb already validated strictly on read; parsed here rather than stored as floats so
	// the status json can echo back the exact string the config file carries.
	float hazeColorR() { return Integer.parseInt(hazeColor.substring(1, 3), 16) / 255.0f; }
	float hazeColorG() { return Integer.parseInt(hazeColor.substring(3, 5), 16) / 255.0f; }
	float hazeColorB() { return Integer.parseInt(hazeColor.substring(5, 7), 16) / 255.0f; }

	// the settings screen's one door into a config's live values -- every OPTIONS key, plus the
	// two extras (diagnostics_path, haze_color) the screen carries through unedited. one switch,
	// so a field renamed here is a compile error there instead of a silent mismatch.
	public static Object value(ClientConfig config, String key) {
		return switch (key) {
			case "mode" -> config.mode();
			case "grain" -> config.grain();
			case "bloom_threshold" -> config.bloomThreshold();
			case "bloom_strength" -> config.bloomStrength();
			case "grade_strength" -> config.gradeStrength();
			case "grain_strength" -> config.grainStrength();
			case "fxaa" -> config.fxaa();
			case "fxaa_strength" -> config.fxaaStrength();
			case "auto_exposure" -> config.autoExposure();
			case "exposure_target" -> config.exposureTarget();
			case "exposure_min" -> config.exposureMin();
			case "exposure_max" -> config.exposureMax();
			case "exposure_speed" -> config.exposureSpeed();
			case "spill_threshold" -> config.spillThreshold();
			case "spill_strength" -> config.spillStrength();
			case "spill_radius" -> config.spillRadius();
			case "contact_shadows" -> config.contactShadows();
			case "contact_strength" -> config.contactStrength();
			case "contact_reach" -> config.contactReach();
			case "contact_steps" -> config.contactSteps();
			case "underwater_rays" -> config.underwaterRays();
			case "rays_strength" -> config.raysStrength();
			case "ambient_occlusion" -> config.ambientOcclusion();
			case "ao_samples" -> config.aoSamples();
			case "ao_radius" -> config.aoRadius();
			case "ao_strength" -> config.aoStrength();
			case "haze" -> config.haze();
			case "haze_distance" -> config.hazeDistance();
			case "haze_strength" -> config.hazeStrength();
			case "haze_tint" -> config.hazeTint();
			case "haze_night" -> config.hazeNight();
			case "water_reflections" -> config.waterReflections();
			case "reflection_strength" -> config.reflectionStrength();
			case "glint_strength" -> config.glintStrength();
			case "colored_light" -> config.coloredLight();
			case "light_strength" -> config.lightStrength();
			case "light_tint" -> config.lightTint();
			case "metal_reflections" -> config.metalReflections();
			case "metal_strength" -> config.metalStrength();
			case "glass_reflections" -> config.glassReflections();
			case "glass_strength" -> config.glassStrength();
			case "sun_shadows" -> config.sunShadows();
			case "shadow_strength" -> config.shadowStrength();
			case "shadow_span" -> config.shadowSpan();
			case "shadow_resolution" -> config.shadowResolution();
			case "glass_light" -> config.glassLight();
			case "glass_tint_strength" -> config.glassTintStrength();
			case "voxel_gi" -> config.voxelGi();
			case "gi_strength" -> config.giStrength();
			case "gi_rays" -> config.giRays();
			case "gi_distance" -> config.giDistance();
			case "gi_sky" -> config.giSky();
			case "gi_bounces" -> config.giBounces();
			case "gi_scale" -> config.giScale();
			case "gi_checkerboard" -> config.giCheckerboard();
			case "gi_emissive" -> config.giEmissive();
			case "volumetric_light" -> config.volumetricLight();
			case "volume_strength" -> config.volumeStrength();
			case "volume_density" -> config.volumeDensity();
			case "volume_steps" -> config.volumeSteps();
			case "volume_distance" -> config.volumeDistance();
			case "volume_glow" -> config.volumeGlow();
			case "sun_rays" -> config.sunRays();
			case "sun_rays_strength" -> config.sunRaysStrength();
			case "wetness" -> config.wetness();
			case "wet_strength" -> config.wetStrength();
			case "wet_dry_seconds" -> config.wetDrySeconds();
			case "voxel_budget_ms" -> config.voxelBudgetMs();
			case "voxel_rebuild_seconds" -> config.voxelRebuildSeconds();
			case "material_budget_ms" -> config.materialBudgetMs();
			case "voxel_emitter_cap" -> config.voxelEmitterCap();
			case "diagnostics_path" -> config.diagnosticsPath();
			case "haze_color" -> config.hazeColor();
			default -> throw new IllegalArgumentException("no option value for " + key);
		};
	}

	// writes a complete config: schema_version plus every OPTIONS key from `values`, in the
	// same order as OPTIONS, so a diff against a hand-edited file stays readable. depth_diagnostic
	// is always written "none" -- the settings screen is for live tuning, not diagnostic capture,
	// so it always closes out any diagnostic run rather than risk writing a combination
	// read() would reject (diagnostic mode requires off/no-effects, which this screen may just
	// have turned on). the two cross-field rules read() enforces are sanitized here first so a
	// player flipping switches in an order read() dislikes never produces an unloadable config.
	public static void write(Path path, java.util.Map<String, Object> values, Path diagnosticsPath, String hazeColor, int shadowDebug) throws IOException {
		java.util.LinkedHashMap<String, Object> sanitized = new java.util.LinkedHashMap<>(values);
		String mode = (String) sanitized.get("mode");
		boolean gradeOrBloom = "grade".equals(mode) || "bloom".equals(mode);
		if (!gradeOrBloom) {
			sanitized.put("grain", false);
			sanitized.put("fxaa", false);
			sanitized.put("auto_exposure", false);
			for (String key : new String[] {"contact_shadows", "underwater_rays", "haze", "ambient_occlusion",
				"water_reflections", "colored_light", "sun_shadows",
				"voxel_gi", "volumetric_light", "sun_rays", "wetness", "glass_light"}) sanitized.put(key, false);
		}
		if (!Boolean.TRUE.equals(sanitized.get("water_reflections"))) {
			sanitized.put("metal_reflections", false);
			sanitized.put("glass_reflections", false);
			sanitized.put("wetness", false);
		}
		if (!Boolean.TRUE.equals(sanitized.get("sun_shadows"))) {
			sanitized.put("glass_light", false);
		}

		JsonObject json = new JsonObject();
		json.addProperty("schema_version", 1);
		for (OptionSpec option : OPTIONS) {
			Object value = sanitized.get(option.key());
			switch (option.kind()) {
				case MODE -> json.addProperty(option.key(), (String) value);
				case BOOLEAN -> json.addProperty(option.key(), (Boolean) value);
				case FLOAT -> json.addProperty(option.key(), (Float) value);
				case INT -> json.addProperty(option.key(), (Integer) value);
			}
		}
		json.addProperty("haze_color", hazeColor);
		if (diagnosticsPath != null) json.addProperty("diagnostics_path", diagnosticsPath.toString());
		json.addProperty("depth_diagnostic", "none");
		json.addProperty("shadow_debug", shadowDebug);

		Files.createDirectories(path.getParent());
		try (var writer = new com.google.gson.stream.JsonWriter(Files.newBufferedWriter(path))) {
			writer.setIndent("\t");
			new com.google.gson.Gson().toJson(json, writer);
		}
	}

	private static float readFloat(JsonObject json, OptionSpec spec) {
		String key = spec.key();
		if (!json.has(key)) return spec.defaultValue();
		require(json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isNumber(), key + " must be a number");
		float value = json.get(key).getAsFloat();
		require(Float.isFinite(value), key + " must be finite");
		require(value >= spec.min() && value <= spec.max(), key + " must be between " + spec.min() + " and " + spec.max());
		return value;
	}

	private static int readInt(JsonObject json, OptionSpec spec) {
		String key = spec.key();
		if (!json.has(key)) return (int) spec.defaultValue();
		require(json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isNumber(), key + " must be a number");
		double raw = json.get(key).getAsDouble();
		require(raw == Math.floor(raw) && Double.isFinite(raw), key + " must be a whole number");
		int value = (int) raw;
		require(value >= spec.min() && value <= spec.max(), key + " must be between " + (int) spec.min() + " and " + (int) spec.max());
		return value;
	}

	// the settings screen preserves this diagnostic switch verbatim on write, without exposing a
	// widget for it -- it is a diagnosis toggle, not a knob (see DepthPass.shadowDebug's own comment).
	public static int peekShadowDebug(Path path) {
		if (!Files.exists(path)) return 0;
		try (var reader = Files.newBufferedReader(path)) {
			var parsed = JsonParser.parseReader(reader);
			if (!parsed.isJsonObject()) return 0;
			JsonObject json = parsed.getAsJsonObject();
			if (!json.has("shadow_debug") || !json.get("shadow_debug").isJsonPrimitive()) return 0;
			var primitive = json.getAsJsonPrimitive("shadow_debug");
			if (primitive.isBoolean()) return primitive.getAsBoolean() ? 1 : 0;
			if (primitive.isNumber()) return primitive.getAsInt();
			return 0;
		} catch (IOException failure) {
			return 0;
		}
	}

	private static boolean optionalBoolean(JsonObject json, String key) {
		if (!json.has(key)) return false;
		require(json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isBoolean(), key + " must be boolean");
		return json.get(key).getAsBoolean();
	}

	static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("bless: " + message);
	}
}
