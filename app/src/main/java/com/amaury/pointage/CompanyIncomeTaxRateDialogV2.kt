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
import com.amaury.pointage.v2.CompanyIncomeTaxRateStoreV2
import com.amaury.pointage.v2.engine.CompanyIncomeTaxRateResolverV2
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Éditeur du taux personnel de prélèvement à la source avec période d'effet explicite. */
object CompanyIncomeTaxRateDialogV2 {
    private val monthFormatter = DateTimeFormatter.ofPattern("MM/uuuu", Locale.FRANCE)

    fun show(context: Context, companyId: String) {
        if (companyId.isBlank()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        box.addView(TextView(context).apply {
            text = "Enregistre uniquement un taux personnel confirmé pour une période précise. Le PAS intervient après le net imposable : ce taux ne modifie ni le brut, ni les cotisations, ni le net avant impôt."
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        val stored = CompanyIncomeTaxRateStoreV2.read(context, companyId)
        val records = stored.records.sortedWith(compareBy({ it.effectiveFrom }, { it.id }))

        if (!stored.reliable) {
            box.addView(TextView(context).apply {
                text = "⚠ Stockage des taux PAS incohérent. Le calcul après impôt reste bloqué et HoraTrack ne réutilise pas l'ancien taux sans date.\n• " +
                    stored.warnings.joinToString("\n• ")
                textSize = 12f
                setPadding(0, 0, 0, dp(context, 8))
            })
        } else {
            addLegacyMigrationInfo(context, companyId, records, box)
        }

        if (records.isEmpty()) {
            box.addView(TextView(context).apply {
                text = if (stored.reliable) "Aucun taux PAS daté enregistré."
                else "Aucun taux PAS exploitable n'a pu être lu dans le stockage incohérent."
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
            text = "AJOUTER UN TAUX PAS DATÉ"
            isEnabled = stored.reliable
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, null)
            }
        }, rowParams(context))

        listDialog = AlertDialog.Builder(context)
            .setTitle("Prélèvement à la source — taux datés")
            .setView(box)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        existing: CompanyIncomeTaxRateResolverV2.Record?
    ) {
        if (!CompanyIncomeTaxRateStoreV2.read(context, companyId).reliable) {
            Toast.makeText(context, "Stockage PAS incohérent : modification bloquée", Toast.LENGTH_LONG).show()
            return
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }
        val rate = field(
            context,
            "Taux PAS (%) — ex. 3,2 ; 0 si taux nul confirmé",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val start = field(context, "Mois de début — MM/AAAA")
        val end = field(context, "Mois de fin — MM/AAAA (facultatif)")
        val source = field(context, "Source — ex. bulletin 09/2026")
        listOf(rate, start, end, source).forEach { box.addView(it, rowParams(context)) }
        box.addView(TextView(context).apply {
            text = "Le mois de fin est inclus. Laisse-le vide si le taux reste applicable jusqu'à nouvel ordre. Deux périodes PAS ne peuvent pas se chevaucher."
            textSize = 12f
            setPadding(0, dp(context, 6), 0, 0)
        })

        existing?.let { record ->
            rate.setText(formatRate(record.ratePercent))
            start.setText(record.effectiveFrom?.let(::formatMonth).orEmpty())
            end.setText(record.effectiveTo?.let(::formatMonth).orEmpty())
            source.setText(record.source.orEmpty())
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Ajouter un taux PAS" else "Modifier le taux PAS")
            .setView(box)
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
        if (existing != null) builder.setNeutralButton("SUPPRIMER", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ratePercent = rate.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (ratePercent == null || !ratePercent.isFinite() || ratePercent < 0.0 || ratePercent > 100.0) {
                    rate.error = "Indique un taux entre 0 et 100 %"
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
                    source.error = "Indique la source du taux confirmé"
                    return@setOnClickListener
                }

                val current = CompanyIncomeTaxRateStoreV2.read(context, companyId)
                if (!current.reliable) {
                    Toast.makeText(context, "Stockage PAS devenu incohérent : enregistrement bloqué", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val overlapping = current.records
                    .asSequence()
                    .filter { it.id != existing?.id }
                    .firstOrNull { other ->
                        val otherStart = other.effectiveFrom ?: return@firstOrNull true
                        periodsOverlap(startMonth, endMonth, otherStart, other.effectiveTo)
                    }
                if (overlapping != null) {
                    Toast.makeText(context, "Une période PAS chevauche déjà ces mois", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val record = CompanyIncomeTaxRateResolverV2.Record(
                    id = existing?.id ?: "income_tax_rate_${UUID.randomUUID()}",
                    ratePercent = ratePercent,
                    effectiveFrom = startMonth,
                    effectiveTo = endMonth,
                    source = rawSource
                )
                if (!CompanyIncomeTaxRateStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement du taux PAS", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Taux PAS daté enregistré", Toast.LENGTH_SHORT).show()
                show(context, companyId)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (CompanyIncomeTaxRateStoreV2.remove(context, companyId, existing.id)) {
                        dialog.dismiss()
                        Toast.makeText(context, "Taux PAS daté supprimé", Toast.LENGTH_SHORT).show()
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
        records: List<CompanyIncomeTaxRateResolverV2.Record>,
        box: LinearLayout
    ) {
        if (records.isNotEmpty()) return
        val raw = SalaryCompanyStore.prefs(context, companyId)
            .getString("income_tax_rate_percent", "")
            .orEmpty()
            .trim()
        val legacy = raw.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 && it <= 100.0 }
            ?: return
        box.addView(TextView(context).apply {
            text = "⚠ Migration à confirmer\nAncien taux PAS : ${formatRate(legacy)} % — sans période ni source confirmée.\nAjoute une période datée avant de considérer ce taux comme exact."
            textSize = 12f
            setPadding(0, 0, 0, dp(context, 8))
        })
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

    private fun recordLabel(record: CompanyIncomeTaxRateResolverV2.Record): String = buildString {
        append("PAS — ").append(formatRate(record.ratePercent)).append(" %")
        append("\nDu ").append(record.effectiveFrom?.let(::formatMonth) ?: "?")
        record.effectiveTo?.let { append(" au ").append(formatMonth(it)) }
        append(" • ").append(record.source?.takeIf { it.isNotBlank() } ?: "source à confirmer")
    }

    private fun formatMonth(value: YearMonth): String = value.format(monthFormatter)
    private fun formatRate(value: Double): String = String.format(Locale.FRANCE, "%.2f", value)

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
