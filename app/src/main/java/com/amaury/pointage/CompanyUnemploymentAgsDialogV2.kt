package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyUnemploymentAgsStoreV2
import com.amaury.pointage.v2.engine.EmployerUnemploymentAgsV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Éditeur des taux employeur chômage et AGS datés. */
object CompanyUnemploymentAgsDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Enregistre les taux employeur réellement confirmés pour la période (notification Urssaf, DSN ou autre source fiable). Le cas général 2026 est souvent 4,00 % chômage et 0,25 % AGS, mais HoraTrack ne les applique jamais sans confirmation car bonus-malus et cas ETT existent."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        val records = CompanyUnemploymentAgsStoreV2.list(context, companyId)
            .sortedByDescending { it.effectiveFrom }
        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucune règle chômage/AGS enregistrée."
                textSize = 13f
                setPadding(0, dp(context, 4), 0, dp(context, 8))
            })
        } else {
            records.forEach { record ->
                box.addView(Button(context).apply {
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    text = recordLabel(record)
                    setOnClickListener {
                        listDialog?.dismiss()
                        showEditor(context, companyId, record)
                    }
                }, rowParams(context))
            }
        }

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "AJOUTER UNE RÈGLE"
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null)
            }
        }, rowParams(context))

        listDialog = AlertDialog.Builder(context)
            .setTitle("Chômage / AGS employeur")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: EmployerUnemploymentAgsV2.Record?
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val unemployment = field(
            context,
            "Taux chômage employeur (%) — ex. 4,00",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val ags = field(
            context,
            "Taux AGS employeur (%) — ex. 0,25",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val start = field(context, "Mois de début — MM/AAAA")
        val end = field(context, "Mois de fin — MM/AAAA (facultatif)")
        val source = field(context, "Source — ex. Notification Urssaf / DSN")
        listOf(unemployment, ags, start, end, source).forEach { box.addView(it, rowParams(context)) }
        box.addView(TextView(context).apply {
            text = "Si un taux change (bonus-malus, changement de situation, etc.), ferme la période précédente et ajoute une nouvelle règle. Des périodes qui se chevauchent bloquent le calcul."
            textSize = 12f
            setPadding(0, dp(context, 6), 0, 0)
        })

        existing?.let { record ->
            unemployment.setText(percent(record.unemploymentRate))
            ags.setText(percent(record.agsRate))
            start.setText(record.effectiveFrom.format(monthFormatter))
            end.setText(record.effectiveTo?.format(monthFormatter).orEmpty())
            source.setText(record.source)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Ajouter chômage / AGS" else "Modifier chômage / AGS")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
        if (existing != null) builder.setNeutralButton("SUPPRIMER", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val unemploymentRate = parseRate(unemployment, "Taux chômage invalide") ?: return@setOnClickListener
                val agsRate = parseRate(ags, "Taux AGS invalide") ?: return@setOnClickListener
                val from = parseRequiredMonth(start, "Mois de début invalide") ?: return@setOnClickListener
                val to = if (end.text.toString().isBlank()) null else parseRequiredMonth(end, "Mois de fin invalide") ?: return@setOnClickListener
                if (to != null && to < from) {
                    end.error = "Le mois de fin doit être après le début"
                    return@setOnClickListener
                }
                val rawSource = source.text.toString().trim()
                if (rawSource.isBlank()) {
                    source.error = "Indique la source des taux"
                    return@setOnClickListener
                }

                val record = EmployerUnemploymentAgsV2.Record(
                    id = existing?.id ?: "unemployment_ags_${UUID.randomUUID()}",
                    unemploymentRate = unemploymentRate,
                    agsRate = agsRate,
                    effectiveFrom = from,
                    effectiveTo = to,
                    source = rawSource
                )
                if (!CompanyUnemploymentAgsStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Taux chômage/AGS enregistrés", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyUnemploymentAgsStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        Toast.makeText(context, "Règle supprimée", Toast.LENGTH_SHORT).show()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun parseRate(field: EditText, error: String): Double? {
        val percent = field.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (percent == null || !percent.isFinite() || percent < 0.0 || percent > 100.0) {
            field.error = error
            return null
        }
        return percent / 100.0
    }

    private fun parseRequiredMonth(field: EditText, error: String): YearMonth? {
        val parsed = runCatching { YearMonth.parse(field.text.toString().trim(), monthFormatter) }.getOrNull()
        if (parsed == null) field.error = error
        return parsed
    }

    private fun recordLabel(record: EmployerUnemploymentAgsV2.Record): String = buildString {
        append("Chômage ").append(String.format(Locale.FRANCE, "%.3f %%", record.unemploymentRate * 100.0))
        append(" • AGS ").append(String.format(Locale.FRANCE, "%.3f %%", record.agsRate * 100.0))
        append("\nDu ").append(record.effectiveFrom.format(monthFormatter))
        record.effectiveTo?.let { append(" au ").append(it.format(monthFormatter)) }
        append(" • ").append(record.source)
    }

    private fun percent(rate: Double): String =
        String.format(Locale.FRANCE, "%.4f", rate * 100.0).trimEnd('0').trimEnd(',')

    private fun field(context: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
    }

    private fun rowParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(context, 6) }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
