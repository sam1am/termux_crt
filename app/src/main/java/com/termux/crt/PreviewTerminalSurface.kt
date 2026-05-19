package com.termux.crt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.concurrent.locks.ReentrantLock

/**
 * A static-content [TerminalSurface] for the settings preview. Renders a
 * faux-neofetch screenful (ASCII logo, info column, ANSI color palette, an
 * `ls --color`-style line, block/box-drawing glyphs, and a prompt with cursor)
 * into a bitmap once, then hands the same bitmap to the CRT renderer every
 * frame so the shader effects animate over a stable sample.
 *
 * Re-render the bitmap by calling [setStyle] when the user picks a different
 * font; the size is fixed by [onSurfaceSize] from the GL viewport.
 */
class PreviewTerminalSurface : TerminalSurface {

    private val lock = ReentrantLock()
    private var bitmap: Bitmap? = null
    @Volatile private var hasNewFrame = false

    private var surfaceW = 0
    private var surfaceH = 0

    private var typeface: Typeface = Typeface.MONOSPACE

    override fun onSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceW = width
        surfaceH = height
        rerender()
    }

    /** Swap the typeface used to render the sample. Cheap — just re-rasterizes. */
    fun setStyle(typeface: Typeface) {
        this.typeface = typeface
        rerender()
    }

    override fun acquireBitmap(): Bitmap? {
        lock.lock()
        if (!hasNewFrame) {
            lock.unlock()
            return null
        }
        hasNewFrame = false
        return bitmap
    }

    override fun releaseBitmap(bitmap: Bitmap) {
        if (lock.isHeldByCurrentThread) lock.unlock()
    }

    private fun rerender() {
        val w = surfaceW
        val h = surfaceH
        if (w <= 0 || h <= 0) return
        lock.lock()
        try {
            val existing = bitmap
            val bmp = if (existing != null && existing.width == w && existing.height == h) {
                existing
            } else {
                existing?.recycle()
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
            }
            drawSample(bmp)
            hasNewFrame = true
        } finally {
            lock.unlock()
        }
    }

    private fun drawSample(bmp: Bitmap) {
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)

        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()

        // 10 rows × ~34 cols leaves enough room for legible glyphs in the
        // ~4:3 preview slot. Pick the text size that satisfies whichever
        // dimension is tighter — monospace 'M' is roughly 0.6× text size,
        // so cap on both height and width.
        val rows = 10
        val cols = 34
        val sizeByHeight = h / rows * 0.92f
        val sizeByWidth = w / cols / 0.6f
        val textSize = minOf(sizeByHeight, sizeByWidth)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@PreviewTerminalSurface.typeface
            this.textSize = textSize
            isSubpixelText = true
        }

        val charW = paint.measureText("M")
        val rowH = textSize * 1.10f
        val baseline = -paint.fontMetrics.top

        // Center the whole block horizontally so it sits in the curved
        // viewport without hugging an edge that the curvature crops.
        val blockWidthChars = cols
        val blockPixelWidth = blockWidthChars * charW
        val leftPad = ((w - blockPixelWidth) / 2f).coerceAtLeast(0f)
        val topPad = (h - rows * rowH) / 2f

        fun put(col: Int, row: Int, s: String, color: Int) {
            paint.color = color
            c.drawText(s, leftPad + col * charW, topPad + row * rowH + baseline, paint)
        }

        // --- Logo (left) + info column (right) ---
        val logoLines = listOf(
            "  _______ ",
            " |  ___  |",
            " | |>_ | |",
            " | |___| |",
            " |_______|",
        )
        for ((i, line) in logoLines.withIndex()) {
            put(0, i, line, FG_CYAN)
        }
        val infoCol = 12
        put(infoCol, 0, "user", FG_GREEN)
        put(infoCol + 4, 0, "@", FG_WHITE)
        put(infoCol + 5, 0, "crt", FG_GREEN)
        put(infoCol, 1, "--------", FG_GRAY)
        put(infoCol, 2, "OS",    FG_YELLOW); put(infoCol + 7, 2, "Termux CRT", FG_WHITE)
        put(infoCol, 3, "Shell", FG_YELLOW); put(infoCol + 7, 3, "bash 5.2",   FG_WHITE)
        put(infoCol, 4, "Term",  FG_YELLOW); put(infoCol + 7, 4, "termux",     FG_WHITE)

        // 16-color palette swatches on the next row, full width of the block.
        val paletteY = topPad + 5.5f * rowH
        val swatchH = textSize * 0.95f
        val swatchW = blockPixelWidth / 16f
        val palette = intArrayOf(
            FG_BLACK_VIS, FG_RED, FG_GREEN, FG_YELLOW,
            FG_BLUE, FG_MAGENTA, FG_CYAN, FG_WHITE,
            FG_BR_BLACK, FG_BR_RED, FG_BR_GREEN, FG_BR_YELLOW,
            FG_BR_BLUE, FG_BR_MAGENTA, FG_BR_CYAN, FG_BR_WHITE,
        )
        paint.style = Paint.Style.FILL
        for ((i, col) in palette.withIndex()) {
            paint.color = col
            val left = leftPad + i * swatchW
            c.drawRect(left, paletteY, left + swatchW * 0.95f, paletteY + swatchH, paint)
        }

        // ls --color row
        put(0,  7, "bin/",    FG_BLUE)
        put(5,  7, "run.sh",  FG_GREEN)
        put(12, 7, "data.gz", FG_RED)
        put(20, 7, "link@",   FG_CYAN)
        put(26, 7, "READ.md", FG_WHITE)

        // Final prompt with block cursor (rect — works on fonts lacking a
        // full block glyph).
        put(0, 9, "user", FG_GREEN); put(4, 9, "@", FG_WHITE); put(5, 9, "crt", FG_GREEN)
        put(8, 9, ":", FG_WHITE); put(9, 9, "~", FG_BLUE)
        put(10, 9, "$ ", FG_WHITE)
        paint.color = FG_WHITE
        val cursorX = leftPad + 12 * charW
        val cursorY = topPad + 9 * rowH + (rowH - textSize * 0.85f)
        c.drawRect(cursorX, cursorY, cursorX + charW * 0.95f, cursorY + textSize * 0.85f, paint)
    }

    companion object {
        // Xterm-ish ANSI 16-color palette. Slightly desaturated so they don't
        // look cartoonish under the bloom shader.
        private const val FG_BLACK_VIS  = 0xFF222222.toInt()
        private const val FG_RED        = 0xFFCD3131.toInt()
        private const val FG_GREEN      = 0xFF0DBC79.toInt()
        private const val FG_YELLOW     = 0xFFE5E510.toInt()
        private const val FG_BLUE       = 0xFF2472C8.toInt()
        private const val FG_MAGENTA    = 0xFFBC3FBC.toInt()
        private const val FG_CYAN       = 0xFF11A8CD.toInt()
        private const val FG_WHITE      = 0xFFE5E5E5.toInt()
        private const val FG_BR_BLACK   = 0xFF666666.toInt()
        private const val FG_BR_RED     = 0xFFF14C4C.toInt()
        private const val FG_BR_GREEN   = 0xFF23D18B.toInt()
        private const val FG_BR_YELLOW  = 0xFFF5F543.toInt()
        private const val FG_BR_BLUE    = 0xFF3B8EEA.toInt()
        private const val FG_BR_MAGENTA = 0xFFD670D6.toInt()
        private const val FG_BR_CYAN    = 0xFF29B8DB.toInt()
        private const val FG_BR_WHITE   = 0xFFFFFFFF.toInt()
        private const val FG_GRAY       = 0xFF808080.toInt()
    }
}
