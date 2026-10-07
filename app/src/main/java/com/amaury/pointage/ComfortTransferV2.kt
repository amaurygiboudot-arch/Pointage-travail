package com.amaury.pointage

import org.json.JSONObject
import org.json.JSONTokener

/** Explicit shared subset; native theme, size, context and writing settings remain local. */
data class ComfortTransferV2(
    val highContrast: Boolean,
    val reduceMotion: Boolean,
    val readerScale: Float,
    val nightSchedule: NightSchedule? = null
) {
    data class NightSchedule(val enabled: Boolean, val startMinute: Int, val endMinute: Int) {
        init {
            require(startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute) { "Horaire de nuit invalide" }
        }
    }

    fun applyTo(current: PersonalizationProfileV2): PersonalizationProfileV2 = current.copy(
        highContrast = highContrast, reduceMotion = reduceMotion, readerScale = readerScale,
        nightScheduleEnabled = nightSchedule?.enabled ?: current.nightScheduleEnabled,
        nightStartMinute = nightSchedule?.startMinute ?: current.nightStartMinute,
        nightEndMinute = nightSchedule?.endMinute ?: current.nightEndMinute
    ).validated()

    /** Both local import and account restore show exactly the fields that will be applied. */
    fun preview(): String {
        fun hour(minute: Int) = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
        val schedule = nightSchedule?.let {
            "Nuit automatique : ${if (it.enabled) "activée" else "désactivée"}, ${hour(it.startMinute)}–${hour(it.endMinute)}. Ces heures suivent le fuseau horaire local de l’appareil destinataire."
        } ?: "Ancienne sauvegarde : l’activation et les horaires de nuit de cet appareil seront conservés."
        return "Contraste renforcé : ${if (highContrast) "oui" else "non"}. Mouvements réduits : ${if (reduceMotion) "oui" else "non"}. Zoom de lecture : ${(readerScale * 100).toInt()} %. $schedule"
    }

    fun encode(): String {
        require(readerScale.isFinite() && readerScale in 1f..4f)
        val json = JSONObject().put("format", FORMAT).put("version", if (nightSchedule == null) 1 else 2)
            .put("highContrast", highContrast).put("reduceMotion", reduceMotion)
            .put("readerScale", readerScale.toDouble())
        nightSchedule?.let {
            json.put("nightScheduleEnabled", it.enabled).put("nightStartMinute", it.startMinute)
                .put("nightEndMinute", it.endMinute)
        }
        return json.toString()
    }

    companion object {
        const val FORMAT = "agkgmg.comfort"
        fun from(profile: PersonalizationProfileV2) = ComfortTransferV2(
            profile.highContrast, profile.reduceMotion, profile.readerScale,
            NightSchedule(profile.nightScheduleEnabled, profile.nightStartMinute, profile.nightEndMinute)
        )
        fun decode(raw: String): ComfortTransferV2 {
            require(raw.toByteArray(Charsets.UTF_8).size <= 4096) { "Fichier partagé trop volumineux" }
            val parser = JSONTokener(raw)
            val json = parser.nextValue() as? JSONObject ?: error("Objet JSON attendu")
            require(parser.nextClean().code == 0) { "Contenu après le profil" }
            val version = json.opt("version")
            require(json.opt("format") == FORMAT && version is Number && version.toDouble() in setOf(1.0, 2.0)) { "Format partagé incompatible" }
            val baseKeys = setOf("format", "version", "highContrast", "reduceMotion", "readerScale")
            val keys = if (version.toDouble() == 2.0) baseKeys + setOf("nightScheduleEnabled", "nightStartMinute", "nightEndMinute") else baseKeys
            require(json.keys().asSequence().toSet() == keys) { "Réglages partagés incomplets ou inconnus" }
            val contrast = json.opt("highContrast")
            val motion = json.opt("reduceMotion")
            val scale = json.opt("readerScale")
            require(contrast is Boolean && motion is Boolean && scale is Number) { "Type de réglage incorrect" }
            val value = scale.toDouble()
            require(value.isFinite() && value in 1.0..4.0) { "Zoom partagé invalide" }
            val schedule = if (version.toDouble() == 2.0) {
                val enabled = json.opt("nightScheduleEnabled")
                require(enabled is Boolean) { "Activation de nuit invalide" }
                fun minute(key: String): Int {
                    val number = json.opt(key)
                    require(number is Number && number.toDouble().isFinite() && number.toDouble() in 0.0..1439.0 && number.toDouble() == number.toInt().toDouble()) { "Horaire de nuit invalide" }
                    return number.toInt()
                }
                NightSchedule(enabled, minute("nightStartMinute"), minute("nightEndMinute"))
            } else null
            return ComfortTransferV2(contrast, motion, value.toFloat(), schedule)
        }
    }
}
