package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DiamondDesignerLibrary {
    data class Preset(
        val name: String,
        val type: DiamondDesignerCanvas.ElementType,
        val width: Float,
        val height: Float,
        val rotation: Float,
        val alpha: Float,
        val lensStrength: Float,
        val lightAngle: Float,
        val ring1Gain: Float,
        val ring2Gain: Float,
        val ring3Gain: Float,
        val frameWidth: Float,
        val cornerRadius: Float
    )

    private const val PREFS = "diamond_designer_library"
    private const val KEY = "presets"

    fun load(context: Context): MutableList<Preset> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw.isNullOrBlank()) return defaultPresets().toMutableList()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrElse { defaultPresets().toMutableList() }
    }

    fun save(context: Context, presets: List<Preset>) {
        val arr = JSONArray()
        presets.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun presetFromElement(name: String, e: DiamondDesignerCanvas.DesignElement) = Preset(
        name = name,
        type = e.type,
        width = e.width,
        height = e.height,
        rotation = e.rotation,
        alpha = e.alpha,
        lensStrength = e.lensStrength,
        lightAngle = e.lightAngle,
        ring1Gain = e.ring1Gain,
        ring2Gain = e.ring2Gain,
        ring3Gain = e.ring3Gain,
        frameWidth = e.frameWidth,
        cornerRadius = e.cornerRadius
    )

    private fun defaultPresets() = listOf(
        Preset("Entrée diamant vert", DiamondDesignerCanvas.ElementType.ENTRY_BUTTON, 300f, 300f, 0f, 1f, .50f, 305f, 1f, 1f, 1f, 12f, 24f),
        Preset("Pause diamant orange", DiamondDesignerCanvas.ElementType.PAUSE_BUTTON, 300f, 300f, 0f, 1f, .50f, 305f, 1f, 1f, 1f, 12f, 24f),
        Preset("Sortie diamant rouge", DiamondDesignerCanvas.ElementType.EXIT_BUTTON, 300f, 300f, 0f, 1f, .50f, 305f, 1f, 1f, 1f, 12f, 24f),
        Preset("Cadre fin", DiamondDesignerCanvas.ElementType.FRAME, 520f, 260f, 0f, .9f, .5f, 305f, 1f, 1f, 1f, 8f, 22f),
        Preset("Cadre 24", DiamondDesignerCanvas.ElementType.FRAME, 520f, 260f, 0f, 1f, .5f, 305f, 1f, 1f, 1f, 24f, 28f),
        Preset("Fond sombre", DiamondDesignerCanvas.ElementType.BACKGROUND, 700f, 900f, 0f, 1f, .5f, 305f, 1f, 1f, 1f, 0f, 0f)
    )

    private fun toJson(p: Preset) = JSONObject().apply {
        put("name", p.name); put("type", p.type.name); put("width", p.width); put("height", p.height)
        put("rotation", p.rotation); put("alpha", p.alpha); put("lens", p.lensStrength); put("light", p.lightAngle)
        put("r1", p.ring1Gain); put("r2", p.ring2Gain); put("r3", p.ring3Gain)
        put("frameWidth", p.frameWidth); put("cornerRadius", p.cornerRadius)
    }

    private fun fromJson(o: JSONObject) = Preset(
        name = o.optString("name", "Preset"),
        type = runCatching { DiamondDesignerCanvas.ElementType.valueOf(o.optString("type")) }.getOrDefault(DiamondDesignerCanvas.ElementType.ENTRY_BUTTON),
        width = o.optDouble("width", 300.0).toFloat(), height = o.optDouble("height", 300.0).toFloat(),
        rotation = o.optDouble("rotation", 0.0).toFloat(), alpha = o.optDouble("alpha", 1.0).toFloat(),
        lensStrength = o.optDouble("lens", .5).toFloat(), lightAngle = o.optDouble("light", 305.0).toFloat(),
        ring1Gain = o.optDouble("r1", 1.0).toFloat(), ring2Gain = o.optDouble("r2", 1.0).toFloat(), ring3Gain = o.optDouble("r3", 1.0).toFloat(),
        frameWidth = o.optDouble("frameWidth", 12.0).toFloat(), cornerRadius = o.optDouble("cornerRadius", 24.0).toFloat()
    )
}
