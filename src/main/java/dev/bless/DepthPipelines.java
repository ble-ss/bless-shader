package dev.bless;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** initialize only when configured, during client setup before shader reload preparation. */
final class DepthPipelines {
	static final RenderPipeline CONTACT_MASK = make("contact_mask", "contact_mask", false, null);
	static final RenderPipeline RAYS_MASK = make("rays_mask", "underwater_rays", false, null);
	static final RenderPipeline CONTACT_RESOLVE = make("contact_resolve", "depth_resolve", true,
		new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE));
	static final RenderPipeline RAYS_RESOLVE = make("rays_resolve", "depth_resolve", true,
		new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE));
	// light rays in the air: the same quarter-res mask shape as the underwater rays, resolved through the
	// same additive RAYS_RESOLVE pipeline (resolve mode 2 picks the sun's colour in depth_resolve.fsh).
	static final RenderPipeline SUN_RAYS_MASK = make("sun_rays_mask", "sun_rays", false, null);
	static final RenderPipeline DIAGNOSTIC = make("depth_diagnostic", "depth_diagnostic", false, null);
	// haze has no mask stage to resolve -- it reads WorldDepth/DepthScene alone and composites straight
	// onto main color, so it skips the EffectMask/DepthResolve bind group a real resolve pass needs but
	// still writes color-only (dest alpha preserved) the way a resolve pass does.
	static final RenderPipeline HAZE = make("haze", "haze", false, true,
		new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE));
	// ao mask/blur write a fresh internal target each time (WRITE_ALL, no blend); only the final
	// resolve composites onto main colour, with the same ZERO / ONE_MINUS_SRC_ALPHA multiply-darken
	// shape contact_resolve already uses.
	static final RenderPipeline AO_MASK = make("ao_mask", "ssao_mask", false, null);
	static final RenderPipeline AO_BLUR_H = make("ao_blur_h", "ssao_blur", true, false, null);
	static final RenderPipeline AO_BLUR_V = make("ao_blur_v", "ssao_blur", true, false, null);
	static final RenderPipeline AO_RESOLVE = make("ao_resolve", "ao_resolve", true, true,
		new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE));
	// water reflections: mask reads the pre-effect colour scratch (item 3: the reflected colour is
	// fetched inside the mask now, at full float precision, never packed into the 8-bit hit uv the
	// old design bilinearly filtered) plus the material mask (MetalMask, below) to tell what kind of
	// surface this pixel is. resolve reads that same mask's rgb/a directly and declares no
	// DepthResolve uniform (item 12: bound and never read).
	// grass-glint brief item 2: the OpaqueDepth sampler (a pre-translucent depth copy used to guess
	// "this pixel is translucent" from a depth gap) is gone -- that gap disagreed with the frame
	// often enough on rori's modded instance to glint grass. water_mask.fsh now reads MetalMask's
	// g channel (a real water-surface mask, see MetalMaskScan and DepthPass.drawMetalMask) instead.
	// metal reflections (brief item 4) add a second colour target (reflection_kind) written
	// alongside reflection_mask by the same fragment shader -- the "second target, cleanest" option
	// the brief leans on, now that a bind group layout supports two ColorTargetStates directly.
	static final RenderPipeline WATER_MASK = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("bless", "pipeline/water_mask"))
		.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
		.withFragmentShader(Identifier.fromNamespaceAndPath("bless", "post/water_mask"))
		.withBindGroupLayout(BindGroupLayout.builder().withSampler("WorldDepth")
			.withUniform("DepthScene", UniformType.UNIFORM_BUFFER)
			.withSampler("ReflectionScratch").withSampler("MetalMask")
			// wetness (bless-wet brief item 3): the sky-exposure march reads the same voxel atlas
			// the light/gi/volumetric stages already bind.
			.withSampler("VoxelAtlas").build())
		.withCull(false).withDepthStencilState(Optional.empty())
		.withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
		.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
		.build());
	static final RenderPipeline WATER_RESOLVE = make("water_resolve", "water_resolve", true, false, true,
		new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE),
		"ReflectionKind", "ReflectionScratch");
	// light volume: full-res, no mask stage (same shape as HAZE) -- reads the cpu-filled colour
	// volume plus a pre-effect main colour scratch (DepthEffects.runLightResolve) as two extra
	// samplers, additive ONE/ONE like RAYS_RESOLVE (item 5: "blended ONE / ONE over main").
	static final RenderPipeline LIGHT_RESOLVE = make("light_resolve", "light_resolve", false, false, true,
		new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE), "LightVolume", "LightMain");
	// the material mask (brief item 3): real 3D geometry, not a screen quad -- its own vertex
	// shader and vertex format (position only, 12 bytes) rather than minecraft:core/screenquad, back
	// faces culled, no depth attachment (the fragment shader depth-tests by hand against WorldDepth,
	// see metal_mask.fsh). grass-glint brief item 1: one pipeline now draws two kinds of geometry --
	// metal cubes and water quads -- distinguished by the MaskKind uniform (0 metal, 1 water; see
	// DepthPass.drawMetalMask's two draw calls), which also picks the fragment's write channel
	// (r for metal, g for water) and its manual depth-test tolerance (0.05 metal, 0.15 water --
	// vanilla's water surface height varies with flow).
	static final RenderPipeline METAL_MASK = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("bless", "pipeline/metal_mask"))
		.withVertexShader(Identifier.fromNamespaceAndPath("bless", "core/metal_mask"))
		.withFragmentShader(Identifier.fromNamespaceAndPath("bless", "post/metal_mask"))
		.withBindGroupLayout(BindGroupLayout.builder().withSampler("WorldDepth")
			.withUniform("DepthScene", UniformType.UNIFORM_BUFFER)
			.withUniform("MaskKind", UniformType.UNIFORM_BUFFER).build())
		.withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION)
		.withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
		.withCull(true).withDepthStencilState(Optional.empty())
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)).build());

	// sun shadows (bless-shadow-pass brief item 2): real 3D block-mesh geometry (ShadowMesh, not a
	// screen quad) drawn depth-only into a persistent shadow map -- no colour attachment at all
	// (RenderPassDescriptor.withUnusedColorAttachment in DepthPass.drawShadowDepth), so this
	// pipeline still needs one ColorTargetState (activeColorTargetStateCount==0 falls back to
	// ColorTargetState.DEFAULT otherwise -- verified against RenderPipeline$Builder.build()'s
	// bytecode) but with WRITE_NONE so it is a declared no-op. depth write on, LESS_THAN (nearer to
	// the sun wins), the map cleared to 1.0 (far) on every redraw. brief item 2 asks for FRONT-face
	// culling to draw the far side of the mesh and reduce acne; this engine's RenderPipeline only
	// exposes a single enable/disable back-face-culling flag (no explicit front/back choice --
	// verified: RenderPipeline$Builder carries one `Optional<Boolean> cull` field, RenderPipeline
	// itself one `boolean cull`), so culling is left off here and acne control rides the resolve's
	// own depth bias (ShadowSettings.z) instead -- an adapted substitute, not the literal ask.
	static final RenderPipeline SHADOW_DEPTH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("bless", "pipeline/shadow_depth"))
		.withVertexShader(Identifier.fromNamespaceAndPath("bless", "core/shadow_depth"))
		.withFragmentShader(Identifier.fromNamespaceAndPath("bless", "post/shadow_depth"))
		.withBindGroupLayout(BindGroupLayout.builder().withUniform("DepthScene", UniformType.UNIFORM_BUFFER).build())
		.withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION)
		.withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
		.withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true))
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE)).build());
	// the resolve (brief item 4): full-res, reads WorldDepth (to rebuild the pixel's world position)
	// and the persistent shadow map as a second sampler. glass-light brief item 3: the blend changed
	// from the original scalar ZERO/ONE_MINUS_SRC_ALPHA (darken-only, src.rgb discarded by the ZERO
	// src factor) to ZERO/ONE_MINUS_SRC_COLOR so shadow_resolve.fsh can write a full rgb multiplier --
	// src.rgb = 1 - (1-shadowFactor)*tint reproduces the old scalar darkening exactly when tint is
	// white, and lets a lit pixel behind stained glass come out coloured instead of only ever darker.
	// flagged in the handback as the one deliberate departure from the brief's literal ask (it named no
	// blend change for this pipeline, only for the new tint-draw one below).
	static final RenderPipeline SHADOW_RESOLVE = make("shadow_resolve", "shadow_resolve", false, false, true,
		new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR, BlendFactor.ZERO, BlendFactor.ONE), "ShadowMap", "ShadowTint");

	// glass-light brief item 3: the position+colour vertex format the tint draw streams -- built by
	// hand (RGB32_FLOAT position, 12 bytes; RGBA8_UNORM colour, 4 bytes; stride 16) rather than trusting
	// DefaultVertexFormat.POSITION_COLOR's own byte layout/channel order sight unseen.
	static final com.mojang.blaze3d.vertex.VertexFormat SHADOW_TINT_VERTEX_FORMAT = com.mojang.blaze3d.vertex.VertexFormat.builder(0)
		.addAttribute(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_SEMANTIC_NAME, GpuFormat.RGB32_FLOAT)
		.addAttribute(com.mojang.blaze3d.vertex.DefaultVertexFormat.COLOR_SEMANTIC_NAME, GpuFormat.RGBA8_UNORM)
		.build();
	// glass-light brief item 3: real geometry again (ShadowMesh/METAL_MASK's own shape), drawn into the
	// persistent shadowTint texture with a MULTIPLICATIVE blend (src DST_COLOR, dst ZERO -- the brief's
	// own instruction) so overlapping panes tint twice, the way two sheets of coloured glass really do.
	// depth-tested LEQUAL against the shadow map's own depth texture, writes off, so glass sitting
	// behind an occluder in the mesh does not tint (a glass cube exactly AT an occluder's own depth --
	// tinted_glass, excluded from this draw already, or a solid block sharing a glass block's cell --
	// is the one case not proven here; flagged in the handback).
	static final RenderPipeline SHADOW_TINT = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("bless", "pipeline/shadow_tint"))
		.withVertexShader(Identifier.fromNamespaceAndPath("bless", "core/shadow_tint"))
		.withFragmentShader(Identifier.fromNamespaceAndPath("bless", "post/shadow_tint"))
		.withBindGroupLayout(BindGroupLayout.builder().withUniform("DepthScene", UniformType.UNIFORM_BUFFER).build())
		.withVertexBinding(0, SHADOW_TINT_VERTEX_FORMAT)
		.withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
		// repair 2026-09-21 item 3: cull was off, so both faces of each tint cube drew and multiplied
		// against DST_COLOR -- a red pane landed at its colour squared. the box windings are outward
		// CCW like the metal mask's, so backface cull is correct here too; one face per pixel.
		.withCull(true).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
		.withColorTargetState(new ColorTargetState(Optional.of(new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ONE)),
			GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR)).build());

	// the voxel path trace (vulkan-client): half-res rgba16f radiance out of gi_trace (no blend, fresh
	// target), a two-target temporal pass (radiance + this frame's camera distance for the next
	// reprojection), two a-trous steps sharing one shader (the step rides DepthResolve.x, the way the ao
	// blur's direction does), and a full-res compose that reads the pre-effect colour back from the
	// same scratch light_resolve uses and writes the whole colour without a blend.
	static final RenderPipeline GI_TRACE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("bless", "pipeline/gi_trace"))
		.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
		.withFragmentShader(Identifier.fromNamespaceAndPath("bless", "post/gi_trace"))
		.withBindGroupLayout(BindGroupLayout.builder().withSampler("WorldDepth")
			.withUniform("DepthScene", UniformType.UNIFORM_BUFFER)
			.withSampler("VoxelAtlas").withSampler("LightAtlas").withSampler("ShadowMap").withSampler("ShadowTint").build())
		.withCull(false).withDepthStencilState(Optional.empty())
		// radiance, and the pixel's view normal + depth the two blur passes stop against (one fetch a
		// tap instead of the five depth reads viewNormal() costs).
		.withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
		.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
		.build());
	static final RenderPipeline GI_ACCUMULATE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("bless", "pipeline/gi_accumulate"))
		.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
		.withFragmentShader(Identifier.fromNamespaceAndPath("bless", "post/gi_accumulate"))
		.withBindGroupLayout(BindGroupLayout.builder().withSampler("WorldDepth")
			.withUniform("DepthScene", UniformType.UNIFORM_BUFFER)
			.withSampler("GiCurrent").withSampler("GiHistory").withSampler("GiHistoryDepth").build())
		.withCull(false).withDepthStencilState(Optional.empty())
		.withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
		.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
		.build());
	static final RenderPipeline GI_BLUR = make("gi_blur", "gi_blur", true, true, false, null, GpuFormat.RGBA16_FLOAT, "GiGeometry");
	static final RenderPipeline GI_RESOLVE = make("gi_resolve", "gi_resolve", false, false, true, null, GpuFormat.RGBA8_UNORM,
		"GiSampler", "LightMain");
	// the air: half-res rgba16f in-scatter + transmittance out of the march, composited full-res with a
	// ONE / SRC_ALPHA blend (frame * transmittance + in-scatter in one draw), colour only.
	static final RenderPipeline VOLUME_MARCH = make("volume_march", "volume_march", false, false, false, null, GpuFormat.RGBA16_FLOAT,
		"LightAtlas", "ShadowMap", "ShadowTint");
	static final RenderPipeline VOLUME_RESOLVE = make("volume_resolve", "volume_resolve", true, true, true,
		new BlendFunction(BlendFactor.ONE, BlendFactor.SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE), GpuFormat.RGBA8_UNORM);

	static void register() { /* class initialization registers all fixed shader variants. */ }

	private static RenderPipeline make(String name, String fragment, boolean resolve, BlendFunction blend) {
		return make(name, fragment, resolve, resolve, blend);
	}

	private static RenderPipeline make(String name, String fragment, boolean bindResolveInputs, boolean writeColorOnly,
		BlendFunction blend, String... extraSamplers) {
		return make(name, fragment, bindResolveInputs, bindResolveInputs, writeColorOnly, blend, extraSamplers);
	}

	// bindMask and bindDepthResolve split apart so a pipeline can read the mask sampler without
	// declaring the DepthResolve uniform block (water_resolve, item 12) -- every other caller still
	// passes them locked together through the 5-arg overload above.
	private static RenderPipeline make(String name, String fragment, boolean bindMask, boolean bindDepthResolve,
		boolean writeColorOnly, BlendFunction blend, String... extraSamplers) {
		return make(name, fragment, bindMask, bindDepthResolve, writeColorOnly, blend, GpuFormat.RGBA8_UNORM, extraSamplers);
	}

	// the colour target's format is a parameter for the hdr intermediates (rgba16f) the bounce and the
	// air write; every earlier pipeline keeps rgba8 through the overload above.
	private static RenderPipeline make(String name, String fragment, boolean bindMask, boolean bindDepthResolve,
		boolean writeColorOnly, BlendFunction blend, GpuFormat format, String... extraSamplers) {
		var layout = BindGroupLayout.builder().withSampler("WorldDepth")
			.withUniform("DepthScene", UniformType.UNIFORM_BUFFER);
		if (bindMask) layout.withSampler("EffectMask");
		if (bindDepthResolve) layout.withUniform("DepthResolve", UniformType.UNIFORM_BUFFER);
		for (String extra : extraSamplers) layout.withSampler(extra);
		return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("bless", "pipeline/" + name))
			.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("bless", "post/" + fragment))
			.withBindGroupLayout(layout.build()).withCull(false).withDepthStencilState(Optional.empty())
			.withColorTargetState(new ColorTargetState(Optional.ofNullable(blend), format,
				writeColorOnly ? ColorTargetState.WRITE_COLOR : ColorTargetState.WRITE_ALL)).build());
	}
}
