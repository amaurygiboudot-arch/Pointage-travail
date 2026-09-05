package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.Instant
import java.time.ZoneId

/** Chaîne BOCC : publications IDCC -> filtre documentaire paie -> métadonnées PDF -> stockage d'audit. */
object BoccPayrollAuditV2 {
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

        val zone = ZoneId.systemDefault()
        val referenceDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        // KALI porte l'état conventionnel consolidé. BOCC sert ici de piste récente de publication :
        // on borne volontairement la recherche aux 24 mois précédant la date de paie.
        val from = referenceDate.minusMonths(24)
        val body = OfficialBoccSourceV2.listBody(idcc, from, referenceDate, pageSize = 100)
        val app = context.applicationContext

        return LegifranceFunctionClientV2.request("/list/boccsAndTexts", body)
            .continueWithTask { searchTask ->
                if (!searchTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            atMs, idcc, 0, 0, 0, 0, false,
                            listOf("BOCC : ${searchTask.exception?.message ?: "recherche officielle impossible"}")
                        )
                    )
                }
                val candidates = OfficialBoccSourceV2.parseCandidates(searchTask.result?.data)
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
                            warnings = listOf(
                                if (candidates.isEmpty())
                                    "BOCC : aucune publication trouvée pour cet IDCC sur la fenêtre contrôlée."
                                else
                                    "BOCC : publications trouvées, mais aucun titre ne touche directement les thèmes de paie suivis."
                            )
                        )
                    )
                }

                OfficialBoccVerifierV2.verify(relevant).continueWith { verifyTask ->
                    if (!verifyTask.isSuccessful) {
                        return@continueWith Summary(
                            atMs, idcc, candidates.size, relevant.size, 0, relevant.size, false,
                            listOf("BOCC : vérification des métadonnées PDF impossible.")
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
                            addAll(result.warnings)
                            if (result.verified.isEmpty()) {
                                add("BOCC : aucun document paie n'a été confirmé par les métadonnées officielles.")
                            }
                            if (!saved) add("BOCC : résultat vérifié mais stockage local impossible.")
                        }
                    )
                }
            }
    }
}
