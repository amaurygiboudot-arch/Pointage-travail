package com.amaury.pointage

import android.content.Context
import android.graphics.Color

/** Valeurs persistantes du banc de réglage développeur des vrais boutons. */
data class PrimaryDiamondLiveTuningConfig(
    val radiusScale: Float = 0.455f,
    val pressScale: Float = 0.93f,
    val saturation: Float = 1.00f,
    val entryGain: Float = 1.00f,
    val pauseGain: Float = 1.00f,
    val exitGain: Float = 1.00f,
    val translucencyScale: Float = 1.00f,
    val baseLuminance: Float = 0.20f,
    val directWeight: Float = 0.42f,
    val internalWeight: Float = 0.38f,
    val internalRetention: Float = 0.86f,
    val responseTau: Float = 0.055f,
    val sunIntensityScale: Float = 1.00f,
    val moonIntensityScale: Float = 1.00f,
    val daySpecularPower: Float = 105f,
    val nightSpecularPower: Float = 58f,
    val specularAlpha: Float = 92f,
    val specularRadius: Float = 0.55f,
    val specularOffset: Float = 0.18f,
    val highlightMix: Float = 0.09f,
    val innerSlope: Float = 0.08f,
    val middleSlope: Float = 0.14f,
    val outerSlope: Float = 0.20f,
    val sunHaloAlpha: Float = 158f,
    val moonHaloAlpha: Float = 118f,
    val haloRadius: Float = 1.05f,
    val haloOffset: Float = 0.48f,
    val sunShadowAlpha: Float = 92f,
    val moonShadowAlpha: Float = 72f,
    val shadowRadius: Float = 0.92f,
    val shadowOffset: Float = 0.62f,
    val sunArcAlpha: Float = 150f,
    val moonArcAlpha: Float = 115f,
    val arcWidth: Float = 0.035f,
    val arcSpanDeg: Float = 54f,
    val edgeWidth: Float = 0.0065f,
    val edgeBaseAlpha: Float = 18f,
    val edgeLightAlpha: Float = 20f,
    val girdleWidth: Float = 0.025f,
    val girdleAlpha: Float = 92f,
    val girdleInnerWidth: Float = 0.007f,
    val girdleInnerAlpha: Float = 44f,
    val girdleRadius: Float = 0.982f,
    val girdleInnerRadius: Float = 0.958f
)

object PrimaryDiamondLiveTuning {
    private const val PREFS = "developer_primary_diamond_live"
    val defaults = PrimaryDiamondLiveTuningConfig()
    @Volatile private var cached: PrimaryDiamondLiveTuningConfig? = null

    fun current(context: Context): PrimaryDiamondLiveTuningConfig = cached ?: load(context).also { cached = it }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        cached = defaults
    }

    fun set(context: Context, key: String, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(key, value).apply()
        cached = load(context)
    }

    fun adjustedMaterialColor(context: Context, viewId: Int, fallback: Int): Int {
        val tuning = current(context)
        val gain = when (viewId) {
            R.id.entryButton -> tuning.entryGain
            R.id.pauseButton -> tuning.pauseGain
            R.id.exitButton -> tuning.exitGain
            else -> 1f
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(fallback, hsv)
        hsv[1] = (hsv[1] * tuning.saturation).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * gain).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun load(context: Context): PrimaryDiamondLiveTuningConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun f(key: String, default: Float) = p.getFloat(key, default)
        return PrimaryDiamondLiveTuningConfig(
            radiusScale=f("radiusScale",defaults.radiusScale), pressScale=f("pressScale",defaults.pressScale),
            saturation=f("saturation",defaults.saturation), entryGain=f("entryGain",defaults.entryGain),
            pauseGain=f("pauseGain",defaults.pauseGain), exitGain=f("exitGain",defaults.exitGain),
            translucencyScale=f("translucencyScale",defaults.translucencyScale), baseLuminance=f("baseLuminance",defaults.baseLuminance),
            directWeight=f("directWeight",defaults.directWeight), internalWeight=f("internalWeight",defaults.internalWeight),
            internalRetention=f("internalRetention",defaults.internalRetention), responseTau=f("responseTau",defaults.responseTau),
            sunIntensityScale=f("sunIntensityScale",defaults.sunIntensityScale), moonIntensityScale=f("moonIntensityScale",defaults.moonIntensityScale),
            daySpecularPower=f("daySpecularPower",defaults.daySpecularPower), nightSpecularPower=f("nightSpecularPower",defaults.nightSpecularPower),
            specularAlpha=f("specularAlpha",defaults.specularAlpha), specularRadius=f("specularRadius",defaults.specularRadius),
            specularOffset=f("specularOffset",defaults.specularOffset), highlightMix=f("highlightMix",defaults.highlightMix),
            innerSlope=f("innerSlope",defaults.innerSlope), middleSlope=f("middleSlope",defaults.middleSlope), outerSlope=f("outerSlope",defaults.outerSlope),
            sunHaloAlpha=f("sunHaloAlpha",defaults.sunHaloAlpha), moonHaloAlpha=f("moonHaloAlpha",defaults.moonHaloAlpha),
            haloRadius=f("haloRadius",defaults.haloRadius), haloOffset=f("haloOffset",defaults.haloOffset),
            sunShadowAlpha=f("sunShadowAlpha",defaults.sunShadowAlpha), moonShadowAlpha=f("moonShadowAlpha",defaults.moonShadowAlpha),
            shadowRadius=f("shadowRadius",defaults.shadowRadius), shadowOffset=f("shadowOffset",defaults.shadowOffset),
            sunArcAlpha=f("sunArcAlpha",defaults.sunArcAlpha), moonArcAlpha=f("moonArcAlpha",defaults.moonArcAlpha),
            arcWidth=f("arcWidth",defaults.arcWidth), arcSpanDeg=f("arcSpanDeg",defaults.arcSpanDeg),
            edgeWidth=f("edgeWidth",defaults.edgeWidth), edgeBaseAlpha=f("edgeBaseAlpha",defaults.edgeBaseAlpha),
            edgeLightAlpha=f("edgeLightAlpha",defaults.edgeLightAlpha), girdleWidth=f("girdleWidth",defaults.girdleWidth),
            girdleAlpha=f("girdleAlpha",defaults.girdleAlpha), girdleInnerWidth=f("girdleInnerWidth",defaults.girdleInnerWidth),
            girdleInnerAlpha=f("girdleInnerAlpha",defaults.girdleInnerAlpha), girdleRadius=f("girdleRadius",defaults.girdleRadius),
            girdleInnerRadius=f("girdleInnerRadius",defaults.girdleInnerRadius)
        )
    }
}
