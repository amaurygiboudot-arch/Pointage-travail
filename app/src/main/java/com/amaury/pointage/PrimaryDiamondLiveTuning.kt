package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import kotlin.math.abs

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

data class DiamondTuningSaveStatus(val ok: Boolean, val key: String, val requested: Float, val persisted: Float, val timestampMs: Long)

object PrimaryDiamondLiveTuning {
    private const val PREFS = "developer_primary_diamond_live"
    private const val META_LAST_KEY = "_last_key"
    private const val META_LAST_REQUESTED = "_last_requested"
    private const val META_LAST_PERSISTED = "_last_persisted"
    private const val META_LAST_OK = "_last_ok"
    private const val META_LAST_TIME = "_last_time"
    val defaults = PrimaryDiamondLiveTuningConfig()
    @Volatile private var cached: PrimaryDiamondLiveTuningConfig? = null

    private val routes = mapOf(
        "radiusScale" to "GÉOMÉTRIE", "pressScale" to "RENDU CANVAS", "saturation" to "MATIÈRE",
        "entryGain" to "MATIÈRE", "pauseGain" to "MATIÈRE", "exitGain" to "MATIÈRE",
        "translucencyScale" to "MOTEUR + RENDU", "baseLuminance" to "MOTEUR OPTIQUE",
        "directWeight" to "MOTEUR OPTIQUE", "internalWeight" to "MOTEUR OPTIQUE",
        "internalRetention" to "MOTEUR OPTIQUE", "responseTau" to "MOTEUR OPTIQUE",
        "sunIntensityScale" to "MOTEUR OPTIQUE", "moonIntensityScale" to "MOTEUR OPTIQUE",
        "daySpecularPower" to "MOTEUR OPTIQUE", "nightSpecularPower" to "MOTEUR OPTIQUE",
        "specularAlpha" to "RENDU CANVAS", "specularRadius" to "RENDU CANVAS", "specularOffset" to "RENDU CANVAS",
        "highlightMix" to "RENDU CANVAS", "innerSlope" to "RENDU CANVAS", "middleSlope" to "RENDU CANVAS", "outerSlope" to "RENDU CANVAS",
        "sunHaloAlpha" to "RENDU CANVAS", "moonHaloAlpha" to "RENDU CANVAS", "haloRadius" to "RENDU CANVAS", "haloOffset" to "RENDU CANVAS",
        "sunShadowAlpha" to "RENDU CANVAS", "moonShadowAlpha" to "RENDU CANVAS", "shadowRadius" to "RENDU CANVAS", "shadowOffset" to "RENDU CANVAS",
        "sunArcAlpha" to "RENDU CANVAS", "moonArcAlpha" to "RENDU CANVAS", "arcWidth" to "RENDU CANVAS", "arcSpanDeg" to "RENDU CANVAS",
        "edgeWidth" to "RENDU CANVAS", "edgeBaseAlpha" to "RENDU CANVAS", "edgeLightAlpha" to "RENDU CANVAS",
        "girdleWidth" to "RENDU CANVAS", "girdleAlpha" to "RENDU CANVAS", "girdleInnerWidth" to "RENDU CANVAS",
        "girdleInnerAlpha" to "RENDU CANVAS", "girdleRadius" to "RENDU CANVAS", "girdleInnerRadius" to "RENDU CANVAS"
    )

    fun current(context: Context): PrimaryDiamondLiveTuningConfig = cached ?: load(context).also { cached = it }

    /** Écriture synchrone puis relecture : une valeur n'est validée que si elle est réellement persistée. */
    fun set(context: Context, key: String, value: Float): Boolean {
        if (key !in routes) return false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val committed = p.edit().putFloat(key, value).putString(META_LAST_KEY, key)
            .putFloat(META_LAST_REQUESTED, value).putLong(META_LAST_TIME, now).commit()
        val persisted = p.getFloat(key, Float.NaN)
        val verified = committed && persisted.isFinite() && abs(persisted - value) <= 0.00001f
        p.edit().putBoolean(META_LAST_OK, verified).putFloat(META_LAST_PERSISTED, persisted).commit()
        cached = load(context)
        return verified
    }

    fun reset(context: Context): Boolean {
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        cached = defaults
        return ok
    }

