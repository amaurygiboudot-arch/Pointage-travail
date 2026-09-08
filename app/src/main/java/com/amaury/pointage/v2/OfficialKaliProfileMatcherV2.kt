package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import java.text.Normalizer
import java.util.Locale

/** Localise les occurrences compactes de la classification exacte dans un article KALI. */
object OfficialKaliProfileMatcherV2 {
    data class Window(val text: String, val start: Int, val endExclusive: Int)

    fun windows(
        rawText: String,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        before: Int = 180,
        after: Int = 420,
        maxClassificationSpan: Int = 450
    ): List<Window> {
        if (classification.isEmpty()) return emptyList()
        val text = normalize(rawText)
        val positionGroups = classificationPositionGroups(text, classification) ?: return emptyList()
        if (positionGroups.isEmpty() || positionGroups.any { it.isEmpty() }) return emptyList()

        val combinations = compactCombinations(positionGroups, maxClassificationSpan)
        return combinations.mapNotNull { positions ->
            val first = positions.minOrNull() ?: return@mapNotNull null
            val last = positions.maxOrNull() ?: return@mapNotNull null
            val start = (first - before).coerceAtLeast(0)
            val desiredEnd = (last + after).coerceAtMost(text.length)
            val nextScope = classificationAnchorRegex.find(text, (last + 1).coerceAtMost(text.length))
            val end = minOf(desiredEnd, nextScope?.range?.first ?: text.length)
            if (end <= start) return@mapNotNull null
            val window = text.substring(start, end)
            if (statusMatches(window, text, professionalStatus)) Window(window, start, end) else null
        }.distinctBy { it.start to it.endExclusive }
    }

    /**
     * Retourne la fenêtre de portée de la classification exacte autour d'une occurrence métier.
     * La fenêtre s'arrête avant toute nouvelle classification afin qu'un taux/une garantie du
     * coefficient ou niveau suivant ne puisse jamais contaminer la clause courante.
     */
    fun nearestScopeWindow(
        rawText: String,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        targetOffset: Int,
        before: Int = 140,
        after: Int = 420,
        maxClassificationSpan: Int = 450
    ): Window? {
        if (classification.isEmpty()) return null
        val text = normalize(rawText)
        if (targetOffset !in 0..text.length) return null
        val positionGroups = classificationPositionGroups(text, classification) ?: return null
        if (positionGroups.isEmpty() || positionGroups.any { it.isEmpty() }) return null

        val candidates = compactCombinations(positionGroups, maxClassificationSpan)
            .filter { positions -> (positions.maxOrNull() ?: Int.MAX_VALUE) <= targetOffset }
            .sortedByDescending { positions -> positions.maxOrNull() ?: Int.MIN_VALUE }

        for (positions in candidates) {
            val first = positions.minOrNull() ?: continue
            val last = positions.maxOrNull() ?: continue
            val nextScope = classificationAnchorRegex.find(text, (last + 1).coerceAtMost(text.length))
            if (nextScope != null && nextScope.range.first < targetOffset) continue

            val start = (first - before).coerceAtLeast(0)
            val desiredEnd = (targetOffset + after).coerceAtMost(text.length)
            val end = minOf(desiredEnd, nextScope?.range?.first ?: text.length)
            if (end <= targetOffset || end <= start) continue
            val scoped = text.substring(start, end)
            if (statusMatches(scoped, text, professionalStatus)) {
                return Window(scoped, start, end)
            }
        }
        return null
    }

    /**
     * Vérifie qu'une occurrence métier située à targetOffset dépend bien de la classification
     * exacte du salarié et qu'aucune nouvelle portée de classification ne commence entre les deux.
     */
    fun nearestScopeMatches(
        rawText: String,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        targetOffset: Int,
        maxClassificationSpan: Int = 450
    ): Boolean = nearestScopeWindow(
        rawText = rawText,
        classification = classification,
        professionalStatus = professionalStatus,
        targetOffset = targetOffset,
        maxClassificationSpan = maxClassificationSpan
    ) != null

