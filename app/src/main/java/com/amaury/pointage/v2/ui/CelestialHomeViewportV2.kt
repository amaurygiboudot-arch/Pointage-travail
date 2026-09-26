package com.amaury.pointage.v2.ui

/** Layout/input policy only. It never alters heading or astronomical positions. */
object CelestialHomeViewportV2 {
    fun panelHeightPx(viewportHeightPx: Int, reservedHeightPx: Int): Int =
        (viewportHeightPx.toLong() - reservedHeightPx.toLong().coerceAtLeast(0L))
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    fun scrollCoordinate(homeActive: Boolean, requestedPx: Int): Int =
        if (homeActive) 0 else requestedPx
}
