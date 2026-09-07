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
import com.amaury.pointage.v2.CompanyPremiumStoreV2
import com.amaury.pointage.v2.engine.CompanyPremiumResolverV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Éditeur des primes brutes contractuelles/personnelles propres à une entreprise. */
object CompanyPremiumDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Ajoute uniquement les primes qui entrent dans le salaire brut. Les paniers, remboursements de frais et indemnités non intégrées au brut restent gérés séparément."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        val records = CompanyPremiumStoreV2.list(context, companyId)
            .sortedWith(compareBy({ it.kind.name }, { it.label.lowercase(Locale.FRANCE) }))
        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucune prime contractuelle/personnelle enregistrée."
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
            text = "AJOUTER UNE PRIME"
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "GÉRER LES AVANTAGES EN NATURE"
            setOnClickListener {
                listDialog?.dismiss()
                CompanyBenefitInKindDialogV2.show(context, companyId)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "GÉRER LE VERSEMENT MOBILITÉ EMPLOYEUR"
            setOnClickListener {
                listDialog?.dismiss()
                CompanyMobilityContributionDialogV2.show(context, companyId)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "GÉRER CHÔMAGE / AGS EMPLOYEUR"
            setOnClickListener {
                listDialog?.dismiss()
                CompanyUnemploymentAgsDialogV2.show(context, companyId)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "GÉRER EFFECTIF — FNAL / FORMATION"
            setOnClickListener {
                listDialog?.dismiss()
                CompanyWorkforceContributionDialogV2.show(context, companyId)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "GÉRER MALADIE / ALLOCATIONS FAMILIALES"
            setOnClickListener {
                listDialog?.dismiss()
                CompanyHealthFamilyDialogV2.show(context, companyId)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "GÉRER TAXE D'APPRENTISSAGE"
            setOnClickListener {
                listDialog?.dismiss()
                CompanyApprenticeshipTaxDialogV2.show(context, companyId)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "GÉRER RÉDUCTIONS / EXONÉRATIONS EMPLOYEUR"
            setOnClickListener {
                listDialog?.dismiss()
                CompanyEmployerReductionDialogV2.show(context, companyId)
            }
        }, rowParams(context))

        listDialog = AlertDialog.Builder(context)
            .setTitle("Primes contractuelles / personnelles")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(context: Context, companyId: String, existing: CompanyPremiumResolverV2.Record?) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val label = field(context, "Libellé — ex. prime qualité")
        val amount = field(context, "Montant brut — ex. 80,00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val kind = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("Mensuelle", "Ponctuelle"))
        }
        val start = field(context, "Mois de début — MM/AAAA")
        val end = field(context, "Mois de fin — MM/AAAA (facultatif)")
        val payment = field(context, "Mois de versement — MM/AAAA")

        listOf(label, amount, kind, start, end, payment).forEach { box.addView(it, rowParams(context)) }
        box.addView(TextView(context).apply {
            text = "Une prime mensuelle est appliquée de son mois de début à son mois de fin inclus. Une prime ponctuelle n'est ajoutée que sur son mois de versement."
            textSize = 12f
            setPadding(0, dp(context, 6), 0, 0)
        })

        existing?.let { record ->
            label.setText(record.label)
            amount.setText(record.grossAmount.toString().replace('.', ','))
            kind.setSelection(if (record.kind == CompanyPremiumResolverV2.Kind.MONTHLY) 0 else 1)
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
            .setTitle(if (existing == null) "Ajouter une prime" else "Modifier la prime")
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
                val grossAmount = amount.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (grossAmount == null || !grossAmount.isFinite() || grossAmount <= 0.0) {
                    amount.error = "Indique un montant brut positif"
                    return@setOnClickListener
                }

                val monthly = kind.selectedItemPosition == 0
                val startMonth = if (monthly) parseRequiredMonth(start, "Mois de début invalide") ?: return@setOnClickListener else null
                val endMonth = if (monthly && end.text.toString().isNotBlank()) parseRequiredMonth(end, "Mois de fin invalide") ?: return@setOnClickListener else null
                if (startMonth != null && endMonth != null && endMonth < startMonth) {
                    end.error = "Le mois de fin doit être après le début"
                    return@setOnClickListener
                }
                val paymentMonth = if (!monthly) parseRequiredMonth(payment, "Mois de versement invalide") ?: return@setOnClickListener else null

                val record = CompanyPremiumResolverV2.Record(
                    id = existing?.id ?: "premium_${UUID.randomUUID()}",
                    label = rawLabel,
                    grossAmount = grossAmount,
                    kind = if (monthly) CompanyPremiumResolverV2.Kind.MONTHLY else CompanyPremiumResolverV2.Kind.ONE_OFF,
                    effectiveFrom = startMonth,
                    effectiveTo = endMonth,
                    paymentMonth = paymentMonth
                )
                if (!CompanyPremiumStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement de la prime", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Prime enregistrée", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyPremiumStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        Toast.makeText(context, "Prime supprimée", Toast.LENGTH_SHORT).show()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun parseRequiredMonth(field: EditText, error: String): YearMonth? {
        val parsed = runCatching { YearMonth.parse(field.text.toString().trim(), monthFormatter) }.getOrNull()
        if (parsed == null) field.error = error
        return parsed
    }

    private fun recordLabel(record: CompanyPremiumResolverV2.Record): String = when (record.kind) {
        CompanyPremiumResolverV2.Kind.MONTHLY -> buildString {
            append(record.label).append(" — ").append(eur(record.grossAmount)).append(" brut/mois")
            append("\nDu ").append(record.effectiveFrom?.let(::formatMonth) ?: "?")
            record.effectiveTo?.let { append(" au ").append(formatMonth(it)) }
        }
        CompanyPremiumResolverV2.Kind.ONE_OFF ->
            "${record.label} — ${eur(record.grossAmount)} brut\nVersement : ${record.paymentMonth?.let(::formatMonth) ?: "?"}"
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