    /**
     * Vérifie le statut professionnel d'une clause sans exiger de classification. Si le texte
     * ne distingue aucun statut, il est considéré général. S'il mélange plusieurs statuts dans
     * la même portée, le résultat reste volontairement faux afin d'éviter toute extrapolation.
     */
    fun statusScopeMatches(rawText: String, professionalStatus: String?): Boolean {
        val text = normalize(rawText)
        return statusMatches(text, text, professionalStatus)
    }

    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun classificationPositionGroups(text: String, value: ConventionClassificationV2): List<List<Int>>? {
        val groups = mutableListOf<List<Int>>()
        fun add(regex: Regex): Boolean {
            val positions = regex.findAll(text).map { it.range.first }.toList()
            if (positions.isEmpty()) return false
            groups += positions
            return true
        }
        value.coefficient?.let {
            if (!add(Regex("\\b(?:coefficient|coef(?:ficient)?)\\s*[:.\\-]?\\s*$it\\b"))) return null
        }
        value.level?.let { if (!add(labelRegex("niveau", it))) return null }
        value.echelon?.let { if (!add(labelRegex("echelon", it))) return null }
        value.position?.let { if (!add(labelRegex("position", it))) return null }
        value.group?.let { if (!add(labelRegex("groupe", it))) return null }
        value.category?.let { if (!add(labelRegex("categorie", it))) return null }
        value.employment?.let {
            val wanted = normalize(it)
            val explicit = Regex("\\b(?:emploi|fonction|poste)\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b")
            val direct = Regex("\\b${Regex.escape(wanted)}\\b")
            val positions = (explicit.findAll(text).map { m -> m.range.first } + direct.findAll(text).map { m -> m.range.first })
                .distinct().toList()
            if (positions.isEmpty()) return null
            groups += positions
        }
        return groups
    }

    private fun compactCombinations(groups: List<List<Int>>, maxSpan: Int): List<List<Int>> {
        if (groups.isEmpty()) return emptyList()
        val candidates = mutableListOf<List<Int>>()
        for (seed in groups.first()) {
            val positions = mutableListOf(seed)
            var min = seed
            var max = seed
            for (group in groups.drop(1)) {
                val nearest = group.minByOrNull { kotlin.math.abs(it - seed) } ?: continue
                positions += nearest
                min = minOf(min, nearest)
                max = maxOf(max, nearest)
            }
            if (positions.size == groups.size && max - min <= maxSpan) candidates += positions
        }
        return candidates.distinctBy { it.sorted().joinToString(",") }
    }

    private fun statusMatches(window: String, wholeText: String, status: String?): Boolean {
        val wanted = status ?: return true
        val wholeNonCadre = nonCadreRegexes.any { it.containsMatchIn(wholeText) }
        val wholeWithoutNonCadre = stripNonCadre(wholeText)
        val wholeCadre = cadreRegex.containsMatchIn(wholeWithoutNonCadre)
        if (!wholeNonCadre && !wholeCadre) return true

        val windowNonCadre = nonCadreRegexes.any { it.containsMatchIn(window) }
        val windowCadre = cadreRegex.containsMatchIn(stripNonCadre(window))
        return when (wanted) {
            "CADRE" -> windowCadre && !windowNonCadre
            "NON_CADRE" -> windowNonCadre && !windowCadre
            else -> false
        }
    }

    private fun stripNonCadre(value: String): String {
        var out = value
        nonCadreRegexes.forEach { out = it.replace(out, " ") }
        return out
    }

    private fun labelRegex(label: String, raw: String): Regex =
        Regex("\\b$label\\s*[:.\\-]?\\s*${Regex.escape(normalize(raw))}\\b")

    private val classificationAnchorRegex = Regex(
        "\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction|poste)s?\\b"
    )
    private val cadreRegex = Regex("\\b(?:cadre|cadres|ingenieur|ingenieurs)\\b")
    private val nonCadreRegexes = listOf(
        Regex("\\bnon[- ]cadres?\\b"),
        Regex("\\bouvriers?\\b"),
        Regex("\\bemployes?\\b"),
        Regex("\\btechniciens?\\b"),
        Regex("\\bagents? de maitrise\\b"),
        Regex("\\betam\\b")
    )
}
