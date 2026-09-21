package dev.bless;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** framegraph draw body; uploaded uniform slices never survive their executing frame. */
final class DepthPass {
	// the view-depth clamp every effect's viewDepth()/packViewDepth() shares (DepthSettings.y in
	// depth_common.glsl) -- also haze's own "auto" distance base (0.75 of this), so ClientConfig
	// reads it from here rather than carrying a second copy of the number.
	static final float FAR_DEPTH_CLAMP = 128.0f;

	// 480 = 5 mat4 (64 each) + 14 vec4 (16 each): sun/daylight, sun screen/framebuffer,
	// ndc/pack settings (now carrying sun elevation too), the contact/rays tuning knobs,
	// haze's own distance/strength/night-floor, its config rgb tint, the scene fog colour
	// the veil now blends toward, the ao sample/radius/strength knobs, the water reflection
	// plane normal (ViewUp) and its strength/step/glint knobs, the camera's world position
	// with its yaw in w (the ripple's world-xz key, brief item 6), and -- appended next,
	// light-volume brief items 2/6 -- the light volume's world origin (w = 1.0 when valid),
	// the inverse view rotation (ViewToWorld, for reconstructing a pixel's world position from
	// its view-space depth) and the light_strength/light_tint knobs, and -- appended last,
	// metal mask brief item 3/4 -- the camera's view rotation matrix (the only way its vertex
	// shader turns a world-space block position into the view space every other effect already
	// reconstructs from clip space) and the metal settings knob, and -- appended last, sun
	// shadows brief item 1 -- the sun's own view-projection (ShadowMatrix) and its
	// strength/texel-size/bias/enabled knobs (ShadowSettings). all appended in order so
	// depth_common.glsl's existing offsets never move. each new knob gets its own vec4 slot
	// (or an unused component of an existing one) rather than packing floats: std140 already
	// rounds every vec4 up to 16 bytes, so packing would cost precision (encode/decode banding
	// on a smooth veil) to save bytes the layout was going to spend anyway.
	// vulkan-client: appended after ShadowSettings -- GiSettings, GiFrame (2 vec4), PrevViewProjection
	// (mat4), PrevCameraPosition, VolumeSettings, SunColor, SkyColor (4 vec4): 560 + 32 + 64 + 64 = 720.
	private static final int SCENE_BYTES = 736; // + GiExtra

	// the temporal pass's frame-to-frame state, owned by DepthEffects (which alone advances it after each
	// executed frame) and read here at upload -- the same static-slot transport shadowDebug already uses,
	// rather than threading four more values through every draw signature.
	static Matrix4f prevViewProjection = new Matrix4f();
	static Vec3 prevCameraPosition = Vec3.ZERO;
	static long frameIndex;
	static boolean historyValid;
	// wetness (bless-wet brief item 2): DepthEffects alone advances this from the rain level and the
	// frame's own dt, same static-slot transport as the temporal state above -- uploaded verbatim
	// into MetalSettings.w, which no other stage claims.
	static volatile float wetValue;

	static void draw(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		RenderPipeline pipeline, GpuTextureView output, GpuTextureView worldDepth, GpuTextureView mask, boolean rays) {
		draw(encoder, frame, tuning, light, pipeline, output, worldDepth, mask, rays ? 1 : 0);
	}

