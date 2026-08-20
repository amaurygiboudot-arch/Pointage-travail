package com.amaury.pointage

/**
 * Petit état partagé entre la couche astronomique et l'horloge.
 * Les coordonnées sont des vecteurs normalisés dans l'espace écran :
 * +X vers la droite, +Y vers le bas.
 */
object CelestialLightingState {
    @Volatile var sunDirX: Float = 0f
    @Volatile var sunDirY: Float = -1f
    @Volatile var hasSunDirection: Boolean = false

    fun updateSunDirection(x: Float, y: Float) {
        val length = kotlin.math.sqrt(x * x + y * y)
        if (length > 0.0001f) {
            sunDirX = x / length
            sunDirY = y / length
            hasSunDirection = true
        }
    }
}
