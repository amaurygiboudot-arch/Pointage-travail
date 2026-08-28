package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.V2PayslipStore
import com.amaury.pointage.v2.engine.SalaryExamplePdfV2
import java.io.File
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class V2PayslipControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    companion object { const val TAG = "v2_payslip_control" }
    private val status = TextView(context)

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(16), 0, dp(6))
        addView(TextView(context).apply { text = "CONTRÔLE DU BULLETIN DE PAIE"; textSize = 15f })
        addView(TextView(context).apply {
            text = "Importe un PDF ou une photo puis confirme les montants lus. Tant qu'une extraction fiable n'est pas disponible, HoraTrack n'invente aucune lecture. Un écart reste toujours « À vérifier »."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })
        addView(Button(context).apply {
            text = "📄 IMPORTER UN BULLETIN PDF / PHOTO"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { (context as? Activity)?.startActivity(Intent(context, V2PayslipImportActivity::class.java)) }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        addView(Button(context).apply {
            text = "🧾 CRÉER UNE FICHE DE PAIE EXEMPLE"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { choosePdfFields() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(7) })
        addView(status.apply { textSize = 13f; setPadding(0, dp(8), 0, 0) })
        refresh()
    }

    fun refresh() {
        val record = V2PayslipStore.latest(context)
        if (record == null) {
            status.text = "Aucun bulletin importé."
            return
        }
        val monthName = DateFormatSymbols(Locale.FRANCE).months.getOrNull(record.month).orEmpty().replaceFirstChar { it.uppercase() }
        val comparison = V2PayslipStore.comparison(context, record)
        val gross = record.gross?.let { String.format(Locale.FRANCE, "%.2f €", it) } ?: "à confirmer"
        status.text = when {
            comparison == null -> "$monthName ${record.year} • brut $gross • fiche Salaire à compléter avant comparaison."
            comparison.conforming -> "$monthName ${record.year} • brut $gross • ✓ concordance V2 dans la tolérance."
            else -> {
                val d = comparison.discrepancies.firstOrNull()
                val delta = if (d?.expected != null && d.observed != null) abs(d.observed - d.expected) else null
                "$monthName ${record.year} • brut $gross • ⚠ À vérifier${delta?.let { " • écart ${String.format(Locale.FRANCE, "%.2f €", it)}" }.orEmpty()}"
            }
        }
    }

    private fun choosePdfFields() {
        val activity = context as? Activity ?: return
        val fields = SalaryExamplePdfV2.Field.entries
        val labels = arrayOf(
            "Entreprise et convention",
            "Contrat et taux horaire",
            "Heures normales / supplémentaires",
            "Pauses déduites",
            "Estimation brute",
            "Compteurs de droits",
            "Sources et éléments à vérifier"
        )
        val selected = BooleanArray(fields.size) { true }
        AlertDialog.Builder(activity)
            .setTitle("Éléments à afficher sur le PDF")
            .setMessage("Ces cases servent uniquement à choisir le contenu. Elles ne seront pas imprimées sur la fiche.")
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("OUVRIR LE PDF") { _, _ ->
                val chosen = fields.filterIndexed { index, _ -> selected[index] }.toSet()
                if (chosen.isEmpty()) {
                    Toast.makeText(activity, "Choisis au moins un élément", Toast.LENGTH_LONG).show()
                } else generatePdf(activity, chosen)
            }
            .setNegativeButton("ANNULER", null)
            .show()
    }

    private fun generatePdf(activity: Activity, fields: Set<SalaryExamplePdfV2.Field>) {
        val latest = V2PayslipStore.latest(activity)
        val cal = Calendar.getInstance(Locale.FRANCE)
        val year = latest?.year ?: cal.get(Calendar.YEAR)
        val month = latest?.month ?: cal.get(Calendar.MONTH)
        runCatching {
            val file = File(activity.cacheDir, "Fiche_paie_exemple_HoraTrack_${year}_${month + 1}.pdf")
            file.outputStream().use { SalaryExamplePdfV2.write(activity, year, month, fields, it) }
            activity.startActivity(Intent(activity, PdfPreviewActivity::class.java).apply {
                putExtra("pdf_path", file.absolutePath)
                putExtra("pdf_name", "Fiche_paie_exemple_HoraTrack_${year}_${month + 1}.pdf")
            })
        }.onFailure {
            Toast.makeText(activity, "Impossible de générer la fiche exemple", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
