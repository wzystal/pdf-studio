package com.pdfstudio.core.pdfengine

import kotlin.math.min
import kotlin.math.sqrt

object PdfRenderLimits {
    /** 单张 Bitmap 最大边长（Android 常见上限约 4096） */
    private const val MAX_BITMAP_DIMENSION = 4096

    /** 单张 Bitmap 最大像素数，避免超大扫描页 OOM */
    private const val MAX_BITMAP_PIXELS = 12 * 1024 * 1024

    data class RenderSize(val width: Int, val height: Int)

    fun computeRenderSize(pageWidthPt: Int, pageHeightPt: Int, requestedWidth: Int): RenderSize {
        val pageW = pageWidthPt.coerceAtLeast(1)
        val pageH = pageHeightPt.coerceAtLeast(1)
        var w = requestedWidth.coerceAtLeast(1)
        var h = (w * pageH.toFloat() / pageW).toInt().coerceAtLeast(1)

        if (w > MAX_BITMAP_DIMENSION || h > MAX_BITMAP_DIMENSION) {
            val scale = min(MAX_BITMAP_DIMENSION.toFloat() / w, MAX_BITMAP_DIMENSION.toFloat() / h)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }

        if (w.toLong() * h > MAX_BITMAP_PIXELS) {
            val scale = sqrt(MAX_BITMAP_PIXELS.toDouble() / (w * h))
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }

        return RenderSize(w, h)
    }
}

class PdfRenderException(message: String, cause: Throwable? = null) : Exception(message, cause)
