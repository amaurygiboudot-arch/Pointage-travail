package com.amaury.pointage

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sin

internal data class DiamondFacetState(
    val baseColor: Int,
    val referenceAlpha: Int,
    var luminosity: Float,
    var reflection: Float,
    var lastAzimuth: Float,
    var lastTilt: Float
)

internal data class DiamondFacetLighting(
    val luminosity: Float,
    val reflection: Float
)

/**
 * Source unique de vérité pour les 80 facettes.
 *
 * Ordre logique :
 * 1. état permanent (couleur + alpha + historique),
 * 2. nouvelle orientation,
 * 3. mise à jour lumière/reflet depuis l'état précédent,
 * 4. rendu par RedDiamondFinalButton,
 * 5. déformation bombée après le rendu.
 */
internal class DiamondFacetEngine(
    private val paletteProvider: () -> IntArray
) {
    companion object {
        const val FACET_COUNT = 80
    }

    private val states = arrayOfNulls<DiamondFacetState>(FACET_COUNT)

    fun stateFor(
        facetId: Int,
        paletteIndex: Int,
        ring: Int,
        azimuth: Float,
        cutTilt: Float,
        currentAzimuth: Float,
        currentTilt: Float
    ): DiamondFacetState {
        require(facetId in 0 until FACET_COUNT) { "facetId=$facetId" }
        states[facetId]?.let { return it }

        val palette = paletteProvider()
        return DiamondFacetState(
            baseColor = palette[paletteIndex % palette.size],
            referenceAlpha = referenceAlpha(facetId, ring, azimuth, cutTilt),
            luminosity = 1f,
            reflection = 0f,
            lastAzimuth = currentAzimuth,
            lastTilt = currentTilt
        ).also { states[facetId] = it }
    }

    fun update(
        state: DiamondFacetState,
        targetLuminosity: Float,
        targetReflection: Float,
        currentAzimuth: Float,
        currentTilt: Float
    ): DiamondFacetLighting {
        val azimuthDelta = abs(shortestDelta(state.lastAzimuth, currentAzimuth))
        val tiltDelta = abs(currentTilt - state.lastTilt)
        val movement = (azimuthDelta / 16f + tiltDelta / 10f).coerceIn(0f, 1f)

        // Réaction immédiate à chaque événement capteur, sans timer/dead zone.
        // L'état précédent reste néanmoins réellement utilisé : il sert de point de départ
        // et le mouvement augmente la vitesse de convergence au lieu de réinitialiser la facette.
        val lightGain = (.82f + movement * .18f).coerceIn(.82f, 1f)
        val reflectionGain = (.88f + movement * .12f).coerceIn(.88f, 1f)

        state.luminosity = (
            state.luminosity + (targetLuminosity - state.luminosity) * lightGain
        ).coerceIn(.68f, 1.26f)

        state.reflection = (
            state.reflection + (targetReflection - state.reflection) * reflectionGain
        ).coerceIn(0f, .36f)

        state.lastAzimuth = currentAzimuth
        state.lastTilt = currentTilt

        return DiamondFacetLighting(state.luminosity, state.reflection)
    }

    private fun referenceAlpha(
        facetId: Int,
        ring: Int,
        azimuth: Float,
        cutTilt: Float
    ): Int {
        // Stable : dépend uniquement de la géométrie/coupe de la facette, jamais du capteur.
        val azimuthShape = ((sin(Math.toRadians((azimuth + 37f).toDouble())).toFloat() + 1f) * 10f).toInt()
        val cutShape = ((cutTilt.coerceIn(6f, 74f) - 6f) / 68f * 22f).toInt()
        val signature = ((facetId * 13 + ring * 17) % 15) - 7
        return (148 + ring * 6 + azimuthShape + cutShape + signature).coerceIn(144, 202)
    }

    private fun shortestDelta(from: Float, to: Float): Float =
        ((to - from + 540f) % 360f) - 180f
}
