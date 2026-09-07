package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.ConventionClassificationStoreV2
import com.amaury.pointage.v2.engine.ConventionClassificationV2

/** Éditeur volontairement neutre : chaque convention peut utiliser une classification différente. */
object CompanyConventionClassificationDialogV2 {
    fun show(context: Context, companyId: String, onSaved: () -> Unit = {}) {
        val current = ConventionClassificationStoreV2.load(context, companyId)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 8))
        }
        content.addView(TextView(context).apply {
            text = "Recopie uniquement la classification réellement indiquée par le contrat, le bulletin ou une source conventionnelle vérifiée. Toutes les conventions n’utilisent pas les mêmes champs : laisse vide ce qui n’existe pas."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        fun field(hint: String, value: String? = null, numeric: Boolean = false) = EditText(context).apply {
            this.hint = hint
            setText(value.orEmpty())
            isSingleLine = true
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)).apply {
                topMargin = dp(context, 5)
            })
        }

        val coefficient = field("Coefficient — ex. 700", current.coefficient?.toString(), numeric = true)
        val level = field("Niveau — ex. III", current.level)
        val echelon = field("Échelon — ex. 2", current.echelon)
        val position = field("Position — ex. 2.1", current.position)
        val group = field("Groupe — ex. B", current.group)
        val category = field("Catégorie — ex. Employé / Technicien", current.category)
        val employment = field("Emploi / emploi repère", current.employment)

        val scroll = ScrollView(context).apply { addView(content) }
        AlertDialog.Builder(context)
            .setTitle("Classification conventionnelle")
            .setView(scroll)
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("ENREGISTRER") { _, _ ->
                val rawCoefficient = coefficient.text.toString().trim()
                val parsedCoefficient = rawCoefficient.takeIf { it.isNotBlank() }?.toIntOrNull()
                if (rawCoefficient.isNotBlank() && (parsedCoefficient == null || parsedCoefficient <= 0)) {
                    Toast.makeText(context, "Coefficient invalide : aucune modification enregistrée.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val value = ConventionClassificationV2(
                    coefficient = parsedCoefficient,
                    level = level.text.toString().trim().takeIf { it.isNotBlank() },
                    echelon = echelon.text.toString().trim().takeIf { it.isNotBlank() },
                    position = position.text.toString().trim().takeIf { it.isNotBlank() },
                    group = group.text.toString().trim().takeIf { it.isNotBlank() },
                    category = category.text.toString().trim().takeIf { it.isNotBlank() },
                    employment = employment.text.toString().trim().takeIf { it.isNotBlank() }
                )
                if (ConventionClassificationStoreV2.save(context, companyId, value)) {
                    Toast.makeText(context, "Classification conventionnelle enregistrée.", Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    Toast.makeText(context, "Impossible d’enregistrer la classification.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
