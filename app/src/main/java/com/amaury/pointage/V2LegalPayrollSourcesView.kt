package com.amaury.pointage

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.LegalPayrollAuditV2
import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.OfficialLegalCodeSourceV2
import com.amaury.pointage.v2.engine.PayrollPeriodV2
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** Vue de contrôle des sources légales LEGI utilisées comme piste d'audit de la paie. */
class V2LegalPayrollSourcesView(context: Context) : LinearLayout(context) {
    private val status = TextView(context)
    private val verifyButton = Button(context)

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        addView(TextView(context).apply {
            text = "SOURCES LÉGALES — LEGI"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "HoraTrack recherche les articles du Code du travail à la date de paie, consulte chaque candidat et ne conserve que les articles dont l'identifiant, l'état VIGUEUR et la période sont confirmés. Aucun taux n'est inventé à partir d'un texte ambigu."
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        })
        addView(verifyButton.apply {
            text = "VÉRIFIER LE CODE DU TRAVAIL"
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
        val snapshot = LegalPayrollSourceStoreV2.snapshot(context, reference.atMs)
        status.text = buildString {
            append("Date de paie contrôlée : ").append(reference.label).append('\n')
            if (snapshot.records.isEmpty()) {
                append("Aucune source LEGI vérifiée pour cette date.")
            } else {
                append(snapshot.coveredTopics.size).append(" / ")
                    .append(OfficialLegalCodeSourceV2.Topic.entries.size)
                    .append(" thèmes couverts\n")
                snapshot.coveredTopics.sortedBy { it.ordinal }.forEach { topic ->
                    val articles = snapshot.records.filter { it.topic == topic }
                    append("• ").append(topic.label).append(" : ")
                    append(articles.joinToString(", ") { it.articleNumber ?: it.articleId })
                    append('\n')
                }
                if (snapshot.missingTopics.isNotEmpty()) {
                    append("À compléter : ")
                    append(snapshot.missingTopics.sortedBy { it.ordinal }.joinToString(", ") { it.label })
                } else {
                    append("Contrôle LEGI complet pour les thèmes Salaire V2.")
                }
            }
        }
    }

    private fun runAudit() {
        val reference = referenceDate()
        verifyButton.isEnabled = false
        status.text = "Vérification LEGI en cours pour ${reference.label}…"
        LegalPayrollAuditV2.auditAll(context, reference.atMs)
            .addOnSuccessListener { summary ->
                verifyButton.isEnabled = true
                refresh()
                val message = if (summary.verifiedArticles > 0) {
                    "LEGI : ${summary.verifiedArticles} article(s) vérifié(s) sur ${summary.completedTopics} thème(s)."
                } else {
                    summary.warnings.firstOrNull() ?: "Aucun article LEGI vérifiable pour cette date."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                verifyButton.isEnabled = true
                status.text = "Vérification LEGI impossible : ${error.message ?: "erreur inconnue"}"
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
