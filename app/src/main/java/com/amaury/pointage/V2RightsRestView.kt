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

class V2RightsRestView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val companyId: String = ""
) : LinearLayout(context, attrs) {

    companion object {
        const val TAG = "v2_rights_rest"
    }

    private val content = TextView(context)

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(16), 0, dp(6))
        addView(TextView(context).apply {
            text = "DROITS, CONGÉS & REPOS"
            textSize = 15f
        })
        addView(TextView(context).apply {
            text = "Les compteurs sont propres à l’entreprise sélectionnée. Le repos est calculé automatiquement ; aucune règle légale ou conventionnelle non confirmée n’est inventée."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })
        addView(
            Button(context).apply {
                text = "➕ AJOUTER / METTRE À JOUR UN COMPTEUR"
                isAllCaps = false
                textSize = 14f
                setBackgroundResource(R.drawable.hp_panel)
                setOnClickListener { showCounterDialog() }
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )
        addView(content.apply {
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
        refresh()
    }

    fun refresh() {
        val balances = if (companyId.isBlank()) {
            V2RightsStore.all(context)
        } else {
            V2RightsStore.forCompany(context, companyId)
        }
        val rights = if (balances.isEmpty()) {
            "Compteurs : aucun renseigné pour cette entreprise."
        } else {
            balances.joinToString("\n") { b ->
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
        }
        val sessions = if (companyId.isBlank()) {
            V2RuntimeStore.allSessions(context)
        } else {
            val accepted = SalaryCompanyStore.acceptedEmployerIds(context, companyId)
            V2RuntimeStore.allSessions(context).filter { it.employerId in accepted }
        }
        val latest = RestEngineV2.dailyRests(sessions).lastOrNull()
        val rest = latest?.let {
            "Dernier repos entre journées : ${duration(it.restMs)} • conformité légale : À confirmer selon la règle applicable"
        } ?: "Repos quotidien : pas encore assez de journées terminées pour calculer un intervalle."
        val warnings = if (companyId.isBlank()) {
            V2RightsStore.snapshot(context).warnings
        } else {
            V2RightsStore.snapshot(context, companyId = companyId).warnings
        }
        content.text = buildString {
            append(rights).append("\n\n").append(rest)
            if (warnings.isNotEmpty()) {
                append("\n\n⚠ ").append(warnings.joinToString("\n⚠ "))
            }
        }
    }

    private fun showCounterDialog() {
        fun amount(h: String) = EditText(context).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            isSingleLine = true
        }

        val label = EditText(context).apply {
            hint = "Nom du compteur — ex. Congés payés"
            isSingleLine = true
        }
        val unit = EditText(context).apply {
            hint = "Unité — ex. jours ou heures"
            isSingleLine = true
            setText("jours")
        }
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
            .setMessage("HoraTrack enregistre les valeurs pour cette entreprise. La période de référence reste non renseignée sans source fiable.")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = label.text.toString().trim()
                if (name.isBlank()) {
                    label.error = "Nom requis"
                    return@setOnClickListener
                }
                val values = listOf(acquired, available, taken, anticipated, remaining)
                    .map { parse(it.text.toString()) }
                if (values.all { it == null }) {
                    Toast.makeText(context, "Renseigne au moins une valeur", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                V2RightsStore.upsert(
                    context,
                    V2RightsStore.Balance(
                        "manual-${UUID.randomUUID()}",
                        name,
                        values[0],
                        values[1],
                        values[2],
                        values[3],
                        values[4],
                        unit.text.toString().trim().ifBlank { "jours" },
                        0L,
                        Long.MAX_VALUE,
                        "MANUAL",
                        companyId
                    )
                )
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun parse(raw: String) = raw.trim().replace(',', '.').toDoubleOrNull()

    private fun number(v: Double) = String.format(Locale.FRANCE, "%.2f", v)
        .trimEnd('0')
        .trimEnd(',')

    private fun duration(ms: Long): String {
        val m = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%dh%02d", m / 60L, m % 60L)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
