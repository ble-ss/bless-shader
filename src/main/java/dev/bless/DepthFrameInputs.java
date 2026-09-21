package dev.bless;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** exact early world inputs; no late color or hand-depth fallback. */
final class DepthFrameInputs {
	private Matrix4f projection;
	private boolean consumed;
	private Map<String, Object> projectionFailure;
	private long skippedInvalidProjectionFrames;

	// renderLevel HEAD: a stale projection from a previous frame is never acceptable.
	void beginLevel() { projection = null; consumed = false; }

	// ModifyArg on ProjectionMatrixBuffer.getBuffer(Matrix4f), index 0; preserve the argument.
	// a nonfinite or singular projection is not an engine error -- it is a frame the depth
	// stage sits out. the argument always passes through unchanged; `projection` stays null
	// so render() below knows not to run this frame, and the frame is counted, not thrown.
	Matrix4f captureProjection(Matrix4f actual) {
		if (projection != null) throw new IllegalStateException("world projection captured twice");
		Matrix4f candidate = new Matrix4f(actual);
		String reason = null;
		for (float value : candidate.get(new float[16]))
			if (!Float.isFinite(value)) { reason = "nonfinite world projection"; break; }
		if (reason == null) {
			float determinant = candidate.determinant();
			if (!Float.isFinite(determinant) || Math.abs(determinant) < 0.00000001f) reason = "singular world projection";
		}
		if (reason != null) {
			recordProjectionFailure(reason, actual, candidate);
			skippedInvalidProjectionFrames++;
			return actual;
		}
		projection = candidate;
		return actual;
	}

	boolean projectionValid() { return projection != null; }
	long skippedInvalidProjectionFrames() { return skippedInvalidProjectionFrames; }

	// preserve the first rejected input verbatim without sending NaN/Infinity to JSON or changing engine state.
	private void recordProjectionFailure(String reason, Matrix4f actual, Matrix4f candidate) {
		if (projectionFailure != null) return;
		var evidence = new LinkedHashMap<String, Object>();
		evidence.put("reason", reason);
		evidence.put("matrix_encoding", "16 column-major Float.toString values; includes nonfinite values as strings");
		evidence.put("actual_projection", matrixStrings(actual));
		evidence.put("copied_projection", matrixStrings(candidate));
		evidence.put("copied_determinant", Float.toString(candidate.determinant()));
		try {
			var client = Minecraft.getInstance();
			var level = client.gameRenderer.gameRenderState().levelRenderState;
			var camera = level.cameraRenderState;
			var entity = camera.entityRenderState;
			var target = client.gameRenderer.mainRenderTarget();
			evidence.put("camera_initialized", camera.initialized);
			evidence.put("base_projection", matrixStrings(camera.projectionMatrix));
			evidence.put("view_rotation", matrixStrings(camera.viewRotationMatrix));
			evidence.put("camera_depth_far", Float.toString(camera.depthFar));
			evidence.put("camera_hud_fov", Float.toString(camera.hudFov));
			evidence.put("camera_position", new String[]{Double.toString(camera.pos.x()), Double.toString(camera.pos.y()), Double.toString(camera.pos.z())});
			evidence.put("entity_is_living", entity.isLiving);
			evidence.put("entity_is_player", entity.isPlayer);
			evidence.put("entity_hurt_time", Float.toString(entity.hurtTime));
			evidence.put("entity_hurt_duration", entity.hurtDuration);
			evidence.put("entity_hurt_direction", Float.toString(entity.hurtDir));
			evidence.put("entity_bob", Float.toString(entity.bob));
			evidence.put("entity_walk_distance", Float.toString(entity.backwardsInterpolatedWalkDistance));
			evidence.put("window_size", new int[]{client.getWindow().getWidth(), client.getWindow().getHeight()});
			evidence.put("main_target_size", new int[]{target.width, target.height});
			evidence.put("game_time", level.gameTime);
		} catch (RuntimeException failure) {
			evidence.put("additional_evidence_error", failure.toString());
		}
		projectionFailure = evidence;
	}

	Map<String, Object> projectionFailureEvidence() { return projectionFailure; }

	private static String[] matrixStrings(Matrix4f matrix) {
		float[] values = matrix.get(new float[16]);
		String[] strings = new String[values.length];
		for (int i = 0; i < values.length; i++) strings[i] = Float.toString(values[i]);
		return strings;
	}

