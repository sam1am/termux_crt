package com.termux.crt

import android.graphics.Bitmap

/**
 * A pluggable source of "terminal pixels" that the CRT renderer samples each frame.
 *
 * Implementations should be cheap to poll: [acquireBitmap] returns the latest
 * bitmap (or null if nothing has changed since the last frame). When non-null,
 * the implementation may hold an internal lock so the bitmap is stable during
 * the GL upload — callers MUST call [releaseBitmap] after they're done.
 */
interface TerminalSurface {
    fun onSurfaceSize(width: Int, height: Int)
    fun acquireBitmap(): Bitmap?
    fun releaseBitmap(bitmap: Bitmap)
}
