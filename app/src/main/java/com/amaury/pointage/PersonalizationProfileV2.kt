package com.amaury.pointage

import org.json.JSONObject

/** Portable preference contract. OS permissions and business data never belong here. */
data class PersonalizationProfileV2(
    val textScale: Float = 1f,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val readerScale: Float = 1.5f,
    val context: String = "normal",
    val writingAssistance: Boolean = true,
    val nightScheduleEnabled: Boolean = false,
    val nightStartMinute: Int = 1320,
    val nightEndMinute: Int = 420
) {
    fun validated(): PersonalizationProfileV2 {
        require(textScale.isFinite() && textScale in 1f..2f) { "Taille du texte invalide" }
        require(readerScale.isFinite() && readerScale in 1f..4f) { "Zoom de lecture invalide" }
        require(context in CONTEXTS) { "Contexte inconnu" }
        require(nightStartMinute in 0..1439 && nightEndMinute in 0..1439 && nightStartMinute != nightEndMinute) { "Horaire de nuit invalide" }
        return this
    }
    val effectiveReduceMotion: Boolean get() = reduceMotion || context == "economy"
    fun effectiveContextAt(minute: Int): String = NightContextPolicyV2.resolve(context, nightScheduleEnabled, nightStartMinute, nightEndMinute, minute)
    fun encode(): String = JSONObject().put("schemaVersion", 1)
        .put("textScale", textScale.toDouble()).put("highContrast", highContrast)
        .put("reduceMotion", reduceMotion).put("readerScale", readerScale.toDouble())
        .put("context", context).put("writingAssistance", writingAssistance)
        .put("nightScheduleEnabled", nightScheduleEnabled).put("nightStartMinute", nightStartMinute)
        .put("nightEndMinute", nightEndMinute).toString()

    companion object {
        val CONTEXTS = setOf("normal", "work", "home", "night", "economy")
        fun decode(raw: String): PersonalizationProfileV2 {
            require(raw.length <= 4096) { "Profil trop volumineux" }
            val json = JSONObject(raw)
            require(json.opt("schemaVersion") == 1) { "Version de profil non prise en charge" }
            val allowed = setOf("schemaVersion", "textScale", "highContrast", "reduceMotion", "readerScale", "context", "writingAssistance", "nightScheduleEnabled", "nightStartMinute", "nightEndMinute")
            require(json.keys().asSequence().all { it in allowed }) { "Réglage inconnu" }
            fun number(key: String): Float {
                val value = json.opt(key)
                require(value is Number) { "Nombre attendu : $key" }
                return value.toFloat()
            }
            fun boolean(key: String): Boolean {
                val value = json.opt(key)
                require(value is Boolean) { "Booléen attendu : $key" }
                return value
            }
            fun optionalMinute(key: String, fallback: Int): Int {
                if (!json.has(key)) return fallback
                val value = json.opt(key)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..1439.0 && value.toDouble() == value.toInt().toDouble()) { "Horaire invalide" }
                return value.toInt()
            }
            require(json.opt("context") is String) { "Contexte attendu" }
            return PersonalizationProfileV2(number("textScale"), boolean("highContrast"),
                boolean("reduceMotion"), number("readerScale"), json.getString("context"), boolean("writingAssistance"),
                if (json.has("nightScheduleEnabled")) boolean("nightScheduleEnabled") else false,
                optionalMinute("nightStartMinute", 1320), optionalMinute("nightEndMinute", 420)).validated()
        }
    }
}
