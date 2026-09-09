package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyEmployerGeneralReductionContextStoreV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionContextV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Saisie explicite des faits mensuels qui autorisent ou bloquent la RGDU automatique. */
object CompanyEmployerGeneralReductionContextDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)
    private val choices = listOf("À confirmer", "Oui", "Non")

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Ces faits servent uniquement à autoriser la RGDU automatique. HoraTrack ne transforme jamais une absence d'information en Oui ou en 0 heure. Une réponse Non bloque le calcul automatique pour le mois concerné."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        val records = CompanyEmployerGeneralReductionContextStoreV2.list(context, companyId)
            .sortedByDescending { it.month }
        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucun contexte RGDU mensuel confirmé."
                textSize = 13f
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
            text = "AJOUTER / CONFIRMER UN MOIS"
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null)
            }
        }, rowParams(context))

        listDialog = AlertDialog.Builder(context)
            .setTitle("Contexte RGDU automatique")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: EmployerGeneralReductionContextV2.Record?
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val month = field(context, "Mois — MM/AAAA")
        val fullMonth = choiceField(context, box, "Présence couvrant le mois selon les règles RGDU")
        val commonLaw = choiceField(context, box, "Cas de droit commun RGDU confirmé")
        val noOtherReduction = choiceField(context, box, "Aucune autre réduction/exonération patronale à agréger")
        val paidHoursComplete = choiceField(context, box, "Toutes les heures rémunérées du mois sont couvertes par HoraTrack")
        val source = field(context, "Source — ex. DSN / bulletin / contrôle employeur")

        box.addView(month, 0, rowParams(context))
        box.addView(source, rowParams(context))

        existing?.let { record ->
            month.setText(record.month.format(monthFormatter))
            fullMonth.setSelection(booleanPosition(record.fullMonthPresent))
            commonLaw.setSelection(booleanPosition(record.standardCommonLawCaseConfirmed))
            noOtherReduction.setSelection(booleanPosition(record.noOtherEmployerReductionConfirmed))
            paidHoursComplete.setSelection(booleanPosition(record.paidHoursComplete))
            source.setText(record.source)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Confirmer le contexte RGDU" else "Modifier le contexte RGDU")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
        if (existing != null) builder.setNeutralButton("SUPPRIMER", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedMonth = runCatching {
                    YearMonth.parse(month.text.toString().trim(), monthFormatter)
                }.getOrNull()
                if (parsedMonth == null) {
                    month.error = "Mois invalide"
                    return@setOnClickListener
                }
                val fullMonthValue = spinnerBoolean(fullMonth)
                val commonLawValue = spinnerBoolean(commonLaw)
                val noOtherReductionValue = spinnerBoolean(noOtherReduction)
                val paidHoursCompleteValue = spinnerBoolean(paidHoursComplete)
                if (listOf(fullMonthValue, commonLawValue, noOtherReductionValue, paidHoursCompleteValue).any { it == null }) {
                    Toast.makeText(context, "Réponds Oui ou Non aux 4 faits RGDU. Une donnée inconnue ne peut pas être confirmée.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val rawSource = source.text.toString().trim()
                if (rawSource.isBlank()) {
                    source.error = "Indique la source"
                    return@setOnClickListener
                }

                val record = EmployerGeneralReductionContextV2.Record(
                    id = existing?.id ?: "rgdu_context_${UUID.randomUUID()}",
                    month = parsedMonth,
                    fullMonthPresent = fullMonthValue!!,
                    standardCommonLawCaseConfirmed = commonLawValue!!,
                    noOtherEmployerReductionConfirmed = noOtherReductionValue!!,
                    source = rawSource,
                    paidHoursComplete = paidHoursCompleteValue!!
                )
                if (!CompanyEmployerGeneralReductionContextStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement du contexte RGDU", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Contexte RGDU enregistré", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyEmployerGeneralReductionContextStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun choiceField(context: Context, box: LinearLayout, label: String): Spinner {
        box.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setPadding(0, dp(context, 8), 0, 0)
        })
        return Spinner(context).also { spinner ->
            spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices)
            box.addView(spinner, rowParams(context))
        }
    }

    private fun booleanPosition(value: Boolean?): Int = when (value) {
        true -> 1
        false -> 2
        null -> 0
    }

    private fun spinnerBoolean(spinner: Spinner): Boolean? = when (spinner.selectedItemPosition) {
        1 -> true
        2 -> false
        else -> null
    }

    private fun recordLabel(record: EmployerGeneralReductionContextV2.Record) = buildString {
        append(record.month.format(monthFormatter))
        append(" — mois ").append(if (record.fullMonthPresent) "oui" else "non")
        append(" • droit commun ").append(if (record.standardCommonLawCaseConfirmed) "oui" else "non")
        append("\nHeures payées exhaustives : ").append(
            when (record.paidHoursComplete) {
                true -> "oui"
                false -> "non"
                null -> "à confirmer"
            }
        )
        append(" • ").append(record.source)
    }

    private fun field(
        context: Context,
        hint: String,
        type: Int = InputType.TYPE_CLASS_TEXT
    ) = EditText(context).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
    }

    private fun rowParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(context, 6) }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
