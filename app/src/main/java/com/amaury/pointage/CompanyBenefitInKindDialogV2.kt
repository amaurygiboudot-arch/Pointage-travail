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
import com.amaury.pointage.v2.CompanyBenefitInKindStoreV2
import com.amaury.pointage.v2.engine.CompanyBenefitInKindResolverV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Éditeur des avantages en nature déjà valorisés pour la paie. */
object CompanyBenefitInKindDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val period = selectedPayrollMonth(context)
        val coverage = CompanyBenefitInKindStoreV2.monthCoverage(context, companyId, period)
        val resolved = CompanyBenefitInKindStoreV2.resolve(context, companyId, period)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Renseigne la valeur brute soumise à cotisations de l’avantage en nature telle qu’elle est confirmée par l’employeur, le bulletin ou une règle officielle. HoraTrack ne calcule pas automatiquement un forfait véhicule/logement/repas à partir d’informations incomplètes."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })
        box.addView(TextView(context).apply {
            text = buildString {
                append("MOIS DE PAIE — ").append(period.format(monthFormatter)).append('\n')
                if (coverage.confirmed && coverage.storageReliable && resolved.reliable) {
                    append("Liste exhaustive confirmée — ").append(eur(resolved.totalGross))
                    coverage.source?.let { append("\nSource : ").append(it) }
                } else {
                    append("À confirmer — aucun total nul n'est supposé automatiquement.")
                    val warnings = (coverage.warnings + resolved.warnings).distinct()
                    if (warnings.isNotEmpty()) append("\n• ").append(warnings.joinToString("\n• "))
                }
            }
            textSize = 13f
            setPadding(0, dp(context, 2), 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        box.addView(Button(context).apply {
            isAllCaps = false
            text = if (coverage.confirmed) {
                "RECONFIRMER LA LISTE DE ${period.format(monthFormatter)}"
            } else {
                "CONFIRMER LA LISTE DE ${period.format(monthFormatter)}"
            }
            isEnabled = coverage.storageReliable
            setOnClickListener {
                listDialog?.dismiss()
                showMonthConfirmation(context, companyId, period, coverage.source)
            }
        }, rowParams(context))

        if (coverage.confirmed) {
            box.addView(Button(context).apply {
                isAllCaps = false
                text = "RETIRER LA CONFIRMATION DU MOIS"
                isEnabled = coverage.storageReliable
                setOnClickListener {
                    if (CompanyBenefitInKindStoreV2.clearMonthConfirmation(context, companyId, period)) {
                        listDialog?.dismiss()
                        Toast.makeText(context, "Exhaustivité du mois remise à confirmer", Toast.LENGTH_SHORT).show()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la modification", Toast.LENGTH_LONG).show()
                    }
                }
            }, rowParams(context))
        }

        val records = CompanyBenefitInKindStoreV2.list(context, companyId)
            .sortedWith(compareBy({ it.kind.name }, { it.label.lowercase(Locale.FRANCE) }))
        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucun avantage en nature enregistré. Pour retenir 0 € de façon fiable, confirme explicitement que cette liste vide est complète pour le mois."
                textSize = 13f
                setPadding(0, dp(context, 8), 0, dp(context, 8))
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
            text = "AJOUTER UN AVANTAGE EN NATURE"
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null)
            }
        }, rowParams(context))

        listDialog = AlertDialog.Builder(context)
            .setTitle("Avantages en nature")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showMonthConfirmation(
        context: Context,
        companyId: String,
        period: YearMonth,
        existingSource: String?
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        box.addView(TextView(context).apply {
            text = "Confirmer signifie que tous les avantages en nature soumis à cotisations pour ${period.format(monthFormatter)} sont enregistrés dans HoraTrack. Si la liste est vide, cette confirmation établit explicitement 0 €. Sans confirmation, le moteur conserve la donnée comme inconnue."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })
        val source = field(context, "Source — ex. bulletin ${period.format(monthFormatter)} / attestation employeur")
        source.setText(existingSource.orEmpty())
        box.addView(source, rowParams(context))

        val dialog = AlertDialog.Builder(context)
            .setTitle("Exhaustivité des avantages en nature")
            .setView(box)
            .setPositiveButton("CONFIRMER", null)
            .setNegativeButton("ANNULER", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawSource = source.text.toString().trim()
                if (rawSource.isBlank()) {
                    source.error = "Indique une source vérifiable"
                    return@setOnClickListener
                }
                if (!CompanyBenefitInKindStoreV2.confirmMonth(context, companyId, period, rawSource)) {
                    Toast.makeText(context, "Confirmation impossible : vérifie les données enregistrées", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Liste du mois confirmée", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
        }
        dialog.show()
    }

    private fun showEditor(context: Context, companyId: String, existing: CompanyBenefitInKindResolverV2.Record?) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val label = field(context, "Libellé — ex. véhicule de fonction")
        val value = field(context, "Valeur brute soumise à cotisations — ex. 180,00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val kind = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("Mensuel", "Ponctuel"))
        }
        val start = field(context, "Mois de début — MM/AAAA")
        val end = field(context, "Mois de fin — MM/AAAA (facultatif)")
        val payment = field(context, "Mois d'application — MM/AAAA")

        listOf(label, value, kind, start, end, payment).forEach { box.addView(it, rowParams(context)) }
        box.addView(TextView(context).apply {
            text = "La valeur de l’avantage augmente le brut soumis à cotisations mais n’est pas versée en espèces : elle sera retirée du net payé tout en restant dans le net imposable. Toute modification invalide les anciennes confirmations mensuelles d’exhaustivité."
            textSize = 12f
            setPadding(0, dp(context, 6), 0, 0)
        })

        existing?.let { record ->
            label.setText(record.label)
            value.setText(record.grossValue.toString().replace('.', ','))
            kind.setSelection(if (record.kind == CompanyBenefitInKindResolverV2.Kind.MONTHLY) 0 else 1)
            start.setText(record.effectiveFrom?.let(::formatMonth).orEmpty())
            end.setText(record.effectiveTo?.let(::formatMonth).orEmpty())
            payment.setText(record.paymentMonth?.let(::formatMonth).orEmpty())
        }

        fun updateFields() {
            val monthly = kind.selectedItemPosition == 0
            start.visibility = if (monthly) View.VISIBLE else View.GONE
            end.visibility = if (monthly) View.VISIBLE else View.GONE
            payment.visibility = if (monthly) View.GONE else View.VISIBLE
        }
        kind.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateFields()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updateFields()

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Ajouter un avantage" else "Modifier l’avantage")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
        if (existing != null) builder.setNeutralButton("SUPPRIMER", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawLabel = label.text.toString().trim()
                if (rawLabel.isBlank()) {
                    label.error = "Indique un libellé"
                    return@setOnClickListener
                }
                val grossValue = value.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (grossValue == null || !grossValue.isFinite() || grossValue <= 0.0) {
                    value.error = "Indique une valeur brute positive"
                    return@setOnClickListener
                }

                val monthly = kind.selectedItemPosition == 0
                val startMonth = if (monthly) parseRequiredMonth(start, "Mois de début invalide") ?: return@setOnClickListener else null
                val endMonth = if (monthly && end.text.toString().isNotBlank()) parseRequiredMonth(end, "Mois de fin invalide") ?: return@setOnClickListener else null
                if (startMonth != null && endMonth != null && endMonth < startMonth) {
                    end.error = "Le mois de fin doit être après le début"
                    return@setOnClickListener
                }
                val paymentMonth = if (!monthly) parseRequiredMonth(payment, "Mois d'application invalide") ?: return@setOnClickListener else null

                val record = CompanyBenefitInKindResolverV2.Record(
                    id = existing?.id ?: "benefit_${UUID.randomUUID()}",
                    label = rawLabel,
                    grossValue = grossValue,
                    kind = if (monthly) CompanyBenefitInKindResolverV2.Kind.MONTHLY else CompanyBenefitInKindResolverV2.Kind.ONE_OFF,
                    effectiveFrom = startMonth,
                    effectiveTo = endMonth,
                    paymentMonth = paymentMonth
                )
                if (!CompanyBenefitInKindStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement de l'avantage", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Avantage enregistré ; confirmations mensuelles à refaire", Toast.LENGTH_LONG).show()
                show(context, companyId)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyBenefitInKindStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        Toast.makeText(context, "Avantage supprimé ; confirmations mensuelles à refaire", Toast.LENGTH_LONG).show()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun selectedPayrollMonth(context: Context): YearMonth {
        val ms = context.getSharedPreferences("navigation_state", Context.MODE_PRIVATE)
            .getLong("report_month_ms", -1L)
        val calendar = Calendar.getInstance(Locale.FRANCE)
        if (ms > 0L) calendar.timeInMillis = ms
        return YearMonth.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
    }

    private fun parseRequiredMonth(field: EditText, error: String): YearMonth? {
        val parsed = runCatching { YearMonth.parse(field.text.toString().trim(), monthFormatter) }.getOrNull()
        if (parsed == null) field.error = error
        return parsed
    }

    private fun recordLabel(record: CompanyBenefitInKindResolverV2.Record): String = when (record.kind) {
        CompanyBenefitInKindResolverV2.Kind.MONTHLY -> buildString {
            append(record.label).append(" — ").append(eur(record.grossValue)).append(" / mois")
            append("\nDu ").append(record.effectiveFrom?.let(::formatMonth) ?: "?")
            record.effectiveTo?.let { append(" au ").append(formatMonth(it)) }
        }
        CompanyBenefitInKindResolverV2.Kind.ONE_OFF ->
            "${record.label} — ${eur(record.grossValue)}\nMois : ${record.paymentMonth?.let(::formatMonth) ?: "?"}"
    }

    private fun formatMonth(value: YearMonth): String = value.format(monthFormatter)
    private fun eur(value: Double): String = String.format(Locale.FRANCE, "%.2f €", value)

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
