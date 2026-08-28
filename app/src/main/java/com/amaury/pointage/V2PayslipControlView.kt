package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.amaury.pointage.v2.V2PayslipStore
import java.text.DateFormatSymbols
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
            text = "Importe un PDF ou une photo puis confirme les montants lus. Un écart est toujours présenté « À vérifier » et jamais comme une erreur employeur automatique."
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
