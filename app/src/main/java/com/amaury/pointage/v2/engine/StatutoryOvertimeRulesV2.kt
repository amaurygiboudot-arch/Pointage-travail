package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.OfficialLegalCodeSourceV2
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Structure uniquement le barème supplétif dont le texte LEGI vérifié confirme encore explicitement
 * les taux de 25 % puis 50 %. Si le texte officiel change, le parseur cesse de produire une règle
 * au lieu de conserver silencieusement une constante devenue obsolète.
 */
object StatutoryOvertimeRulesV2 {
    const val FALLBACK_ARTICLE = "L3121-36"
    const val AGREEMENT_ARTICLE = "L3121-33"

    data class Rule(
        val tiers: List<OvertimeTierV2>,
        val articleId: String,
        val articleNumber: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?
    ) {
        val fingerprint: String = tiers.joinToString("|") {
            "${it.fromMinutes}:${it.toMinutes ?: -1}:${it.multiplier}"
        }
    }

    /**
     * L3121-36 : à défaut d'accord, +25 % pour les huit premières heures supplémentaires,
     * puis +50 % pour les suivantes.
     */
    fun fallbackRule(
        records: List<LegalPayrollSourceStoreV2.Record>,
        referenceDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Rule? {
        val record = records
            .filter { it.topic == OfficialLegalCodeSourceV2.Topic.OVERTIME }
            .filter { normalizeArticleNumber(it.articleNumber) == FALLBACK_ARTICLE }
            .filter { applies(it, referenceDate, zoneId) }
            .maxByOrNull { it.checkedAtMs }
            ?: return null

        val text = normalize(record.excerpt)
        if (!containsPercent(text, 25) || !containsPercent(text, 50)) return null
        if (!text.contains("huit premiere") && !text.contains("8 premiere")) return null

        return Rule(
            tiers = listOf(
                // 36e à 43e heure incluses : après 35 h et jusqu'à 43 h.
                OvertimeTierV2(fromMinutes = 35 * 60, toMinutes = 43 * 60, multiplier = 1.25),
                OvertimeTierV2(fromMinutes = 43 * 60, toMinutes = null, multiplier = 1.50)
            ),
            articleId = record.articleId,
            articleNumber = FALLBACK_ARTICLE,
            effectiveFrom = Instant.ofEpochMilli(record.effectiveFromMs).atZone(zoneId).toLocalDate(),
            effectiveTo = record.effectiveToMs?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
        )
    }

    /**
     * Vérifie le plancher d'un taux négocié à partir de L3121-33 au lieu de le coder sans source.
     */
    fun agreementMinimumPercent(
        records: List<LegalPayrollSourceStoreV2.Record>,
        referenceDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Double? {
        val record = records
            .filter { it.topic == OfficialLegalCodeSourceV2.Topic.OVERTIME }
            .filter { normalizeArticleNumber(it.articleNumber) == AGREEMENT_ARTICLE }
            .filter { applies(it, referenceDate, zoneId) }
            .maxByOrNull { it.checkedAtMs }
            ?: return null
        val text = normalize(record.excerpt)
        return if (containsPercent(text, 10) && text.contains("ne peut etre inferieur")) 10.0 else null
    }

    private fun applies(
        record: LegalPayrollSourceStoreV2.Record,
        referenceDate: LocalDate,
        zoneId: ZoneId
    ): Boolean {
        val at = referenceDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return at >= record.effectiveFromMs && (record.effectiveToMs == null || at <= record.effectiveToMs)
    }

    private fun containsPercent(text: String, value: Int): Boolean =
        Regex("(?:^|\\D)$value\\s*%", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun normalizeArticleNumber(value: String?): String? = value
        ?.uppercase(Locale.ROOT)
        ?.replace(" ", "")
        ?.replace(".", "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.FRANCE),
        Normalizer.Form.NFD
    ).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
