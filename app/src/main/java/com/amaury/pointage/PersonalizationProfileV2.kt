package com.amaury.pointage

import org.json.JSONObject

/** Portable preference contract. OS permissions and business data never belong here. */
data class PersonalizationProfileV2(
    val textScale: Float = 1f,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val readerScale: Float = 1.5f,
    val context: String = "normal",
    val writingAssistance: Boolean = true
) {
    fun validated(): PersonalizationProfileV2 {
        require(textScale.isFinite() && textScale in 1f..2f) { "Taille du texte invalide" }
        require(readerScale.isFinite() && readerScale in 1f..4f) { "Zoom de lecture invalide" }
        require(context in CONTEXTS) { "Contexte inconnu" }
        return this
    }
    val effectiveReduceMotion: Boolean get() = reduceMotion || context == "economy"
    fun encode(): String = JSONObject().put("schemaVersion", 1)
        .put("textScale", textScale.toDouble()).put("highContrast", highContrast)
        .put("reduceMotion", reduceMotion).put("readerScale", readerScale.toDouble())
        .put("context", context).put("writingAssistance", writingAssistance).toString()

    companion object {
        val CONTEXTS = setOf("normal", "work", "home", "night", "economy")
        fun decode(raw: String): PersonalizationProfileV2 {
            require(raw.length <= 4096) { "Profil trop volumineux" }
            val json = JSONObject(raw)
            require(json.opt("schemaVersion") == 1) { "Version de profil non prise en charge" }
            val allowed = setOf("schemaVersion", "textScale", "highContrast", "reduceMotion", "readerScale", "context", "writingAssistance")
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
            require(json.opt("context") is String) { "Contexte attendu" }
            return PersonalizationProfileV2(number("textScale"), boolean("highContrast"),
                boolean("reduceMotion"), number("readerScale"), json.getString("context"), boolean("writingAssistance")).validated()
        }
    }
}
