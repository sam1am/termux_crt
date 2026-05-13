package com.termux.crt

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Two-pass renderer:
 *   1. Sample the terminal bitmap + apply all CRT effects → FBO ping-pong target
 *   2. Blit that target to the default framebuffer
 *
 * The FBO step exists so the **burn-in** effect can read last frame's output as
 * an input (`uPrevFrame`). On each frame we swap which FBO is "current" vs
 * "previous", so we always have a one-frame history.
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
    private var uResolutionLoc = 0
    private var uTextureSizeLoc = 0
    private var uTimeLoc = 0

    private val effectUniforms = mutableMapOf<String, Int>()  // name -> location

    private var blitProgram = 0
    private var blitPositionLoc = 0
    private var blitTexCoordLoc = 0
    private var blitTextureLoc = 0

    private var terminalTexId = 0
    private var terminalTexInitialized = false
    private var textureWidth = 1
    private var textureHeight = 1

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // Ping-pong FBO setup for burn-in.
    private val fboIds = IntArray(2)
    private val fboTexIds = IntArray(2)
    private var fboInitialized = false
    private var currentFbo = 0  // which of the two is "this frame's" target

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
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        program = buildProgram(readAsset("shaders/crt.vert"), readAsset("shaders/crt.frag"))
        aPositionLoc    = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc    = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc     = GLES20.glGetUniformLocation(program, "uTexture")
        uPrevFrameLoc   = GLES20.glGetUniformLocation(program, "uPrevFrame")
        uResolutionLoc  = GLES20.glGetUniformLocation(program, "uResolution")
        uTextureSizeLoc = GLES20.glGetUniformLocation(program, "uTextureSize")
        uTimeLoc        = GLES20.glGetUniformLocation(program, "uTime")

        // Each effect has matched on/strength uniforms in the shader. Cache the
        // locations once so we don't re-look-them-up every frame.
        for (key in EFFECT_KEYS) {
            effectUniforms[onName(key)] = GLES20.glGetUniformLocation(program, onName(key))
            effectUniforms[strName(key)] = GLES20.glGetUniformLocation(program, strName(key))
        }

        // Simple passthrough program for the FBO → screen blit.
        blitProgram = buildProgram(readAsset("shaders/crt.vert"), BLIT_FRAG_SRC)
        blitPositionLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition")
        blitTexCoordLoc = GLES20.glGetAttribLocation(blitProgram, "aTexCoord")
        blitTextureLoc  = GLES20.glGetUniformLocation(blitProgram, "uTexture")

        terminalTexId = genTexture()
        terminalTexInitialized = false
        fboInitialized = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        source.onSurfaceSize(width, height)
        setupFbos(width, height)
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
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        // ----- Pass 1: render CRT into current FBO, reading previous as uPrevFrame -----
        val prevFbo = 1 - currentFbo
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[currentFbo])
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        bindQuadAttribs(aPositionLoc, aTexCoordLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, terminalTexId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[prevFbo])
        GLES20.glUniform1i(uPrevFrameLoc, 1)

        GLES20.glUniform2f(uResolutionLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES20.glUniform2f(uTextureSizeLoc, textureWidth.toFloat(), textureHeight.toFloat())
        GLES20.glUniform1f(uTimeLoc, (System.nanoTime() - startNanos) / 1_000_000_000f)
        pushEffects(settings)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // ----- Pass 2: blit current FBO to screen -----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(blitProgram)
        bindQuadAttribs(blitPositionLoc, blitTexCoordLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[currentFbo])
        GLES20.glUniform1i(blitTextureLoc, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        currentFbo = prevFbo  // swap for next frame
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

    private fun push(key: String, e: CrtSettings.Effect) {
        val onLoc = effectUniforms[onName(key)] ?: return
        val strLoc = effectUniforms[strName(key)] ?: return
        GLES20.glUniform1f(onLoc, if (e.enabled) 1f else 0f)
        GLES20.glUniform1f(strLoc, e.strength)
    }

    private fun bindQuadAttribs(positionLoc: Int, texCoordLoc: Int) {
        quadVertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)

        quadVertices.position(2)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
    }

    private fun uploadBitmap(bitmap: Bitmap) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, terminalTexId)
        if (!terminalTexInitialized || bitmap.width != textureWidth || bitmap.height != textureHeight) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureWidth = bitmap.width
            textureHeight = bitmap.height
            terminalTexInitialized = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    private fun setupFbos(w: Int, h: Int) {
        if (fboInitialized) {
            GLES20.glDeleteFramebuffers(2, fboIds, 0)
            GLES20.glDeleteTextures(2, fboTexIds, 0)
        }
        GLES20.glGenTextures(2, fboTexIds, 0)
        GLES20.glGenFramebuffers(2, fboIds, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTexIds[i], 0,
            )
        }
        // Clear both FBOs to opaque black so the first frame's burn-in read
        // doesn't pick up uninitialized texture memory (which manifested as a
        // squished ghost of a previous frame at a different resolution after
        // keyboard popup/dismiss resized the surface).
        for (i in 0..1) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboInitialized = true
        currentFbo = 0
    }

    private fun genTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vert)
        GLES20.glAttachShader(prog, frag)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log\n$src")
        }
        return shader
    }

    companion object {
        private val EFFECT_KEYS = listOf(
            "bloom", "burnin", "static", "jitter", "glowline",
            "curvature", "ambient", "flicker", "hsync", "rgbshift",
        )

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
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "    vec2 uv = vec2(vTexCoord.x, 1.0 - vTexCoord.y);\n" +
            "    gl_FragColor = vec4(texture2D(uTexture, uv).rgb, 1.0);\n" +
            "}\n"
    }
}
