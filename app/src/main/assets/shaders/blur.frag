#version 300 es
precision highp float;

// One axis of a separable Gaussian blur. The renderer dispatches this twice
// per blur iteration with `uTexelDir` set to (1/w, 0) or (0, 1/h). Uses the
// standard 5-sample bilinear-tap trick to approximate a 13-tap Gaussian
// (sigma ~ 3) in 5 texture reads — fragment-shader-bound on mobile GPUs is
// real, so this matters even at quarter resolution.

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec2 uTexelDir;

void main() {
    vec3 sum = texture(uTexture, vTexCoord).rgb * 0.227027;
    sum += texture(uTexture, vTexCoord + uTexelDir * 1.3846153846).rgb * 0.3162162162;
    sum += texture(uTexture, vTexCoord - uTexelDir * 1.3846153846).rgb * 0.3162162162;
    sum += texture(uTexture, vTexCoord + uTexelDir * 3.2307692308).rgb * 0.0702702703;
    sum += texture(uTexture, vTexCoord - uTexelDir * 3.2307692308).rgb * 0.0702702703;
    fragColor = vec4(sum, 1.0);
}
