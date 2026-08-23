package com.amaury.pointage

import kotlin.math.abs
import kotlin.math.sin

/**
 * Chef d'équipe des 80 facettes d'un bouton diamant.
 *
 * Responsabilité unique : conserver l'identité de chaque facette et faire
 * évoluer son état depuis la valeur précédente. La géométrie, le dessin et
 * la déformation bombée restent dans le bouton de rendu.
 */
class DiamondFacetManager(private val facetCount: Int = 80) {

    data class FacetState(
        val baseColor: Int,
        val referenceAlpha: Int,
        var luminosity: Float = 1f,
        var reflection: Float = 0f,
        var lastAzimuth: Float = 0f,
        var lastTilt: Float = 0f
    )

    private val states = arrayOfNulls<FacetState>(facetCount)

    fun stateFor(
        facetId: Int,
        paletteColor: Int,
        ring: Int,
        baseAzimuth: Float,
        cutTilt: Float,
        currentAzimuth: Float,
        currentTilt: Float
    ): FacetState {
        require(facetId in 0 until facetCount) { "Facet id $facetId hors plage 0..${facetCount - 1}" }
        return states[facetId] ?: FacetState(
            baseColor = paletteColor,
            referenceAlpha = referenceAlpha(facetId, ring, baseAzimuth, cutTilt),
            lastAzimuth = currentAzimuth,
            lastTilt = currentTilt
        ).also { states[facetId] = it }
    }

    /**
     * Évolue toujours depuis l'état précédent. Il n'y a aucune dead-zone :
     * le plus petit changement d'orientation influence le coefficient de
     * réponse. Les valeurs de couleur et d'alpha de référence ne changent pas.
     */
    fun update(
        state: FacetState,
        targetLuminosity: Float,
        targetReflection: Float,
        currentAzimuth: Float,
        currentTilt: Float
    ): FacetState {
        val azDelta = angularDistance(state.lastAzimuth, currentAzimuth)
        val tiltDelta = abs(currentTilt - state.lastTilt)
        val motion = (azDelta / 24f + tiltDelta / 18f).coerceIn(0f, 1f)

        // Réactif même au mouvement minuscule, tout en conservant réellement
        // une mémoire de la frame précédente.
        val lightResponse = (0.42f + motion * 0.48f).coerceIn(0.42f, 0.90f)
        val reflectionResponse = (0.52f + motion * 0.43f).coerceIn(0.52f, 0.95f)

        state.luminosity += (targetLuminosity - state.luminosity) * lightResponse
        state.reflection += (targetReflection - state.reflection) * reflectionResponse
        state.lastAzimuth = currentAzimuth
        state.lastTilt = currentTilt
        return state
    }

    fun initializedCount(): Int = states.count { it != null }

    private fun referenceAlpha(facetId: Int, ring: Int, az: Float, cutTilt: Float): Int {
        val azimuthShape = ((sin(Math.toRadians((az + 37f).toDouble())).toFloat() + 1f) * 8f).toInt()
        val cutShape = ((cutTilt.coerceIn(6f, 74f) - 6f) / 68f * 18f).toInt()
        val signature = ((facetId * 13 + ring * 17) % 13) - 6
        return (166 + ring * 5 + azimuthShape + cutShape + signature).coerceIn(160, 214)
    }

    private fun angularDistance(a: Float, b: Float): Float = abs(((b - a + 540f) % 360f) - 180f)
}