    fun effectRoute(key: String): String = routes[key] ?: "NON CONNECTÉ"
    fun allConnected(): Boolean = values(defaults).all { effectRoute(it.first) != "NON CONNECTÉ" }
    fun lastSaveStatus(context: Context): DiamondTuningSaveStatus? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = p.getString(META_LAST_KEY, null) ?: return null
        return DiamondTuningSaveStatus(p.getBoolean(META_LAST_OK, false), key, p.getFloat(META_LAST_REQUESTED, Float.NaN), p.getFloat(META_LAST_PERSISTED, Float.NaN), p.getLong(META_LAST_TIME, 0L))
    }

    fun values(c: PrimaryDiamondLiveTuningConfig): List<Pair<String, Float>> = listOf(
        "radiusScale" to c.radiusScale, "pressScale" to c.pressScale, "saturation" to c.saturation, "entryGain" to c.entryGain,
        "pauseGain" to c.pauseGain, "exitGain" to c.exitGain, "translucencyScale" to c.translucencyScale, "baseLuminance" to c.baseLuminance,
        "directWeight" to c.directWeight, "internalWeight" to c.internalWeight, "internalRetention" to c.internalRetention, "responseTau" to c.responseTau,
        "sunIntensityScale" to c.sunIntensityScale, "moonIntensityScale" to c.moonIntensityScale, "daySpecularPower" to c.daySpecularPower, "nightSpecularPower" to c.nightSpecularPower,
        "specularAlpha" to c.specularAlpha, "specularRadius" to c.specularRadius, "specularOffset" to c.specularOffset, "highlightMix" to c.highlightMix,
        "innerSlope" to c.innerSlope, "middleSlope" to c.middleSlope, "outerSlope" to c.outerSlope, "sunHaloAlpha" to c.sunHaloAlpha,
        "moonHaloAlpha" to c.moonHaloAlpha, "haloRadius" to c.haloRadius, "haloOffset" to c.haloOffset, "sunShadowAlpha" to c.sunShadowAlpha,
        "moonShadowAlpha" to c.moonShadowAlpha, "shadowRadius" to c.shadowRadius, "shadowOffset" to c.shadowOffset, "sunArcAlpha" to c.sunArcAlpha,
        "moonArcAlpha" to c.moonArcAlpha, "arcWidth" to c.arcWidth, "arcSpanDeg" to c.arcSpanDeg, "edgeWidth" to c.edgeWidth,
        "edgeBaseAlpha" to c.edgeBaseAlpha, "edgeLightAlpha" to c.edgeLightAlpha, "girdleWidth" to c.girdleWidth, "girdleAlpha" to c.girdleAlpha,
        "girdleInnerWidth" to c.girdleInnerWidth, "girdleInnerAlpha" to c.girdleInnerAlpha, "girdleRadius" to c.girdleRadius, "girdleInnerRadius" to c.girdleInnerRadius
    )

    fun adjustedMaterialColor(context: Context, viewId: Int, fallback: Int): Int {
        val tuning = current(context)
        val gain = when (viewId) { R.id.entryButton -> tuning.entryGain; R.id.pauseButton -> tuning.pauseGain; R.id.exitButton -> tuning.exitGain; else -> 1f }
        val hsv = FloatArray(3); Color.colorToHSV(fallback, hsv)
        hsv[1] = (hsv[1] * tuning.saturation).coerceIn(0f, 1f); hsv[2] = (hsv[2] * gain).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun load(context: Context): PrimaryDiamondLiveTuningConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); fun f(key: String, d: Float) = p.getFloat(key, d)
        return PrimaryDiamondLiveTuningConfig(
            f("radiusScale",defaults.radiusScale),f("pressScale",defaults.pressScale),f("saturation",defaults.saturation),f("entryGain",defaults.entryGain),f("pauseGain",defaults.pauseGain),f("exitGain",defaults.exitGain),
            f("translucencyScale",defaults.translucencyScale),f("baseLuminance",defaults.baseLuminance),f("directWeight",defaults.directWeight),f("internalWeight",defaults.internalWeight),f("internalRetention",defaults.internalRetention),f("responseTau",defaults.responseTau),
            f("sunIntensityScale",defaults.sunIntensityScale),f("moonIntensityScale",defaults.moonIntensityScale),f("daySpecularPower",defaults.daySpecularPower),f("nightSpecularPower",defaults.nightSpecularPower),f("specularAlpha",defaults.specularAlpha),f("specularRadius",defaults.specularRadius),f("specularOffset",defaults.specularOffset),
            f("highlightMix",defaults.highlightMix),f("innerSlope",defaults.innerSlope),f("middleSlope",defaults.middleSlope),f("outerSlope",defaults.outerSlope),f("sunHaloAlpha",defaults.sunHaloAlpha),f("moonHaloAlpha",defaults.moonHaloAlpha),f("haloRadius",defaults.haloRadius),f("haloOffset",defaults.haloOffset),
            f("sunShadowAlpha",defaults.sunShadowAlpha),f("moonShadowAlpha",defaults.moonShadowAlpha),f("shadowRadius",defaults.shadowRadius),f("shadowOffset",defaults.shadowOffset),f("sunArcAlpha",defaults.sunArcAlpha),f("moonArcAlpha",defaults.moonArcAlpha),f("arcWidth",defaults.arcWidth),f("arcSpanDeg",defaults.arcSpanDeg),
            f("edgeWidth",defaults.edgeWidth),f("edgeBaseAlpha",defaults.edgeBaseAlpha),f("edgeLightAlpha",defaults.edgeLightAlpha),f("girdleWidth",defaults.girdleWidth),f("girdleAlpha",defaults.girdleAlpha),f("girdleInnerWidth",defaults.girdleInnerWidth),f("girdleInnerAlpha",defaults.girdleInnerAlpha),f("girdleRadius",defaults.girdleRadius),f("girdleInnerRadius",defaults.girdleInnerRadius)
        )
    }
}
