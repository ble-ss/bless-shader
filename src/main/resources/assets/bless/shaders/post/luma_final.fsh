#version 330

// eye adaptation, stage 2 of 3: averages the 16x16 log-luminance downsample to a single
// texel. the encoding luma_reduce writes is affine, so averaging the encoded values first
// and decoding once in exposure_adapt is exactly the mean of log luminance -- the
// geometric mean of the frame's luminance.
uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float sum = 0.0;
    for (int y = 0; y < 16; y++) {
        for (int x = 0; x < 16; x++) {
            vec2 uv = (vec2(x, y) + 0.5) / 16.0;
            sum += texture(InSampler, uv).r;
        }
    }
    fragColor = vec4(sum / 256.0, 0.0, 0.0, 1.0);
}
