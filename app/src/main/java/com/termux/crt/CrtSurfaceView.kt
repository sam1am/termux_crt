package com.termux.crt

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent

class CrtSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private lateinit var renderer: CrtRenderer

    init {
        // Composite ON TOP of the activity window. The default "behind window"
        // mode only shows the GL surface where the window has a transparent
        // hole; Termux's Material theme is fully opaque, so behind-window mode
        // would render nothing visible. On-top mode lets the shader draw over
        // the TerminalView (which we set to alpha=0 when CRT is enabled, so
        // there's nothing to overdraw).
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
    }

    fun attach(source: TerminalSurface) {
        setEGLContextClientVersion(3)
        renderer = CrtRenderer(context.applicationContext, source)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun applySettings(settings: CrtSettings) {
        if (::renderer.isInitialized) renderer.setSettings(settings)
    }

    /**
     * Pass touches through to the TerminalView underneath — this surface is
     * a visual decorator only. Without this override the GL surface would
     * swallow taps, breaking soft-keyboard activation and input.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