	// call immediately after LevelRenderer.render, before the hand depth clear.
	Frame take(GameRenderer renderer, boolean contactRequested, boolean raysRequested, boolean hazeRequested,
		boolean aoRequested, boolean reflectionsRequested, boolean lightRequested, boolean sunShadowsRequested,
		boolean giRequested, boolean volumetricRequested, boolean sunRaysRequested) {
		RenderSystem.assertOnRenderThread();
		if (projection == null || consumed) throw new IllegalStateException("missing or reused world projection");
		consumed = true;
		var client = Minecraft.getInstance();
		var level = renderer.gameRenderState().levelRenderState;
		var camera = level.cameraRenderState;
		var target = renderer.mainRenderTarget();
		if (client.level == null || !camera.initialized || target.getDepthTextureView() == null)
			throw new IllegalStateException("world depth inputs unavailable");
		// the sky pass is skipped underwater and its render state goes stale (skybox NONE, sun angle 0), which
		// is exactly where the rays live. read the same sources the sky renderer reads when it does run.
		float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		var skybox = client.level.dimensionType().skybox();
		// the attribute is in degrees (a live run read 304.8 at dawn); the trig below and the recorded angle are radians.
		float sunAngle = (float) Math.toRadians(renderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partial));
		float rainBrightness = 1f - client.level.getRainLevel(partial);
		if (!Float.isFinite(sunAngle) || !Float.isFinite(rainBrightness))
			throw new IllegalStateException("invalid sky inputs");

		// mirrors SkyRenderer's Y(-90 degrees), X(sunAngle), local +Y sun center.
		float elevation = (float) Math.cos(sunAngle);

		// the haze veil's colour source: the engine's own sky colour for this camera and frame,
		// read from the same render state the sky renderer itself just populated this frame.
		// confirmed against the overnight javap dump: SkyRenderer.extractRenderState(ClientLevel,
		// float, Camera, SkyRenderState) writes SkyRenderState.skyColor via ARGB.color(r,g,b) (an
		// opaque packed int, standard 0xRRGGBB in the low three bytes) -- LevelRenderState carries
		// a `public final SkyRenderState skyRenderState` field alongside cameraRenderState, so it
		// is never null in practice, but the read is still guarded the way every other engine
		// input here is. haze already excludes underwater frames, which is exactly where this sky
		// pass is known (see the comment above) to go stale, so no extra staleness gate is needed.
		var sky = level.skyRenderState;
		boolean skyColorAvailable = sky != null;
		int packedSkyColor = skyColorAvailable ? sky.skyColor : 0;
		float skyColorR = ((packedSkyColor >> 16) & 0xFF) / 255f;
		float skyColorG = ((packedSkyColor >> 8) & 0xFF) / 255f;
		float skyColorB = (packedSkyColor & 0xFF) / 255f;
		var worldSun = new Vector3f(-(float) Math.sin(sunAngle), elevation, 0);
		var viewSun = camera.viewRotationMatrix.transformDirection(worldSun, new Vector3f()).normalize();
		// water reflections' plane normal: world up (0,1,0) rotated the same way the sun direction
		// just was, so both live in the same view space DepthPass uploads as ViewSun/ViewUp.
		var viewUp = camera.viewRotationMatrix.transformDirection(new Vector3f(0, 1, 0), new Vector3f()).normalize();
		var sunClip = projection.transform(new Vector4f(viewSun.x * 100, viewSun.y * 100, viewSun.z * 100, 1));
		boolean sunFront = sunClip.w > 0.0001f;
		float sunU = sunFront ? sunClip.x / sunClip.w * .5f + .5f : -1;
		float sunV = sunFront ? sunClip.y / sunClip.w * .5f + .5f : -1;
		boolean sunOnScreen = sunFront && sunU > .02f && sunU < .98f && sunV > .02f && sunV < .98f;

		// raw ingredients for the rays gate, kept and reported even though only `doesMobEffectBlockSky`
		// below decides -- this is the evidence that tells whether the render-state flag or the actual
		// mob effect list is lying. `client.player` is the LocalPlayer, null only before it spawns.
		var player = client.player;
		boolean cameraEntityInitialized = camera.initialized;
		boolean doesMobEffectBlockSkyRaw = camera.entityRenderState.doesMobEffectBlockSky;
		boolean playerHasBlindness = player != null && player.hasEffect(MobEffects.BLINDNESS);
		boolean playerHasDarkness = player != null && player.hasEffect(MobEffects.DARKNESS);
		List<String> playerEffects = player == null ? List.of()
			: player.getActiveEffects().stream().map(instance -> instance.getEffect().getRegisteredName()).toList();
		// the player's own hasEffect checks are ground truth when the player exists; the cached
		// render-state flag is only a fallback for the rare frame with no player (e.g. before spawn).
		String skyBlockSource = player != null ? "player_haseffect" : "render_state_flag";
		boolean doesMobEffectBlockSky = player != null ? (playerHasBlindness || playerHasDarkness) : doesMobEffectBlockSkyRaw;

