package com.amaury.pointage.v2.ui

import org.junit.Assert.*
import org.junit.Test

class CelestialHomeViewportV2Test {
    @Test fun homeNeverAcceptsAScrollOffset() {
        for (offset in listOf(Int.MIN_VALUE, -1000, -1, 0, 1, 800, Int.MAX_VALUE))
            assertEquals(0, CelestialHomeViewportV2.scrollCoordinate(true, offset))
    }
    @Test fun businessTabsKeepTheirScrollOffsets() {
        for (offset in listOf(-1000, -1, 0, 1, 800, Int.MAX_VALUE))
            assertEquals(offset, CelestialHomeViewportV2.scrollCoordinate(false, offset))
    }
    @Test fun measuredWindowNotPhysicalDisplayDefinesTheScene() {
        for (height in listOf(240, 360, 640, 800, 1200, 2392)) {
            val reserved = 100
            assertEquals(height, reserved + CelestialHomeViewportV2.panelHeightPx(height, reserved))
        }
    }
    @Test fun invisibleTabsKeepExactlyTheSameSceneSize() {
        val visible = CelestialHomeViewportV2.panelHeightPx(1200, 160)
        val invisible = CelestialHomeViewportV2.panelHeightPx(1200, 160)
        assertEquals(visible, invisible)
        assertNotEquals(visible, CelestialHomeViewportV2.panelHeightPx(1200, 0))
    }
    @Test fun tinyWindowsAndInvalidReservationsCannotOverflow() {
        assertEquals(1, CelestialHomeViewportV2.panelHeightPx(100, 200))
        assertEquals(1, CelestialHomeViewportV2.panelHeightPx(Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, CelestialHomeViewportV2.panelHeightPx(Int.MAX_VALUE, -1))
        assertEquals(1, CelestialHomeViewportV2.panelHeightPx(0, 0))
    }
}
