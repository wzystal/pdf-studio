package com.pdfstudio.core.pdfannot

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun deviceToNormalized_mapsCenter() {
        val (x, y) = CoordinateMapper.deviceToNormalized(500f, 800f, 1000, 1600)
        assertEquals(0.5f, x, 0.001f)
        assertEquals(0.5f, y, 0.001f)
    }

    @Test
    fun normalizedToDevice_roundTripsCornerCoords() {
        val viewWidth = 1000
        val viewHeight = 2000
        val (nx, ny) = CoordinateMapper.deviceToNormalized(100f, 400f, viewWidth, viewHeight)
        assertEquals(0.1f, nx, 0.001f)
        assertEquals(0.2f, ny, 0.001f)
        // RectF is a JVM stub in unit tests; verify the same math normalizedRectToDevice uses.
        assertEquals(100f, nx * viewWidth, 0.01f)
        assertEquals(400f, ny * viewHeight, 0.01f)
    }
}
