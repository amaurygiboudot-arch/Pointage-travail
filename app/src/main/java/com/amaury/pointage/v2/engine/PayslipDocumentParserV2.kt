package com.amaury.pointage.v2.engine

import java.text.Normalizer
import java.util.Locale

/**
 * Parseur prudent de bulletin de paie après OCR local.
 *
 * Le texte OCR complet n'est jamais persisté ici. Les montants proposés doivent
 * toujours être confirmés par l'utilisateur avant stockage.
 */
object PayslipDocumentParserV2 {
    const val KEY_GROSS = "Brut"
    const val KEY_NET_BEFORE_TAX = "Net avant PAS"
    const val KEY_NET_TAXABLE = "Net imposable"
    const val KEY_OVERTIME_GROSS = "Heures supplémentaires"
    const val KEY_PREMIUMS_GROSS = "Primes / majorations"
    const val KEY_MEAL_BASKETS = "Paniers"
    const val KEY_MUTUAL_EMPLOYEE = "Mutuelle salariale"
    const val KEY_PROVIDENT_EMPLOYEE = "Prévoyance salariale"

    data class Candidate(
        val amount: Double?,
        val confidence: Double,
        val sourceLabel: String? = null
    ) {
        val highConfidence: Boolean get() = amount != null && confidence >= 0.85
    }

    data class Result(
        val gross: Candidate,
        val netBeforeTax: Candidate,
        val netTaxable: Candidate,
        val overtimeGross: Candidate,
        val premiumsGross: Candidate,
        val mealBaskets: Candidate,
        val mutualEmployee: Candidate,
        val providentEmployee: Candidate,
        val warnings: List<String>
    ) {
        fun confirmedCandidates(): Map<String, Candidate> = linkedMapOf(
            KEY_GROSS to gross,
            KEY_NET_BEFORE_TAX to netBeforeTax,
            KEY_NET_TAXABLE to netTaxable,
            KEY_OVERTIME_GROSS to overtimeGross,
            KEY_PREMIUMS_GROSS to premiumsGross,
            KEY_MEAL_BASKETS to mealBaskets,
            KEY_MUTUAL_EMPLOYEE to mutualEmployee,
            KEY_PROVIDENT_EMPLOYEE to providentEmployee
        )
    }

    fun parse(rawText: String): Result {
        val lines = rawText
            .replace('\u00A0', ' ')
            .lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .take(3_000)
            .toList()

        val gross = singleTotal(lines, ::grossScore)
        val netBeforeTax = singleTotal(lines, ::netBeforeTaxScore)
        val netTaxable = singleTotal(lines, ::netTaxableScore)
        val overtime = aggregateRows(lines, ::overtimeScore)
        val premiums = aggregateRows(lines, ::premiumScore)
        val baskets = aggregateRows(lines, ::basketScore)
        val mutual = singleTotal(lines, ::mutualEmployeeScore)
        val provident = singleTotal(lines, ::providentEmployeeScore)

        val warnings = buildList {
            if (!gross.highConfidence) add("Brut : vérification manuelle nécessaire.")
            if (!netBeforeTax.highConfidence) add("Net avant PAS : non prérempli faute de libellé suffisamment explicite.")
            if (!netTaxable.highConfidence) add("Net imposable : non prérempli faute de libellé suffisamment explicite.")
            if (!mutual.highConfidence) add("Mutuelle salariale : part salarié non identifiée avec certitude.")
            if (!provident.highConfidence) add("Prévoyance salariale : part salarié non identifiée avec certitude.")
        }
        return Result(gross, netBeforeTax, netTaxable, overtime, premiums, baskets, mutual, provident, warnings)
    }

    private fun singleTotal(lines: List<String>, scorer: (String) -> Double): Candidate {
        val found = mutableListOf<Triple<Double, Double, String>>()
        lines.forEach { line ->
            val score = scorer(normalize(line))
            if (score <= 0.0) return@forEach
            val amount = lastMoneyAmount(line) ?: return@forEach
            found += Triple(amount, score, line.take(180))
        }
        if (found.isEmpty()) return Candidate(null, 0.0)
        val sorted = found.sortedByDescending { it.second }
        val best = sorted.first()
        val competing = sorted.drop(1).firstOrNull {
            it.second >= best.second - 0.05 && kotlin.math.abs(it.first - best.first) > 0.02
        }
        return if (competing != null) Candidate(null, (best.second - 0.35).coerceAtLeast(0.0), best.third)
        else Candidate(best.first, best.second.coerceIn(0.0, 1.0), best.third)
    }

