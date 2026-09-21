#version 330

// eye adaptation, stage 3 of 3: reads the previous frame's exposure back out of a
// persistent 1x1 target and eases it toward the wanted exposure for this frame.
//
// dt is not an uploaded per-frame uniform (design called for one, "ExposureFrame"), because
// PostChainConfig.Pass.uniforms() -- the only door this mod's F3+T mixin has into a pass's
// uniform buffers -- is read exactly once per pass per shader-chain reload (see the
// ShaderManager CompilationCache doc comment on PostChainConfigPassMixin), never per frame.
// there is no declarative per-frame uniform in vanilla's post-chain json. instead this reads
// GameTime from minecraft:globals.glsl, which the engine already refreshes every frame for
// free: GameTime is (dayTime % 24000 + partialTick) / 24000, the fraction of the current
// in-game day. history.g carries the GameTime this pass last ran at, so dt is derived
// in-shader with no CPU round trip.
//
// that also means dt is real seconds only at the vanilla 20 ticks/sec, 24000-tick day
// default; a sped-up or frozen daylight cycle skews the adaptation rate, and a day
// rollover or a sleep-skipped night both look like an implausible dt -- both are treated
// as "invalid history" below, the same branch a first frame after reload takes.
#moj_import <minecraft:globals.glsl>

uniform sampler2D HistorySampler;
uniform sampler2D LumaSampler;

// (target, min, max, speed) -- see ClientConfig's exposure_target/min/max/speed. off bakes
// min == max == 1.0 so wanted always clamps to 1.0: same shader, same targets, same bind
// group, just neutralized.
layout(std140) uniform ExposureConfig {
    vec4 ExposureSettings;
};

const float RMLS_LOG_LO = -14.0;
const float RMLS_LOG_SPAN = 14.0;
const float RMLS_EXPOSURE_SPAN = 4.0;
// a day at the vanilla 20 ticks/sec, 24000-tick default.
const float RMLS_SECONDS_PER_DAY = 1200.0;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float target = ExposureSettings.x;
    float minExposure = ExposureSettings.y;
    float maxExposure = ExposureSettings.z;
    float speed = ExposureSettings.w;

    vec2 history = texture(HistorySampler, texCoord).rg;
    float storedExposure = history.r * RMLS_EXPOSURE_SPAN;
    float storedGameTime = history.g;
    // clear_color leaves a fresh persistent target at (0,0): storedExposure is then exactly
    // 0.0, below any real exposure (exposure_min's own floor is 0.25), so it doubles as the
    // "never written yet" flag with no separate channel needed.
    bool historyValid = storedExposure > 0.0;

    float dt = GameTime - storedGameTime;
    // a day rollover wraps GameTime back through 0 (dt goes sharply negative); sleeping
    // through a night or a /time set can jump it forward by a large fraction in one frame.
    // either reads as an implausible dt and falls back to a straight jump this frame.
    bool dtPlausible = dt >= 0.0 && dt < 0.5;

    float meanLog = texture(LumaSampler, texCoord).r * RMLS_LOG_SPAN + RMLS_LOG_LO;
    float wanted = clamp(target / exp2(meanLog), minExposure, maxExposure);

    float newExposure;
    if (historyValid && dtPlausible) {
        float dtSeconds = dt * RMLS_SECONDS_PER_DAY;
        // a frozen day clock (the bench's tick freeze, a world with doDaylightCycle off) leaves
        // GameTime exactly where it was, so dt reads 0 and the exposure would sit forever on
        // whatever the first frame after a reload happened to see. assume one 60 hz frame instead:
        // the glide then still lands on the target, just paced by frames rather than the clock.
        if (dtSeconds <= 0.0) dtSeconds = 1.0 / 60.0;
        float blend = 1.0 - exp(-dtSeconds * speed);
        newExposure = mix(storedExposure, wanted, blend);
    } else {
        newExposure = wanted;
    }

    fragColor = vec4(clamp(newExposure, 0.0, RMLS_EXPOSURE_SPAN) / RMLS_EXPOSURE_SPAN, GameTime, 0.0, 1.0);
}
