package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionRuleSnapshotV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate

/**
 * Audit ciblé KALI des taux d'heures supplémentaires.
 *
 * La recherche par mots-clés sert à trouver des règles, jamais à prouver leur absence. Un résultat
 * vide ne crée donc aucune preuve CONFIRMED_ABSENCE. Seul un article KALI consulté, applicable à la
 * date et structuré sans hypothèse peut produire un snapshot conventionnel de calcul.
 */
object KaliOvertimePayrollAuditV2 {
    private const val PAGE_SIZE = 25
    private const val MAX_PAGES = 20
    private const val MAX_TEXTS_TO_EXPAND = 12
    private const val MAX_ARTICLES_TO_CONSULT = 80

    data class Summary(
        val idcc: String,
        val referenceDate: LocalDate,
        val pagesRead: Int,
        val candidates: Int,
        val articlesConsulted: Int,
        val structuredSchedules: Int,
        val saved: Boolean,
        val selectedSourceId: String? = null,
        val warnings: List<String> = emptyList()
    )

    private data class SearchBatch(
        val candidates: List<OfficialKaliOvertimeSourceV2.Candidate>,
        val pagesRead: Int,
        val resultCountComplete: Boolean,
        val warnings: List<String>
    )

    private data class TextExpansionBatch(
        val articleIds: List<String>,
        val textsConsulted: Int,
        val warnings: List<String>
    )

    private data class ConsultBatch(
        val schedules: List<OfficialKaliOvertimeRuleParserV2.ParsedSchedule>,
        val consulted: Int,
        val warnings: List<String>,
        val diagnostics: List<OfficialKaliOvertimeRuleParserV2.ArticleDiagnostic> = emptyList()
    )

    fun audit(
        context: Context,
        idcc: String,
        referenceDate: LocalDate
    ): Task<Summary> {
        val normalizedIdcc = normalizeIdcc(idcc)
            ?: return Tasks.forResult(
                Summary(idcc, referenceDate, 0, 0, 0, 0, false, warnings = listOf("KALI : IDCC invalide."))
            )

        return fetchPages(normalizedIdcc)
            .continueWithTask { searchTask ->
                if (!searchTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            normalizedIdcc,
                            referenceDate,
                            0,
                            0,
                            0,
                            0,
                            false,
                            warnings = listOf("KALI : ${searchTask.exception?.message ?: "recherche officielle impossible"}")
                        )
                    )
                }

                val batch = searchTask.result
                val directArticles = batch.candidates
                    .filter { it.id.startsWith("KALIARTI") }
                    .distinctBy { it.id }
                val textCandidates = batch.candidates
                    .filter { it.id.startsWith("KALITEXT") }
                    .distinctBy { it.id }
                val sectionCandidates = batch.candidates
                    .filter { it.id.startsWith("KALISCTA") }
                    .distinctBy { it.id }
                val textsToExpand = textCandidates.take(MAX_TEXTS_TO_EXPAND)

