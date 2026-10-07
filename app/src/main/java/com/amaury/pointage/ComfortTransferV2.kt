package com.amaury.pointage

import org.json.JSONObject
import org.json.JSONTokener

/** Explicit shared subset; native theme, size, context and writing settings remain local. */
data class ComfortTransferV2(val highContrast: Boolean, val reduceMotion: Boolean, val readerScale: Float) {
    fun applyTo(current: PersonalizationProfileV2): PersonalizationProfileV2 = current.copy(
        highContrast = highContrast, reduceMotion = reduceMotion, readerScale = readerScale
    ).validated()

    fun encode(): String {
        require(readerScale.isFinite() && readerScale in 1f..4f)
        return JSONObject().put("format", FORMAT).put("version", 1)
            .put("highContrast", highContrast).put("reduceMotion", reduceMotion)
            .put("readerScale", readerScale.toDouble()).toString()
    }

    companion object {
        const val FORMAT = "agkgmg.comfort"
        fun from(profile: PersonalizationProfileV2) = ComfortTransferV2(profile.highContrast, profile.reduceMotion, profile.readerScale)
        fun decode(raw: String): ComfortTransferV2 {
            require(raw.toByteArray(Charsets.UTF_8).size <= 4096) { "Fichier partagé trop volumineux" }
            val parser = JSONTokener(raw)
            val json = parser.nextValue() as? JSONObject ?: error("Objet JSON attendu")
            require(parser.nextClean().code == 0) { "Contenu après le profil" }
            val keys = setOf("format", "version", "highContrast", "reduceMotion", "readerScale")
            require(json.keys().asSequence().toSet() == keys) { "Réglages partagés incomplets ou inconnus" }
            val version = json.opt("version")
            require(json.opt("format") == FORMAT && version is Number && version.toDouble() == 1.0) { "Format partagé incompatible" }
            val contrast = json.opt("highContrast")
            val motion = json.opt("reduceMotion")
            val scale = json.opt("readerScale")
            require(contrast is Boolean && motion is Boolean && scale is Number) { "Type de réglage incorrect" }
            val value = scale.toDouble()
            require(value.isFinite() && value in 1.0..4.0) { "Zoom partagé invalide" }
            return ComfortTransferV2(contrast, motion, value.toFloat())
        }
    }
}
