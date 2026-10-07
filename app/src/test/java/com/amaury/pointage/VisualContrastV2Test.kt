package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class VisualContrastV2Test {
    @Test fun `black and white have full contrast`() {
        assertEquals(21.0, VisualContrastV2.ratio(VisualContrastV2.WHITE, VisualContrastV2.BLACK), .00001)
    }
    @Test fun `middle gray needs black not luminance threshold white`() {
        assertEquals(VisualContrastV2.BLACK, VisualContrastV2.bestText(0xFF808080.toInt()))
        assertTrue(VisualContrastV2.ratio(VisualContrastV2.bestText(0xFF808080.toInt()), 0xFF808080.toInt()) >= 4.5)
    }
    @Test fun `opaque color sample grid always chooses readable text`() {
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val color = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            assertTrue(VisualContrastV2.ratio(VisualContrastV2.bestText(color), color) >= 4.5)
        }
    }
}
