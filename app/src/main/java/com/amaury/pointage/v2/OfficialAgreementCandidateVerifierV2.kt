package com.amaury.pointage.v2

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/** Vérifie chaque candidat /search par /consult/acco avant qu'il puisse être retenu pour l'entreprise. */
object OfficialAgreementCandidateVerifierV2 {
    data class Result(
        val verified: List<CompanyAgreementStoreV2.Agreement>,
        val rejectedCount: Int
    )

    fun verify(
        candidates: List<CompanyAgreementStoreV2.Agreement>,
        expectedSiret: String
    ): Task<Result> {
        val siret = expectedSiret.filter(Char::isDigit)
        if (siret.length != 14 || candidates.isEmpty()) {
            return Tasks.forResult(Result(emptyList(), candidates.size))
        }

        val checks = candidates.map { candidate ->
            LegifranceFunctionClientV2.request("/consult/acco", mapOf("id" to candidate.id))
                .continueWith { task ->
                    if (!task.isSuccessful) return@continueWith null
                    val content = OfficialAgreementContentParserV2.extractVerified(task.result?.data, siret)
                        ?: return@continueWith null
                    candidate.copy(
                        status = CompanyAgreementStoreV2.Status.UNKNOWN,
                        notes = "SIRET vérifié dans la consultation officielle. Contenu et période d’application à valider."
                    )
                }
        }

        return Tasks.whenAllComplete(checks).continueWith {
            val verified = checks.mapNotNull { check ->
                if (check.isSuccessful) check.result else null
            }.distinctBy { it.id }
            Result(
                verified = verified,
                rejectedCount = candidates.size - verified.size
            )
        }
    }
}
