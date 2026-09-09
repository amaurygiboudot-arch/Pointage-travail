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
import com.amaury.pointage.v2.CompanyEmployeeDeductionStoreV2
import com.amaury.pointage.v2.engine.CompanyEmployeeDeductionResolverV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Éditeur des retenues salarié/fiscales propres à une entreprise, avec période d'effet explicite. */
object CompanyEmployeeDeductionDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Enregistre uniquement des montants confirmés pour une période précise. Dès qu'un type possède une règle datée, HoraTrack ne réutilise plus son ancienne valeur sans date pour combler un mois non renseigné."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        val stored = CompanyEmployeeDeductionStoreV2.read(context, companyId)
        val records = stored.records
            .sortedWith(compareBy({ it.kind.ordinal }, { it.effectiveFrom }, { it.id }))

        if (!stored.reliable) {
            box.addView(TextView(context).apply {
                text = "⚠ Stockage des retenues incohérent. HoraTrack bloque les calculs concernés et n'utilise aucune ancienne valeur sans date en remplacement.\n• " +
                    stored.warnings.joinToString("\n• ")
                textSize = 12f
                setPadding(0, 0, 0, dp(context, 8))
            })
        } else {
            addLegacyMigrationInfo(context, companyId, records, box)
        }

        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = if (stored.reliable) {
                    "Aucune retenue datée enregistrée."
                } else {
                    "Aucune retenue exploitable n'a pu être lue dans le stockage incohérent."
                }
                textSize = 13f
                setPadding(0, dp(context, 4), 0, dp(context, 8))
            })
        } else {
            records.forEach { record ->
                box.addView(Button(context).apply {
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    text = recordLabel(record)
                    isEnabled = stored.reliable
                    setOnClickListener {
                        listDialog?.dismiss()
                        showEditor(context, companyId, record)
                    }
                }, rowParams(context))
            }
        }

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "AJOUTER UNE RETENUE DATÉE"
            isEnabled = stored.reliable
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null)
            }
        }, rowParams(context))

        listDialog = AlertDialog.Builder(context)
            .setTitle("Retenues salarié / fiscales datées")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: CompanyEmployeeDeductionResolverV2.Record?
    ) {
        val storedAtOpen = CompanyEmployeeDeductionStoreV2.read(context, companyId)
        if (!storedAtOpen.reliable) {
            Toast.makeText(context, "Stockage incohérent : modification bloquée", Toast.LENGTH_LONG).show()
            return
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val kinds = CompanyEmployeeDeductionResolverV2.Kind.entries
        val kind = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                kinds.map { it.label }
            )
        }
        val amount = field(
            context,
            "Montant mensuel — 0 si absence confirmée",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val start = field(context, "Mois de début — MM/AAAA")
        val end = field(context, "Mois de fin — MM/AAAA (facultatif)")
        val source = field(context, "Source — ex. bulletin 09/2026")

        listOf(kind, amount, start, end, source).forEach { box.addView(it, rowParams(context)) }
        box.addView(TextView(context).apply {
            text = "Le mois de fin est inclus. Laisse-le vide si le montant reste applicable jusqu'à nouvel ordre. Deux périodes du même type ne peuvent pas se chevaucher."
            textSize = 12f
            setPadding(0, dp(context, 6), 0, 0)
        })

        existing?.let { record ->
            kind.setSelection(kinds.indexOf(record.kind).coerceAtLeast(0))
            kind.isEnabled = false
            amount.setText(formatAmount(record.amount))
            start.setText(record.effectiveFrom?.let(::formatMonth).orEmpty())
            end.setText(record.effectiveTo?.let(::formatMonth).orEmpty())
            source.setText(record.source.orEmpty())
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Ajouter une retenue datée" else "Modifier la retenue")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
        if (existing != null) builder.setNeutralButton("SUPPRIMER", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedKind = kinds.getOrNull(kind.selectedItemPosition) ?: return@setOnClickListener
                val value = amount.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (value == null || !value.isFinite() || value < 0.0) {
                    amount.error = "Indique un montant positif ou 0"
                    return@setOnClickListener
                }
                val startMonth = parseRequiredMonth(start, "Mois de début invalide") ?: return@setOnClickListener
                val endMonth = if (end.text.toString().isNotBlank()) {
                    parseRequiredMonth(end, "Mois de fin invalide") ?: return@setOnClickListener
                } else null
                if (endMonth != null && endMonth < startMonth) {
                    end.error = "Le mois de fin doit être après le début"
                    return@setOnClickListener
                }
                val rawSource = source.text.toString().trim()
                if (rawSource.isBlank()) {
                    source.error = "Indique la source du montant confirmé"
                    return@setOnClickListener
                }

                val current = CompanyEmployeeDeductionStoreV2.read(context, companyId)
                if (!current.reliable) {
                    Toast.makeText(context, "Stockage devenu incohérent : enregistrement bloqué", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val overlapping = current.records
                    .asSequence()
                    .filter { it.kind == selectedKind && it.id != existing?.id }
                    .firstOrNull { other ->
                        val otherStart = other.effectiveFrom ?: return@firstOrNull true
                        periodsOverlap(startMonth, endMonth, otherStart, other.effectiveTo)
                    }
                if (overlapping != null) {
                    Toast.makeText(
                        context,
                        "Une période ${selectedKind.label.lowercase(Locale.FRANCE)} chevauche déjà ces mois",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                val record = CompanyEmployeeDeductionResolverV2.Record(
                    id = existing?.id ?: "employee_deduction_${UUID.randomUUID()}",
                    kind = selectedKind,
                    amount = value,
                    effectiveFrom = startMonth,
                    effectiveTo = endMonth,
                    source = rawSource
                )
                if (!CompanyEmployeeDeductionStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement de la retenue", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Retenue datée enregistrée", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyEmployeeDeductionStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        Toast.makeText(context, "Retenue datée supprimée", Toast.LENGTH_SHORT).show()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la suppression", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun addLegacyMigrationInfo(
        context: Context,
        companyId: String,
        records: List<CompanyEmployeeDeductionResolverV2.Record>,
        box: LinearLayout
    ) {
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        val legacyKeys = mapOf(
            CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE to "mutual_employee_amount",
            CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE to "provident_employee_amount",
            CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE to "transport_employee_amount",
            CompanyEmployeeDeductionResolverV2.Kind.EMPLOYER_PROTECTION_TAXABLE to "employer_protection_taxable_amount",
            CompanyEmployeeDeductionResolverV2.Kind.EMPLOYEE_PROVIDENT_NON_DEDUCTIBLE to "employee_provident_nondeductible_amount"
        )
        val messages = legacyKeys.mapNotNull { (kind, key) ->
            if (records.any { it.kind == kind }) return@mapNotNull null
            val raw = prefs.getString(key, "").orEmpty().trim()
            val value = raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return@mapNotNull null
            "${kind.label} : ${formatAmount(value)} € — ancienne valeur sans période"
        }
        if (messages.isNotEmpty()) {
            box.addView(TextView(context).apply {
                text = "⚠ Migration à confirmer\n" + messages.joinToString("\n") +
                    "\nAjoute une période datée pour chaque valeur avant de la considérer comme exacte."
                textSize = 12f
                setPadding(0, 0, 0, dp(context, 8))
            })
        }
    }

    private fun periodsOverlap(
        startA: YearMonth,
        endA: YearMonth?,
        startB: YearMonth,
        endB: YearMonth?
    ): Boolean =
        (endA == null || startB <= endA) && (endB == null || startA <= endB)

    private fun parseRequiredMonth(field: EditText, error: String): YearMonth? {
        val parsed = runCatching { YearMonth.parse(field.text.toString().trim(), monthFormatter) }.getOrNull()
        if (parsed == null) field.error = error
        return parsed
    }

    private fun recordLabel(record: CompanyEmployeeDeductionResolverV2.Record): String = buildString {
        append(record.kind.label).append(" — ").append(formatAmount(record.amount)).append(" € / mois")
        append("\nDu ").append(record.effectiveFrom?.let(::formatMonth) ?: "?")
        record.effectiveTo?.let { append(" au ").append(formatMonth(it)) }
        append(" • ").append(record.source?.takeIf { it.isNotBlank() } ?: "source à confirmer")
    }

    private fun formatMonth(value: YearMonth): String = value.format(monthFormatter)
    private fun formatAmount(value: Double): String = String.format(Locale.FRANCE, "%.2f", value)

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
