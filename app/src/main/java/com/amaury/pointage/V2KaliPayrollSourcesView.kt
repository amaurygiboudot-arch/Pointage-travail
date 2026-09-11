package com.amaury.pointage

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.KaliNightPayrollAuditV2
import com.amaury.pointage.v2.KaliOvertimePayrollAuditV2
import com.amaury.pointage.v2.KaliPublicHolidayPremiumAuditV2
import com.amaury.pointage.v2.KaliWeekdayPremiumAuditV2
import com.amaury.pointage.v2.V2ConventionNightRuleStore
import com.amaury.pointage.v2.V2ConventionPublicHolidayPremiumStore
import com.amaury.pointage.v2.V2ConventionRuleStore
import com.amaury.pointage.v2.V2ConventionWeekdayPremiumStore
import com.amaury.pointage.v2.engine.ConventionNightRuleHistoryV2
import com.amaury.pointage.v2.engine.ConventionPublicHolidayPremiumHistoryV2
import com.amaury.pointage.v2.engine.ConventionRuleHistoryV2
import com.amaury.pointage.v2.engine.ConventionWeekdayPremiumHistoryV2
import com.amaury.pointage.v2.engine.PayrollPeriodV2
import com.amaury.pointage.v2.engine.WeekdayPremiumKindV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

internal fun kaliStorageReliabilityText(
    family: String,
    reliable: Boolean,
    warnings: List<String>
): String? {
    if (reliable) return null
    return buildString {
        append("⚠ Stockage KALI ").append(family)
            .append(" incohérent : aucune règle enregistrée n'est considérée fiable.")
        if (warnings.isNotEmpty()) {
            append('\n').append(warnings.distinct().joinToString(" • "))
        }
    }
}