    /**
     * Pour les heures sup / primes / paniers, la dernière valeur monétaire de la ligne
     * est considérée comme le montant de ligne. Quantité et taux peuvent la précéder.
     */
    private fun aggregateRows(lines: List<String>, scorer: (String) -> Double): Candidate {
        val rows = lines.mapNotNull { line ->
            val normalized = normalize(line)
            val score = scorer(normalized)
            if (score < 0.85) return@mapNotNull null
            val amount = lastMoneyAmount(line) ?: return@mapNotNull null
            Triple(amount, score, line.take(180))
        }
        if (rows.isEmpty()) return Candidate(null, 0.0)
        val distinct = rows.distinctBy { it.third }
        return Candidate(
            amount = distinct.sumOf { it.first },
            confidence = distinct.minOf { it.second }.coerceIn(0.0, 1.0),
            sourceLabel = distinct.joinToString(" • ") { it.third }.take(300)
        )
    }

    private fun grossScore(text: String): Double = when {
        text.contains("net") -> 0.0
        text.contains("total brut") -> 1.0
        text.contains("salaire brut") && (text.contains("total") || text.startsWith("salaire brut")) -> 0.95
        text.matches(Regex(".*\\bbrut\\s+(mensuel|soumis|fiscal)\\b.*")) -> 0.88
        else -> 0.0
    }

    private fun netBeforeTaxScore(text: String): Double = when {
        text.contains("net a payer avant impot sur le revenu") -> 1.0
        text.contains("net a payer avant impot") -> 0.98
        text.contains("net avant impot") && !text.contains("imposable") -> 0.92
        else -> 0.0
    }

    private fun netTaxableScore(text: String): Double = when {
        text.contains("net imposable") -> 1.0
        text.contains("net fiscal") -> 0.95
        else -> 0.0
    }

    private fun overtimeScore(text: String): Double = when {
        text.contains("heure") && (text.contains("supplementaire") || Regex("\\bhs\\b").containsMatchIn(text)) -> 0.95
        else -> 0.0
    }

    private fun premiumScore(text: String): Double {
        if (overtimeScore(text) > 0.0 || basketScore(text) > 0.0) return 0.0
        if (text.contains("prime") || text.contains("majoration")) {
            if (text.contains("total") && !text.contains("prime")) return 0.0
            return 0.90
        }
        return 0.0
    }

    private fun basketScore(text: String): Double = when {
        text.contains("panier") -> 0.95
        text.contains("indemnite repas") || text.contains("indemnite de repas") -> 0.92
        else -> 0.0
    }

    private fun mutualEmployeeScore(text: String): Double {
        val mutual = text.contains("mutuelle") || text.contains("complementaire sante") || text.contains("frais de sante")
        if (!mutual) return 0.0
        val employee = text.contains("salarie") || text.contains("salariale") || text.contains("part sal")
        return if (employee) 0.92 else 0.0
    }

    private fun providentEmployeeScore(text: String): Double {
        if (!text.contains("prevoyance")) return 0.0
        val employee = text.contains("salarie") || text.contains("salariale") || text.contains("part sal")
        return if (employee) 0.92 else 0.0
    }

    private fun lastMoneyAmount(raw: String): Double? {
        val regex = Regex(
            "(?<![\\d/])([+-]?\\d{1,3}(?:[ .\\u00A0]\\d{3})*|[+-]?\\d+)(?:[,.](\\d{2}))?\\s*(€|eur|euros?)?",
            RegexOption.IGNORE_CASE
        )
        val candidates = regex.findAll(raw).mapNotNull { match ->
            val tail = raw.substring(match.range.last + 1).trimStart()
            if (tail.startsWith("%") || tail.startsWith("/") || tail.startsWith("h", ignoreCase = true)) return@mapNotNull null
            val token = match.value.trim()
            val hasCurrency = match.groupValues[3].isNotBlank()
            val hasDecimals = match.groupValues[2].isNotBlank()
            // Sans symbole monétaire, on exige deux décimales pour éviter heures, coefficients et dates.
            if (!hasCurrency && !hasDecimals) return@mapNotNull null
            val integer = match.groupValues[1].replace(" ", "").replace(".", "").replace("\u00A0", "")
            val decimals = match.groupValues[2].ifBlank { "00" }
            "$integer.$decimals".toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10_000_000.0 }
        }.toList()
        return candidates.lastOrNull()
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}+"), "").replace('’', '\'')
    }
}