	// resolveMode is DepthResolve.x: 0 contact, 1 underwater rays, 2 sun rays (depth_resolve.fsh); the
	// ao blur reads it as its direction, the gi blur as its step -- each pass's own contract.
	static void draw(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		RenderPipeline pipeline, GpuTextureView output, GpuTextureView worldDepth, GpuTextureView mask, int resolveMode) {
		RenderSystem.assertOnRenderThread();
		if (output.texture() == worldDepth.texture() || (mask != null && output.texture() == mask.texture()))
			throw new IllegalStateException("depth pass input/output alias");
		if (output.texture().getFormat() != GpuFormat.RGBA8_UNORM || worldDepth.texture().getFormat() != GpuFormat.D32_FLOAT)
			throw new IllegalStateException("unverified depth pass format");
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var resolve = mask == null ? null : encoder.transientMemory().uploadGpu(
				Std140Builder.onStack(stack, 16).putVec4(resolveMode, 0, 0, 0).get(), alignment, GpuBuffer.USAGE_UNIFORM);
			var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			// color-only attachment: main depth is sampled, never simultaneously attached or cleared.
			// the pass label names the pipeline (its location path) so the harness's per-pass breakdown tells the effects apart.
			try (var pass = encoder.createRenderPass(() -> "bless " + pipeline.getLocation().getPath(), output, Optional.empty())) {
				pass.setPipeline(pipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, sampler);
				if (mask != null) {
					pass.setUniform("DepthResolve", resolve);
					pass.bindTexture("EffectMask", mask, sampler);
				}
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	// water reflections' mask pass: same DepthScene upload, plus the pre-effect colour scratch so a
	// hit can fetch its reflected colour right here at full precision (item 3) instead of packing a
	// hit uv into 8 bits for the resolve pass to sample later, and the material mask (MetalMask;
	// DepthPass.drawMetalMask's own output) so the shader can tell a metal surface, a water surface,
	// or neither apart by looking rather than guessing from a depth gap (grass-glint brief item 2 --
	// see DepthPipelines.WATER_MASK's own doc comment for what that gap used to be). depths sampled
	// NEAREST like every other depth read; the scratch is sampled LINEAR since it stands in for a
	// smooth reflected image. metal reflections (item 4) add a second colour output --
	// reflection_kind, written by the same fragment shader alongside reflection_mask so
	// water_resolve.fsh can tell the two reflection kinds apart without a second render pass over
	// the same geometry.
	static void drawWaterMask(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		GpuTextureView output, GpuTextureView kindOutput, GpuTextureView worldDepth,
		GpuTextureView scratch, GpuTextureView metalMask, GpuTextureView voxelAtlas) {
		RenderSystem.assertOnRenderThread();
		if (output.texture() == worldDepth.texture() || output.texture() == scratch.texture()
			|| output.texture() == kindOutput.texture())
			throw new IllegalStateException("depth pass input/output alias");
		if (output.texture().getFormat() != GpuFormat.RGBA8_UNORM || kindOutput.texture().getFormat() != GpuFormat.RGBA8_UNORM
			|| worldDepth.texture().getFormat() != GpuFormat.D32_FLOAT)
			throw new IllegalStateException("unverified depth pass format");
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			// a hand-built descriptor carries no render area of its own; the two-target pass threw
			// "renderArea must be provided" on its first frame and took the whole depth stage down.
			var descriptor = com.mojang.blaze3d.systems.RenderPassDescriptor.create(() -> "bless water mask")
				.withColorAttachment(output).withColorAttachment(kindOutput)
				.withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, output.getWidth(0), output.getHeight(0)));
			try (var pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(DepthPipelines.WATER_MASK);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("ReflectionScratch", scratch, linear);
				pass.bindTexture("MetalMask", metalMask, nearest);
				// wetness (bless-wet brief item 3): NEAREST, same as GiTrace's own VoxelAtlas bind --
				// texelFetch in the shader ignores the sampler's filter anyway.
				pass.bindTexture("VoxelAtlas", voxelAtlas, nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	// water reflections' resolve pass: reads the half-res mask directly -- rgb the reflected colour
	// the mask pass already fetched, a its confidence (item 3; no more hit uv, no ReflectionScratch
	// here). the mask is sampled LINEAR here (unlike every other resolve, which does its own
	// depth-aware bilinear against a packed view depth this mask does not carry -- the single-target
	// tradeoff from item 2 of the brief). declares no DepthResolve uniform (item 12: bound and never read).
	static void drawWaterResolve(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		GpuTextureView output, GpuTextureView worldDepth, GpuTextureView mask, GpuTextureView kind, GpuTextureView scratch) {
		RenderSystem.assertOnRenderThread();
		if (output.texture() == worldDepth.texture() || output.texture() == mask.texture() || output.texture() == kind.texture())
			throw new IllegalStateException("depth pass input/output alias");
		if (output.texture().getFormat() != GpuFormat.RGBA8_UNORM || worldDepth.texture().getFormat() != GpuFormat.D32_FLOAT)
			throw new IllegalStateException("unverified depth pass format");
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			try (var pass = encoder.createRenderPass(() -> "bless water resolve", output, Optional.empty())) {
				pass.setPipeline(DepthPipelines.WATER_RESOLVE);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("EffectMask", mask, linear);
				pass.bindTexture("ReflectionKind", kind, linear);
				pass.bindTexture("ReflectionScratch", scratch, linear);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	// the material mask draw (brief item 3, grass-glint brief item 1): real geometry, one
	// non-indexed triangle list per frame's tracked block list, cull-tested and depth-tested by hand
	// in metal_mask.fsh (no depth attachment on this pass -- see DepthPipelines.METAL_MASK's doc
	// comment). always clears to zero first, whether or not there is anything to draw -- this target
	// is pooled (CrossFrameResourcePool) and read every frame water reflections run (water_mask.fsh's
	// MetalMask sampler is bound unconditionally, see DepthPipelines.WATER_MASK's doc comment), so a
	// frame with nothing to draw still needs a defined all-zero result, not whatever the pool last
	// held. three draw calls into the one target now (still one render pass, one clear): metal's
	// cubes write r, water's flat quads (DepthEffects.WATER_QUAD_OFFSETS) write g, glass's cubes
	// (glass brief item 4, reusing CUBE_OFFSETS same as metal) write b -- the same pipeline and
	// vertex/fragment shader, told which by the MaskKind uniform (also picks the depth-test tolerance
	// in metal_mask.fsh: 0.05 metal and glass, 0.15 water).
	static void drawMetalMask(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning,
		GpuTextureView output, GpuTextureView worldDepth,
		com.mojang.blaze3d.buffers.GpuBufferSlice metalVertices, int metalVertexCount,
		com.mojang.blaze3d.buffers.GpuBufferSlice waterVertices, int waterVertexCount,
		com.mojang.blaze3d.buffers.GpuBufferSlice glassVertices, int glassVertexCount,
		com.mojang.blaze3d.buffers.GpuBufferSlice paneVertices, int paneVertexCount) {
		RenderSystem.assertOnRenderThread();
		if (output.texture() == worldDepth.texture()) throw new IllegalStateException("depth pass input/output alias");
		if (output.texture().getFormat() != GpuFormat.RGBA8_UNORM || worldDepth.texture().getFormat() != GpuFormat.D32_FLOAT)
			throw new IllegalStateException("unverified depth pass format");
		try (var pass = encoder.createRenderPass(() -> "bless metal mask", output, Optional.of(new org.joml.Vector4f(0, 0, 0, 0)))) {
			if (metalVertexCount > 0 || waterVertexCount > 0 || glassVertexCount > 0 || paneVertexCount > 0) {
				long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
				try (var stack = MemoryStack.stackPush()) {
					var scene = uploadScene(encoder, stack, frame, tuning, VoxelVolume.Sample.NONE, alignment);
					var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
					pass.setPipeline(DepthPipelines.METAL_MASK);
					RenderSystem.bindDefaultUniforms(pass);
					pass.setUniform("DepthScene", scene);
					pass.bindTexture("WorldDepth", worldDepth, sampler);
					if (metalVertexCount > 0) {
						var kind = encoder.transientMemory().uploadGpu(
							Std140Builder.onStack(stack, 16).putVec4(0, 0, 0, 0).get(), alignment, GpuBuffer.USAGE_UNIFORM);
						pass.setUniform("MaskKind", kind);
						pass.setVertexBuffer(0, metalVertices);
						pass.draw(metalVertexCount, 1, 0, 0);
					}
					if (waterVertexCount > 0) {
						var kind = encoder.transientMemory().uploadGpu(
							Std140Builder.onStack(stack, 16).putVec4(1, 0, 0, 0).get(), alignment, GpuBuffer.USAGE_UNIFORM);
						pass.setUniform("MaskKind", kind);
						pass.setVertexBuffer(0, waterVertices);
						pass.draw(waterVertexCount, 1, 0, 0);
					}
					// glass brief item 4: a third draw, same pipeline, MaskKind 2 -- cube geometry (reused
					// CUBE_OFFSETS) writing b=1, tolerance 0.05 same as metal (see metal_mask.fsh).
					if (glassVertexCount > 0) {
						var kind = encoder.transientMemory().uploadGpu(
							Std140Builder.onStack(stack, 16).putVec4(2, 0, 0, 0).get(), alignment, GpuBuffer.USAGE_UNIFORM);
						pass.setUniform("MaskKind", kind);
						pass.setVertexBuffer(0, glassVertices);
						pass.draw(glassVertexCount, 1, 0, 0);
					}
					// panes brief item 3: a fourth draw, same pipeline, MaskKind 2 reused whole (glass's own
					// channel and tolerance -- a pane face sits on the block's real surface, not inset from
					// it, so glass's 0.05 depth-test tolerance is correct unchanged) -- geometry is the
					// pane's own collision-shape boxes (DepthEffects.paneBoxVertices), not a cube.
					if (paneVertexCount > 0) {
						var kind = encoder.transientMemory().uploadGpu(
							Std140Builder.onStack(stack, 16).putVec4(2, 0, 0, 0).get(), alignment, GpuBuffer.USAGE_UNIFORM);
						pass.setUniform("MaskKind", kind);
						pass.setVertexBuffer(0, paneVertices);
						pass.draw(paneVertexCount, 1, 0, 0);
					}
				}
			}
		}
	}

	// sun shadows (bless-shadow-pass brief item 2): depth-only into the persistent shadow map, no
	// colour attachment at all. cleared to 1.0 (far) on every call -- only invoked when DepthEffects
	// has already decided this frame redraws the map (brief item 3), so the clear-and-redraw cost is
	// already budgeted. ShadowMesh.drawAll binds and draws each ready section's own vertex buffer;
	// this method only sets the pipeline and uniforms it draws against.
	// set by ClientConfig.read: 2.0 in ShadowSettings.w makes the resolve draw the raw mask.
	static volatile int shadowDebug; // 0 off, 1 raw mask, 2 stored map depth, 3 the pixel's own light depth
	static volatile int lastMapVertices;
	// frozen at the frame that drew the map and used for ShadowMatrix until the next redraw -- by the
	// resolve, and by the bounce and the air, which read the same uploadScene block. the matrix used to
	// follow the live camera every frame while the map itself was drawn up to eight blocks earlier, so
	// every shadow slid with the player and snapped back on each redraw (rori's first walk through a
	// village: "dragging and jerking").
	static volatile Matrix4f activeShadowMatrix;

	static int drawShadowDepth(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning,
		GpuTextureView shadowMap, GpuTextureView shadowColor, int resolution, ShadowMesh mesh) {
		RenderSystem.assertOnRenderThread();
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		int vertices;
		activeShadowMatrix = buildShadowMatrix(frame.worldSun(), frame.elevation(), frame.cameraPosition(), tuning.shadowSpan(), tuning.shadowResolution(), frame.zeroToOne());
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, VoxelVolume.Sample.NONE, alignment);
			var descriptor = com.mojang.blaze3d.systems.RenderPassDescriptor.create(() -> "bless shadow depth")
				.withColorAttachment(shadowColor)
				.withDepthAttachment(shadowMap, java.util.OptionalDouble.of(1.0))
				.withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, resolution, resolution));
			try (var pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(DepthPipelines.SHADOW_DEPTH);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				vertices = mesh.drawAll(pass);
				lastMapVertices = vertices;
			}
		}
		return vertices;
	}

	// sun shadows (brief item 4): full-res, composited onto whatever `current` already holds --
	// reads WorldDepth (to reconstruct the pixel's world position, same worldPosition() helper
	// light_resolve uses) and the persistent shadow map as a second sampler.
	static void drawShadowResolve(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning,
		GpuTextureView output, GpuTextureView worldDepth, GpuTextureView shadowMap, GpuTextureView shadowTint) {
		RenderSystem.assertOnRenderThread();
		if (output.texture() == worldDepth.texture() || output.texture() == shadowMap.texture())
			throw new IllegalStateException("depth pass input/output alias");
		if (output.texture().getFormat() != GpuFormat.RGBA8_UNORM || worldDepth.texture().getFormat() != GpuFormat.D32_FLOAT)
			throw new IllegalStateException("unverified depth pass format");
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, VoxelVolume.Sample.NONE, alignment);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			try (var pass = encoder.createRenderPass(() -> "bless shadow resolve", output, Optional.empty())) {
				pass.setPipeline(DepthPipelines.SHADOW_RESOLVE);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("ShadowMap", shadowMap, nearest);
				pass.bindTexture("ShadowTint", shadowTint, nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	// glass-light brief item 3: the tint draw, called directly right after drawShadowDepth in
	// DepthEffects.updateShadowMap -- not wrapped in a framegraph pass, same as drawShadowDepth itself.
	// clears shadowTint to white every redraw, then multiplies in one cube per tracked glass block
	// (MaskKind-free -- SHADOW_TINT is its own pipeline, no metal/water/glass-reflection sibling to
	// distinguish). depth-tested against the map drawShadowDepth just wrote, in the same resolution,
	// reused without its own clear (LOAD, not CLEAR) so the test compares against this frame's mesh.
	static void drawShadowTint(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning,
		GpuTextureView shadowTint, GpuTextureView shadowMap, int resolution,
		com.mojang.blaze3d.buffers.GpuBufferSlice glassTintVertices, int glassTintVertexCount,
		com.mojang.blaze3d.buffers.GpuBufferSlice paneTintVertices, int paneTintVertexCount) {
		RenderSystem.assertOnRenderThread();
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, VoxelVolume.Sample.NONE, alignment);
			var descriptor = com.mojang.blaze3d.systems.RenderPassDescriptor.create(() -> "bless shadow tint")
				.withColorAttachment(shadowTint, Optional.of(new org.joml.Vector4f(1, 1, 1, 1)))
				.withDepthAttachment(shadowMap)
				.withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, resolution, resolution));
			try (var pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(DepthPipelines.SHADOW_TINT);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				if (glassTintVertexCount > 0 && glassTintVertices != null) {
					pass.setVertexBuffer(0, glassTintVertices);
					pass.draw(glassTintVertexCount, 1, 0, 0);
				}
				// panes brief item 3: the pane tint boxes, drawn after the block tint cubes into the same
				// texture -- same pipeline, same vertex format (DepthEffects.buildPaneTintBuffer already
				// writes SHADOW_TINT_VERTEX_FORMAT's position+colour layout), no separate clear.
				if (paneTintVertexCount > 0 && paneTintVertices != null) {
					pass.setVertexBuffer(0, paneTintVertices);
					pass.draw(paneTintVertexCount, 1, 0, 0);
				}
			}
		}
	}

	private static com.mojang.blaze3d.buffers.GpuBufferSlice uploadScene(CommandEncoder encoder, MemoryStack stack,
		DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light, long alignment) {
		boolean skySource = frame.skyColorAvailable();
		float fogR = skySource ? frame.skyColorR() : tuning.hazeColorR();
		float fogG = skySource ? frame.skyColorG() : tuning.hazeColorG();
		float fogB = skySource ? frame.skyColorB() : tuning.hazeColorB();
		// light-volume item 2: world = CameraPosition.xyz + (ViewToWorld * vec4(viewPos, 0)).xyz --
		// ViewToWorld is the inverse of the same view rotation DepthFrameInputs already captures for
		// the sun/ViewUp directions (frame.viewRotation()), computed fresh here since no other pass needs it.
		var viewToWorld = new Matrix4f(frame.viewRotation()).invert();
		float sunScale = 1.15f * frame.daylightWeight();
		float sunR = sunTint(frame.elevation(), 0) * sunScale, sunG = sunTint(frame.elevation(), 1) * sunScale, sunB = sunTint(frame.elevation(), 2) * sunScale;
		var data = Std140Builder.onStack(stack, SCENE_BYTES).putMat4f(frame.projection()).putMat4f(frame.inverseProjection())
			.putVec4(frame.viewSun().x, frame.viewSun().y, frame.viewSun().z, frame.daylightWeight())
			.putVec4(frame.sunU(), frame.sunV(), frame.width(), frame.height())
			.putVec4(frame.zeroToOne() ? 1 : 0, FAR_DEPTH_CLAMP, frame.elevation(), tuning.shadowSpan() / tuning.shadowResolution())
			.putVec4(tuning.contactStrength(), tuning.contactReach(), tuning.contactSteps(), tuning.raysStrength())
			.putVec4(tuning.hazeDistance(), tuning.hazeStrength(), tuning.hazeNight(), 0)
			.putVec4(tuning.hazeColorR(), tuning.hazeColorG(), tuning.hazeColorB(), 0)
			.putVec4(fogR, fogG, fogB, tuning.hazeTint())
			.putVec4(tuning.aoSamples(), tuning.aoRadius(), tuning.aoStrength(), 0)
			.putVec4(frame.viewUp().x, frame.viewUp().y, frame.viewUp().z, 0)
			// wetness (bless-wet brief item 5): w was unused; wet_strength rides here since water_mask.fsh
			// and water_resolve.fsh already read x/y/z of this same vec4 and nothing else claims it.
			.putVec4(tuning.reflectionStrength(), RmlsDepthTuning.REFLECTION_STEP, tuning.glintStrength(), tuning.wetStrength())
			.putVec4((float) frame.cameraPosition().x(), (float) frame.cameraPosition().y(), (float) frame.cameraPosition().z(), frame.cameraYaw())
			.putVec4(light.origin().x(), light.origin().y(), light.origin().z(), light.valid() ? 1f : 0f)
			.putMat4f(viewToWorld)
			.putVec4(tuning.lightStrength(), tuning.lightTint(), 0, 0)
			.putMat4f(frame.viewRotation())
			.putVec4(tuning.metalStrength(), tuning.glassStrength(), tuning.glassTintStrength(), wetValue)
			// the matrix the map was DRAWN with, not one rebuilt from this frame's camera: between
			// redraws the camera keeps moving, and a fresh matrix reprojected every pixel against a map
			// made elsewhere, so shadows dragged along the ground and whole hills went dark.
			.putMat4f(activeShadowMatrix != null ? activeShadowMatrix
				: buildShadowMatrix(frame.worldSun(), frame.elevation(), frame.cameraPosition(), tuning.shadowSpan(), tuning.shadowResolution(), frame.zeroToOne()))
			// y is uv-space texel size (1/resolution), not world-space blocks: the resolve samples
			// ShadowMap by uv, and a uv offset is what its PCF loop actually needs -- the world-space
			// texel size (used for the java-side snap, buildShadowMatrix above) never leaves that method.
			.putVec4(tuning.shadowStrength(), 1f / tuning.shadowResolution(),
				RmlsDepthTuning.SHADOW_BIAS, frame.sunShadows() ? 1f + shadowDebug : 0f)
			// vulkan-client: the bounce and the air.
			.putVec4(tuning.giStrength(), tuning.giRays(), tuning.giDistance(), tuning.giSky())
			.putVec4((float) (frameIndex % 4096), RmlsDepthTuning.GI_HISTORY_BLEND, historyValid ? 1f : 0f, tuning.sunRaysStrength())
			.putMat4f(prevViewProjection)
			.putVec4((float) prevCameraPosition.x(), (float) prevCameraPosition.y(), (float) prevCameraPosition.z(), 0f)
			.putVec4(tuning.volumeStrength(), tuning.volumeDensity(), tuning.volumeSteps(), tuning.volumeDistance())
			.putVec4(sunR, sunG, sunB, 0.6f)
			.putVec4(fogR, fogG, fogB, 1f)
			.putVec4(tuning.giBounces(), tuning.giCheckerboard() ? 1f : 0f, tuning.giEmissive(), tuning.volumeGlow()).get();
		return encoder.transientMemory().uploadGpu(data, alignment, GpuBuffer.USAGE_UNIFORM);
	}

	// the sun's light colour in vanilla's own brightness units (a sunlit white block reads about 1.0):
	// warm at the horizon, white overhead, scaled by the daylight/weather gate so a rainy noon and a
	// sunset both dim the bounce and the shafts the way they dim vanilla's own sky.
	private static float sunTint(float elevation, int channel) {
		float t = Math.clamp((elevation - 0.05f) / 0.35f, 0f, 1f);
		t = t * t * (3f - 2f * t);
		float[] warm = {1.0f, 0.62f, 0.38f}, white = {1.0f, 0.97f, 0.92f};
		return warm[channel] + (white[channel] - warm[channel]) * t;
	}

	// bless-shadow-pass brief item 1: the sun's own orthographic view-projection. built in double
	// precision (JOML Matrix4d) around the camera so the lookAt/ortho math itself never sees a
	// huge single-precision magnitude; the resulting float matrix, applied directly to ShadowMesh's
	// absolute-float world positions (no camera subtraction -- see core/shadow_depth.vsh's doc
	// comment), is only as precise as those vertices already are, the same floor this codebase
	// already accepts for the metal mask's cube vertices (DepthEffects.CUBE_OFFSETS).
	private static Matrix4f buildShadowMatrix(Vector3f worldSun, float elevation, Vec3 cameraPosition,
		float spanBlocks, int resolution, boolean zeroToOne) {
		boolean nearZenith = Math.abs(elevation) > 0.98f;
		double upX = 0, upY = nearZenith ? 0 : 1, upZ = nearZenith ? 1 : 0;
		double sunX = worldSun.x(), sunY = worldSun.y(), sunZ = worldSun.z();
		// the light's own screen axes, used only to snap the shadow box's centre to whole texels
		// (below) so the map does not swim sub-texel as the camera drifts.
		double rightX = sunZ * upY - sunY * upZ, rightY = sunX * upZ - sunZ * upX, rightZ = sunY * upX - sunX * upY;
		double rightLen = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
		rightX /= rightLen; rightY /= rightLen; rightZ /= rightLen;
		double trueUpX = rightY * sunZ - rightZ * sunY, trueUpY = rightZ * sunX - rightX * sunZ, trueUpZ = rightX * sunY - rightY * sunX;
		double cx = cameraPosition.x(), cy = cameraPosition.y(), cz = cameraPosition.z();
		double texelSize = (double) spanBlocks / resolution;
		double alongRight = cx * rightX + cy * rightY + cz * rightZ;
		double alongUp = cx * trueUpX + cy * trueUpY + cz * trueUpZ;
		double snappedRight = Math.floor(alongRight / texelSize + 0.5) * texelSize;
		double snappedUp = Math.floor(alongUp / texelSize + 0.5) * texelSize;
		double shiftRight = snappedRight - alongRight, shiftUp = snappedUp - alongUp;
		double centerX = cx + rightX * shiftRight + trueUpX * shiftUp;
		double centerY = cy + rightY * shiftRight + trueUpY * shiftUp;
		double centerZ = cz + rightZ * shiftRight + trueUpZ * shiftUp;
		double eyeX = centerX + sunX * 200.0, eyeY = centerY + sunY * 200.0, eyeZ = centerZ + sunZ * 200.0;
		var view = new Matrix4d().setLookAt(eyeX, eyeY, eyeZ, centerX, centerY, centerZ, upX, upY, upZ);
		float half = spanBlocks / 2.0f;
		var proj = new Matrix4d().setOrtho(-half, half, -128.0, 128.0, 1.0, 512.0, zeroToOne);
		proj.mul(view);
		return new Matrix4f(proj);
	}

	// light-volume item 5: full-res pass, WorldDepth for the sky/world-position test, VoxelVolume
	// (the cpu-filled texture) and LightMain (the pre-effect main colour scratch DepthEffects keeps,
	// same idea as the reflections stage's own scratch -- see DepthEffects.runLightResolve) as the
	// two extra samplers. never called when light.valid() is false (DepthEffects gates on frame.light()).
	static void drawLight(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		GpuTextureView output, GpuTextureView worldDepth, GpuTextureView volume, GpuTextureView mainScratch) {
		RenderSystem.assertOnRenderThread();
		if (output.texture() == worldDepth.texture() || output.texture() == volume.texture() || output.texture() == mainScratch.texture())
			throw new IllegalStateException("depth pass input/output alias");
		if (output.texture().getFormat() != GpuFormat.RGBA8_UNORM || worldDepth.texture().getFormat() != GpuFormat.D32_FLOAT)
			throw new IllegalStateException("unverified depth pass format");
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			try (var pass = encoder.createRenderPass(() -> "bless light resolve", output, Optional.empty())) {
				pass.setPipeline(DepthPipelines.LIGHT_RESOLVE);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("LightVolume", volume, linear);
				pass.bindTexture("LightMain", mainScratch, nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	// ---- vulkan-client: the bounce ----

	private static void checkDepth(GpuTextureView output, GpuTextureView worldDepth) {
		RenderSystem.assertOnRenderThread();
		if (output.texture() == worldDepth.texture()) throw new IllegalStateException("depth pass input/output alias");
		if (worldDepth.texture().getFormat() != GpuFormat.D32_FLOAT) throw new IllegalStateException("unverified depth pass format");
	}

	/** gi_trace: half-res rgba16f radiance (a = sky visibility) out of the voxel and light atlases and the shadow map. */
	static void drawGiTrace(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		GpuTextureView output, GpuTextureView geometry, GpuTextureView worldDepth, GpuTextureView shadowMap, GpuTextureView shadowTint) {
		checkDepth(output, worldDepth);
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			var descriptor = com.mojang.blaze3d.systems.RenderPassDescriptor.create(() -> "bless gi trace")
				.withColorAttachment(output).withColorAttachment(geometry)
				.withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, output.getWidth(0), output.getHeight(0)));
			try (var pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(DepthPipelines.GI_TRACE);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("VoxelAtlas", light.voxels(), nearest);
				pass.bindTexture("LightAtlas", light.view(), linear);
				pass.bindTexture("ShadowMap", shadowMap, nearest);
				pass.bindTexture("ShadowTint", shadowTint, nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	/** gi_accumulate: blends the fresh trace with the reprojected history; writes the new history colour and distance. */
	static void drawGiAccumulate(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		GpuTextureView outputColor, GpuTextureView outputDistance, GpuTextureView worldDepth,
		GpuTextureView current, GpuTextureView history, GpuTextureView historyDistance) {
		checkDepth(outputColor, worldDepth);
		if (outputColor.texture() == history.texture() || outputDistance.texture() == historyDistance.texture() || outputColor.texture() == current.texture())
			throw new IllegalStateException("gi history input/output alias");
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			var descriptor = com.mojang.blaze3d.systems.RenderPassDescriptor.create(() -> "bless gi accumulate")
				.withColorAttachment(outputColor).withColorAttachment(outputDistance)
				.withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, outputColor.getWidth(0), outputColor.getHeight(0)));
			try (var pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(DepthPipelines.GI_ACCUMULATE);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("GiCurrent", current, nearest);
				pass.bindTexture("GiHistory", history, linear);
				pass.bindTexture("GiHistoryDepth", historyDistance, nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	/** gi_blur: one a-trous step of the given width in texels over the half-res radiance. */
	static void drawGiBlur(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		GpuTextureView output, GpuTextureView worldDepth, GpuTextureView input, GpuTextureView geometry, float step) {
		checkDepth(output, worldDepth);
		if (output.texture() == input.texture()) throw new IllegalStateException("gi blur input/output alias");
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var resolve = encoder.transientMemory().uploadGpu(
				Std140Builder.onStack(stack, 16).putVec4(step, 0, 0, 0).get(), alignment, GpuBuffer.USAGE_UNIFORM);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			try (var pass = encoder.createRenderPass(() -> "bless gi blur", output, Optional.empty())) {
				pass.setPipeline(DepthPipelines.GI_BLUR);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.setUniform("DepthResolve", resolve);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("EffectMask", input, nearest);
				pass.bindTexture("GiGeometry", geometry, nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	/** gi_resolve: full-res compose of the denoised bounce over the pre-effect colour (mainScratch), no blend. */
	static void drawGiResolve(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		GpuTextureView output, GpuTextureView worldDepth, GpuTextureView gi, GpuTextureView mainScratch) {
		checkDepth(output, worldDepth);
		if (output.texture() == gi.texture() || output.texture() == mainScratch.texture()) throw new IllegalStateException("gi resolve input/output alias");
		if (output.texture().getFormat() != GpuFormat.RGBA8_UNORM) throw new IllegalStateException("unverified depth pass format");
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			try (var pass = encoder.createRenderPass(() -> "bless gi resolve", output, Optional.empty())) {
				pass.setPipeline(DepthPipelines.GI_RESOLVE);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("GiSampler", gi, nearest);
				pass.bindTexture("LightMain", mainScratch, nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	// ---- vulkan-client: the air ----

	/** volume_march: half-res rgba16f (in-scatter, transmittance) through the light atlas and the shadow map. */
	static void drawVolumeMarch(CommandEncoder encoder, DepthFrameInputs.Frame frame, RmlsDepthTuning tuning, VoxelVolume.Sample light,
		GpuTextureView output, GpuTextureView worldDepth, GpuTextureView shadowMap, GpuTextureView shadowTint) {
		checkDepth(output, worldDepth);
		long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (var stack = MemoryStack.stackPush()) {
			var scene = uploadScene(encoder, stack, frame, tuning, light, alignment);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			try (var pass = encoder.createRenderPass(() -> "bless volume march", output, Optional.empty())) {
				pass.setPipeline(DepthPipelines.VOLUME_MARCH);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DepthScene", scene);
				pass.bindTexture("WorldDepth", worldDepth, nearest);
				pass.bindTexture("LightAtlas", light.view(), linear);
				pass.bindTexture("ShadowMap", shadowMap, nearest);
				pass.bindTexture("ShadowTint", shadowTint, nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}
}
