package com.termux.crt

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Multi-pass renderer on GLES 3.0.
 *
 *   Pass 0 (only if bloom is enabled): brightpass the terminal into a
 *           quarter-resolution FBO.
 *   Pass 1 (only if bloom is enabled): horizontal separable Gaussian blur
 *           of the brightpass FBO into a second quarter-res FBO.
 *   Pass 2 (only if bloom is enabled): vertical separable Gaussian blur
 *           back into the first quarter-res FBO. Two H/V iterations are
 *           run to widen the kernel — still cheap at 1/4 res, gives a
 *           soft, wide halo instead of the old chunky 13-tap.
 *   Pass 3: render CRT effects into the current full-res FBO. Reads the
 *           bloom result, the terminal texture, and the *previous* full-
 *           res FBO (so the burn-in / phosphor-persistence effect can
 *           feed back from frame N-1).
 *   Pass 4: blit the current full-res FBO to the default framebuffer.
 *
 * The two full-res FBOs ping-pong every frame so burn-in always reads the
 * previous frame's output.
 */
class CrtRenderer(
    private val context: Context,
    private val source: TerminalSurface,
) : GLSurfaceView.Renderer {

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uPrevFrameLoc = 0
    private var uBloomLoc = 0
    private var uResolutionLoc = 0
    private var uTextureSizeLoc = 0
    private var uTimeLoc = 0
    private var uBgColorOnLoc = 0
    private var uBgColorLoc = 0
    private var uTextColorOnLoc = 0
    private var uTextColorLoc = 0
    private var uTextColorMixLoc = 0

    private val effectUniforms = mutableMapOf<String, Int>()  // name -> location

    private var blitProgram = 0
    private var blitPositionLoc = 0
    private var blitTexCoordLoc = 0
    private var blitTextureLoc = 0

    private var brightProgram = 0
    private var brightPositionLoc = 0
    private var brightTexCoordLoc = 0
    private var brightTextureLoc = 0
    private var brightThresholdLoc = 0
    private var brightSoftKneeLoc = 0

    private var blurProgram = 0
    private var blurPositionLoc = 0
    private var blurTexCoordLoc = 0
    private var blurTextureLoc = 0
    private var blurTexelDirLoc = 0

    private var terminalTexId = 0
    private var terminalTexInitialized = false
    private var textureWidth = 1
    private var textureHeight = 1

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // Full-res ping-pong FBOs for burn-in feedback.
    private val fboIds = IntArray(2)
    private val fboTexIds = IntArray(2)
    private var fboInitialized = false
    private var currentFbo = 0  // which of the two is "this frame's" target

    // Quarter-res ping-pong FBOs for the bloom pipeline (brightpass + blur).
    private val bloomFboIds = IntArray(2)
    private val bloomTexIds = IntArray(2)
    private var bloomFboInitialized = false
    private var bloomWidth = 1
    private var bloomHeight = 1

    @Volatile private var settings: CrtSettings = CrtSettings.DEFAULT

    private val startNanos = System.nanoTime()

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD).position(0) }

    fun setSettings(s: CrtSettings) {
        settings = s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        program = buildProgram(readAsset("shaders/crt.vert"), readAsset("shaders/crt.frag"))
        aPositionLoc    = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc    = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc     = GLES30.glGetUniformLocation(program, "uTexture")
        uPrevFrameLoc   = GLES30.glGetUniformLocation(program, "uPrevFrame")
        uBloomLoc       = GLES30.glGetUniformLocation(program, "uBloom")
        uResolutionLoc  = GLES30.glGetUniformLocation(program, "uResolution")
        uTextureSizeLoc = GLES30.glGetUniformLocation(program, "uTextureSize")
        uTimeLoc        = GLES30.glGetUniformLocation(program, "uTime")
        uBgColorOnLoc   = GLES30.glGetUniformLocation(program, "uBgColorOn")
        uBgColorLoc     = GLES30.glGetUniformLocation(program, "uBgColor")
        uTextColorOnLoc  = GLES30.glGetUniformLocation(program, "uTextColorOn")
        uTextColorLoc    = GLES30.glGetUniformLocation(program, "uTextColor")
        uTextColorMixLoc = GLES30.glGetUniformLocation(program, "uTextColorMix")

        // Each effect has matched on/strength uniforms in the shader. Cache the
        // locations once so we don't re-look-them-up every frame.
        for (key in EFFECT_KEYS) {
            effectUniforms[onName(key)] = GLES30.glGetUniformLocation(program, onName(key))
            effectUniforms[strName(key)] = GLES30.glGetUniformLocation(program, strName(key))
        }

        // Simple passthrough program for the FBO → screen blit.
        blitProgram = buildProgram(readAsset("shaders/crt.vert"), BLIT_FRAG_SRC)
        blitPositionLoc = GLES30.glGetAttribLocation(blitProgram, "aPosition")
        blitTexCoordLoc = GLES30.glGetAttribLocation(blitProgram, "aTexCoord")
        blitTextureLoc  = GLES30.glGetUniformLocation(blitProgram, "uTexture")

        // Bloom pre-passes: brightpass + separable Gaussian blur.
        brightProgram = buildProgram(readAsset("shaders/crt.vert"), readAsset("shaders/brightpass.frag"))
        brightPositionLoc  = GLES30.glGetAttribLocation(brightProgram, "aPosition")
        brightTexCoordLoc  = GLES30.glGetAttribLocation(brightProgram, "aTexCoord")
        brightTextureLoc   = GLES30.glGetUniformLocation(brightProgram, "uTexture")
        brightThresholdLoc = GLES30.glGetUniformLocation(brightProgram, "uThreshold")
        brightSoftKneeLoc  = GLES30.glGetUniformLocation(brightProgram, "uSoftKnee")

        blurProgram = buildProgram(readAsset("shaders/crt.vert"), readAsset("shaders/blur.frag"))
        blurPositionLoc = GLES30.glGetAttribLocation(blurProgram, "aPosition")
        blurTexCoordLoc = GLES30.glGetAttribLocation(blurProgram, "aTexCoord")
        blurTextureLoc  = GLES30.glGetUniformLocation(blurProgram, "uTexture")
        blurTexelDirLoc = GLES30.glGetUniformLocation(blurProgram, "uTexelDir")

        terminalTexId = genTexture()
        terminalTexInitialized = false
        fboInitialized = false
        bloomFboInitialized = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
        source.onSurfaceSize(width, height)
        setupFbos(width, height)
        // Quarter-res keeps the blur cheap and naturally widens the kernel
        // when sampled back at full resolution. Clamp to at least 1 to avoid
        // a zero-sized FBO on weird surface sizes.
        setupBloomFbos(maxOf(width / 4, 1), maxOf(height / 4, 1))
    }

    override fun onDrawFrame(gl: GL10?) {
        // Pull a frame from the terminal mirror and update our texture.
        val bitmap = source.acquireBitmap()
        if (bitmap != null) {
            try {
                uploadBitmap(bitmap)
            } finally {
                source.releaseBitmap(bitmap)
            }
        }
        if (!terminalTexInitialized) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        // ----- Bloom pre-passes: brightpass → H blur → V blur (×2 iterations) -----
        // Skipped when the user has bloom disabled — the main shader multiplies
        // by uBloomOn, so even if bloomTex[0] still holds stale content from a
        // previous "on" frame, the contribution is zeroed out.
        val s = settings
        if (s.bloom.enabled) renderBloom()

        // ----- Main pass: CRT effects into current full-res FBO -----
        val prevFbo = 1 - currentFbo
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[currentFbo])
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(program)
        bindQuadAttribs(aPositionLoc, aTexCoordLoc)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, terminalTexId)
        GLES30.glUniform1i(uTextureLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexIds[prevFbo])
        GLES30.glUniform1i(uPrevFrameLoc, 1)

        // Bloom result lives in bloomTexIds[0] after renderBloom() (V-blur
        // writes there on the second iteration). See renderBloom() for the
        // ping-pong details.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexIds[0])
        GLES30.glUniform1i(uBloomLoc, 2)

        GLES30.glUniform2f(uResolutionLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES30.glUniform2f(uTextureSizeLoc, textureWidth.toFloat(), textureHeight.toFloat())
        GLES30.glUniform1f(uTimeLoc, (System.nanoTime() - startNanos) / 1_000_000_000f)
        pushColorOverrides(s)
        pushEffects(s)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // ----- Blit current FBO to screen -----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(blitProgram)
        bindQuadAttribs(blitPositionLoc, blitTexCoordLoc)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexIds[currentFbo])
        GLES30.glUniform1i(blitTextureLoc, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        currentFbo = prevFbo  // swap for next frame
    }

    private fun renderBloom() {
        GLES30.glViewport(0, 0, bloomWidth, bloomHeight)

        // Brightpass: terminal → bloomTex[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboIds[0])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(brightProgram)
        bindQuadAttribs(brightPositionLoc, brightTexCoordLoc)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, terminalTexId)
        GLES30.glUniform1i(brightTextureLoc, 0)
        GLES30.glUniform1f(brightThresholdLoc, BLOOM_THRESHOLD)
        GLES30.glUniform1f(brightSoftKneeLoc, BLOOM_SOFT_KNEE)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // Two separable-Gaussian iterations to widen the halo. Each iteration
        // is one horizontal + one vertical pass, ping-ponging between the
        // two bloom FBOs. After iter 1: result lives in bloomTex[0]. After
        // iter 2: result lives in bloomTex[0] again. The main pass reads
        // bloomTex[0].
        val texelX = 1f / bloomWidth
        val texelY = 1f / bloomHeight
        for (i in 0 until BLOOM_ITERATIONS) {
            blur(srcIdx = 0, dstIdx = 1, dirX = texelX, dirY = 0f)
            blur(srcIdx = 1, dstIdx = 0, dirX = 0f,     dirY = texelY)
        }
    }

    private fun blur(srcIdx: Int, dstIdx: Int, dirX: Float, dirY: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboIds[dstIdx])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(blurProgram)
        bindQuadAttribs(blurPositionLoc, blurTexCoordLoc)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexIds[srcIdx])
        GLES30.glUniform1i(blurTextureLoc, 0)
        GLES30.glUniform2f(blurTexelDirLoc, dirX, dirY)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun pushEffects(s: CrtSettings) {
        push("bloom",     s.bloom)
        push("burnin",    s.burnin)
        push("static",    s.staticNoise)
        push("jitter",    s.jitter)
        push("glowline",  s.glowLine)
        push("curvature", s.curvature)
        push("ambient",   s.ambient)
        push("flicker",   s.flicker)
        push("hsync",     s.hsync)
        push("rgbshift",  s.rgbShift)
    }

    private fun pushColorOverrides(s: CrtSettings) {
        GLES30.glUniform1f(uBgColorOnLoc, if (s.bgColorOverride) 1f else 0f)
        rgbFromColor(s.bgColor, rgb)
        GLES30.glUniform3f(uBgColorLoc, rgb[0], rgb[1], rgb[2])
        GLES30.glUniform1f(uTextColorOnLoc, if (s.textColorOverride) 1f else 0f)
        rgbFromColor(s.textColor, rgb)
        GLES30.glUniform3f(uTextColorLoc, rgb[0], rgb[1], rgb[2])
        GLES30.glUniform1f(uTextColorMixLoc, s.textColorMix)
    }

    private val rgb = FloatArray(3)

    private fun rgbFromColor(argb: Int, out: FloatArray) {
        out[0] = ((argb shr 16) and 0xFF) / 255f
        out[1] = ((argb shr 8) and 0xFF) / 255f
        out[2] = (argb and 0xFF) / 255f
    }

    private fun push(key: String, e: CrtSettings.Effect) {
        val onLoc = effectUniforms[onName(key)] ?: return
        val strLoc = effectUniforms[strName(key)] ?: return
        GLES30.glUniform1f(onLoc, if (e.enabled) 1f else 0f)
        GLES30.glUniform1f(strLoc, e.strength)
    }

    private fun bindQuadAttribs(positionLoc: Int, texCoordLoc: Int) {
        quadVertices.position(0)
        GLES30.glEnableVertexAttribArray(positionLoc)
        GLES30.glVertexAttribPointer(positionLoc, 2, GLES30.GL_FLOAT, false, STRIDE, quadVertices)

        quadVertices.position(2)
        GLES30.glEnableVertexAttribArray(texCoordLoc)
        GLES30.glVertexAttribPointer(texCoordLoc, 2, GLES30.GL_FLOAT, false, STRIDE, quadVertices)
    }

    private fun uploadBitmap(bitmap: Bitmap) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, terminalTexId)
        if (!terminalTexInitialized || bitmap.width != textureWidth || bitmap.height != textureHeight) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            textureWidth = bitmap.width
            textureHeight = bitmap.height
            terminalTexInitialized = true
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    private fun setupFbos(w: Int, h: Int) {
        if (fboInitialized) {
            GLES30.glDeleteFramebuffers(2, fboIds, 0)
            GLES30.glDeleteTextures(2, fboTexIds, 0)
        }
        GLES30.glGenTextures(2, fboTexIds, 0)
        GLES30.glGenFramebuffers(2, fboIds, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexIds[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, fboTexIds[i], 0,
            )
        }
        // Clear both FBOs to opaque black so the first frame's burn-in read
        // doesn't pick up uninitialized texture memory (which manifested as a
        // squished ghost of a previous frame at a different resolution after
        // keyboard popup/dismiss resized the surface).
        for (i in 0..1) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[i])
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        fboInitialized = true
        currentFbo = 0
    }

    private fun setupBloomFbos(w: Int, h: Int) {
        if (bloomFboInitialized) {
            GLES30.glDeleteFramebuffers(2, bloomFboIds, 0)
            GLES30.glDeleteTextures(2, bloomTexIds, 0)
        }
        bloomWidth = w
        bloomHeight = h
        GLES30.glGenTextures(2, bloomTexIds, 0)
        GLES30.glGenFramebuffers(2, bloomFboIds, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexIds[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboIds[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, bloomTexIds[i], 0,
            )
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        bloomFboInitialized = true
    }

    private fun genTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vert)
        GLES30.glAttachShader(prog, frag)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw RuntimeException("Program link failed: $log")
        }
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log\n$src")
        }
        return shader
    }

    companion object {
        private val EFFECT_KEYS = listOf(
            "bloom", "burnin", "static", "jitter", "glowline",
            "curvature", "ambient", "flicker", "hsync", "rgbshift",
        )

        // Brightpass cuts off below this luma. Terminal text is usually
        // bright on a dark background, so even a fairly aggressive threshold
        // catches glyphs while letting the background sit at zero.
        private const val BLOOM_THRESHOLD = 0.30f
        private const val BLOOM_SOFT_KNEE = 0.5f
        private const val BLOOM_ITERATIONS = 2

        private fun onName(key: String) = "u" + cap(key) + "On"
        private fun strName(key: String) = "u" + cap(key) + "Strength"
        private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        )
        private const val STRIDE = 4 * 4

        // The FBO is a regular GL texture whose framebuffer origin is bottom-
        // left, so what we rendered at the top of pass-1's viewport ends up at
        // the top of the texture (high `t`). Our QUAD UVs map screen-top to
        // t=0, so we have to flip t when reading the FBO back.
        private const val BLIT_FRAG_SRC =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "out vec4 fragColor;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "    vec2 uv = vec2(vTexCoord.x, 1.0 - vTexCoord.y);\n" +
            "    fragColor = vec4(texture(uTexture, uv).rgb, 1.0);\n" +
            "}\n"
    }
}
