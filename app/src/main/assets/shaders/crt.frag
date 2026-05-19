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
uniform float uGlowlineOn;
uniform float uGlowlineStrength;
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

// Color overrides. Each `*On` is 0/1; the color is plain linear RGB.
// uBgColor   — fills the screen where the terminal has no content (luma≈0).
// uTextColor — re-tints terminal output by luminance, giving a single-phosphor
//              look (white text → chosen color; ANSI colors collapsed to mono).
uniform float uBgColorOn;
uniform vec3  uBgColor;
uniform float uTextColorOn;
uniform vec3  uTextColor;

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
        float bandFast = step(0.90, sin(uv.y * 25.0 + uTime * 3.0)   * 0.5 + 0.5);
        float bandSlow = step(0.88, sin(uv.y *  4.5 + uTime * 0.85)  * 0.5 + 0.5);
        float slip = hash(vec2(floor(uv.y * 700.0), floor(uTime * 12.0))) - 0.5;
        uv.x += (bandFast * 0.06 + bandSlow * 0.035) * slip * uHsyncStrength;
        hsyncBand = (bandFast + bandSlow * 0.7) * uHsyncStrength * 0.5;
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

    // -------- Text color override (monochrome phosphor) --------
    // Preserve luminance, swap hue. Done before bloom so the additive halo
    // glows in the same phosphor color.
    if (uTextColorOn > 0.5) {
        float luma = dot(col, vec3(0.299, 0.587, 0.114));
        col = uTextColor * luma;
    }

    // -------- Background color override --------
    // Show the chosen background where the terminal is dark; let bright text
    // stay as-is. smoothstep gives a soft edge so anti-aliased glyphs don't
    // get a hard halo of background color.
    if (uBgColorOn > 0.5) {
        float luma = dot(col, vec3(0.299, 0.587, 0.114));
        col = mix(uBgColor, col, smoothstep(0.0, 0.25, luma));
    }

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

    // -------- Glow line (rolling refresh bar) --------
    // Bounded-height band (~20% screen) so the effect terminates cleanly
    // instead of fading across the whole frame as a contrast-killing
    // gradient. Band intensity ramps from 0 at the top up to a peak at the
    // leading edge, then drops sharply below. The band itself has no hue;
    // it just pulls its pixels toward middle gray, so it reads as a soft
    // contrast dip rolling down the screen.
    if (uGlowlineOn > 0.5) {
        float lineY = fract(uTime * 0.10);
        float bandHeight = 0.20;
        float trail = smoothstep(lineY - bandHeight, lineY, uv.y);   // 0 at top of band → 1 at lead
        float lead  = 1.0 - smoothstep(lineY, lineY + 0.008, uv.y);  // sharp leading edge
        float beam  = trail * lead;
        float strength = sqrt(uGlowlineStrength);                    // perceptual taper
        // Pure contrast decrease — pull band pixels toward middle gray.
        // No hue of its own: bright pixels dim, dark pixels lift slightly,
        // colored content desaturates a touch. Very subtle by design.
        col = mix(col, vec3(0.5), beam * strength * 0.03);
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
    // Center-focused glow — simulates a phosphor screen brighter in the
    // middle where the electron beam dwells. Uses the chosen background
    // color (if the override is on) as the tint, so a green CRT bg gets a
    // green ambient lift and an amber bg gets an amber one. Falls back to
    // a warm neutral when no bg override is set.
    if (uAmbientOn > 0.5) {
        vec2 ac = uv - 0.5;
        // Squared radial distance from center; gaussian-ish falloff peaks at
        // center (=1) and decays toward the edges (~0.05 at the corners).
        float r2 = dot(ac, ac);
        float halo = exp(-r2 * 6.0);
        vec3 tint = (uBgColorOn > 0.5)
            ? max(uBgColor, vec3(0.03))   // lift pitch-black bg to a faint glow
            : vec3(0.22, 0.17, 0.10);     // warm neutral default
        col += tint * halo * uAmbientStrength * 0.9;
    }

    // -------- Curvature-tied vignette --------
    if (uCurvatureOn > 0.5) {
        vec2 vig = uv * (1.0 - uv);
        float v = clamp(vig.x * vig.y * 18.0, 0.0, 1.0);
        col *= mix(mix(1.0, 0.55, uCurvatureStrength), 1.0, pow(v, 0.4));
    }

    fragColor = vec4(col, 1.0);
}
