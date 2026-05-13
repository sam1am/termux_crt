package com.termux.crt

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import com.termux.view.TerminalView

/**
 * Applies font size + typeface to a [TerminalView].
 *
 * `TerminalView.setTextSize` exists but it copies the previous typeface, and
 * there's no public `setTypeface`. The `mRenderer` field is package-private,
 * and `TerminalRenderer`'s typeface/textSize fields are `final`. So changing
 * the font means constructing a fresh `TerminalRenderer` and dropping it in
 * via reflection.
 */
object TerminalFont {
    private const val TAG = "TermuxCRT.Font"

    private val cache = mutableMapOf<CrtFont, Typeface>()

    fun typeface(context: Context, font: CrtFont): Typeface {
        cache[font]?.let { return it }
        val tf = try {
            val asset = font.assetName
            if (asset == null) Typeface.MONOSPACE
            else Typeface.createFromAsset(context.assets, asset)
        } catch (e: Throwable) {
            Log.w(TAG, "failed to load font ${font.name}, falling back to monospace", e)
            Typeface.MONOSPACE
        }
        cache[font] = tf
        return tf
    }

    fun apply(view: TerminalView, sizePx: Int, typeface: Typeface) {
        // Termux's fork added public setTypeface() / setTextSize() to
        // TerminalView. Use them directly — reflection on mRenderer worked
        // before but lost a race with Termux's own checkForFontAndColors()
        // which calls setTypeface(MONOSPACE) after our reflection write,
        // wiping our typeface. The public API is what Termux respects.
        view.setTextSize(sizePx)
        view.setTypeface(typeface)
        view.invalidate()
    }
}
