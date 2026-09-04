package com.amaury.pointage.v2

import org.json.JSONArray
import org.json.JSONObject

/** Extrait les IDCC de l'Annuaire des entreprises en privilégiant le SIRET demandé. */
object EnterpriseIdccParserV2 {
    private val directKeys = listOf(
        "idcc",
        "idcc_principal",
        "numero_idcc",
        "id_convention_collective"
    )
    private val listKeys = listOf("liste_idcc", "idccs")

    fun find(result: JSONObject, preferredSiret: String = ""): List<String> =
        find(toPlainMap(result), preferredSiret)

    fun find(result: Map<String, Any?>, preferredSiret: String = ""): List<String> {
        val seat = result.objectValue("siege")
        val matching = result.objectList("matching_etablissements")
        val expectedSiret = preferredSiret.filter(Char::isDigit).takeIf { it.length == 14 }
        val preferred = expectedSiret?.let { wanted ->
            matching.firstOrNull { it.stringValue("siret").filter(Char::isDigit) == wanted }
                ?: seat?.takeIf { it.stringValue("siret").filter(Char::isDigit) == wanted }
        }

        val sources = buildList {
            preferred?.let { add(it) }
            seat?.let { add(it) }
            result.objectValue("complements")?.let { add(it) }
            add(result)
            matching.forEach { add(it) }
        }
        val values = linkedSetOf<String>()
        sources.forEach { collect(it, values) }
        return values.toList()
    }

    fun normalize(value: Any?): String? {
        val digits = value?.toString().orEmpty().filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0) return null
        val normalized = number.toString().padStart(4, '0')
        // 9999 signale l'absence de convention exploitable et n'est pas un conteneur KALI.
        return normalized.takeUnless { it == "9999" }
    }

    private fun collect(source: Map<String, Any?>, out: MutableSet<String>) {
        directKeys.forEach { key -> normalize(source[key])?.let(out::add) }
        listKeys.forEach { key -> source.valueList(key).forEach { normalize(it)?.let(out::add) } }
        source.objectList("conventions_collectives").forEach { convention ->
            directKeys.forEach { key -> normalize(convention[key])?.let(out::add) }
        }
    }

    private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?>? =
        (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

    private fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>> =
        valueList(key).mapNotNull { value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }

    private fun Map<String, Any?>.valueList(key: String): List<Any?> = when (val value = this[key]) {
        is List<*> -> value
        is Array<*> -> value.toList()
        null -> emptyList()
        else -> listOf(value)
    }

    private fun Map<String, Any?>.stringValue(key: String): String = this[key]?.toString().orEmpty()

    private fun toPlainMap(source: JSONObject): Map<String, Any?> = buildMap {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, toPlainValue(source.opt(key)))
        }
    }

    private fun toPlainValue(value: Any?): Any? = when (value) {
        is JSONObject -> toPlainMap(value)
        is JSONArray -> buildList {
            for (index in 0 until value.length()) add(toPlainValue(value.opt(index)))
        }
        JSONObject.NULL -> null
        else -> value
    }
}
