package com.amaury.pointage

/** Compatibility overload used by the 3D diamond motion sensor. */
internal fun atan2(y: Float, x: Double): Double = kotlin.math.atan2(y.toDouble(), x)
