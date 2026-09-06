package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.PayrollPeriodV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

/**
 * Consomme uniquement les réanalyses préparées par Firebase et lance les analyseurs V2 déjà sûrs.
 * Le téléphone ne modifie jamais l'état juridique partagé dans Firestore.
 */
object LegalAutoUpdateCoordinatorV2 {
    private const val PREFS = "legal_auto_update_v2"
    private const val RETRY_DELAY_MS = 6L * 60L * 60L * 1000L
    private val PARIS = ZoneId.of("Europe/Paris")

    data class Summary(
        val readyJobs: Int,
        val alreadyHandledJobs: Int,
        val kaliAuditsRun: Int,
        val kaliRuleSaved: Boolean,
        val legiAuditsRun: Int,
        val legiVerifiedArticles: Int,
        val unsupportedAccoJobs: Int,
        val accoAuditsRun: Int = 0,
        val accoCandidatesExtracted: Int = 0,
        val warnings: List<String> = emptyList()
    ) {
        val hadAutomaticWork: Boolean get() = kaliAuditsRun > 0 || legiAuditsRun > 0 || accoAuditsRun > 0
    }

    fun referenceDate(context: Context): LocalDate {
        val prefs = context.getSharedPreferences("navigation_state", Context.MODE_PRIVATE)
        val selectedMs = prefs.getLong("report_month_ms", -1L)
        val calendar = Calendar.getInstance(Locale.FRANCE)
        if (selectedMs > 0L) calendar.timeInMillis = selectedMs
        return PayrollPeriodV2.month(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH)).referenceDate
    }

    fun sync(
        context: Context,
        company: SalaryCompanyStore.Company,
        referenceDate: LocalDate = referenceDate(context)
    ): Task<Summary> {
        val app = context.applicationContext
        return LegalReanalysisPlanClientV2.fetch(company.idcc, company.siret)
            .continueWithTask { planTask ->
                if (!planTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            readyJobs = 0,
                            alreadyHandledJobs = 0,
                            kaliAuditsRun = 0,
                            kaliRuleSaved = false,
                            legiAuditsRun = 0,
                            legiVerifiedArticles = 0,
                            unsupportedAccoJobs = 0,
                            warnings = listOf("Veille juridique : synchronisation différée.")
                        )
                    )
                }

                val plan = planTask.result ?: LegalReanalysisPlanClientV2.Plan(0, 0L, emptyList())
                executePlan(app, company, referenceDate, plan)
            }
    }

    internal fun selectKinds(
        jobs: List<LegalReanalysisPlanClientV2.Job>
    ): Triple<List<LegalReanalysisPlanClientV2.Job>, List<LegalReanalysisPlanClientV2.Job>, List<LegalReanalysisPlanClientV2.Job>> {
        val kali = jobs.filter { "KALI_OVERTIME" in it.analysisKinds }
        val legi = jobs.filter { "LEGI_ALL" in it.analysisKinds }
        val acco = jobs.filter {
            "ACCO_EXTRACT_CANDIDATES" in it.analysisKinds || "ACCO_PENDING_PARSER" in it.analysisKinds
        }
        return Triple(kali, legi, acco)
    }

    private fun executePlan(
        context: Context,
        company: SalaryCompanyStore.Company,
        referenceDate: LocalDate,
        plan: LegalReanalysisPlanClientV2.Plan
    ): Task<Summary> {
        val (allKali, allLegi, allAcco) = selectKinds(plan.jobs)
        val nowMs = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kali = allKali.filter { canAttempt(prefs, it, "KALI_OVERTIME", nowMs) }
        val legi = allLegi.filter { canAttempt(prefs, it, "LEGI_ALL", nowMs) }
        val acco = allAcco.filter {
            "ACCO_EXTRACT_CANDIDATES" in it.analysisKinds &&
                canAttempt(prefs, it, "ACCO_EXTRACT_CANDIDATES", nowMs)
        }
        val legacyAcco = allAcco.filter {
            "ACCO_PENDING_PARSER" in it.analysisKinds && "ACCO_EXTRACT_CANDIDATES" !in it.analysisKinds
        }
        val alreadyHandled = plan.jobs.count { job ->
            val kinds = job.analysisKinds.filter { it != "ACCO_PENDING_PARSER" }
            kinds.isNotEmpty() && kinds.all { isDone(prefs, job, it) }
        }

        val baseWarnings = buildList {
            if (legacyAcco.isNotEmpty()) {
                add("ACCO : ${legacyAcco.size} ancienne(s) mise(s) à jour restent en attente de la nouvelle extraction automatique.")
            }
            if (allAcco.isNotEmpty()) {
                add("ACCO : les règles extraites restent à valider avant toute utilisation dans la paie.")
            }
        }

        if (kali.isEmpty() && legi.isEmpty() && acco.isEmpty()) {
            return Tasks.forResult(
                Summary(
                    readyJobs = plan.jobs.size,
                    alreadyHandledJobs = alreadyHandled,
                    kaliAuditsRun = 0,
                    kaliRuleSaved = false,
                    legiAuditsRun = 0,
                    legiVerifiedArticles = 0,
                    unsupportedAccoJobs = allAcco.size,
                    warnings = baseWarnings
                )
            )
        }

        return runKali(context, company, referenceDate, kali, nowMs)
            .continueWithTask { kaliTask ->
                val kaliOutcome = if (kaliTask.isSuccessful) {
                    kaliTask.result ?: KaliOutcome()
                } else {
                    KaliOutcome(warnings = listOf("KALI : mise à jour automatique interrompue."))
                }
                runLegi(context, referenceDate, legi, nowMs)
                    .continueWithTask { legiTask ->
                        val legiOutcome = if (legiTask.isSuccessful) {
                            legiTask.result ?: LegiOutcome()
                        } else {
                            LegiOutcome(warnings = listOf("LEGI : mise à jour automatique interrompue."))
                        }
                        runAcco(context, company, acco, nowMs)
                            .continueWith { accoTask ->
                                val accoOutcome = if (accoTask.isSuccessful) {
                                    accoTask.result ?: AccoOutcome()
                                } else {
                                    AccoOutcome(warnings = listOf("ACCO : mise à jour automatique interrompue."))
                                }
                                Summary(
                                    readyJobs = plan.jobs.size,
                                    alreadyHandledJobs = alreadyHandled,
                                    kaliAuditsRun = kaliOutcome.ran,
                                    kaliRuleSaved = kaliOutcome.saved,
                                    legiAuditsRun = legiOutcome.ran,
                                    legiVerifiedArticles = legiOutcome.verifiedArticles,
                                    unsupportedAccoJobs = allAcco.size,
                                    accoAuditsRun = accoOutcome.ran,
                                    accoCandidatesExtracted = accoOutcome.extractedCandidates,
                                    warnings = baseWarnings + kaliOutcome.warnings + legiOutcome.warnings + accoOutcome.warnings
                                )
                            }
                    }
            }
    }

    private data class KaliOutcome(
        val ran: Int = 0,
        val saved: Boolean = false,
        val warnings: List<String> = emptyList()
    )

    private fun runKali(
        context: Context,
        company: SalaryCompanyStore.Company,
        referenceDate: LocalDate,
        jobs: List<LegalReanalysisPlanClientV2.Job>,
        nowMs: Long
    ): Task<KaliOutcome> {
        if (jobs.isEmpty()) return Tasks.forResult(KaliOutcome())
        val idcc = company.idcc.filter(Char::isDigit)
        if (idcc.isBlank()) {
            return Tasks.forResult(KaliOutcome(warnings = listOf("KALI : IDCC requis pour la mise à jour automatique.")))
        }
        markAttempt(context, jobs, "KALI_OVERTIME", nowMs)
        return KaliOvertimePayrollAuditV2.audit(context, idcc, referenceDate)
            .continueWith { task ->
                if (!task.isSuccessful) {
                    return@continueWith KaliOutcome(1, false, listOf("KALI : contrôle automatique impossible."))
                }
                val summary = task.result
                val officialAuditCompleted = summary != null && summary.pagesRead > 0
                if (officialAuditCompleted) markDone(context, jobs, "KALI_OVERTIME")
                KaliOutcome(
                    ran = 1,
                    saved = summary?.saved == true,
                    warnings = if (officialAuditCompleted) emptyList() else listOf("KALI : contrôle officiel à retenter ultérieurement.")
                )
            }
    }

    private data class LegiOutcome(
        val ran: Int = 0,
        val verifiedArticles: Int = 0,
        val warnings: List<String> = emptyList()
    )

    private fun runLegi(
        context: Context,
        referenceDate: LocalDate,
        jobs: List<LegalReanalysisPlanClientV2.Job>,
        nowMs: Long
    ): Task<LegiOutcome> {
        if (jobs.isEmpty()) return Tasks.forResult(LegiOutcome())
        markAttempt(context, jobs, "LEGI_ALL", nowMs)
        val atMs = referenceDate.atTime(12, 0).atZone(PARIS).toInstant().toEpochMilli()
        return LegalPayrollAuditV2.auditAll(context, atMs)
            .continueWith { task ->
                if (!task.isSuccessful) {
                    return@continueWith LegiOutcome(1, 0, listOf("LEGI : contrôle automatique impossible."))
                }
                val summary = task.result
                val completed = summary != null && legiAuditCompleted(summary)
                if (completed) markDone(context, jobs, "LEGI_ALL")
                LegiOutcome(
                    ran = 1,
                    verifiedArticles = summary?.verifiedArticles ?: 0,
                    warnings = if (completed) emptyList() else listOf("LEGI : contrôle officiel à retenter ultérieurement.")
                )
            }
    }

    private data class AccoOutcome(
        val ran: Int = 0,
        val extractedCandidates: Int = 0,
        val warnings: List<String> = emptyList()
    )

    private fun runAcco(
        context: Context,
        company: SalaryCompanyStore.Company,
        jobs: List<LegalReanalysisPlanClientV2.Job>,
        nowMs: Long
    ): Task<AccoOutcome> {
        if (jobs.isEmpty()) return Tasks.forResult(AccoOutcome())
        val siret = company.siret.filter(Char::isDigit)
        if (siret.length != 14) {
            return Tasks.forResult(AccoOutcome(warnings = listOf("ACCO : SIRET requis pour la réanalyse automatique.")))
        }
        markAttempt(context, jobs, "ACCO_EXTRACT_CANDIDATES", nowMs)
        return CompanyAgreementOfficialAuditV2.audit(context, company.id, siret)
            .continueWith { task ->
                if (!task.isSuccessful) {
                    return@continueWith AccoOutcome(1, 0, listOf("ACCO : contrôle automatique impossible."))
                }
                val summary = task.result
                if (summary?.completed == true) markDone(context, jobs, "ACCO_EXTRACT_CANDIDATES")
                AccoOutcome(
                    ran = 1,
                    extractedCandidates = summary?.extractedCandidates ?: 0,
                    warnings = summary?.warnings.orEmpty() +
                        if (summary?.completed == true) emptyList() else listOf("ACCO : contrôle officiel à retenter ultérieurement.")
                )
            }
    }

    internal fun legiAuditCompleted(summary: LegalPayrollAuditV2.Summary): Boolean {
        if (summary.results.isEmpty()) return false
        return summary.results.any { result ->
            result.candidates > 0 || result.verified > 0 || result.saved ||
                result.warnings.isEmpty() || result.warnings.any { !isTransientFailure(it) }
        }
    }

    private fun isTransientFailure(message: String): Boolean {
        val value = message.lowercase(Locale.FRANCE)
        return value.contains("impossible") || value.contains("interrompu") ||
            value.contains("indisponible") || value.contains("erreur") || value.contains("timeout")
    }

    private fun canAttempt(
        prefs: android.content.SharedPreferences,
        job: LegalReanalysisPlanClientV2.Job,
        kind: String,
        nowMs: Long
    ): Boolean {
        if (isDone(prefs, job, kind)) return false
        val lastAttempt = prefs.getLong(attemptKey(job, kind), 0L)
        return lastAttempt <= 0L || nowMs - lastAttempt >= RETRY_DELAY_MS
    }

    private fun isDone(
        prefs: android.content.SharedPreferences,
        job: LegalReanalysisPlanClientV2.Job,
        kind: String
    ): Boolean = prefs.getBoolean(doneKey(job, kind), false)

    private fun markAttempt(
        context: Context,
        jobs: List<LegalReanalysisPlanClientV2.Job>,
        kind: String,
        nowMs: Long
    ) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        jobs.forEach { editor.putLong(attemptKey(it, kind), nowMs) }
        editor.apply()
    }

    private fun markDone(
        context: Context,
        jobs: List<LegalReanalysisPlanClientV2.Job>,
        kind: String
    ) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        jobs.forEach { editor.putBoolean(doneKey(it, kind), true) }
        editor.apply()
    }

    private fun doneKey(job: LegalReanalysisPlanClientV2.Job, kind: String) =
        "done_${kind}_${job.revisionKey}"

    private fun attemptKey(job: LegalReanalysisPlanClientV2.Job, kind: String) =
        "attempt_${kind}_${job.revisionKey}"
}
