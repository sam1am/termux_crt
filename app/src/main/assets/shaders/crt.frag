#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;      // current terminal frame
uniform sampler2D uPrevFrame;    // previous CRT output, used for burn-in
uniform sampler2D uBloom;        // pre-computed bloom texture (downsampled)
uniform vec2 uResolution;        // output surface in pixels
uniform vec2 uTextureSize;       // source bitmap in pixels
uniform float uTime;             // seconds since renderer start

// Cool-retro-term style toggles + intensities. Each `*_Strength` is 0..1.
uniform float uBloomOn;
uniform float uBloomStrength;
uniform float uBurninOn;
uniform float uBurninStrength;
uniform float uStaticOn;
uniform float uStaticStrength;
uniform float uJitterOn;
uniform float uJitterStrength;
uniform float uGlowLineOn;
uniform float uGlowLineStrength;
uniform float uCurvatureOn;
uniform float uCurvatureStrength;
uniform float uAmbientOn;
uniform float uAmbientStrength;
uniform float uFlickerOn;
uniform float uFlickerStrength;
uniform float uHsyncOn;
uniform float uHsyncStrength;
uniform float uRgbshiftOn;
uniform float uRgbshiftStrength;

// ---------- Helpers ----------

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float hash11(float n) { return fract(sin(n * 12.9898) * 43758.5453); }

vec2 curve(vec2 uv) {
    if (uCurvatureOn < 0.5) return uv;
    vec2 c = uv * 2.0 - 1.0;
    float r2 = dot(c, c);
    c *= 1.0 + r2 * (uCurvatureStrength * 0.22);
    return c * 0.5 + 0.5;
}

vec3 sampleTerminal(vec2 uv) {
    if (uRgbshiftOn < 0.5) {
        return texture(uTexture, uv).rgb;
    }
    float h = uRgbshiftStrength * 0.005;
    float v = uRgbshiftStrength * 0.0015;
    vec3 col;
    col.r = texture(uTexture, vec2(uv.x + h, uv.y - v)).r;
    col.g = texture(uTexture, uv).g;
    col.b = texture(uTexture, vec2(uv.x - h, uv.y + v)).b;
    return col;
}

// FBO textures have bottom-left origin, so we render-to-FBO with our QUAD's
// uv convention (uv.y = 0 at screen top) and then flip y when reading them
// back as input textures. Same trick as the burn-in `uPrevFrame` read below.
vec3 sampleBloom(vec2 uv) {
    return texture(uBloom, vec2(uv.x, 1.0 - uv.y)).rgb;
}

// ---------- Main ----------

void main() {
    vec2 uv = vTexCoord;

    // -------- Horizontal sync slip --------
    // Faster than before: two band selectors (one fast and frequent, one
    // wider/slower) plus a tracked-row tint so the band is visible even on
    // empty (black) background — not just where there's text to displace.
    float hsyncBand = 0.0;
    if (uHsyncOn > 0.5) {
        float bandFast = step(0.90, sin(uv.y * 51.0 + uTime * 6.0)  * 0.5 + 0.5);
        float bandSlow = step(0.88, sin(uv.y *  9.0 + uTime * 1.7)  * 0.5 + 0.5);
        float slip = hash(vec2(floor(uv.y * 700.0), floor(uTime * 12.0))) - 0.5;
        uv.x += (bandFast * 0.18 + bandSlow * 0.10) * slip * uHsyncStrength;
        hsyncBand = (bandFast + bandSlow * 0.7) * uHsyncStrength;
    }

    // -------- Jitter (per-scanline horizontal wobble) --------
    if (uJitterOn > 0.5) {
        float row = floor(uv.y * uResolution.y);
        float bucket = floor(uTime * 30.0);
        float dx = (hash(vec2(row, bucket)) - 0.5) * 2.0;
        uv.x += dx * uJitterStrength * 0.0025;
    }

    // -------- Screen curvature --------
    uv = curve(uv);

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // -------- Base color + bloom --------
    // Bloom is a real separable-Gaussian blur over a brightpass of the
    // terminal, computed in earlier passes into uBloom. We just sample +
    // additively blend; `1.8` matches the visual weight of the old inline
    // 13-tap version at default settings.
    vec3 col = sampleTerminal(uv);
    col += sampleBloom(uv) * uBloomStrength * uBloomOn * 1.8;

    // -------- H-sync visible tint on slipped bands --------
    // Add a faint cool tint to slipped rows so glitches are perceptible
    // against a black background, not just where the text moves.
    if (uHsyncOn > 0.5) {
        col += vec3(0.04, 0.08, 0.12) * hsyncBand;
    }

    // -------- Burn-in (phosphor persistence) --------
    if (uBurninOn > 0.5) {
        vec3 prev = texture(uPrevFrame, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).rgb;
        float decay = mix(0.85, 0.995, uBurninStrength);
        col = max(col, prev * decay);
    }

    // -------- Glow line (scrolling refresh beam) --------
    // Slow scroll. Wider beam, brighter additive halo so it's unmistakable
    // even on a black background.
    if (uGlowLineOn > 0.5) {
        float lineY = fract(uTime * 0.20);
        float d = abs(uv.y - lineY);
        float beam = exp(-d * 50.0);                              // softer Gaussian
        col *= 1.0 + beam * 0.6 * uGlowLineStrength;              // local amplify under beam
        col += vec3(0.55, 0.7, 0.55) * beam * 0.45 * uGlowLineStrength;  // visible additive glow
    }

    // -------- Flicker --------
    // Up to 30% dimming at max strength.
    if (uFlickerOn > 0.5) {
        float fast = (sin(uTime * 70.0) * 0.5 + 0.5);
        float slow = hash11(floor(uTime * 4.0));
        float f = mix(fast, slow, 0.35);
        col *= 1.0 - f * uFlickerStrength * 0.30;
    }

    // -------- Static noise --------
    // Positive-only flecks so they're visible on a black background. Stretching
    // the noise UV horizontally gives a faint vertical streak.
    if (uStaticOn > 0.5) {
        vec2 np = uv * uResolution;
        np.x *= 0.5;
        float n = hash(np + uTime * 73.0);
        float fleck = smoothstep(0.92, 1.0, n);    // sparse bright pixels
        col += vec3(fleck) * uStaticStrength * 0.6;
    }

    // -------- Ambient light --------
    // Lift the black level — visible "screen is in a lit room" look. Stronger
    // than before; at max strength the off-pixel is a dim warm grey.
    if (uAmbientOn > 0.5) {
        col += vec3(0.16, 0.13, 0.09) * uAmbientStrength;
    }

    // -------- Curvature-tied vignette --------
    if (uCurvatureOn > 0.5) {
        vec2 vig = uv * (1.0 - uv);
        float v = clamp(vig.x * vig.y * 18.0, 0.0, 1.0);
        col *= mix(mix(1.0, 0.55, uCurvatureStrength), 1.0, pow(v, 0.4));
    }

    fragColor = vec4(col, 1.0);
}
