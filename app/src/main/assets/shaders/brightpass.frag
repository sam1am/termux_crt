#version 300 es
precision highp float;

// First pass of real bloom: read the terminal, keep only the pixels whose
// luma exceeds `uThreshold`, then write them to a downsampled FBO. The
// follow-up separable Gaussian passes blur that FBO into the final glow
// the main shader composites back over the terminal.

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform float uThreshold;   // luma below this is fully discarded
uniform float uSoftKnee;    // 0..1: width of the soft-knee transition

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec3 c = texture(uTexture, vTexCoord).rgb;
    float l = luma(c);
    // Soft knee around the threshold so anti-aliased text edges fade into the
    // glow rather than popping in. Standard COD-style brightpass.
    float knee = uThreshold * uSoftKnee + 1e-5;
    float soft = clamp(l - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee);
    float contrib = max(soft, l - uThreshold) / max(l, 1e-5);
    fragColor = vec4(c * contrib, 1.0);
}
