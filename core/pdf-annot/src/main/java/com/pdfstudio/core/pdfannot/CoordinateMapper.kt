package com.pdfstudio.core.pdfannot

import android.graphics.RectF

/**
 * Uses normalized page coordinates (0..1) to avoid device/zoom mapping issues.
 */
object CoordinateMapper {

    fun deviceToNormalized(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0) return 0f to 0f
        return (x / viewWidth).coerceIn(0f, 1f) to (y / viewHeight).coerceIn(0f, 1f)
    }

    fun normalizedRectToDevice(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): RectF {
        return RectF(
            left * viewWidth,
            top * viewHeight,
            right * viewWidth,
            bottom * viewHeight,
        )
    }

    fun rectFromPoints(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): RectF {
        val (n1x, n1y) = deviceToNormalized(x1, y1, viewWidth, viewHeight)
        val (n2x, n2y) = deviceToNormalized(x2, y2, viewWidth, viewHeight)
        return normalizedRectToDevice(
            minOf(n1x, n2x),
            minOf(n1y, n2y),
            maxOf(n1x, n2x),
            maxOf(n1y, n2y),
            viewWidth,
            viewHeight,
        )
    }

    fun normalizedRect(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): RectF {
        val (n1x, n1y) = deviceToNormalized(x1, y1, viewWidth, viewHeight)
        val (n2x, n2y) = deviceToNormalized(x2, y2, viewWidth, viewHeight)
        return RectF(
            minOf(n1x, n2x),
            minOf(n1y, n2y),
            maxOf(n1x, n2x),
            maxOf(n1y, n2y),
        )
    }
}
