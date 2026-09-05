package com.amaury.pointage

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.JorfPayrollAuditV2
import com.amaury.pointage.v2.JorfPayrollSourceStoreV2
import com.amaury.pointage.v2.engine.PayrollPeriodV2
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** Vue JORF : piste récente de publications légales susceptibles d'affecter la paie. */
class V2JorfPayrollSourcesView(context: Context) : LinearLayout(context) {
    private val status = TextView(context)
    private val verifyButton = Button(context)

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        addView(TextView(context).apply {
            text = "PUBLICATIONS OFFICIELLES — JORF"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "HoraTrack parcourt les journaux officiels récents, repère les textes dont le titre touche potentiellement la paie ou le temps de travail, puis consulte le JORFTEXT officiel avant de conserver la référence. Cette piste sert à détecter des publications nouvelles ; elle ne transforme jamais un simple titre en règle chiffrée."
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        })
        addView(verifyButton.apply {
            text = "VÉRIFIER LE JOURNAL OFFICIEL"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
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
        val records = JorfPayrollSourceStoreV2.snapshot(context, reference.atMs)
        status.text = buildString {
            append("Date de paie contrôlée : ").append(reference.label).append('\n')
            append("Périmètre JORF : derniers journaux disponibles jusqu'à cette date\n")
            if (records.isEmpty()) {
                append("Aucune référence JORF vérifiée pour cette date.")
            } else {
                append(records.size).append(" référence(s) JORF vérifiée(s)\n")
                records.take(12).forEach { record ->
                    append("• ").append(record.title)
                    val details = listOfNotNull(record.nature, record.nor, displayDate(record.publicationDate))
                    if (details.isNotEmpty()) append(" — ").append(details.joinToString(" · "))
                    append('\n')
                }
                if (records.size > 12) append("… +").append(records.size - 12).append(" autre(s) référence(s)\n")
                append("Ces textes sont des publications officielles confirmées. Leur effet précis sur la paie reste contrôlé par les moteurs LEGI/KALI et les règles applicables à la date concernée.")
            }
        }
    }

    private fun runAudit() {
        val reference = referenceDate()
        verifyButton.isEnabled = false
        status.text = "Vérification JORF en cours pour ${reference.label}…"
        JorfPayrollAuditV2.audit(context, reference.atMs)
            .addOnSuccessListener { summary ->
                verifyButton.isEnabled = true
                refresh()
                val message = when {
                    summary.verified > 0 -> "JORF : ${summary.verified} référence(s) officielle(s) vérifiée(s)."
                    summary.warnings.isNotEmpty() -> summary.warnings.first()
                    else -> "JORF : aucune publication paie vérifiée."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                verifyButton.isEnabled = true
                status.text = "Vérification JORF impossible : ${error.message ?: "erreur inconnue"}"
            }
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

    private fun displayDate(raw: String): String? {
        val ms = com.amaury.pointage.v2.OfficialJorfVerifierV2.parseDateAtMs(raw) ?: return raw.takeIf { it.isNotBlank() }
        return java.time.Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
