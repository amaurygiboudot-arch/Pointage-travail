package com.amaury.pointage

import android.content.Context

data class DiamondTuning(
    val transparency: Float = 0.58f,
    val facetDepth: Float = 0.72f,
    val refraction: Float = 0.72f,
    val sparkle: Float = 0.66f,
    val iceBlue: Float = 0.30f,
    val bevel: Float = 0.78f
)

object DiamondTuningStore {
    private const val PREFS = "diamond_lab"
    private const val K_TRANSPARENCY = "transparency"
    private const val K_FACET = "facet_depth"
    private const val K_REFRACTION = "refraction"
    private const val K_SPARKLE = "sparkle"
    private const val K_BLUE = "ice_blue"
    private const val K_BEVEL = "bevel"

    val defaults = DiamondTuning()

    fun load(context: Context): DiamondTuning {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DiamondTuning(
            transparency = p.getFloat(K_TRANSPARENCY, defaults.transparency),
            facetDepth = p.getFloat(K_FACET, defaults.facetDepth),
            refraction = p.getFloat(K_REFRACTION, defaults.refraction),
            sparkle = p.getFloat(K_SPARKLE, defaults.sparkle),
            iceBlue = p.getFloat(K_BLUE, defaults.iceBlue),
            bevel = p.getFloat(K_BEVEL, defaults.bevel)
        )
    }

    fun save(context: Context, value: DiamondTuning) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(K_TRANSPARENCY, value.transparency.coerceIn(0f, 1f))
            .putFloat(K_FACET, value.facetDepth.coerceIn(0f, 1f))
            .putFloat(K_REFRACTION, value.refraction.coerceIn(0f, 1f))
            .putFloat(K_SPARKLE, value.sparkle.coerceIn(0f, 1f))
            .putFloat(K_BLUE, value.iceBlue.coerceIn(0f, 1f))
            .putFloat(K_BEVEL, value.bevel.coerceIn(0f, 1f))
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
