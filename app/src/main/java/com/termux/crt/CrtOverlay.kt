package com.termux.crt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import com.termux.view.TerminalView

/**
 * Wires the CRT shader overlay into Termux's existing `TermuxActivity` with
 * minimal touch — Termux's view stack, lifecycle, and `findViewById` calls
 * stay exactly as upstream.
 *
 * Behavior is controlled by [CrtSettings.crtEnabled]:
 *   - enabled  → TerminalView alpha=0, CrtSurfaceView visible. The mirror
 *                captures every TerminalView draw into a bitmap that the
 *                renderer samples and runs the shader over.
 *   - disabled → TerminalView fully visible, CrtSurfaceView gone. The mirror
 *                detaches so we don't pay the double-draw cost.
 */
class CrtOverlay(
    private val activity: Activity,
    private val terminalView: TerminalView,
    private val crtSurfaceView: CrtSurfaceView,
) {
    private val mirror = TerminalViewMirror(terminalView)
    private var attached = false
    private var rendererAttached = false

    fun onCreate() {
        // Pure black under the GL surface so any sliver outside the curve
        // matches the TerminalView background.
        terminalView.setBackgroundColor(Color.BLACK)
        applySettings(CrtSettings.load(activity))
    }

    fun onResume() {
        crtSurfaceView.onResume()
        applySettings(CrtSettings.load(activity))
        TerminalFont.apply(
            terminalView,
            spToPx(CrtSettings.load(activity).fontSizeSp),
            TerminalFont.typeface(activity, CrtSettings.load(activity).font),
        )
    }

    fun onPause() {
        crtSurfaceView.onPause()
    }

    fun onDestroy() {
        if (attached) { mirror.detach(); attached = false }
    }

    private fun applySettings(s: CrtSettings) {
        if (!rendererAttached) {
            crtSurfaceView.attach(mirror)
            rendererAttached = true
        }
        crtSurfaceView.applySettings(s)

        if (s.crtEnabled) {
            if (!attached) { mirror.attach(); attached = true }
            terminalView.alpha = 0f
            crtSurfaceView.visibility = View.VISIBLE
        } else {
            if (attached) { mirror.detach(); attached = false }
            terminalView.alpha = 1f
            crtSurfaceView.visibility = View.GONE
        }
    }

    private fun spToPx(sp: Int): Int =
        (activity.resources.displayMetrics.scaledDensity * sp).toInt().coerceAtLeast(8)

    companion object {
        @JvmStatic
        fun openSettings(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
