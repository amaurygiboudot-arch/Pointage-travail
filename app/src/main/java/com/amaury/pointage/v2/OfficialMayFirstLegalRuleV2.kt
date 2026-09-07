package com.amaury.pointage.v2

import java.text.Normalizer
import java.util.Locale

/**
 * Interprétation strictement bornée de l'article L3133-6 déjà vérifié par la chaîne LEGI.
 *
 * Le multiplicateur total 2,0 n'est produit que si le texte officiel applicable confirme à la fois :
 * - le travail accompli le 1er mai ;
 * - le salaire correspondant à ce travail ;
 * - une indemnité, en plus de ce salaire, égale au montant de ce salaire.
 */
object OfficialMayFirstLegalRuleV2 {
    data class Rule(
        val articleId: String,
        val articleNumber: String,
        val effectiveFromMs: Long,
        val effectiveToMs: Long?,
        val referenceAtMs: Long,
        val checkedAtMs: Long,
        /** Part additionnelle par rapport au salaire de base déjà payé. */
        val extraMultiplier: Double = 1.0
    ) {
        val totalMultiplier: Double get() = 1.0 + extraMultiplier
    }

    fun parse(record: LegalPayrollSourceStoreV2.Record): Rule? {
        if (record.topic != OfficialLegalCodeSourceV2.Topic.PUBLIC_HOLIDAYS) return null
        if (!LegalPayrollSourceStoreV2.isApplicableStatus(record.status)) return null
        if (normalizeArticleNumber(record.articleNumber) != "L3133-6") return null
        if (!record.articleId.startsWith("LEGIARTI")) return null
        if (record.effectiveFromMs <= 0L || record.referenceAtMs <= 0L || record.checkedAtMs <= 0L) return null
        if (record.referenceAtMs < record.effectiveFromMs) return null
        if (record.effectiveToMs != null && record.referenceAtMs > record.effectiveToMs) return null

        val text = normalize(record.excerpt)
        if (!mentionsMayFirst(text)) return null
        if (!mentionsWorkedSalary(text)) return null
        if (!mentionsAdditionalEqualIndemnity(text)) return null

        return Rule(
            articleId = record.articleId,
            articleNumber = "L3133-6",
            effectiveFromMs = record.effectiveFromMs,
            effectiveToMs = record.effectiveToMs,
            referenceAtMs = record.referenceAtMs,
            checkedAtMs = record.checkedAtMs
        )
    }

    fun parseVerified(article: OfficialLegalCodeVerifierV2.VerifiedArticle): Rule? = parse(
        LegalPayrollSourceStoreV2.Record(
            topic = article.topic,
            articleId = article.articleId,
            articleNumber = article.articleNumber,
            status = article.status,
            excerpt = article.excerpt,
            effectiveFromMs = article.effectiveFromMs,
            effectiveToMs = article.effectiveToMs,
            referenceAtMs = article.referenceAtMs,
            checkedAtMs = article.checkedAtMs
        )
    )

    private fun normalizeArticleNumber(value: String?): String = value.orEmpty()
        .uppercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")
        .replace('–', '-')
        .replace('—', '-')

    private fun mentionsMayFirst(text: String): Boolean =
        text.contains("1er mai") || text.contains("premier mai")

    private fun mentionsWorkedSalary(text: String): Boolean =
        text.contains("salaire correspondant au travail accompli") ||
            (text.contains("salaire") && text.contains("travail accompli"))

    private fun mentionsAdditionalEqualIndemnity(text: String): Boolean =
        text.contains("en plus du salaire") &&
            text.contains("indemnite") &&
            (text.contains("egale au montant de ce salaire") || text.contains("egale au salaire"))

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.FRANCE),
        Normalizer.Form.NFD
    )
        .replace(Regex("\\p{M}+"), "")
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()
}
