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
import com.amaury.pointage.v2.V2ConventionRuleStore
import com.amaury.pointage.v2.engine.PayrollPeriodV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** Vue KALI : audits prudents des règles conventionnelles sans déduire une règle d'un simple résultat de recherche. */
class V2KaliPayrollSourcesView(
    context: Context,
    private val company: SalaryCompanyStore.Company
) : LinearLayout(context) {
    private val status = TextView(context)
    private val overtimeButton = Button(context)
    private val nightButton = Button(context)
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE)
    private var lastOvertimeAuditSummary: KaliOvertimePayrollAuditV2.Summary? = null
    private var lastNightAuditSummary: KaliNightPayrollAuditV2.Summary? = null

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        addView(TextView(context).apply {
            text = "RÈGLES CONVENTIONNELLES — KALI"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "HoraTrack consulte les sources officielles KALI par famille de règle. Les heures supplémentaires peuvent être enregistrées uniquement lorsqu’un barème complet est vérifiable. Pour le travail de nuit, l’audit reste volontairement en diagnostic tant que la plage horaire officielle n’est pas reliée directement au calcul des minutes. Une recherche vide ne prouve jamais qu’aucune règle n’existe."
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
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(8)
        })
        addView(status.apply {
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        setAuditButtonsEnabled(true)
        refresh()
    }

    fun refresh() {
        val referenceDate = referenceDate()
        val idcc = company.idcc.filter(Char::isDigit)
        val snapshot = if (idcc.isBlank()) null else runCatching {
            V2ConventionRuleStore.history(context).applicable(idcc, referenceDate.toEpochDay())
        }.getOrNull()

        status.text = buildString {
            append("Entreprise : ").append(company.name.ifBlank { "Entreprise" }).append('\n')
            append("IDCC : ").append(idcc.ifBlank { "non renseigné" }).append('\n')
            append("Date de paie contrôlée : ").append(referenceDate.format(dateFormat)).append('\n')
            when {
                idcc.isBlank() -> append("IDCC requis pour interroger KALI.")
                snapshot == null -> {
                    append("Aucun barème KALI confirmé n’est enregistré pour cette date.\n")
                    append("Cela ne signifie pas qu’aucune règle conventionnelle n’existe.")
                }
                snapshot.rules.overtimeTiers.isEmpty() -> {
                    append("Snapshot KALI présent, mais aucun barème complet d’heures supplémentaires n’est exploitable.")
                }
                else -> {
                    append("Barème heures supplémentaires confirmé :\n")
                    snapshot.rules.overtimeTiers.forEach { tier ->
                        append("• ")
                        append(formatBand(tier.fromMinutes, tier.toMinutes))
                        append(" : +")
                        append(formatPercent((tier.multiplier - 1.0) * 100.0))
                        append(" %\n")
                    }
                    append("Source : ").append(snapshot.sourceId).append('\n')
                    append("Applicable depuis : ")
                        .append(LocalDate.ofEpochDay(snapshot.effectiveFromEpochDay).format(dateFormat))
                    snapshot.effectiveToEpochDay?.let {
                        append(" jusqu’au ").append(LocalDate.ofEpochDay(it).format(dateFormat))
                    }
                }
            }

            lastOvertimeAuditSummary?.let { summary ->
                append("\n\nDERNIER AUDIT KALI — HEURES SUP\n")
                append("Pages analysées : ").append(summary.pagesRead).append('\n')
                append("Candidats trouvés : ").append(summary.candidates).append('\n')
                append("Articles examinés : ").append(summary.articlesConsulted).append('\n')
                append("Barèmes structurés : ").append(summary.structuredSchedules).append('\n')
                append("Barème enregistré : ").append(if (summary.saved) "oui" else "non")
                summary.selectedSourceId?.let {
                    append("\nSource retenue : ").append(it)
                }
                if (summary.warnings.isNotEmpty()) {
                    append("\n\nDiagnostic heures sup :")
                    summary.warnings.forEach { warning ->
                        append("\n• ").append(warning)
                    }
                }
            }

            lastNightAuditSummary?.let { summary ->
                append("\n\nDERNIER AUDIT KALI — TRAVAIL DE NUIT\n")
                append("Pages analysées : ").append(summary.pagesRead).append('\n')
                append("Candidats trouvés : ").append(summary.candidates).append('\n')
                append("Articles examinés : ").append(summary.articlesConsulted).append('\n')
                append("Candidats plage+taux structurés : ").append(summary.structuredCandidates).append('\n')
                append("Règle nuit enregistrée : ").append(if (summary.saved) "oui" else "non")
                if (summary.previews.isNotEmpty()) {
                    append("\n\nCandidats structurés :")
                    summary.previews.forEach { preview ->
                        append("\n• ").append(preview.articleId)
                        append(" — ").append(formatMinute(preview.startMinute))
                        append(" → ").append(formatMinute(preview.endMinute))
                        append(" : +").append(formatPercent(preview.percentage)).append(" %")
                        append(" — depuis ").append(preview.effectiveFrom.format(dateFormat))
                        preview.effectiveTo?.let { append(" jusqu’au ").append(it.format(dateFormat)) }
                    }
                }
                if (summary.warnings.isNotEmpty()) {
                    append("\n\nDiagnostic nuit :")
                    summary.warnings.forEach { warning ->
                        append("\n• ").append(warning)
                    }
                }
            }
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
        KaliNightPayrollAuditV2.audit(idcc, referenceDate)
            .addOnSuccessListener { summary ->
                setAuditButtonsEnabled(true)
                lastNightAuditSummary = summary
                refresh()
                val message = when {
                    summary.structuredCandidates > 0 ->
                        "KALI nuit : ${summary.structuredCandidates} candidat(s) structuré(s), aucun appliqué automatiquement."
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

    private fun setAuditButtonsEnabled(enabled: Boolean) {
        val hasIdcc = company.idcc.filter(Char::isDigit).isNotBlank()
        overtimeButton.isEnabled = enabled && hasIdcc
        nightButton.isEnabled = enabled && hasIdcc
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
