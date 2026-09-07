package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyWorkforceContributionStoreV2
import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Éditeur de la tranche d'effectif social utilisée pour FNAL et formation. */
object CompanyWorkforceContributionDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Choisis la tranche d'effectif social confirmée pour la période. HoraTrack utilise cette donnée uniquement pour les contributions employeur FNAL et formation professionnelle."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        val records = CompanyWorkforceContributionStoreV2.list(context, companyId).sortedByDescending { it.effectiveFrom }
        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucune tranche d'effectif enregistrée."
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
            text = "AJOUTER UNE TRANCHE"
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null)
            }
        }, rowParams(context))

        listDialog = AlertDialog.Builder(context)
            .setTitle("Effectif employeur — FNAL / formation")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: EmployerWorkforceContributionsV2.Record?
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val band = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Moins de 11 salariés", "11 à 49 salariés", "50 salariés ou plus")
            )
        }
        val start = field(context, "Mois de début — MM/AAAA")
        val end = field(context, "Mois de fin — MM/AAAA (facultatif)")
        val source = field(context, "Source — ex. effectif Urssaf / DSN")
        listOf(band, start, end, source).forEach { box.addView(it, rowParams(context)) }
        box.addView(TextView(context).apply {
            text = "En cas de changement de tranche, ferme la période précédente avant d'en créer une nouvelle. Des périodes qui se chevauchent bloquent le calcul."
            textSize = 12f
            setPadding(0, dp(context, 6), 0, 0)
        })

        existing?.let { record ->
            band.setSelection(
                when (record.band) {
                    EmployerWorkforceContributionsV2.Band.UNDER_11 -> 0
                    EmployerWorkforceContributionsV2.Band.FROM_11_TO_49 -> 1
                    EmployerWorkforceContributionsV2.Band.AT_LEAST_50 -> 2
                }
            )
            start.setText(record.effectiveFrom.format(monthFormatter))
            end.setText(record.effectiveTo?.format(monthFormatter).orEmpty())
            source.setText(record.source)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Ajouter une tranche d'effectif" else "Modifier la tranche d'effectif")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
        if (existing != null) builder.setNeutralButton("SUPPRIMER", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = parseRequiredMonth(start, "Mois de début invalide") ?: return@setOnClickListener
                val to = if (end.text.toString().isBlank()) null else parseRequiredMonth(end, "Mois de fin invalide") ?: return@setOnClickListener
                if (to != null && to < from) {
                    end.error = "Le mois de fin doit être après le début"
                    return@setOnClickListener
                }
                val rawSource = source.text.toString().trim()
                if (rawSource.isBlank()) {
                    source.error = "Indique la source de la tranche d'effectif"
                    return@setOnClickListener
                }
                val selectedBand = when (band.selectedItemPosition) {
                    0 -> EmployerWorkforceContributionsV2.Band.UNDER_11
                    1 -> EmployerWorkforceContributionsV2.Band.FROM_11_TO_49
                    else -> EmployerWorkforceContributionsV2.Band.AT_LEAST_50
                }
                val record = EmployerWorkforceContributionsV2.Record(
                    id = existing?.id ?: "workforce_${UUID.randomUUID()}",
                    band = selectedBand,
                    effectiveFrom = from,
                    effectiveTo = to,
                    source = rawSource
                )
                if (!CompanyWorkforceContributionStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Tranche d'effectif enregistrée", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyWorkforceContributionStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        Toast.makeText(context, "Tranche supprimée", Toast.LENGTH_SHORT).show()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun label(band: EmployerWorkforceContributionsV2.Band): String = when (band) {
        EmployerWorkforceContributionsV2.Band.UNDER_11 -> "Moins de 11 salariés"
        EmployerWorkforceContributionsV2.Band.FROM_11_TO_49 -> "11 à 49 salariés"
        EmployerWorkforceContributionsV2.Band.AT_LEAST_50 -> "50 salariés ou plus"
    }

    private fun recordLabel(record: EmployerWorkforceContributionsV2.Record): String = buildString {
        append(label(record.band))
        append("\nDu ").append(record.effectiveFrom.format(monthFormatter))
        record.effectiveTo?.let { append(" au ").append(it.format(monthFormatter)) }
        append(" • ").append(record.source)
    }

    private fun parseRequiredMonth(field: EditText, error: String): YearMonth? {
        val parsed = runCatching { YearMonth.parse(field.text.toString().trim(), monthFormatter) }.getOrNull()
        if (parsed == null) field.error = error
        return parsed
    }

    private fun field(context: Context, hint: String) = EditText(context).apply {
        this.hint = hint
        isSingleLine = true
    }

    private fun rowParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(context, 6) }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
