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
import com.amaury.pointage.v2.CompanyAtMpRateStoreV2
import com.amaury.pointage.v2.engine.EmployerAtMpRateHistoryV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID

/** Confirmation explicite du taux notifié pour un établissement et une période. */
object CompanyAtMpRateDialogV2 {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE)
        .withResolverStyle(ResolverStyle.STRICT)

    fun show(context: Context, companyId: String, onChanged: () -> Unit = {}) {
        val stored = CompanyAtMpRateStoreV2.read(context, companyId)
        val box = box(context)
        box.addView(label(context, "Confirme uniquement le taux AT/MP notifié pour cet établissement, ses dates d'application et sa source. Un changement au milieu du mois doit être réparti avant le calcul mensuel. Les versions se conservent dans l'historique."))
        stored.warnings.forEach { box.addView(label(context, it)) }
        if (stored.reliable && stored.records.isEmpty()) box.addView(label(context, "Aucune version datée confirmée. L'ancienne valeur éventuelle reste un brouillon."))
        var dialog: AlertDialog? = null
        stored.records.sortedByDescending { it.effectiveFrom }.forEach { record ->
            box.addView(Button(context).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = recordLabel(record)
                setOnClickListener { dialog?.dismiss(); edit(context, companyId, record, onChanged) }
            }, row(context))
        }
        box.addView(Button(context).apply {
            text = "CONFIRMER UNE NOUVELLE VERSION"
            isAllCaps = false
            isEnabled = stored.reliable
            setOnClickListener { dialog?.dismiss(); edit(context, companyId, null, onChanged) }
        }, row(context))
        dialog = AlertDialog.Builder(context).setTitle("Taux AT/MP datés")
            .setView(ScrollView(context).apply { addView(box) }).setNegativeButton("FERMER", null).create()
        dialog.show()
    }

    private fun edit(context: Context, companyId: String, existing: EmployerAtMpRateHistoryV2.Record?, onChanged: () -> Unit) {
        val company = SalaryCompanyStore.withConfirmedCompany(context, companyId.trim()) { it }
        if (company == null) {
            Toast.makeText(context, "Entreprise non confirmée ; édition AT/MP indisponible", Toast.LENGTH_LONG).show()
            return
        }
        val box = box(context)
        val siret = field(context, "SIRET de l'établissement — 14 chiffres", InputType.TYPE_CLASS_NUMBER)
        val rate = field(context, "Taux AT/MP (%) — 0 si notifié nul", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val start = field(context, "Début d'application — JJ/MM/AAAA")
        val end = field(context, "Dernier jour d'application — facultatif")
        val source = field(context, "Source — notification Carsat/Cramif/CGSS ou bulletin daté")
        listOf(siret, rate, start, end, source).forEach { box.addView(it, row(context)) }
        box.addView(label(context, "En confirmant, tu attestes que le taux et la période correspondent à la source pour ce SIRET. Si un nouveau taux remplace l'ancien, renseigne le dernier jour de l'ancienne version. Des périodes qui se chevauchent bloquent le calcul."))
        if (existing != null) {
            siret.setText(existing.establishmentSiret)
            rate.setText(percent(existing.rate))
            start.setText(existing.effectiveFrom.format(dateFormatter))
            end.setText(existing.effectiveTo?.format(dateFormatter).orEmpty())
            source.setText(existing.source)
        } else {
            siret.setText(company.siret.trim())
            // Valeur legacy uniquement : aucune date ou preuve n'est créée depuis les préférences.
            val draft = SalaryCompanyStore.withConfirmedCompany(context, company.id) {
                val raw = runCatching { SalaryCompanyStore.prefs(context, it.id).getString("atmp_employer_rate_percent", "") }.getOrNull()
                draftRatePercent(raw)
            }
            draft?.let { rate.setText(it.toString().replace('.', ',')) }
        }
        val builder = AlertDialog.Builder(context).setTitle(if (existing == null) "Confirmer le taux AT/MP" else "Confirmer cette version AT/MP")
            .setView(ScrollView(context).apply { addView(box) }).setPositiveButton("CONFIRMER CETTE VERSION", null).setNegativeButton("ANNULER", null)
        if (existing != null) builder.setNeutralButton("SUPPRIMER CETTE VERSION", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawSiret = siret.text.toString().trim()
                if (!EmployerAtMpRateHistoryV2.validSiret(rawSiret) || rawSiret != company.siret.trim()) {
                    siret.error = "Le SIRET doit correspondre à l'établissement de l'entreprise sélectionnée"
                    return@setOnClickListener
                }
                val percent = draftRatePercent(rate.text.toString())
                if (percent == null) { rate.error = "Indique un taux entre 0 et 100 %"; return@setOnClickListener }
                val from = parseDate(start, "Date de début invalide — JJ/MM/AAAA") ?: return@setOnClickListener
                val to = if (end.text.toString().isBlank()) null else parseDate(end, "Date de fin invalide — JJ/MM/AAAA") ?: return@setOnClickListener
                if (to != null && to.isBefore(from)) { end.error = "La fin ne peut pas précéder le début"; return@setOnClickListener }
                val proof = source.text.toString().trim()
                if (proof.isBlank()) { source.error = "Indique la source du taux et de sa période"; return@setOnClickListener }
                val record = EmployerAtMpRateHistoryV2.Record(existing?.id ?: "atmp_${UUID.randomUUID()}", rawSiret,
                    percent / 100.0, from, to, proof, System.currentTimeMillis())
                if (!CompanyAtMpRateStoreV2.save(context, company.id, record)) {
                    Toast.makeText(context, "Confirmation impossible : vérifie l'entreprise et le stockage historique", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onChanged()
                show(context, company.id, onChanged)
            }
            if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (CompanyAtMpRateStoreV2.remove(context, company.id, existing.id)) {
                    dialog.dismiss(); onChanged(); show(context, company.id, onChanged)
                } else Toast.makeText(context, "Suppression impossible : stockage ou entreprise à vérifier", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    /** Une valeur historique sans date/source reste exclusivement un brouillon de saisie. */
    internal fun draftRatePercent(raw: String?): Double? = raw?.trim()?.replace(',', '.')?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..100.0 }

    internal fun parseEffectiveDate(raw: String): LocalDate? = runCatching {
        LocalDate.parse(raw.trim(), dateFormatter)
    }.getOrNull()

    private fun parseDate(field: EditText, error: String): LocalDate? = parseEffectiveDate(field.text.toString()).also {
        if (it == null) field.error = error
    }
    private fun recordLabel(record: EmployerAtMpRateHistoryV2.Record) = buildString {
        append(percent(record.rate)).append(" % — SIRET ").append(record.establishmentSiret)
        append("\nDu ").append(record.effectiveFrom.format(dateFormatter))
        record.effectiveTo?.let { append(" au ").append(it.format(dateFormatter)) }
        append("\n").append(record.source)
    }
    private fun percent(rate: Double) = (rate * 100.0).toString().replace('.', ',')
    private fun field(context: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply {
        this.hint = hint; inputType = type; isSingleLine = true
    }
    private fun label(context: Context, value: String) = TextView(context).apply { text = value; textSize = 13f; setPadding(0, dp(context, 6), 0, dp(context, 6)) }
    private fun box(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8)) }
    private fun row(context: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 6) }
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