		boolean daylight = skybox == DimensionType.Skybox.OVERWORLD && elevation > .08f && rainBrightness > .05f
			&& !doesMobEffectBlockSky;
		boolean underwater = camera.fogType == FogType.WATER;
		boolean ordinaryAir = camera.fogType == FogType.NONE || camera.fogType == FogType.ATMOSPHERIC;
		// conservative camera-column gate, not directional visibility for every receiving pixel.
		// contact keeps the vanilla sky test. rays cannot: canSeeSkyFromBelowWater reads sky light, which
		// six blocks of water already attenuate below 15, so the sun would never count as reaching the camera.
		// for rays the honest question is whether anything solid stands above this column; water is not solid.
		boolean columnOpenThroughWater = client.level.getHeight(Heightmap.Types.OCEAN_FLOOR,
			camera.blockPos.getX(), camera.blockPos.getZ()) <= camera.blockPos.getY();
		boolean skyOpen = daylight && ((contactRequested && client.level.canSeeSkyFromBelowWater(camera.blockPos))
			|| (raysRequested && underwater && columnOpenThroughWater));
		boolean contact = contactRequested && daylight && skyOpen && ordinaryAir;
		boolean rays = raysRequested && daylight && skyOpen && underwater && sunOnScreen;
		// haze has no sun/sky gate at all -- it grades every non-underwater pixel by distance alone,
		// so the only thing that turns it off is the water fog already doing the same job.
		boolean haze = hazeRequested && !underwater;
		// ao has no sun/sky gate either -- it darkens corners from geometry alone, so any ordinary-air
		// or underwater frame with valid depth qualifies (lava/powder-snow fog obscures the view entirely).
		boolean ao = aoRequested && (ordinaryAir || underwater);
		// reflections have no sun/sky gate either -- the water surface is visible day or night; the
		// glint term alone follows the sun via ViewSun.w. the one hard gate is being underwater: the
		// camera is then behind the reflecting surface, not in front of it.
		boolean reflections = reflectionsRequested && !underwater;
		// light volume has no sun/sky gate either -- it reads a cpu-filled colour volume, not sunlight;
		// the same fog-obscured gate ao uses (lava/powder-snow fog obscures the view entirely).
		boolean light = lightRequested && (ordinaryAir || underwater);
		String commonSkip = skybox != DimensionType.Skybox.OVERWORLD ? "no_overworld_sun"
			: doesMobEffectBlockSky ? "sky_blocking_effect"
			: elevation <= .08f ? "sun_below_threshold" : rainBrightness <= .05f ? "rain_blocks_sun"
			: !skyOpen ? "camera_sky_column_closed" : "none";
		String contactSkip = !contactRequested ? "not_requested" : !ordinaryAir ? "not_ordinary_air" : commonSkip;
		String raysSkip = !raysRequested ? "not_requested" : !underwater ? "not_underwater"
			: !commonSkip.equals("none") ? commonSkip : !sunOnScreen ? "sun_offscreen_or_behind" : "none";
		String hazeSkip = !hazeRequested ? "not_requested" : underwater ? "underwater" : "none";
		String aoSkip = !aoRequested ? "not_requested" : ao ? "none" : "fog_obscured";
		String reflectionsSkip = !reflectionsRequested ? "not_requested" : underwater ? "underwater" : "none";
		String lightSkip = !lightRequested ? "not_requested" : light ? "none" : "fog_obscured";
		// the bounce reads the same voxel volume the light stage does and has the same fog-obscured gate; the
		// air additionally sits out underwater, where the underwater rays already own the light in the water.
		boolean gi = giRequested && (ordinaryAir || underwater);
		boolean volumetric = volumetricRequested && ordinaryAir;
		String giSkip = !giRequested ? "not_requested" : gi ? "none" : "fog_obscured";
		String volumetricSkip = !volumetricRequested ? "not_requested" : underwater ? "underwater" : volumetric ? "none" : "fog_obscured";
		// the sun rays want daylight, ordinary air and the sun on screen -- no camera-column gate, since a
		// hill in front of the sun is exactly what throws them.
		boolean sunRays = sunRaysRequested && daylight && ordinaryAir && sunOnScreen;
		String sunRaysSkip = !sunRaysRequested ? "not_requested" : !ordinaryAir ? "not_ordinary_air"
			: !daylight ? (skybox != DimensionType.Skybox.OVERWORLD ? "no_overworld_sun" : doesMobEffectBlockSky ? "sky_blocking_effect"
			: elevation <= .08f ? "sun_below_threshold" : "rain_blocks_sun") : !sunOnScreen ? "sun_offscreen_or_behind" : "none";
		// sun shadows (bless-shadow-pass brief item 1/4): needs the same daylight gate contact reuses
		// (commonSkip) plus "no sense underwater" (own reason, not shared with contact's "not ordinary
		// air" text since shadows have no half/quarter mask stage to blame for it) -- not gated on the
		// camera's own sky column (skyOpen), unlike contact/rays: a shadow falls on world geometry
		// generally, not on whatever the player's own head happens to see right now.
		String sunShadowsSkip = !sunShadowsRequested ? "not_requested" : underwater ? "underwater"
			: skybox != DimensionType.Skybox.OVERWORLD ? "no_overworld_sun"
			: doesMobEffectBlockSky ? "sky_blocking_effect"
			: elevation <= .08f ? "sun_below_threshold" : rainBrightness <= .05f ? "rain_blocks_sun" : "none";
		boolean sunShadows = sunShadowsSkip.equals("none");
		float daylightWeight = Math.clamp((elevation - .08f) / .22f, 0, 1) * Math.clamp(rainBrightness, 0, 1);
		var inverse = new Matrix4f(projection).invert();
		for (float value : inverse.get(new float[16]))
			if (!Float.isFinite(value)) throw new IllegalStateException("invalid inverse world projection");
		// camera.yRot is degrees (vanilla convention); the ripple's world-xz reconstruction in
		// water_mask.fsh/water_resolve.fsh (brief item 6) needs it in radians, uploaded as
		// CameraPosition.w -- any fixed rotation by the camera yaw pins the ripple pattern to the
		// world, the sign convention only has to be consistent frame to frame.
		float cameraYaw = (float) Math.toRadians(camera.yRot);
		return new Frame(new Matrix4f(projection), inverse, viewSun, viewUp,
			sunU, sunV, daylightWeight, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
			contact, rays, haze, ao, reflections, light, sunShadows, gi, volumetric, sunRays, underwater, skyOpen, target.width, target.height,
			new Matrix4f(camera.viewRotationMatrix), camera.pos, cameraYaw, sunAngle, rainBrightness,
			skybox.name(), camera.fogType.name(), contactSkip, raysSkip, hazeSkip, aoSkip, reflectionsSkip, lightSkip, sunShadowsSkip, giSkip, volumetricSkip, sunRaysSkip, level.gameTime,
			doesMobEffectBlockSkyRaw, cameraEntityInitialized, playerHasBlindness, playerHasDarkness,
			playerEffects, skyBlockSource, elevation, skyColorAvailable, skyColorR, skyColorG, skyColorB, worldSun);
	}

	// matrices and vectors are per-frame values; upload during the executing graph pass only.
	record Frame(Matrix4f projection, Matrix4f inverseProjection, Vector3f viewSun, Vector3f viewUp,
		float sunU, float sunV, float daylightWeight, boolean zeroToOne,
		boolean contact, boolean rays, boolean haze, boolean ao, boolean reflections, boolean light, boolean sunShadows,
		boolean gi, boolean volumetric, boolean sunRays, boolean underwater, boolean skyOpen,
		int width, int height,
		Matrix4f viewRotation, Vec3 cameraPosition, float cameraYaw, float sunAngle, float rainBrightness,
		String skybox, String fogType, String contactSkip, String raysSkip, String hazeSkip, String aoSkip, String reflectionsSkip,
		String lightSkip, String sunShadowsSkip, String giSkip, String volumetricSkip, String sunRaysSkip, long gameTime,
		boolean doesMobEffectBlockSkyRaw, boolean cameraEntityInitialized,
		boolean playerHasBlindness, boolean playerHasDarkness, List<String> playerEffects, String skyBlockSource,
		float elevation, boolean skyColorAvailable, float skyColorR, float skyColorG, float skyColorB, Vector3f worldSun) {}
}
