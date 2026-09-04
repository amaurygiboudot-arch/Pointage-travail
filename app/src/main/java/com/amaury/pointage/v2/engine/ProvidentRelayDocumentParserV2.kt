package com.amaury.pointage.v2.engine

import java.text.Normalizer
import java.util.Locale

/**
 * Extraction locale et prudente des trois montants nécessaires au contrôle du relais prévoyance.
 *
 * Le texte OCR complet n'est jamais stocké par cette couche. Un montant n'est proposé que si une
 * ligne (ou une ligne + sa voisine) contient des libellés suffisamment explicites et un seul montant.
 * L'utilisateur doit toujours confirmer les valeurs avant qu'elles soient utilisées par HoraTrack.
 */
object ProvidentRelayDocumentParserV2 {
    data class Candidate(
        val amount: Double?,
        val confidence: Double,
        val matchedLabel: String? = null
    ) {
        val highConfidence: Boolean get() = amount != null && confidence >= 0.85
    }

    data class Result(
        val targetGross60: Candidate,
        val socialSecurityGross: Candidate,
        val observedProvidentGross: Candidate,
        val warnings: List<String>
    ) {
        val allHighConfidence: Boolean
            get() = targetGross60.highConfidence && socialSecurityGross.highConfidence && observedProvidentGross.highConfidence
    }

    private data class Scored(val amount: Double, val score: Double, val label: String)

    fun parse(rawText: String): Result {
        val lines = rawText
            .replace('\u00A0', ' ')
            .lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .take(2_000)
            .toList()

        val windows = buildList {
            lines.forEachIndexed { index, line ->
                add(line)
                if (index + 1 < lines.size) add("$line ${lines[index + 1]}")
            }
        }.distinct()

        val target = best(windows, ::targetScore)
        val ss = best(windows, ::socialSecurityScore)
        val provident = best(windows, ::providentScore)
        val warnings = buildList {
            if (!target.highConfidence) add("60 % du salaire brut de référence : extraction automatique insuffisamment sûre.")
            if (!ss.highConfidence) add("Prestations Sécurité sociale brutes : extraction automatique insuffisamment sûre.")
            if (!provident.highConfidence) add("Prestation prévoyance brute observée : extraction automatique insuffisamment sûre.")
        }
        return Result(target, ss, provident, warnings)
    }

    private fun best(windows: List<String>, scorer: (String) -> Double): Candidate {
        val found = mutableListOf<Scored>()
        windows.forEach { original ->
            val score = scorer(normalize(original))
            if (score <= 0.0) return@forEach
            val amounts = extractMoneyAmounts(original)
            if (amounts.size != 1) return@forEach
            found += Scored(amounts.first(), score, original.take(180))
        }
        if (found.isEmpty()) return Candidate(null, 0.0)
        val sorted = found.sortedByDescending { it.score }
        val best = sorted.first()
        val competing = sorted.drop(1).firstOrNull { it.score >= best.score - 0.05 && kotlin.math.abs(it.amount - best.amount) > 0.01 }
        return if (competing != null) {
            Candidate(null, (best.score - 0.35).coerceAtLeast(0.0), best.label)
        } else {
            Candidate(best.amount, best.score.coerceIn(0.0, 1.0), best.label)
        }
    }

    private fun targetScore(text: String): Double {
        val has60 = Regex("(^|\\D)60\\s*%($|\\D)").containsMatchIn(text)
        if (!has60) return 0.0
        val hasGross = text.contains("brut")
        val hasSalary = text.contains("salaire") || text.contains("remuneration")
        val hasReference = text.contains("reference") || text.contains("base") || text.contains("garantie") || text.contains("minimum")
        return when {
            hasGross && hasSalary && hasReference -> 1.0
            hasGross && hasSalary -> 0.95
            hasGross && hasReference -> 0.90
            hasSalary && hasReference -> 0.82
            else -> 0.0
        }
    }

    private fun socialSecurityScore(text: String): Double {
        val strong = text.contains("securite sociale") || text.contains("prestations ss") ||
            text.contains("indemnites journalieres") || text.contains("indemnite journaliere")
        val ijss = Regex("(^|\\W)ijss($|\\W)").containsMatchIn(text)
        if (!strong && !ijss) return 0.0
        val gross = text.contains("brut") || text.contains("brutes")
        val deducted = text.contains("deduit") || text.contains("deduction") || text.contains("prestations") || text.contains("ijss")
        return when {
            strong && gross && deducted -> 1.0
            (strong || ijss) && gross -> 0.92
            strong && deducted -> 0.84
            ijss -> 0.75
            else -> 0.0
        }
    }

    private fun providentScore(text: String): Double {
        if (!text.contains("prevoyance")) return 0.0
        if (Regex("(^|\\D)60\\s*%($|\\D)").containsMatchIn(text) && (text.contains("minimum") || text.contains("garantie"))) {
            return 0.0
        }
        val observed = text.contains("verse") || text.contains("versee") || text.contains("prestation") ||
            text.contains("indemnite") || text.contains("incapacite") || text.contains("montant")
        val gross = text.contains("brut") || text.contains("brute")
        return when {
            observed && gross -> 1.0
            observed -> 0.88
            gross -> 0.78
            else -> 0.0
        }
    }

    private fun extractMoneyAmounts(raw: String): List<Double> {
        val regex = Regex("(?<![\\d/])([+-]?\\d{1,3}(?:[ .\\u00A0]\\d{3})*|[+-]?\\d+)(?:[,.](\\d{2}))\\s*(?:€|eur|euros?)?", RegexOption.IGNORE_CASE)
        return regex.findAll(raw).mapNotNull { match ->
            val tail = raw.substring(match.range.last + 1).trimStart()
            if (tail.startsWith("%")) return@mapNotNull null
            val integer = match.groupValues[1].replace(" ", "").replace(".", "").replace("\u00A0", "")
            val decimals = match.groupValues[2]
            "$integer.$decimals".toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10_000_000.0 }
        }.distinct().toList()
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}+"), "").replace('’', '\'')
    }
}
