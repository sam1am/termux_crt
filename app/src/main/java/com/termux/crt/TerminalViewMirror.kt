package com.termux.crt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import com.termux.view.TerminalView
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.math.min

/**
 * Captures every draw of an externally-owned [TerminalView] into an offscreen
 * bitmap that the GL renderer samples each frame.
 *
 * We don't own the TerminalView (Termux's `TermuxActivity` does, and it's
 * `final` so we can't subclass), so we attach via a [ViewTreeObserver.OnPreDrawListener]
 * and re-draw the view onto our private canvas before each frame is committed.
 * The extra draw is paid for in exchange for not having to touch Termux's view
 * stack at all.
 *
 * Threading: the pre-draw hook fires on the UI thread; the GL thread pulls
 * frames via [acquireBitmap]/[releaseBitmap]. A [ReentrantLock] keeps them
 * from stepping on each other.
 */
class TerminalViewMirror(
    private val terminalView: TerminalView,
) : TerminalSurface {

    private val captureLock = ReentrantLock()
    private var captureBitmap: Bitmap? = null
    private var captureCanvas: Canvas? = null

    @Volatile private var hasNewFrame = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        captureNow()
        true
    }

    fun attach() {
        terminalView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    fun detach() {
        terminalView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    }

    override fun onSurfaceSize(width: Int, height: Int) {
        val (capW, capH) = pickCaptureSize(width, height)
        uiHandler.post {
            resizeCapture(capW, capH)
            terminalView.invalidate()
        }
    }

    override fun acquireBitmap(): Bitmap? {
        captureLock.lock()
        if (!hasNewFrame) {
            captureLock.unlock()
            return null
        }
        hasNewFrame = false
        return captureBitmap
    }

    override fun releaseBitmap(bitmap: Bitmap) {
        if (captureLock.isHeldByCurrentThread) captureLock.unlock()
    }

    private fun resizeCapture(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        captureLock.lock()
        try {
            val existing = captureBitmap
            if (existing != null && existing.width == width && existing.height == height) return
            existing?.recycle()
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            captureBitmap = bmp
            captureCanvas = Canvas(bmp)
            hasNewFrame = false
        } finally {
            captureLock.unlock()
        }
        terminalView.invalidate()
    }

    private fun captureNow() {
        if (!captureLock.tryLock()) return
        try {
            val bmp = captureBitmap ?: return
            val c = captureCanvas ?: return
            val tw = terminalView.width
            val th = terminalView.height
            if (tw <= 0 || th <= 0) return
            c.save()
            c.drawColor(Color.BLACK)
            c.scale(bmp.width.toFloat() / tw, bmp.height.toFloat() / th)
            terminalView.draw(c)
            c.restore()
            hasNewFrame = true
        } finally {
            captureLock.unlock()
        }
    }

    companion object {
        /** Cap longest dimension so GL uploads stay cheap. */
        fun pickCaptureSize(viewW: Int, viewH: Int): Pair<Int, Int> {
            val maxDim = 1280
            val longest = max(viewW, viewH).coerceAtLeast(1)
            val scale = min(1f, maxDim.toFloat() / longest)
            return (viewW * scale).toInt().coerceAtLeast(1) to (viewH * scale).toInt().coerceAtLeast(1)
        }
    }
}
