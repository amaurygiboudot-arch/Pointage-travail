package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.RestEngineV2
import java.util.Locale
import java.util.UUID

/** Affiche les droits déclarés et le repos réellement observé entre journées. */
class V2RightsRestView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    companion object { const val TAG = "v2_rights_rest" }

    private val content = TextView(context)

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(16), 0, dp(6))
        addView(TextView(context).apply { text = "DROITS, CONGÉS & REPOS"; textSize = 15f })
        addView(TextView(context).apply {
            text = "Les compteurs restent déclaratifs. Le repos est calculé entre la sortie comptée et l'entrée comptée suivante ; aucun seuil légal n'est inventé si la règle applicable n'est pas confirmée."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })
        addView(Button(context).apply {
            text = "➕ AJOUTER / METTRE À JOUR UN COMPTEUR"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { showCounterDialog() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        addView(content.apply { textSize = 13f; setPadding(0, dp(8), 0, 0) })
        refresh()
    }

    fun refresh() {
        val balances = V2RightsStore.all(context)
        val rightsText = if (balances.isEmpty()) {
            "Compteurs : aucun renseigné."
        } else balances.joinToString("\n") { b ->
            buildString {
                append("• ").append(b.label)
                b.acquired?.let { append(" • acquis ").append(number(it)) }
                b.available?.let { append(" • disponible ").append(number(it)) }
                b.taken?.let { append(" • pris ").append(number(it)) }
                b.anticipated?.let { append(" • anticipé ").append(number(it)) }
                b.remaining?.let { append(" • restant ").append(number(it)) }
                append(' ').append(b.unit)
            }
        }

        val rests = RestEngineV2.dailyRests(V2RuntimeStore.allSessions(context)).takeLast(4)
        val restText = if (rests.isEmpty()) {
            "Repos quotidien : pas encore assez de journées terminées pour calculer un intervalle."
        } else {
            "Repos entre journées :\n" + rests.joinToString("\n") { "• ${duration(it.restMs)} • conformité légale : À confirmer selon la règle applicable" }
        }

        val warnings = V2RightsStore.snapshot(context).warnings
        content.text = buildString {
            append(rightsText).append("\n\n").append(restText)
            if (warnings.isNotEmpty()) append("\n\n⚠ ").append(warnings.joinToString("\n⚠ "))
        }
    }

    private fun showCounterDialog() {
        fun amount(hintText: String) = EditText(context).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            isSingleLine = true
        }
        val label = EditText(context).apply { hint = "Nom du compteur — ex. Congés payés"; isSingleLine = true }
        val unit = EditText(context).apply { hint = "Unité — ex. jours ou heures"; isSingleLine = true; setText("jours") }
        val acquired = amount("Acquis — facultatif")
        val available = amount("Disponible — facultatif")
        val taken = amount("Pris — facultatif")
        val anticipated = amount("Anticipé — facultatif")
        val remaining = amount("Restant — facultatif")
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            listOf(label, unit, acquired, available, taken, anticipated, remaining).forEach(::addView)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Compteur de droits")
            .setMessage("HoraTrack enregistre uniquement les valeurs que tu renseignes. La période de référence reste non renseignée tant qu'aucune source fiable ne l'a fournie.")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = label.text.toString().trim()
                val unitValue = unit.text.toString().trim().ifBlank { "jours" }
                if (name.isBlank()) { label.error = "Nom requis"; return@setOnClickListener }
                val values = listOf(acquired, available, taken, anticipated, remaining).map { parse(it.text.toString()) }
                if (values.all { it == null }) {
                    Toast.makeText(context, "Renseigne au moins une valeur", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                V2RightsStore.upsert(
                    context,
                    V2RightsStore.Balance(
                        id = "manual-${UUID.randomUUID()}",
                        label = name,
                        acquired = values[0],
                        available = values[1],
                        taken = values[2],
                        anticipated = values[3],
                        remaining = values[4],
                        unit = unitValue,
                        referenceStartMs = 0L,
                        referenceEndMs = Long.MAX_VALUE,
                        source = "MANUAL"
                    )
                )
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun parse(raw: String): Double? = raw.trim().replace(',', '.').toDoubleOrNull()
    private fun number(value: Double): String = String.format(Locale.FRANCE, "%.2f", value).trimEnd('0').trimEnd(',')
    private fun duration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%dh%02d", minutes / 60L, minutes % 60L)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