/** Vue KALI : audits prudents des règles conventionnelles sans déduire une règle d'un simple résultat de recherche. */
class V2KaliPayrollSourcesView(
    context: Context,
    private val company: SalaryCompanyStore.Company
) : LinearLayout(context) {
    private val status = TextView(context)
    private val overtimeButton = Button(context)
    private val nightButton = Button(context)
    private val saturdayButton = Button(context)
    private val sundayButton = Button(context)
    private val publicHolidayButton = Button(context)
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE)
    private var lastOvertimeAuditSummary: KaliOvertimePayrollAuditV2.Summary? = null
    private var lastNightAuditSummary: KaliNightPayrollAuditV2.Summary? = null
    private var lastSaturdayAuditSummary: KaliWeekdayPremiumAuditV2.Summary? = null
    private var lastSundayAuditSummary: KaliWeekdayPremiumAuditV2.Summary? = null
    private var lastPublicHolidayAuditSummary: KaliPublicHolidayPremiumAuditV2.Summary? = null

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        addView(TextView(context).apply {
            text = "RÈGLES CONVENTIONNELLES — KALI"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "HoraTrack consulte KALI par famille de règle. Heures supplémentaires, nuit, samedi, dimanche et jours fériés ne sont enregistrés que si la recherche officielle est suffisamment complète et qu'une règle datée, unique et non conditionnelle peut être structurée. Le 1er mai reste séparé du modèle générique des jours fériés. Une recherche vide ne prouve jamais l'absence de règle."
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        })
        addView(overtimeButton.apply {
            text = "ANALYSER LES HEURES SUP DANS KALI"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { runOvertimeAudit() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        addView(nightButton.apply {
            text = "ANALYSER LE TRAVAIL DE NUIT DANS KALI"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { runNightAudit() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        addView(saturdayButton.apply {
            text = "ANALYSER LE SAMEDI DANS KALI"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { runWeekdayAudit(WeekdayPremiumKindV2.SATURDAY) }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        addView(sundayButton.apply {
            text = "ANALYSER LE DIMANCHE DANS KALI"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { runWeekdayAudit(WeekdayPremiumKindV2.SUNDAY) }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        addView(publicHolidayButton.apply {
            text = "ANALYSER LES JOURS FÉRIÉS DANS KALI"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { runPublicHolidayAudit() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        addView(status.apply {
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        setAuditButtonsEnabled(true)
        refresh()
    }

    fun refresh() {
        val referenceDate = referenceDate()
        val epochDay = referenceDate.toEpochDay()
        val idcc = company.idcc.filter(Char::isDigit)
        val overtimeState = if (idcc.isBlank()) null else V2ConventionRuleStore.readConfirmed(context)
        val snapshot = overtimeState?.takeIf { it.reliable }?.let {
            ConventionRuleHistoryV2(it.snapshots).applicable(idcc, epochDay)
        }
        val nightState = if (idcc.isBlank()) null else V2ConventionNightRuleStore.readConfirmed(context)
        val nightSnapshot = nightState?.takeIf { it.reliable }?.let {
            ConventionNightRuleHistoryV2(it.snapshots).applicable(idcc, epochDay)
        }
        val weekdayState = if (idcc.isBlank()) null else V2ConventionWeekdayPremiumStore.readConfirmed(context)
        val weekdayHistory = weekdayState?.takeIf { it.reliable }?.let {
            ConventionWeekdayPremiumHistoryV2(it.snapshots)
        }
        val saturdaySnapshot = weekdayHistory?.applicable(idcc, WeekdayPremiumKindV2.SATURDAY, epochDay)
        val sundaySnapshot = weekdayHistory?.applicable(idcc, WeekdayPremiumKindV2.SUNDAY, epochDay)
        val publicHolidayState = if (idcc.isBlank()) null else V2ConventionPublicHolidayPremiumStore.readConfirmed(context)
        val publicHolidaySnapshot = publicHolidayState?.takeIf { it.reliable }?.let {
            ConventionPublicHolidayPremiumHistoryV2(it.snapshots).applicable(idcc, epochDay)
        }

        status.text = buildString {
            append("Entreprise : ").append(company.name.ifBlank { "Entreprise" }).append('\n')
            append("IDCC : ").append(idcc.ifBlank { "non renseigné" }).append('\n')
            append("Date de paie contrôlée : ").append(referenceDate.format(dateFormat)).append('\n')
            val overtimeStorageWarning = overtimeState?.let {
                kaliStorageReliabilityText("heures supplémentaires", it.reliable, it.warnings)
            }
            when {
                idcc.isBlank() -> append("IDCC requis pour interroger KALI.")
                overtimeStorageWarning != null -> append(overtimeStorageWarning)
                snapshot == null -> {
                    append("Aucun barème d’heures supplémentaires KALI confirmé n’est enregistré pour cette date.\n")
                    append("Cela ne signifie pas qu’aucune règle conventionnelle n’existe.")
                }
                snapshot.rules.overtimeTiers.isEmpty() -> {
                    append("Snapshot KALI présent, mais aucun barème complet d’heures supplémentaires n’est exploitable.")
                }
                else -> {
                    append("Barème heures supplémentaires confirmé :\n")
                    snapshot.rules.overtimeTiers.forEach { tier ->
                        append("• ").append(formatBand(tier.fromMinutes, tier.toMinutes))
                            .append(" : +")
                            .append(formatPercent((tier.multiplier - 1.0) * 100.0)).append(" %\n")
                    }
                    append("Source : ").append(snapshot.sourceId).append('\n')
                    append("Applicable depuis : ")
                        .append(LocalDate.ofEpochDay(snapshot.effectiveFromEpochDay).format(dateFormat))
                    snapshot.effectiveToEpochDay?.let {
                        append(" jusqu’au ").append(LocalDate.ofEpochDay(it).format(dateFormat))
                    }
                }
            }

            if (idcc.isNotBlank()) {
                append("\n\nRÈGLE NUIT KALI\n")
                val nightStorageWarning = nightState?.let {
                    kaliStorageReliabilityText("nuit", it.reliable, it.warnings)
                }
                if (nightStorageWarning != null) {
                    append(nightStorageWarning)
                } else if (nightSnapshot == null) {
                    append("Aucune règle de nuit KALI confirmée pour cette date.")
                } else {
                    append("Plage : ").append(formatMinute(nightSnapshot.rule.startMinute))
                        .append(" → ").append(formatMinute(nightSnapshot.rule.endMinute)).append('\n')
                    append("Majoration : +").append(formatPercent(nightSnapshot.rule.percentage)).append(" %\n")
                    append("Source : ").append(nightSnapshot.sourceId).append('\n')
                    append("Applicable depuis : ")
                        .append(LocalDate.ofEpochDay(nightSnapshot.effectiveFromEpochDay).format(dateFormat))
                    nightSnapshot.effectiveToEpochDay?.let {
                        append(" jusqu’au ").append(LocalDate.ofEpochDay(it).format(dateFormat))
                    }
                }

                val weekdayStorageWarning = weekdayState?.let {
                    kaliStorageReliabilityText("samedi/dimanche", it.reliable, it.warnings)
                }
                if (weekdayStorageWarning != null) {
                    append("\n\nRÈGLES SAMEDI / DIMANCHE KALI\n")
                    append(weekdayStorageWarning)
                } else {
                    append("\n\nRÈGLE SAMEDI KALI\n")
                    if (saturdaySnapshot == null) {
                        append("Aucune majoration simple du samedi KALI confirmée pour cette date.")
                    } else {
                        append("Majoration : +").append(formatPercent(saturdaySnapshot.rule.percentage)).append(" %\n")
                        append("Source : ").append(saturdaySnapshot.sourceId).append('\n')
                        append("Applicable depuis : ")
                            .append(LocalDate.ofEpochDay(saturdaySnapshot.effectiveFromEpochDay).format(dateFormat))
                        saturdaySnapshot.effectiveToEpochDay?.let {
                            append(" jusqu’au ").append(LocalDate.ofEpochDay(it).format(dateFormat))
                        }
                    }

                    append("\n\nRÈGLE DIMANCHE KALI\n")
                    if (sundaySnapshot == null) {
                        append("Aucune majoration simple du dimanche KALI confirmée pour cette date.")
                    } else {
                        append("Majoration : +").append(formatPercent(sundaySnapshot.rule.percentage)).append(" %\n")
                        append("Source : ").append(sundaySnapshot.sourceId).append('\n')
                        append("Applicable depuis : ")
                            .append(LocalDate.ofEpochDay(sundaySnapshot.effectiveFromEpochDay).format(dateFormat))
                        sundaySnapshot.effectiveToEpochDay?.let {
                            append(" jusqu’au ").append(LocalDate.ofEpochDay(it).format(dateFormat))
                        }
                    }
                }

                append("\n\nRÈGLE JOURS FÉRIÉS KALI\n")
                val publicHolidayStorageWarning = publicHolidayState?.let {
                    kaliStorageReliabilityText("jours fériés", it.reliable, it.warnings)
                }
                if (publicHolidayStorageWarning != null) {
                    append(publicHolidayStorageWarning)
                } else if (publicHolidaySnapshot == null) {
                    append("Aucune majoration uniforme des jours fériés KALI confirmée pour cette date.")
                } else {
                    append("Majoration générique : +")
                        .append(formatPercent(publicHolidaySnapshot.rule.percentage)).append(" %\n")
                    append("Source : ").append(publicHolidaySnapshot.sourceId).append('\n')
                    append("Applicable depuis : ")
                        .append(LocalDate.ofEpochDay(publicHolidaySnapshot.effectiveFromEpochDay).format(dateFormat))
                    publicHolidaySnapshot.effectiveToEpochDay?.let {
                        append(" jusqu’au ").append(LocalDate.ofEpochDay(it).format(dateFormat))
                    }
                    append("\nLe 1er mai n'est jamais déduit de cette règle générique.")
                }
            }

            lastOvertimeAuditSummary?.let { summary ->
                append("\n\nDERNIER AUDIT KALI — HEURES SUP\n")
                append("Pages analysées : ").append(summary.pagesRead).append('\n')
                append("Candidats trouvés : ").append(summary.candidates).append('\n')
                append("Articles examinés : ").append(summary.articlesConsulted).append('\n')
                append("Barèmes structurés : ").append(summary.structuredSchedules).append('\n')
                append("Barème enregistré : ").append(if (summary.saved) "oui" else "non")
                summary.selectedSourceId?.let { append("\nSource retenue : ").append(it) }
                if (summary.warnings.isNotEmpty()) {
                    append("\n\nDiagnostic heures sup :")
                    summary.warnings.forEach { warning -> append("\n• ").append(warning) }
                }
            }

            lastNightAuditSummary?.let { summary ->
                append("\n\nDERNIER AUDIT KALI — TRAVAIL DE NUIT\n")
                append("Pages analysées : ").append(summary.pagesRead).append('\n')
                append("Candidats trouvés : ").append(summary.candidates).append('\n')
                append("Articles examinés : ").append(summary.articlesConsulted).append('\n')
                append("Candidats plage+taux structurés : ").append(summary.structuredCandidates).append('\n')
                append("Règle nuit enregistrée : ").append(if (summary.saved) "oui" else "non")
                summary.selectedSourceId?.let { append("\nSource retenue : ").append(it) }
                if (summary.previews.isNotEmpty()) {
                    append("\n\nCandidats structurés :")
                    summary.previews.forEach { preview ->
                        append("\n• ").append(preview.articleId)
                            .append(" — ").append(formatMinute(preview.startMinute))
                            .append(" → ").append(formatMinute(preview.endMinute))
                            .append(" : +").append(formatPercent(preview.percentage)).append(" %")
                            .append(" — depuis ").append(preview.effectiveFrom.format(dateFormat))
                        preview.effectiveTo?.let { append(" jusqu’au ").append(it.format(dateFormat)) }
                    }
                }
                if (summary.warnings.isNotEmpty()) {
                    append("\n\nDiagnostic nuit :")
                    summary.warnings.forEach { warning -> append("\n• ").append(warning) }
                }
            }

            appendWeekdayAudit(lastSaturdayAuditSummary, "SAMEDI")
            appendWeekdayAudit(lastSundayAuditSummary, "DIMANCHE")
            appendPublicHolidayAudit(lastPublicHolidayAuditSummary)
        }
    }

    private fun StringBuilder.appendWeekdayAudit(summary: KaliWeekdayPremiumAuditV2.Summary?, title: String) {
        summary ?: return
        append("\n\nDERNIER AUDIT KALI — ").append(title).append('\n')
        append("Pages analysées : ").append(summary.pagesRead).append('\n')
        append("Candidats trouvés : ").append(summary.candidates).append('\n')
        append("Articles examinés : ").append(summary.articlesConsulted).append('\n')
        append("Candidats simples structurés : ").append(summary.structuredCandidates).append('\n')
        append("Règle enregistrée : ").append(if (summary.saved) "oui" else "non")
        summary.selectedSourceId?.let { append("\nSource retenue : ").append(it) }
        if (summary.previews.isNotEmpty()) {
            append("\n\nCandidats structurés :")
            summary.previews.forEach { preview ->
                append("\n• ").append(preview.articleId)
                    .append(" : +").append(formatPercent(preview.percentage)).append(" %")
                    .append(" — depuis ").append(preview.effectiveFrom.format(dateFormat))
                preview.effectiveTo?.let { append(" jusqu’au ").append(it.format(dateFormat)) }
            }
        }
        if (summary.warnings.isNotEmpty()) {
            append("\n\nDiagnostic ").append(title.lowercase(Locale.FRANCE)).append(" :")
            summary.warnings.forEach { warning -> append("\n• ").append(warning) }
        }
    }

    private fun StringBuilder.appendPublicHolidayAudit(summary: KaliPublicHolidayPremiumAuditV2.Summary?) {
        summary ?: return
        append("\n\nDERNIER AUDIT KALI — JOURS FÉRIÉS\n")
        append("Pages analysées : ").append(summary.pagesRead).append('\n')
        append("Candidats trouvés : ").append(summary.candidates).append('\n')
        append("Articles examinés : ").append(summary.articlesConsulted).append('\n')
        append("Candidats uniformes structurés : ").append(summary.structuredCandidates).append('\n')
        append("Règle enregistrée : ").append(if (summary.saved) "oui" else "non")
        summary.selectedSourceId?.let { append("\nSource retenue : ").append(it) }
        if (summary.previews.isNotEmpty()) {
            append("\n\nCandidats structurés :")
            summary.previews.forEach { preview ->
                append("\n• ").append(preview.articleId)
                    .append(" : +").append(formatPercent(preview.percentage)).append(" %")
                    .append(" — depuis ").append(preview.effectiveFrom.format(dateFormat))
                preview.effectiveTo?.let { append(" jusqu’au ").append(it.format(dateFormat)) }
            }
        }
        if (summary.warnings.isNotEmpty()) {
            append("\n\nDiagnostic jours fériés :")
            summary.warnings.forEach { warning -> append("\n• ").append(warning) }
        }
    }

    private fun runOvertimeAudit() {
        val referenceDate = referenceDate()
        val idcc = company.idcc.filter(Char::isDigit)
        if (idcc.isBlank()) return
        setAuditButtonsEnabled(false)
        lastOvertimeAuditSummary = null
        status.text = "Analyse KALI des heures supplémentaires en cours pour ${referenceDate.format(dateFormat)}…"
        KaliOvertimePayrollAuditV2.audit(context, idcc, referenceDate)
            .addOnSuccessListener { summary ->
                setAuditButtonsEnabled(true)
                lastOvertimeAuditSummary = summary
                refresh()
                val message = when {
                    summary.saved -> "KALI : barème conventionnel vérifié et enregistré."
                    summary.structuredSchedules > 0 && summary.warnings.isNotEmpty() -> summary.warnings.last()
                    summary.warnings.isNotEmpty() -> summary.warnings.first()
                    else -> "KALI : aucun barème complet n’a pu être confirmé."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                setAuditButtonsEnabled(true)
                lastOvertimeAuditSummary = null
                status.text = "Analyse KALI des heures supplémentaires impossible : ${error.message ?: "erreur inconnue"}"
            }
    }

    private fun runNightAudit() {
        val referenceDate = referenceDate()
        val idcc = company.idcc.filter(Char::isDigit)
        if (idcc.isBlank()) return
        setAuditButtonsEnabled(false)
        lastNightAuditSummary = null
        status.text = "Analyse KALI du travail de nuit en cours pour ${referenceDate.format(dateFormat)}…"
        KaliNightPayrollAuditV2.audit(context, idcc, referenceDate)
            .addOnSuccessListener { summary ->
                setAuditButtonsEnabled(true)
                lastNightAuditSummary = summary
                refresh()
                val message = when {
                    summary.saved -> "KALI nuit : règle vérifiée et enregistrée."
                    summary.structuredCandidates > 0 -> "KALI nuit : candidat structuré trouvé mais non enregistré ; voir le diagnostic."
                    summary.warnings.isNotEmpty() -> summary.warnings.first()
                    else -> "KALI nuit : aucune règle simple n’a pu être structurée."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                setAuditButtonsEnabled(true)
                lastNightAuditSummary = null
                status.text = "Analyse KALI du travail de nuit impossible : ${error.message ?: "erreur inconnue"}"
            }
    }

    private fun runWeekdayAudit(kind: WeekdayPremiumKindV2) {
        val referenceDate = referenceDate()
        val idcc = company.idcc.filter(Char::isDigit)
        if (idcc.isBlank()) return
        setAuditButtonsEnabled(false)
        if (kind == WeekdayPremiumKindV2.SATURDAY) lastSaturdayAuditSummary = null else lastSundayAuditSummary = null
        val label = if (kind == WeekdayPremiumKindV2.SATURDAY) "samedi" else "dimanche"
        status.text = "Analyse KALI du $label en cours pour ${referenceDate.format(dateFormat)}…"
        KaliWeekdayPremiumAuditV2.audit(context, idcc, kind, referenceDate)
            .addOnSuccessListener { summary ->
                setAuditButtonsEnabled(true)
                if (kind == WeekdayPremiumKindV2.SATURDAY) lastSaturdayAuditSummary = summary else lastSundayAuditSummary = summary
                refresh()
                val message = when {
                    summary.saved -> "KALI $label : règle vérifiée et enregistrée."
                    summary.structuredCandidates > 0 -> "KALI $label : candidat structuré trouvé mais non enregistré ; voir le diagnostic."
                    summary.warnings.isNotEmpty() -> summary.warnings.first()
                    else -> "KALI $label : aucune règle simple n’a pu être structurée."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                setAuditButtonsEnabled(true)
                if (kind == WeekdayPremiumKindV2.SATURDAY) lastSaturdayAuditSummary = null else lastSundayAuditSummary = null
                status.text = "Analyse KALI du $label impossible : ${error.message ?: "erreur inconnue"}"
            }
    }

    private fun runPublicHolidayAudit() {
        val referenceDate = referenceDate()
        val idcc = company.idcc.filter(Char::isDigit)
        if (idcc.isBlank()) return
        setAuditButtonsEnabled(false)
        lastPublicHolidayAuditSummary = null
        status.text = "Analyse KALI des jours fériés en cours pour ${referenceDate.format(dateFormat)}…"
        KaliPublicHolidayPremiumAuditV2.audit(context, idcc, referenceDate)
            .addOnSuccessListener { summary ->
                setAuditButtonsEnabled(true)
                lastPublicHolidayAuditSummary = summary
                refresh()
                val message = when {
                    summary.saved -> "KALI jours fériés : règle uniforme vérifiée et enregistrée."
                    summary.structuredCandidates > 0 -> "KALI jours fériés : candidat structuré trouvé mais non enregistré ; voir le diagnostic."
                    summary.warnings.isNotEmpty() -> summary.warnings.first()
                    else -> "KALI jours fériés : aucune règle uniforme n’a pu être structurée."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                setAuditButtonsEnabled(true)
                lastPublicHolidayAuditSummary = null
                status.text = "Analyse KALI des jours fériés impossible : ${error.message ?: "erreur inconnue"}"
            }
    }

    private fun setAuditButtonsEnabled(enabled: Boolean) {
        val hasIdcc = company.idcc.filter(Char::isDigit).isNotBlank()
        overtimeButton.isEnabled = enabled && hasIdcc
        nightButton.isEnabled = enabled && hasIdcc
        saturdayButton.isEnabled = enabled && hasIdcc
        sundayButton.isEnabled = enabled && hasIdcc
        publicHolidayButton.isEnabled = enabled && hasIdcc
    }

    private fun referenceDate(): LocalDate {
        val prefs = context.getSharedPreferences("navigation_state", Context.MODE_PRIVATE)
        val selectedMs = prefs.getLong("report_month_ms", -1L)
        val calendar = Calendar.getInstance(Locale.FRANCE)
        if (selectedMs > 0L) calendar.timeInMillis = selectedMs
        return PayrollPeriodV2.month(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH)).referenceDate
    }

    private fun formatBand(fromMinutes: Int, toMinutes: Int?): String {
        val fromHour = fromMinutes / 60 + 1
        return if (toMinutes == null) "à partir de la ${fromHour}e heure" else {
            val toHour = toMinutes / 60
            "${fromHour}e à ${toHour}e heure"
        }
    }

    private fun formatMinute(minute: Int): String =
        String.format(Locale.FRANCE, "%02d:%02d", minute / 60, minute % 60)

    private fun formatPercent(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.FRANCE, "%.2f", value)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
