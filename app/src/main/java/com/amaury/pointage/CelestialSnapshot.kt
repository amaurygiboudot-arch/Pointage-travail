package com.amaury.pointage

/**
 * Etat céleste immuable partagé par tous les consommateurs visuels.
 *
 * Règle d'architecture : un même snapshot correspond à un seul instant, une seule
 * position géographique de référence et une seule orientation physique du téléphone.
 * Les vues lisent cet état ; elles ne le recalculent pas et ne le modifient pas.
 */
data class CelestialSnapshot(
    val timestampMs: Long,
    val sun: CelestialBodyState,
    val moon: CelestialBodyState,
    val isNight: Boolean,
    val location: CelestialLocationState?,
    val orientation: DeviceOrientationState,
    val locationConfidence: LocationConfidence,
    /** Direction écran normalisée : +X droite, +Y bas. */
    val sunScreenDirection: ScreenDirection?,
    val moonScreenDirection: ScreenDirection?
) {
    companion object {
        val EMPTY = CelestialSnapshot(
            timestampMs = 0L,
            sun = CelestialBodyState.EMPTY,
            moon = CelestialBodyState.EMPTY,
            isNight = false,
            location = null,
            orientation = DeviceOrientationState.IDENTITY,
            locationConfidence = LocationConfidence.NONE,
            sunScreenDirection = null,
            moonScreenDirection = null
        )
    }
}

data class ScreenDirection(val x: Float, val y: Float)

data class CelestialBodyState(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val apparentScale: Double,
    /** Contribution optique normalisée. 0 = aucune, 1 = maximum du moteur. */
    val opticalIntensity: Float,
    val available: Boolean
) {
    companion object {
        val EMPTY = CelestialBodyState(0.0, -90.0, 1.0, 0f, false)
    }
}

data class CelestialLocationState(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val fixTimeMs: Long
)

enum class LocationConfidence { NONE, STALE, USABLE, FRESH }

/**
 * Matrice de rotation 3x3 issue du Rotation Vector Android.
 * On conserve la représentation 3D complète : yaw/pitch/roll ne sont que des
 * valeurs dérivées destinées au diagnostic ou à l'interface.
 */
data class DeviceOrientationState(
    val r00: Float, val r01: Float, val r02: Float,
    val r10: Float, val r11: Float, val r12: Float,
    val r20: Float, val r21: Float, val r22: Float,
    val azimuthDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float
) {
    companion object {
        val IDENTITY = DeviceOrientationState(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            0f, 0f, 0f
        )

        fun fromRotationMatrix(matrix: FloatArray, azimuthDeg: Float, pitchDeg: Float, rollDeg: Float): DeviceOrientationState {
            if (matrix.size < 9) return IDENTITY.copy(
                azimuthDeg = azimuthDeg,
                pitchDeg = pitchDeg,
                rollDeg = rollDeg
            )
            return DeviceOrientationState(
                matrix[0], matrix[1], matrix[2],
                matrix[3], matrix[4], matrix[5],
                matrix[6], matrix[7], matrix[8],
                azimuthDeg, pitchDeg, rollDeg
            )
        }
    }
}

/**
 * Source de vérité en mémoire. Les mises à jour remplacent atomiquement le snapshot
 * complet afin qu'aucun consommateur ne voie un mélange de deux instants différents.
 */
object CelestialStateStore {
    @Volatile
    private var currentState: CelestialSnapshot = CelestialSnapshot.EMPTY

    fun current(): CelestialSnapshot = currentState

    fun publish(snapshot: CelestialSnapshot) {
        currentState = snapshot
    }
}
