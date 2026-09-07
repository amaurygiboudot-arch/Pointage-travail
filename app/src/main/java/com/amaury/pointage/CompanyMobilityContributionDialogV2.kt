package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyMobilityContributionStoreV2
import com.amaury.pointage.v2.engine.EmployerMobilityContributionV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Éditeur des règles datées de versement mobilité employeur. */
object CompanyMobilityContributionDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Le versement mobilité est une contribution patronale. Enregistre uniquement une applicabilité et un taux confirmés pour la période (Urssaf, DSN, notification ou autre source fiable). HoraTrack ne déduit aucun taux de l'adresse seule."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        val records = CompanyMobilityContributionStoreV2.list(context, companyId)
            .sortedByDescending { it.effectiveFrom }
        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucune règle de versement mobilité enregistrée."
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
            .setTitle("Versement mobilité employeur")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: EmployerMobilityContributionV2.Record?
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val status = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Applicable", "Non applicable confirmé")
            )
        }
        val rate = field(
            context,
            "Taux employeur (%) — ex. 2,50",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val start = field(context, "Mois de début — MM/AAAA")
        val end = field(context, "Mois de fin — MM/AAAA (facultatif)")
        val source = field(context, "Source — ex. Urssaf taux VM / DSN")
        listOf(status, rate, start, end, source).forEach { box.addView(it, rowParams(context)) }
        box.addView(TextView(context).apply {
            text = "Si le taux change au 1er janvier ou au 1er juillet, ferme la période précédente puis ajoute une nouvelle règle. Des périodes qui se chevauchent bloqueront le calcul."
            textSize = 12f
            setPadding(0, dp(context, 6), 0, 0)
        })

        existing?.let { record ->
            status.setSelection(if (record.status == EmployerMobilityContributionV2.Status.APPLICABLE) 0 else 1)
            rate.setText(record.rate?.times(100.0)?.let { String.format(Locale.FRANCE, "%.4f", it).trimEnd('0').trimEnd(',') }.orEmpty())
            start.setText(record.effectiveFrom.format(monthFormatter))
            end.setText(record.effectiveTo?.format(monthFormatter).orEmpty())
            source.setText(record.source)
        }

        fun updateFields() {
            rate.visibility = if (status.selectedItemPosition == 0) View.VISIBLE else View.GONE
        }
        status.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateFields()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updateFields()

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Ajouter une règle VM" else "Modifier la règle VM")
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
                    source.error = "Indique la source du taux ou de la non-applicabilité"
                    return@setOnClickListener
                }
                val applicable = status.selectedItemPosition == 0
                val decimalRate = if (applicable) {
                    val percent = rate.text.toString().trim().replace(',', '.').toDoubleOrNull()
                    if (percent == null || !percent.isFinite() || percent < 0.0 || percent > 100.0) {
                        rate.error = "Indique un taux entre 0 et 100 %"
                        return@setOnClickListener
                    }
                    percent / 100.0
                } else null

                val record = EmployerMobilityContributionV2.Record(
                    id = existing?.id ?: "mobility_${UUID.randomUUID()}",
                    status = if (applicable) EmployerMobilityContributionV2.Status.APPLICABLE else EmployerMobilityContributionV2.Status.NOT_APPLICABLE,
                    rate = decimalRate,
                    effectiveFrom = from,
                    effectiveTo = to,
                    source = rawSource
                )
                if (!CompanyMobilityContributionStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Règle de versement mobilité enregistrée", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyMobilityContributionStoreV2.remove(context, companyId, existing.id)) {
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

    private fun recordLabel(record: EmployerMobilityContributionV2.Record): String = buildString {
        append(
            if (record.status == EmployerMobilityContributionV2.Status.APPLICABLE) {
                "Applicable — ${record.rate?.times(100.0)?.let { String.format(Locale.FRANCE, "%.3f %%", it) } ?: "taux ?"}"
            } else {
                "Non applicable confirmé"
            }
        )
        append("\nDu ").append(record.effectiveFrom.format(monthFormatter))
        record.effectiveTo?.let { append(" au ").append(it.format(monthFormatter)) }
        append(" • ").append(record.source)
    }

    private fun parseRequiredMonth(field: EditText, error: String): YearMonth? {
        val parsed = runCatching { YearMonth.parse(field.text.toString().trim(), monthFormatter) }.getOrNull()
        if (parsed == null) field.error = error
        return parsed
    }

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
