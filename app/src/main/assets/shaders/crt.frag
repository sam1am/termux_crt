precision highp float;

varying vec2 vTexCoord;

uniform sampler2D uTexture;      // current terminal frame
uniform sampler2D uPrevFrame;    // previous CRT output, used for burn-in
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
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec2 curve(vec2 uv) {
    if (uCurvatureOn < 0.5) return uv;
    vec2 c = uv * 2.0 - 1.0;
    float r2 = dot(c, c);
    c *= 1.0 + r2 * (uCurvatureStrength * 0.22);
    return c * 0.5 + 0.5;
}

vec3 sampleTerminal(vec2 uv) {
    if (uRgbshiftOn < 0.5) {
        return texture2D(uTexture, uv).rgb;
    }
    float h = uRgbshiftStrength * 0.005;
    float v = uRgbshiftStrength * 0.0015;
    vec3 col;
    col.r = texture2D(uTexture, vec2(uv.x + h, uv.y - v)).r;
    col.g = texture2D(uTexture, uv).g;
    col.b = texture2D(uTexture, vec2(uv.x - h, uv.y + v)).b;
    return col;
}

vec3 sampleTerminalPlain(vec2 uv) { return texture2D(uTexture, uv).rgb; }

// 13-tap bloom: 3×3 inner + 4 "+" outer halo taps.
vec3 bloom(vec2 uv) {
    if (uBloomOn < 0.5) return vec3(0.0);
    vec2 texel = 1.0 / uTextureSize;
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 off = vec2(float(i), float(j)) * texel * 3.0;
            vec3 s = sampleTerminalPlain(clamp(uv + off, vec2(0.0), vec2(1.0)));
            float gate = smoothstep(0.30, 0.85, luma(s));
            float w = 1.0 - 0.2 * (abs(float(i)) + abs(float(j)));
            sum += s * gate * w;
            wsum += w;
        }
    }
    vec2 outer[4];
    outer[0] = vec2( 1.0,  0.0);
    outer[1] = vec2(-1.0,  0.0);
    outer[2] = vec2( 0.0,  1.0);
    outer[3] = vec2( 0.0, -1.0);
    for (int k = 0; k < 4; k++) {
        vec3 s = sampleTerminalPlain(clamp(uv + outer[k] * texel * 7.0, vec2(0.0), vec2(1.0)));
        float gate = smoothstep(0.30, 0.85, luma(s));
        float w = 0.5;
        sum += s * gate * w;
        wsum += w;
    }
    return (sum / wsum) * uBloomStrength * 1.8;
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
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // -------- Base color + bloom --------
    vec3 col = sampleTerminal(uv);
    col += bloom(uv);

    // -------- H-sync visible tint on slipped bands --------
    // Add a faint cool tint to slipped rows so glitches are perceptible
    // against a black background, not just where the text moves.
    if (uHsyncOn > 0.5) {
        col += vec3(0.04, 0.08, 0.12) * hsyncBand;
    }

    // -------- Burn-in (phosphor persistence) --------
    if (uBurninOn > 0.5) {
        vec3 prev = texture2D(uPrevFrame, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).rgb;
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

    gl_FragColor = vec4(col, 1.0);
}
