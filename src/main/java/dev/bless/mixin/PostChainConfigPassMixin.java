package dev.bless.mixin;

import dev.bless.RmlsClient;
import dev.bless.RmlsTuning;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * substitutes the F3+T tunable values into the passes bless owns.
 * ShaderManager builds a fresh CompilationCache on every resource reload and only calls
 * PostChainConfig.Pass.uniforms() when that cache lazily (re)compiles a chain -- so this
 * fires exactly once per pass per reload, including the reload F3+T triggers. reading the
 * config file here, at that moment, is what makes the knobs apply without a client restart.
 */
@Mixin(PostChainConfig.Pass.class)
abstract class PostChainConfigPassMixin {
	private static final Identifier GRADE = Identifier.fromNamespaceAndPath("bless", "post/grade");
	private static final Identifier BLOOM_GRADE = Identifier.fromNamespaceAndPath("bless", "post/bloom_grade");
	private static final Identifier BLOOM_EXTRACT = Identifier.fromNamespaceAndPath("bless", "post/bloom_extract");
	private static final Identifier SPILL_EXTRACT = Identifier.fromNamespaceAndPath("bless", "post/spill_extract");
	private static final Identifier SPILL_BLUR = Identifier.fromNamespaceAndPath("bless", "post/spill_blur");
	private static final Identifier EXPOSURE_ADAPT = Identifier.fromNamespaceAndPath("bless", "post/exposure_adapt");
	private static final Identifier FXAA = Identifier.fromNamespaceAndPath("bless", "post/fxaa");

	@Inject(method = "uniforms", at = @At("RETURN"), cancellable = true)
	private void blessTuneUniforms(CallbackInfoReturnable<Map<String, List<UniformValue>>> cir) {
		PostChainConfig.Pass self = (PostChainConfig.Pass) (Object) this;
		Identifier fragmentShader = self.fragmentShaderId();
		boolean isGrade = GRADE.equals(fragmentShader);
		boolean isBloomGrade = BLOOM_GRADE.equals(fragmentShader);
		boolean isBloomExtract = BLOOM_EXTRACT.equals(fragmentShader);
		boolean isSpillExtract = SPILL_EXTRACT.equals(fragmentShader);
		boolean isSpillBlur = SPILL_BLUR.equals(fragmentShader);
		boolean isExposureAdapt = EXPOSURE_ADAPT.equals(fragmentShader);
		boolean isFxaa = FXAA.equals(fragmentShader);
		if (!isGrade && !isBloomGrade && !isBloomExtract && !isSpillExtract && !isSpillBlur && !isExposureAdapt && !isFxaa) return;

		RmlsTuning tuning = RmlsClient.currentTuning();
		Map<String, List<UniformValue>> uniforms = new LinkedHashMap<>(cir.getReturnValue());
		if (isGrade || isBloomGrade) {
			uniforms.put("RmlsGradeTuning", List.of(new UniformValue.FloatUniform(tuning.gradeStrength())));
			// a baked 0.0 is the no-grain chain variant; leave it off instead of turning grain on.
			List<UniformValue> grain = uniforms.get("GrainConfig");
			if (grain != null && !grain.isEmpty() && grain.get(0) instanceof UniformValue.FloatUniform baked && baked.value() != 0.0f) {
				uniforms.put("GrainConfig", List.of(new UniformValue.FloatUniform(tuning.grainStrength())));
			}
		}
		if (isBloomGrade) {
			uniforms.put("BloomGradeConfig", List.of(new UniformValue.FloatUniform(tuning.bloomStrength())));
			uniforms.put("SpillGradeConfig", List.of(new UniformValue.FloatUniform(tuning.spillStrength())));
		}
		if (isBloomExtract) {
			uniforms.put("BloomExtractConfig", List.of(new UniformValue.FloatUniform(tuning.bloomThreshold())));
		}
		if (isSpillExtract) {
			uniforms.put("SpillExtractConfig", List.of(new UniformValue.FloatUniform(tuning.spillThreshold())));
		}
		if (isExposureAdapt) {
			// off means "pin the exposure multiplier to 1.0": clamping min/max to the same value
			// forces the wanted exposure to 1.0 regardless of measured luminance, so the pipeline
			// never changes shape between auto_exposure on and off -- only the numbers do.
			float min = tuning.autoExposure() ? tuning.exposureMin() : 1.0f;
			float max = tuning.autoExposure() ? tuning.exposureMax() : 1.0f;
			uniforms.put("ExposureConfig", List.of(new UniformValue.Vec4Uniform(
				new Vector4f(tuning.exposureTarget(), min, max, tuning.exposureSpeed()))));
		}
		if (isFxaa) {
			// the fxaa pass is always present in the chain (no separate on/off chain variant, unlike
			// grain) -- a negative FxaaSettings.x is the shader's own early-out to a plain copy.
			uniforms.put("FxaaConfig", List.of(new UniformValue.Vec4Uniform(
				new Vector4f(tuning.fxaa() ? tuning.fxaaStrength() : -1.0f, 0.0f, 0.0f, 0.0f))));
		}
		if (isSpillBlur) {
			// Direction is baked per pass (horizontal then vertical); only Radius is live-tunable.
			List<UniformValue> baked = uniforms.get("SpillBlurConfig");
			if (baked != null && baked.size() == 2) {
				uniforms.put("SpillBlurConfig", List.of(baked.get(0), new UniformValue.FloatUniform(tuning.spillRadius())));
			}
		}
		RmlsClient.recordAppliedTuning(tuning);
		cir.setReturnValue(uniforms);
	}
}
