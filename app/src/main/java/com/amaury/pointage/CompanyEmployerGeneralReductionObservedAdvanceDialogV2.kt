package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyEmployerGeneralReductionObservedAdvanceStoreV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionObservedAdvanceV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Saisie humaine des montants RGDU réellement constatés sur une source vérifiable.
 *
 * Important : ce dialogue ne saisit que la RGDU du mois. Il est volontairement séparé du total
 * global des réductions/exonérations patronales.
 */
object CompanyEmployerGeneralReductionObservedAdvanceDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return

        val selectedMonth = selectedPayrollMonth(context)
        val stored = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.read(context, companyId)
        val annual = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.resolveYear(
            context = context,
            companyId = companyId,
            year = selectedMonth.year
        )
        val records = stored.records
            .filter { it.month.year == selectedMonth.year }
            .sortedByDescending { it.month }
        val current = records.firstOrNull { it.month == selectedMonth }
        val confirmedMonths = records.map { it.month.monthValue }.distinct().size

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 12))
        }
        box.addView(TextView(context).apply {
            text = "Saisis ici uniquement le montant RGDU réellement constaté pour le mois depuis une DSN, un bulletin ou une source employeur vérifiable. Ne saisis pas ici le total des autres exonérations. Un mois absent reste inconnu ; 0 € doit être enregistré explicitement si la RGDU confirmée du mois est nulle."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })
        box.addView(TextView(context).apply {
            text = buildString {
                append("MOIS SÉLECTIONNÉ — ").append(selectedMonth.format(monthFormatter)).append("\n")
                if (!stored.reliable) {
                    append("Stockage bloqué")
                    if (stored.warnings.isNotEmpty()) append("\n• ").append(stored.warnings.joinToString("\n• "))
                } else if (current != null) {
                    append(String.format(Locale.FRANCE, "%.2f €", current.amount))
                    append(" — ").append(current.source)
                } else {
                    append("À confirmer")
                }
                append("\n\nANNÉE ").append(selectedMonth.year).append(" — ")
                append(confirmedMonths).append("/12 mois enregistrés")
                when (annual.state) {
                    EmployerGeneralReductionObservedAdvanceV2.YearState.COMPLETE_CONFIRMED ->
                        append("\nBase RGDU réelle complète : la régularisation annuelle peut utiliser les montants constatés.")
                    EmployerGeneralReductionObservedAdvanceV2.YearState.INCOMPLETE ->
                        append("\nBase réelle incomplète : HoraTrack ne transforme jamais les mois manquants en 0 €.")
                    EmployerGeneralReductionObservedAdvanceV2.YearState.INVALID ->
                        append("\nBase historique incohérente : régularisation bloquée.")
                }
                if (annual.warnings.isNotEmpty()) append("\n• ").append(annual.warnings.joinToString("\n• "))
            }
            textSize = 13f
            setPadding(0, dp(context, 4), 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        box.addView(Button(context).apply {
            isAllCaps = false
            text = if (current == null) {
                "CONFIRMER LA RGDU DE ${selectedMonth.format(monthFormatter)}"
            } else {
                "MODIFIER LA RGDU DE ${selectedMonth.format(monthFormatter)}"
            }
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, current, selectedMonth)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "AJOUTER / CONFIRMER UN AUTRE MOIS"
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null, selectedMonth)
            }
        }, rowParams(context))

        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = "Aucun montant RGDU constaté enregistré pour ${selectedMonth.year}."
                textSize = 13f
                setPadding(0, dp(context, 10), 0, 0)
            })
        } else {
            box.addView(TextView(context).apply {
                text = "Montants confirmés ${selectedMonth.year}"
                textSize = 13f
                setPadding(0, dp(context, 12), 0, dp(context, 2))
            })
            records.forEach { record ->
                box.addView(Button(context).apply {
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    text = recordLabel(record)
                    setOnClickListener {
                        listDialog?.dismiss()
                        showEditor(context, companyId, record, record.month)
                    }
                }, rowParams(context))
            }
        }

        val scroll = ScrollView(context).apply { addView(box) }
        listDialog = AlertDialog.Builder(context)
            .setTitle("RGDU réellement constatée")
            .setView(scroll)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: EmployerGeneralReductionObservedAdvanceV2.Record?,
        defaultMonth: YearMonth
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        box.addView(TextView(context).apply {
            text = "Montant RGDU uniquement. Si la source confirme 0 €, saisis 0. Si tu ne connais pas le montant, annule : une absence de donnée doit rester inconnue."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 6))
        })

        val month = field(context, "Mois — MM/AAAA")
        val amount = field(
            context,
            "RGDU constatée (€) — 0 si explicitement nulle",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val source = field(context, "Source — ex. DSN 09/2026 / bulletin")
        listOf(month, amount, source).forEach { box.addView(it, rowParams(context)) }

        val initialMonth = existing?.month ?: defaultMonth
        month.setText(initialMonth.format(monthFormatter))
        existing?.let {
            amount.setText(String.format(Locale.FRANCE, "%.2f", it.amount))
            source.setText(it.source)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Confirmer une RGDU mensuelle" else "Modifier la RGDU mensuelle")
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

                val parsedAmount = amount.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (parsedAmount == null || !parsedAmount.isFinite() || parsedAmount < 0.0) {
                    amount.error = "Montant RGDU invalide"
                    return@setOnClickListener
                }

                val rawSource = source.text.toString().trim()
                if (rawSource.isBlank()) {
                    source.error = "Indique la source vérifiable"
                    return@setOnClickListener
                }

                val record = EmployerGeneralReductionObservedAdvanceV2.Record(
                    id = existing?.id ?: "rgdu_observed_${UUID.randomUUID()}",
                    month = parsedMonth,
                    amount = parsedAmount,
                    source = rawSource
                )
                if (!CompanyEmployerGeneralReductionObservedAdvanceStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement RGDU", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "RGDU constatée enregistrée", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }

            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyEmployerGeneralReductionObservedAdvanceStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        show(context, companyId)
                    } else {
                        Toast.makeText(context, "Échec de la suppression RGDU", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun recordLabel(record: EmployerGeneralReductionObservedAdvanceV2.Record) = buildString {
        append(record.month.format(monthFormatter))
        append(" — ")
        append(String.format(Locale.FRANCE, "%.2f €", record.amount))
        append("\n")
        append(record.source)
    }

    private fun selectedPayrollMonth(context: Context): YearMonth {
        val ms = context.getSharedPreferences("navigation_state", Context.MODE_PRIVATE)
            .getLong("report_month_ms", -1L)
        val calendar = Calendar.getInstance(Locale.FRANCE)
        if (ms > 0L) calendar.timeInMillis = ms
        return YearMonth.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
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