                expandKaliTexts(textsToExpand)
                    .continueWithTask { expansionTask ->
                        val expansion = if (expansionTask.isSuccessful) {
                            expansionTask.result
                        } else {
                            TextExpansionBatch(
                                emptyList(),
                                0,
                                listOf("KALI : consultation des textes KALITEXT impossible ; les articles directs restent analysés.")
                            )
                        }

                        val expandedArticles = expansion.articleIds.map { articleId ->
                            OfficialKaliOvertimeSourceV2.Candidate(
                                id = articleId,
                                title = "Article découvert via KALITEXT",
                                snippet = null
                            )
                        }
                        val articleCandidates = (directArticles + expandedArticles).distinctBy { it.id }
                        val limited = articleCandidates.take(MAX_ARTICLES_TO_CONSULT)
                        val preWarnings = buildList {
                            addAll(batch.warnings)
                            addAll(expansion.warnings)
                            if (textCandidates.isNotEmpty()) {
                                add(
                                    "KALI : ${expansion.textsConsulted} texte(s) KALITEXT consulté(s) ; " +
                                        "${expandedArticles.map { it.id }.distinct().size} article(s) supplémentaire(s) découvert(s)."
                                )
                            }
                            if (textCandidates.size > textsToExpand.size) {
                                add(
                                    "KALI : ${textCandidates.size - textsToExpand.size} texte(s) KALITEXT supplémentaire(s) " +
                                        "non développé(s) à cause de la limite de sécurité."
                                )
                            }
                            if (sectionCandidates.isNotEmpty()) {
                                add(
                                    "KALI : ${sectionCandidates.size} section(s) KALISCTA restent des pistes ; " +
                                        "elles ne sont pas transformées directement en règles de paie."
                                )
                            }
                            if (articleCandidates.size > limited.size) {
                                add(
                                    "KALI : ${articleCandidates.size - limited.size} article(s) candidat(s) non consulté(s) " +
                                        "à cause de la limite de sécurité."
                                )
                            }
                        }

                        consultArticles(limited, referenceDate)
                            .continueWith { consultTask ->
                                if (!consultTask.isSuccessful) {
                                    return@continueWith Summary(
                                        normalizedIdcc,
                                        referenceDate,
                                        batch.pagesRead,
                                        batch.candidates.size,
                                        0,
                                        0,
                                        false,
                                        warnings = preWarnings + "KALI : consultation des articles impossible."
                                    )
                                }
                                val consulted = consultTask.result
                                finalizeAudit(
                                    context = context.applicationContext,
                                    idcc = normalizedIdcc,
                                    referenceDate = referenceDate,
                                    search = batch,
                                    consult = consulted.copy(warnings = preWarnings + consulted.warnings)
                                )
                            }
                    }
            }
    }

    private fun finalizeAudit(
        context: Context,
        idcc: String,
        referenceDate: LocalDate,
        search: SearchBatch,
        consult: ConsultBatch
    ): Summary {
        val schedules = consult.schedules
        val baseWarnings = (consult.warnings + diagnosticWarnings(consult.diagnostics)).distinct()
        if (schedules.isEmpty()) {
            return Summary(
                idcc,
                referenceDate,
                search.pagesRead,
                search.candidates.size,
                consult.consulted,
                0,
                false,
                warnings = (baseWarnings + listOf(
                    "KALI : aucun barème complet et daté n'a pu être structuré automatiquement.",
                    "KALI : une recherche ciblée vide ou non structurée ne prouve jamais l'absence officielle d'une règle d'heures supplémentaires."
                )).distinct()
            )
        }

        val fingerprints = schedules.map { it.fingerprint }.distinct()
        if (fingerprints.size > 1) {
            return Summary(
                idcc,
                referenceDate,
                search.pagesRead,
                search.candidates.size,
                consult.consulted,
                schedules.size,
                false,
                warnings = (baseWarnings +
                    "KALI : plusieurs barèmes applicables incompatibles ont été trouvés ; aucun n'est enregistré automatiquement.").distinct()
            )
        }

        val selected = schedules.maxWithOrNull(
            compareBy<OfficialKaliOvertimeRuleParserV2.ParsedSchedule> { it.article.effectiveFrom }
                .thenBy { it.article.articleId }
        ) ?: return Summary(idcc, referenceDate, search.pagesRead, search.candidates.size, consult.consulted, 0, false)

        val sourceId = "legifrance:KALI:${selected.article.articleId}"
        val versionId = "KALI-OT-${selected.article.articleId}"
        val overlapping = runCatching {
            V2ConventionRuleStore.history(context).allVersions(idcc).filter { existing ->
                intervalsOverlap(
                    selected.article.effectiveFrom.toEpochDay(),
                    selected.article.effectiveTo?.toEpochDay(),
                    existing.effectiveFromEpochDay,
                    existing.effectiveToEpochDay
                ) && !(existing.versionId == versionId && existing.sourceId == sourceId)
            }
        }.getOrElse {
            return Summary(
                idcc,
                referenceDate,
                search.pagesRead,
                search.candidates.size,
                consult.consulted,
                schedules.size,
                false,
                warnings = (baseWarnings + "KALI : historique conventionnel existant incohérent ; aucun écrasement automatique.").distinct()
            )
        }
        if (overlapping.isNotEmpty()) {
            return Summary(
                idcc,
                referenceDate,
                search.pagesRead,
                search.candidates.size,
                consult.consulted,
                schedules.size,
                false,
                warnings = (baseWarnings +
                    "KALI : un snapshot conventionnel couvre déjà cette période ; HoraTrack refuse de l'écraser sans arbitrage temporel explicite.").distinct()
            )
        }

        val snapshot = ConventionRuleSnapshotV2(
            idcc = idcc,
            versionId = versionId,
            sourceId = sourceId,
            effectiveFromEpochDay = selected.article.effectiveFrom.toEpochDay(),
            effectiveToEpochDay = selected.article.effectiveTo?.toEpochDay(),
            rules = PayrollRulesV2(
                weeklyRegularMinutes = selected.weeklyRegularMinutes,
                overtimeTiers = selected.tiers
            ),
            checkedAtMs = System.currentTimeMillis(),
            note = "Barème heures supplémentaires extrait d'un article KALI consulté et applicable à la date de paie."
        )
        val saved = runCatching {
            V2ConventionRuleStore.saveConfirmed(context, snapshot)
            true
        }.getOrDefault(false)

        return Summary(
            idcc = idcc,
            referenceDate = referenceDate,
            pagesRead = search.pagesRead,
            candidates = search.candidates.size,
            articlesConsulted = consult.consulted,
            structuredSchedules = schedules.size,
            saved = saved,
            selectedSourceId = if (saved) sourceId else null,
            warnings = buildList {
                addAll(baseWarnings)
                if (!search.resultCountComplete) {
                    add("KALI : le total officiel de recherche n'a pas permis de certifier une pagination exhaustive ; cela n'empêche pas d'utiliser un article positivement vérifié, mais interdit toute conclusion d'absence.")
                }
                if (!saved) add("KALI : barème structuré mais stockage du snapshot impossible.")
            }.distinct()
        )
    }

    private fun fetchPages(
        idcc: String,
        pageNumber: Int = 1,
        accumulated: List<OfficialKaliOvertimeSourceV2.Candidate> = emptyList(),
        expectedTotal: Int? = null
    ): Task<SearchBatch> {
        val body = OfficialKaliOvertimeSourceV2.searchBody(idcc, pageNumber, PAGE_SIZE)
        return LegifranceFunctionClientV2.request("/search", body)
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    if (pageNumber == 1) {
                        return@continueWithTask Tasks.forException(
                            task.exception ?: IllegalStateException("KALI : première page inaccessible")
                        )
                    }
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(
                            accumulated.distinctBy { it.id },
                            pageNumber - 1,
                            false,
                            listOf("KALI : pagination interrompue après ${pageNumber - 1} page(s).")
                        )
                    )
                }

                val page = OfficialKaliOvertimeSourceV2.parsePage(task.result.data, pageNumber, PAGE_SIZE)
                val total = expectedTotal ?: page.totalResults
                val combined = (accumulated + page.candidates).distinctBy { it.id }
                val completeByCount = total != null && combined.size >= total
                val emptyPage = page.candidates.isEmpty()
                val reachedLimit = pageNumber >= MAX_PAGES
                val continuePaging = !completeByCount && !emptyPage && !reachedLimit

                if (continuePaging) {
                    fetchPages(idcc, pageNumber + 1, combined, total)
                } else {
                    Tasks.forResult(
                        SearchBatch(
                            candidates = combined,
                            pagesRead = pageNumber,
                            resultCountComplete = completeByCount,
                            warnings = buildList {
                                if (reachedLimit && !completeByCount) {
                                    add("KALI : recherche bornée à $MAX_PAGES pages.")
                                }
                                if (total == null) {
                                    add("KALI : le nombre total de résultats n'est pas fourni par la réponse officielle.")
                                }
                            }
                        )
                    )
                }
            }
    }

    private fun expandKaliTexts(
        candidates: List<OfficialKaliOvertimeSourceV2.Candidate>,
        index: Int = 0,
        articleIds: List<String> = emptyList(),
        textsConsulted: Int = 0,
        warnings: List<String> = emptyList()
    ): Task<TextExpansionBatch> {
        if (index >= candidates.size) {
            return Tasks.forResult(TextExpansionBatch(articleIds.distinct(), textsConsulted, warnings.distinct()))
        }
        val candidate = candidates[index]
        return LegifranceFunctionClientV2.request("/consult/kaliText", mapOf("id" to candidate.id))
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    return@continueWithTask expandKaliTexts(
                        candidates,
                        index + 1,
                        articleIds,
                        textsConsulted + 1,
                        warnings + "KALI : ${candidate.id} n'a pas pu être développé."
                    )
                }
                val expansion = OfficialKaliTextExpansionV2.parse(task.result.data)
                expandKaliTexts(
                    candidates,
                    index + 1,
                    (articleIds + expansion.articleIds).distinct(),
                    textsConsulted + 1,
                    warnings
                )
            }
    }

    private fun consultArticles(
        candidates: List<OfficialKaliOvertimeSourceV2.Candidate>,
        referenceDate: LocalDate,
        index: Int = 0,
        schedules: List<OfficialKaliOvertimeRuleParserV2.ParsedSchedule> = emptyList(),
        consulted: Int = 0,
        warnings: List<String> = emptyList(),
        diagnostics: List<OfficialKaliOvertimeRuleParserV2.ArticleDiagnostic> = emptyList()
    ): Task<ConsultBatch> {
        if (index >= candidates.size) {
            return Tasks.forResult(ConsultBatch(schedules, consulted, warnings, diagnostics))
        }
        val candidate = candidates[index]
        return LegifranceFunctionClientV2.request("/consult/kaliArticle", mapOf("id" to candidate.id))
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    return@continueWithTask consultArticles(
                        candidates,
                        referenceDate,
                        index + 1,
                        schedules,
                        consulted + 1,
                        warnings + "KALI : ${candidate.id} n'a pas pu être consulté.",
                        diagnostics
                    )
                }
                val article = OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(
                    task.result.data,
                    candidate.id,
                    referenceDate
                )
                val diagnostic = article?.let(OfficialKaliOvertimeRuleParserV2::analyzeArticle)
                val schedule = diagnostic?.schedule
                consultArticles(
                    candidates,
                    referenceDate,
                    index + 1,
                    if (schedule == null) schedules else schedules + schedule,
                    consulted + 1,
                    warnings,
                    if (diagnostic == null) diagnostics else diagnostics + diagnostic
                )
            }
    }

    private fun diagnosticWarnings(
        diagnostics: List<OfficialKaliOvertimeRuleParserV2.ArticleDiagnostic>
    ): List<String> = buildList {
        val explicitRates = diagnostics.filter {
            it.kind == OfficialKaliOvertimeRuleParserV2.DiagnosticKind.EXPLICIT_RATES_WITHOUT_35H
        }
        if (explicitRates.isNotEmpty()) {
            val samples = explicitRates.take(4).joinToString(" ; ") { diagnostic ->
                val thresholds = diagnostic.hourThresholds.joinToString("/") { "$it h" }.ifBlank { "seuil non identifié" }
                val rates = diagnostic.percentages.joinToString("/") { value ->
                    val rendered = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
                    "$rendered %"
                }.ifBlank { "taux non identifié" }
                "${diagnostic.article.articleId} ($thresholds ; $rates)"
            }
            add(
                "KALI : ${explicitRates.size} article(s) applicable(s) contiennent des taux explicites mais aucun seuil 35 h confirmé : $samples. " +
                    "HoraTrack les conserve comme indices et ne les applique pas automatiquement."
            )
        }

        val thirtyFiveUnstructured = diagnostics.filter {
            it.kind == OfficialKaliOvertimeRuleParserV2.DiagnosticKind.THIRTY_FIVE_WITHOUT_STRUCTURED_RATES
        }
        if (thirtyFiveUnstructured.isNotEmpty()) {
            add(
                "KALI : ${thirtyFiveUnstructured.size} article(s) applicable(s) mentionnent 35 h mais leur rédaction ne fournit pas un barème numérique complet exploitable ; exemples : " +
                    thirtyFiveUnstructured.take(4).joinToString(", ") { it.article.articleId } + "."
            )
        }

        val legalReferences = diagnostics.filter {
            it.kind == OfficialKaliOvertimeRuleParserV2.DiagnosticKind.LEGAL_REFERENCE_ONLY
        }
        if (legalReferences.isNotEmpty()) {
            add(
                "KALI : ${legalReferences.size} article(s) applicable(s) renvoient à la législation ou aux dispositions légales sans fixer eux-mêmes un barème complet ; exemples : " +
                    legalReferences.take(4).joinToString(", ") { it.article.articleId } + "."
            )
        }
    }

    internal fun intervalsOverlap(
        leftFrom: Long,
        leftTo: Long?,
        rightFrom: Long,
        rightTo: Long?
    ): Boolean {
        val leftEnd = leftTo ?: Long.MAX_VALUE
        val rightEnd = rightTo ?: Long.MAX_VALUE
        return leftFrom <= rightEnd && rightFrom <= leftEnd
    }

    private fun normalizeIdcc(value: String): String? {
        val digits = value.filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0 || number == 9999) return null
        return number.toString().padStart(4, '0')
    }
}
