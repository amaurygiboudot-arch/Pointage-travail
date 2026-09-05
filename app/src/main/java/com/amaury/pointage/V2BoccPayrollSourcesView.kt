package com.amaury.pointage

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.BoccPayrollAuditV2
import com.amaury.pointage.v2.BoccPayrollSourceStoreV2
import com.amaury.pointage.v2.engine.PayrollPeriodV2
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** Vue BOCC : piste de publication conventionnelle par entreprise/IDCC, sans extraction automatique de taux. */
class V2BoccPayrollSourcesView(
    context: Context,
    private val company: SalaryCompanyStore.Company
) : LinearLayout(context) {
    private val status = TextView(context)
    private val verifyButton = Button(context)

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        addView(TextView(context).apply {
            text = "SOURCES CONVENTIONNELLES — BOCC"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "HoraTrack contrôle les BOCC publiés pour l’IDCC de l’entreprise et conserve les références des avenants dont le titre touche potentiellement la paie ou le temps de travail. Le BOCC constitue une piste de publication ; KALI reste la source conventionnelle consolidée. Aucune règle chiffrée n’est appliquée à partir du seul titre ou du seul PDF."
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        })
        addView(verifyButton.apply {
            text = "VÉRIFIER LES BOCC"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            isEnabled = company.idcc.filter(Char::isDigit).isNotBlank()
            setOnClickListener { runAudit() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        addView(status.apply {
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        refresh()
    }

    fun refresh() {
        val reference = referenceDate()
        val idcc = company.idcc.filter(Char::isDigit)
        val records = if (idcc.isBlank()) emptyList() else {
            BoccPayrollSourceStoreV2.snapshot(context, company.id, reference.atMs, idcc)
        }
        status.text = buildString {
            append("Entreprise : ").append(company.name.ifBlank { "Entreprise" }).append('\n')
            append("IDCC : ").append(idcc.ifBlank { "non renseigné" }).append('\n')
            append("Date de paie contrôlée : ").append(reference.label).append('\n')
            append("Fenêtre BOCC : 24 mois précédant cette date\n")
            if (idcc.isBlank()) {
                append("IDCC requis pour interroger les BOCC de la convention.")
            } else if (records.isEmpty()) {
                append("Aucune référence BOCC vérifiée pour cette entreprise et cette date.")
            } else {
                append(records.size).append(" référence(s) BOCC vérifiée(s)\n")
                records.take(12).forEach { record ->
                    append("• ").append(record.title)
                    val details = listOfNotNull(record.bulletinNumber, formatPublicationDate(record.publicationDate))
                    if (details.isNotEmpty()) append(" — ").append(details.joinToString(" · "))
                    append('\n')
                }
                if (records.size > 12) append("… +").append(records.size - 12).append(" autre(s) référence(s)\n")
                append("Ces références prouvent des publications BOCC pertinentes ; elles ne suffisent pas seules à créer une règle de paie.")
            }
        }
    }

    private fun runAudit() {
        val reference = referenceDate()
        verifyButton.isEnabled = false
        status.text = "Vérification BOCC en cours pour ${reference.label}…"
        BoccPayrollAuditV2.audit(context, company, reference.atMs)
            .addOnSuccessListener { summary ->
                verifyButton.isEnabled = company.idcc.filter(Char::isDigit).isNotBlank()
                refresh()
                val message = when {
                    summary.verified > 0 -> "BOCC : ${summary.verified} référence(s) officielle(s) vérifiée(s)."
                    summary.warnings.isNotEmpty() -> summary.warnings.first()
                    else -> "BOCC : aucune référence paie vérifiée."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                verifyButton.isEnabled = company.idcc.filter(Char::isDigit).isNotBlank()
                status.text = "Vérification BOCC impossible : ${error.message ?: "erreur inconnue"}"
            }
    }

    private fun formatPublicationDate(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val epochMs = value.toLongOrNull()
        if (epochMs != null && epochMs > 0L) {
            return runCatching {
                Instant.ofEpochMilli(epochMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .format(DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE))
            }.getOrNull() ?: value
        }
        return value
    }

    private data class Reference(val atMs: Long, val label: String)

    private fun referenceDate(): Reference {
        val prefs = context.getSharedPreferences("navigation_state", Context.MODE_PRIVATE)
        val selectedMs = prefs.getLong("report_month_ms", -1L)
        val calendar = Calendar.getInstance(Locale.FRANCE)
        if (selectedMs > 0L) calendar.timeInMillis = selectedMs
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val date = PayrollPeriodV2.month(year, month).referenceDate
        val atMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Reference(
            atMs = atMs,
            label = date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE))
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
