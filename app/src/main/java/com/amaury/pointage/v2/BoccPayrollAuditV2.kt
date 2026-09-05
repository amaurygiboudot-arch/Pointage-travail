package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Chaîne BOCC : publications IDCC -> filtre documentaire paie -> métadonnées PDF -> stockage d'audit. */
object BoccPayrollAuditV2 {
    private const val PAGE_SIZE = 50
    private const val MAX_PAGES = 5

    data class Summary(
        val referenceAtMs: Long,
        val idcc: String,
        val candidates: Int,
        val payrollRelevant: Int,
        val verified: Int,
        val rejectedOrSkipped: Int,
        val saved: Boolean,
        val warnings: List<String> = emptyList()
    )

    private data class SearchBatch(
        val candidates: List<OfficialBoccSourceV2.Candidate>,
        val expectedTotal: Int?,
        val pagesRead: Int,
        val partial: Boolean,
        val warnings: List<String> = emptyList()
    )

    fun audit(
        context: Context,
        company: SalaryCompanyStore.Company,
        atMs: Long
    ): Task<Summary> {
        require(atMs > 0L) { "Date BOCC invalide" }
        val idcc = company.idcc.filter(Char::isDigit)
        if (idcc.isBlank()) {
            return Tasks.forResult(
                Summary(atMs, "", 0, 0, 0, 0, false, listOf("BOCC : IDCC de l'entreprise requis."))
            )
        }
        val normalizedIdcc = idcc.toIntOrNull()?.takeIf { it > 0 }?.toString()
            ?: return Tasks.forResult(
                Summary(atMs, idcc, 0, 0, 0, 0, false, listOf("BOCC : IDCC de l'entreprise invalide."))
            )

        val zone = ZoneId.systemDefault()
        val referenceDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        // Un BOCC est une publication : pour une paie future, on ne demande jamais à PISTE
        // une date de publication qui n'existe pas encore. La date de paie reste inchangée pour le stockage.
        val publicationEnd = minOf(referenceDate, today)
        val publicationStart = referenceDate.minusMonths(24).coerceAtMost(publicationEnd)
        val app = context.applicationContext

        return fetchPages(idcc, publicationStart, publicationEnd)
            .continueWithTask { searchTask ->
                if (!searchTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            atMs, idcc, 0, 0, 0, 0, false,
                            listOf("BOCC : ${searchTask.exception?.message ?: "recherche officielle impossible"}")
                        )
                    )
                }
                val batch = searchTask.result
                val allCandidates = batch.candidates
                val candidates = allCandidates.filter { candidate ->
                    candidate.idccs.isEmpty() || candidate.idccs.any { raw ->
                        raw.filter(Char::isDigit).toIntOrNull()?.takeIf { it > 0 }?.toString() == normalizedIdcc
                    }
                }
                val relevant = candidates.filter(OfficialBoccSourceV2::isPayrollRelevant)
                if (relevant.isEmpty()) {
                    val saved = BoccPayrollSourceStoreV2.replaceSnapshot(app, company.id, atMs, idcc, emptyList())
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            referenceAtMs = atMs,
                            idcc = idcc,
                            candidates = candidates.size,
                            payrollRelevant = 0,
                            verified = 0,
                            rejectedOrSkipped = 0,
                            saved = saved,
                            warnings = buildList {
                                addAll(batch.warnings)
                                add(
                                    if (candidates.isEmpty())
                                        "BOCC : aucune publication correspondant à cet IDCC sur la fenêtre contrôlée."
                                    else
                                        "BOCC : publications trouvées, mais aucun titre ne touche directement les thèmes de paie suivis."
                                )
                            }
                        )
                    )
                }

                OfficialBoccVerifierV2.verify(relevant).continueWith { verifyTask ->
                    if (!verifyTask.isSuccessful) {
                        return@continueWith Summary(
                            atMs, idcc, candidates.size, relevant.size, 0, relevant.size, false,
                            batch.warnings + "BOCC : vérification des métadonnées PDF impossible."
                        )
                    }
                    val result = verifyTask.result
                    val saved = BoccPayrollSourceStoreV2.replaceSnapshot(
                        app, company.id, atMs, idcc, result.verified
                    )
                    Summary(
                        referenceAtMs = atMs,
                        idcc = idcc,
                        candidates = candidates.size,
                        payrollRelevant = relevant.size,
                        verified = result.verified.size,
                        rejectedOrSkipped = result.rejectedOrSkippedCount,
                        saved = saved,
                        warnings = buildList {
                            addAll(batch.warnings)
                            addAll(result.warnings)
                            if (allCandidates.size != candidates.size) {
                                add("BOCC : ${allCandidates.size - candidates.size} publication(s) écartée(s) car l'IDCC ne correspondait pas.")
                            }
                            if (result.verified.isEmpty()) {
                                add("BOCC : aucun document paie n'a été confirmé par les métadonnées officielles.")
                            }
                            if (!saved) add("BOCC : résultat vérifié mais stockage local impossible.")
                        }
                    )
                }
            }
    }

    /** Lecture séquentielle et bornée pour ne pas provoquer de rafale PISTE. */
    private fun fetchPages(
        idcc: String,
        from: LocalDate,
        to: LocalDate,
        pageNumber: Int = 1,
        accumulated: List<OfficialBoccSourceV2.Candidate> = emptyList(),
        expectedTotal: Int? = null
    ): Task<SearchBatch> {
        val body = OfficialBoccSourceV2.listBody(
            idcc = idcc,
            from = from,
            to = to,
            pageSize = PAGE_SIZE,
            pageNumber = pageNumber
        )
        return LegifranceFunctionClientV2.request("/list/boccsAndTexts", body)
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    if (pageNumber == 1) {
                        return@continueWithTask Tasks.forException(
                            task.exception ?: IllegalStateException("BOCC : première page inaccessible")
                        )
                    }
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(
                            candidates = accumulated.distinctBy { it.fileName },
                            expectedTotal = expectedTotal,
                            pagesRead = pageNumber - 1,
                            partial = true,
                            warnings = listOf("BOCC : pagination interrompue après ${pageNumber - 1} page(s).")
                        )
                    )
                }

                val pageCandidates = OfficialBoccSourceV2.parseCandidates(task.result?.data)
                val total = expectedTotal ?: OfficialBoccSourceV2.totalResultNumber(task.result?.data)
                val combined = (accumulated + pageCandidates).distinctBy { it.fileName }
                val completeByCount = total != null && combined.size >= total
                val noMoreResults = pageCandidates.isEmpty()
                val reachedLimit = pageNumber >= MAX_PAGES
                val shouldContinue = total != null && !completeByCount && !noMoreResults && !reachedLimit

                if (shouldContinue) {
                    fetchPages(idcc, from, to, pageNumber + 1, combined, total)
                } else {
                    val partial = (total != null && combined.size < total) ||
                        (reachedLimit && pageCandidates.isNotEmpty() && total == null)
                    Tasks.forResult(
                        SearchBatch(
                            candidates = combined,
                            expectedTotal = total,
                            pagesRead = pageNumber,
                            partial = partial,
                            warnings = buildList {
                                if (partial) {
                                    add("BOCC : lecture bornée à $pageNumber page(s) ; la piste de publication peut être partielle.")
                                }
                            }
                        )
                    )
                }
            }
    }
}
