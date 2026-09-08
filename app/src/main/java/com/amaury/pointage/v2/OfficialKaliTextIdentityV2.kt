package com.amaury.pointage.v2

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Identité formelle d'un KALITEXT consulté. Ne déduit jamais une date d'accord depuis une
 * simple date d'effet : seules des métadonnées explicitement nommées signature/conclusion/accord
 * ou une date explicitement portée par le titre "Accord/Avenant/Convention du ..." sont retenues.
 */
object OfficialKaliTextIdentityV2 {
    data class Identity(
        val textId: String,
        val title: String?,
        val agreementDate: LocalDate?,
        val source: String
    ) {
        val reliableForCrossSourceScope: Boolean
            get() = textId.matches(Regex("^KALITEXT\\d+$")) && !title.isNullOrBlank() && agreementDate != null
    }

    data class Diagnostic(
        val identity: Identity?,
        val reasons: List<String>
    )

    fun parse(data: Any?, expectedTextId: String): Diagnostic {
        val expected = expectedTextId.trim().uppercase(Locale.ROOT)
        if (!expected.matches(Regex("^KALITEXT\\d+$"))) {
            return Diagnostic(null, listOf("KALI identité texte : KALITEXT attendu invalide."))
        }
        val root = data as? Map<*, *> ?: return Diagnostic(null, listOf("KALI identité texte : réponse non structurée."))
        val textMap = findMap(root, expected) ?: return Diagnostic(
            null,
            listOf("KALI identité texte : la réponse ne contient pas exactement $expected.")
        )

        val title = firstString(textMap, "titre", "title", "libelle", "intitule", "nature")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val metadataDates = explicitAgreementDateKeys.mapNotNull { key -> firstDate(textMap, key) }.distinct()
        val titleDates = title.orEmpty().let(::datesFromFormalTitle).distinct()
        val allDates = (metadataDates + titleDates).distinct()
        val date = allDates.singleOrNull()

        return Diagnostic(
            identity = Identity(
                textId = expected,
                title = title,
                agreementDate = date,
                source = "Légifrance KALI — $expected"
            ),
            reasons = buildList {
                if (title == null) add("KALI identité texte : titre officiel absent ; rapprochement APEC bloqué.")
                if (allDates.isEmpty()) add("KALI identité texte : date d'accord/signature non prouvée ; rapprochement APEC bloqué.")
                if (allDates.size > 1) add("KALI identité texte : plusieurs dates d'accord contradictoires ; rapprochement APEC bloqué.")
            }
        )
    }

    private fun findMap(value: Any?, expected: String, depth: Int = 0): Map<*, *>? {
        if (depth > 18) return null
        return when (value) {
            is Map<*, *> -> {
                val id = firstString(value, "id", "cid")?.uppercase(Locale.ROOT)
                if (id == expected) value
                else value.values.firstNotNullOfOrNull { child -> findMap(child, expected, depth + 1) }
            }
            is List<*> -> value.firstNotNullOfOrNull { child -> findMap(child, expected, depth + 1) }
            else -> null
        }
    }

    private fun firstString(map: Map<*, *>, vararg keys: String): String? {
        val wanted = keys.map { it.lowercase(Locale.ROOT) }.toSet()
        return map.entries.firstNotNullOfOrNull { (key, value) ->
            if (key?.toString()?.lowercase(Locale.ROOT) !in wanted) return@firstNotNullOfOrNull null
            when (value) {
                is String -> value
                is Number -> value.toString()
                else -> null
            }
        }
    }

    private fun firstDate(map: Map<*, *>, key: String): LocalDate? {
        val value = map.entries.firstOrNull { it.key?.toString()?.equals(key, ignoreCase = true) == true }?.value ?: return null
        return parseDateValue(value)
    }

    private fun parseDateValue(value: Any?): LocalDate? = when (value) {
        is Number -> runCatching {
            Instant.ofEpochMilli(value.toLong()).atZone(ZoneId.of("Europe/Paris")).toLocalDate()
        }.getOrNull()
        is String -> parseDateString(value)
        else -> null
    }

    private fun parseDateString(raw: String): LocalDate? {
        val value = raw.trim()
        runCatching { LocalDate.parse(value.take(10)) }.getOrNull()?.let { return it }
        numericDateRegex.find(value)?.let { match ->
            return date(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        frenchDateRegex.find(OfficialKaliProfileMatcherV2.normalize(value))?.let { match ->
            return date(match.groupValues[1], monthNumber(match.groupValues[2])?.toString().orEmpty(), match.groupValues[3])
        }
        return null
    }

    private fun datesFromFormalTitle(raw: String): List<LocalDate> {
        val text = OfficialKaliProfileMatcherV2.normalize(raw)
        return formalTitleDateRegex.findAll(text).mapNotNull { match ->
            val month = match.groupValues[2].toIntOrNull() ?: monthNumber(match.groupValues[2]) ?: return@mapNotNull null
            date(match.groupValues[1], month.toString(), match.groupValues[3])
        }.toList()
    }

    private fun date(day: String, month: String, year: String): LocalDate? = runCatching {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    }.getOrNull()

    private fun monthNumber(raw: String): Int? = when (OfficialKaliProfileMatcherV2.normalize(raw).uppercase(Locale.FRANCE)) {
        "JANVIER" -> 1; "FEVRIER" -> 2; "MARS" -> 3; "AVRIL" -> 4; "MAI" -> 5; "JUIN" -> 6
        "JUILLET" -> 7; "AOUT" -> 8; "SEPTEMBRE" -> 9; "OCTOBRE" -> 10; "NOVEMBRE" -> 11; "DECEMBRE" -> 12
        else -> null
    }

    private val explicitAgreementDateKeys = listOf(
        "dateSignature", "dateSignatureTexte", "dateConclusion", "dateAccord"
    )
    private val numericDateRegex = Regex("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})")
    private val frenchDateRegex = Regex("(\\d{1,2})\\s+(janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)\\s+(\\d{4})")
    private val formalTitleDateRegex = Regex("\\b(?:accord|avenant|convention)[^.;]{0,120}?\\bdu\\s+(\\d{1,2})\\s+(\\d{1,2}|janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)\\s+(\\d{4})")
}
