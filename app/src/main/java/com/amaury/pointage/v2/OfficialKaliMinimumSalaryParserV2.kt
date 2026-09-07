package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** Parse uniquement un minimum explicitement rattaché à la classification exacte du salarié. */
object OfficialKaliMinimumSalaryParserV2 {
    data class Diagnostic(
        val articleId: String,
        val rule: ConventionMinimumSalaryV2.Rule?,
        val reasons: List<String>
    )

    fun parse(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        profile: ConventionLegalProfileV2,
        auditDate: LocalDate
    ): Diagnostic {
        if (profile.classification.isEmpty()) {
            return Diagnostic(article.articleId, null, listOf("classification salarié absente"))
        }
        val text = normalize(listOfNotNull(article.title, article.content).joinToString(" "))
        if (!minimumVocabulary.any(text::contains)) {
            return Diagnostic(article.articleId, null, listOf("article sans vocabulaire explicite de minimum salarial"))
        }

        val anchors = classificationAnchors(text, profile.classification)
        if (anchors == null || anchors.isEmpty()) {
            return Diagnostic(article.articleId, null, listOf("classification exacte non retrouvée dans l'article"))
        }
        val first = anchors.minOrNull()!!
        val last = anchors.maxOrNull()!!
        if (last - first > 450) {
            return Diagnostic(article.articleId, null, listOf("critères de classification trop éloignés pour identifier une même ligne de barème"))
        }
        val from = (first - 180).coerceAtLeast(0)
        val to = (last + 420).coerceAtMost(text.length)
        val window = text.substring(from, to)

        val statusReason = professionalStatusCheck(window, text, profile.professionalStatus)
        if (statusReason != null) return Diagnostic(article.articleId, null, listOf(statusReason))

        val amounts = euroAmountRegex.findAll(window)
            .mapNotNull { match -> parseMoney(match.groupValues[1]) }
            .filter { it > 0.0 && it < 1_000_000.0 }
            .distinct()
            .toList()
        if (amounts.size != 1) {
            return Diagnostic(article.articleId, null, listOf("${amounts.size} montant(s) en euros près de la classification ; montant unique non démontré"))
        }

        val periodicities = buildSet {
            if (monthlyWords.any(window::contains)) add(ConventionMinimumSalaryV2.Periodicity.MONTHLY)
            if (hourlyWords.any(window::contains)) add(ConventionMinimumSalaryV2.Periodicity.HOURLY)
            if (annualWords.any(window::contains)) add(ConventionMinimumSalaryV2.Periodicity.ANNUAL)
        }
        if (periodicities.size != 1) {
            return Diagnostic(article.articleId, null, listOf("périodicité du minimum non unique ou non explicite près du montant"))
        }

        val extensionStatus = when (article.status.uppercase(Locale.ROOT)) {
            "VIGUEUR_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED
            "VIGUEUR_NON_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED
            else -> ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
        }
        val rule = ConventionMinimumSalaryV2.Rule(
            idcc = profile.idcc,
            ruleId = "KALI-MIN-${article.articleId}-${profile.classification.normalized().label().hashCode().toUInt().toString(16)}",
            effectiveFrom = article.effectiveFrom,
            effectiveTo = article.effectiveTo,
            classification = profile.classification.normalized(),
            amount = amounts.single(),
            periodicity = periodicities.single(),
            source = "Légifrance KALI — ${article.articleId}${article.title?.let { " — $it" }.orEmpty()}",
            extensionStatus = extensionStatus,
            // VIGUEUR_ETEN prouve l'extension à la date auditée, pas nécessairement sa date historique exacte.
            extensionEffectiveFrom = if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) auditDate else null
        )
        return if (rule.structurallyValid()) Diagnostic(article.articleId, rule, emptyList())
        else Diagnostic(article.articleId, null, listOf("règle structurée incohérente"))
    }

    private fun classificationAnchors(text: String, value: ConventionClassificationV2): List<Int>? {
        val anchors = mutableListOf<Int>()
        fun requireRegex(regex: Regex): Boolean {
            val position = regex.find(text)?.range?.first ?: return false
            anchors += position
            return true
        }
        value.coefficient?.let {
            if (!requireRegex(Regex("\\b(?:coefficient|coef(?:ficient)?)\\s*[:.\\-]?\\s*$it\\b"))) return null
        }
        value.level?.let {
            if (!requireRegex(labelRegex("niveau", it))) return null
        }
        value.echelon?.let {
            if (!requireRegex(labelRegex("echelon", it))) return null
        }
        value.position?.let {
            if (!requireRegex(labelRegex("position", it))) return null
        }
        value.group?.let {
            if (!requireRegex(labelRegex("groupe", it))) return null
        }
        value.category?.let {
            if (!requireRegex(labelRegex("categorie", it))) return null
        }
        value.employment?.let {
            val wanted = normalize(it)
            val explicit = Regex("\\b(?:emploi|fonction|poste)\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b")
            val direct = Regex("\\b${Regex.escape(wanted)}\\b")
            if (!requireRegex(explicit) && !requireRegex(direct)) return null
        }
        return anchors
    }

    private fun labelRegex(label: String, raw: String): Regex {
        val wanted = normalize(raw)
        return Regex("\\b$label\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b")
    }

    /**
     * Si l'article distingue explicitement les statuts, le statut demandé doit être présent
     * dans la même fenêtre que la ligne de classification. S'il n'y a aucun vocabulaire de
     * statut dans l'article, la classification exacte reste le seul sélecteur utilisé.
     */
    private fun professionalStatusCheck(window: String, wholeText: String, status: String?): String? {
        val wanted = status ?: return null
        val articleHasStatusVocabulary = cadreWords.any(wholeText::contains) || nonCadreWords.any(wholeText::contains)
        if (!articleHasStatusVocabulary) return null
        val matches = when (wanted) {
            "CADRE" -> cadreWords.any(window::contains) && !nonCadreWords.any(window::contains)
            "NON_CADRE" -> nonCadreWords.any(window::contains) && !cadreOnlyWords.any(window::contains)
            else -> false
        }
        return if (matches) null else "statut professionnel non isolé avec certitude près de la classification"
    }

    private fun parseMoney(raw: String): Double? {
        val value = raw.replace(" ", "").replace("\u00a0", "").replace(',', '.')
        return value.toDoubleOrNull()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()

    private val minimumVocabulary = listOf("salaire minimum", "salaires minima", "salaire minimal", "minimum conventionnel", "remuneration minimale", "remunerations minimales", "minima conventionnels")
    private val monthlyWords = listOf("mensuel", "mensuelle", "par mois", "mensuellement")
    private val hourlyWords = listOf("horaire", "par heure", "de l'heure")
    private val annualWords = listOf("annuel", "annuelle", "par an", "annuellement")
    private val cadreWords = listOf("cadre", "cadres", "ingenieur", "ingenieurs")
    private val cadreOnlyWords = cadreWords
    private val nonCadreWords = listOf("non cadre", "non-cadre", "ouvrier", "ouvriers", "employe", "employes", "technicien", "techniciens", "agent de maitrise", "agents de maitrise", "etam")
    private val euroAmountRegex = Regex("(?<!\\d)(\\d{1,6}(?:[ \\u00a0]\\d{3})*(?:[,.]\\d{1,2})?)\\s*(?:€|euros?)\\b")
}
