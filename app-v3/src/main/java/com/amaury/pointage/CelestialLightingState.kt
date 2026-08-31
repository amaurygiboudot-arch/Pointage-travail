package com.amaury.pointage

/**
 * Etat partagé de la source céleste utilisée par toute l'application.
 * +X vers la droite, +Y vers le bas dans l'espace écran.
 *
 * Le diamant lit ici la même source que l'horloge : direction, intensité et teinte.
 */
object CelestialLightingState {
    @Volatile var sunDirX: Float = 0f
    @Volatile var sunDirY: Float = -1f
    @Volatile var hasSunDirection: Boolean = false

    @Volatile var opticalIntensity: Float = 0.78f
    @Volatile var opticalRed: Float = 1f
    @Volatile var opticalGreen: Float = 1f
    @Volatile var opticalBlue: Float = 1f
    @Volatile var opticalWarmth: Float = 0f
    @Volatile var opticalNight: Boolean = false

    fun updateSunDirection(x: Float, y: Float) {
        val length = kotlin.math.sqrt(x * x + y * y)
        if (length > 0.0001f) {
            sunDirX = x / length
            sunDirY = y / length
            hasSunDirection = true
        }
    }

    /**
     * Convertit l'état astronomique existant en source optique commune.
     * Soleil haut : blanc/neutre. Soleil bas : plus chaud. Lune : faible et froide.
     */
    fun updateOpticalLight(intensity: Float, elevationDegrees: Float, night: Boolean) {
        opticalNight = night
        opticalIntensity = intensity.coerceIn(0.12f, 1f)

        if (night) {
            opticalWarmth = -0.32f
            opticalRed = 0.72f
            opticalGreen = 0.82f
            opticalBlue = 1.00f
        } else {
            val height = ((elevationDegrees + 2f) / 47f).coerceIn(0f, 1f)
            val warmth = (1f - height) * 0.46f
            opticalWarmth = warmth
            opticalRed = 1.00f
            opticalGreen = 1.00f - warmth * 0.22f
            opticalBlue = 1.00f - warmth * 0.52f
        }
    }
}
