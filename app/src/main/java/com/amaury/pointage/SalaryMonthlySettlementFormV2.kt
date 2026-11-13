package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.ConfirmedMonthlyPayrollSettlementV2 as Settlement
import com.amaury.pointage.v2.SalaryAccountScope
import java.math.BigDecimal

/** Explicit monthly facts. Changing a field invalidates the user's confirmation. */
object SalaryMonthlySettlementFormV2 {
    fun show(hostContext: Context, company: SalaryCompanyStore.Company, period: String,
             selectionStillCurrent: () -> Boolean, onSaved: () -> Unit) {
        val context = SalaryAccountScope.bindContext(hostContext)
        fun current() = SalaryAccountScope.available(context) && selectionStillCurrent() &&
            SalaryCompanyStore.confirmedCompany(SalaryCompanyStore.readConfirmed(context), company.id) != null
        if (!current()) return
        val initial = Settlement.read(context, company.id, period)
        fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        if (!initial.reliable) {
            SalaryAccountUi.dialog(context).setTitle("Source mensuelle à vérifier — $period")
                .setMessage(initial.warnings.joinToString("\n") + "\nLa source illisible est conservée. Supprimer explicitement cette source pour ${company.name}, $period ?")
                .setNegativeButton("ANNULER", null).setPositiveButton("SUPPRIMER") { _, _ ->
                    if (current() && Settlement.remove(context, company.id, period, initial)) onSaved()
                    else message("Source modifiée ou non vérifiable : suppression annulée ; les données sont conservées.")
                }.show()
            return
        }
        val labels = listOf("Transport remboursé hors brut (€)", "Saisie-arrêt sur versement (€)",
            "Autres ajouts hors brut, dont paniers et frais non déjà inclus (€)",
            "Autres retenues hors cotisations et PAS (€)", "Net social constaté sur le bulletin (€)", "Net réellement payé constaté (€)")
        val amounts = initial.record?.amounts() ?: List(6) { null }
        val inputs = labels.zip(amounts).map { (label, value) -> EditText(context).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            value?.let { setText(BigDecimal.valueOf(it).toPlainString()) }
        } }
        val source = EditText(context).apply { hint = "Source vérifiable : bulletin, date et référence (500 caractères maximum)"; setText(initial.record?.source.orEmpty()) }
        val exhaustive = CheckBox(context).apply { text = Settlement.ADJUSTMENTS_CONFIRMATION; isChecked = initial.record?.adjustmentsComplete == true }
        val confirmed = CheckBox(context).apply { text = "Je confirme l’entreprise ${company.name}, le mois $period, la source et les montants constatés. Un champ vide reste inconnu ; zéro doit être saisi explicitement." }
        val listener = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { confirmed.isChecked = false; exhaustive.isChecked = false }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        (inputs + source).forEach { it.addTextChangedListener(listener) }
        exhaustive.setOnCheckedChangeListener { _, _ -> confirmed.isChecked = false }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "${company.name} — $period\nCes faits restent propres à ce mois. Le net social et le net payé constatés ne remplacent pas le calcul AGKGMG. Montants exacts au centime ; ne pas saisir les IJSS versées séparément ici." })
            inputs.forEach { addView(TextView(context).apply { text = it.hint }); addView(it) }
            addView(source); addView(exhaustive); addView(confirmed)
        }
        val dialog = SalaryAccountUi.dialog(context).setTitle("Frais, retenues et versement — $period")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("ANNULER", null).setPositiveButton("ENREGISTRER", null).setNeutralButton("SUPPRIMER", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!current() || !confirmed.isChecked) { confirmed.isChecked = false; message("Confirme le compte, l’entreprise, le mois et la saisie avant d’enregistrer."); return@setOnClickListener }
                val texts = inputs.map { it.text.toString().trim() }
                val values = texts.map { if (it.isBlank()) null else Settlement.parseAmount(it.replace(',', '.')) }
                if (texts.indices.any { texts[it].isNotBlank() && values[it] == null }) { message("Montant invalide : utilise des centimes exacts, un montant positif ou zéro, ou laisse le champ vide."); return@setOnClickListener }
                val record = Settlement.Record(company.id, period, values[0], values[1], values[2], values[3], values[4], values[5],
                    source.text.toString().trim(), System.currentTimeMillis(), exhaustive.isChecked)
                if (!Settlement.valid(record)) { message("Source requise, au moins un montant et, pour l’exhaustivité, les quatre ajustements renseignés (zéro si confirmé)."); return@setOnClickListener }
                if (!Settlement.save(context, record, true, initial)) { confirmed.isChecked = false; message("Saisie non enregistrée : compte, entreprise ou source modifiés. Rouvre le formulaire ; les données existantes sont conservées."); return@setOnClickListener }
                dialog.dismiss(); onSaved()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                SalaryAccountUi.dialog(context).setTitle("Supprimer la source mensuelle ?")
                    .setMessage("${company.name} — $period. Seuls les frais, retenues et versements confirmés de ce mois seront retirés.")
                    .setNegativeButton("ANNULER", null).setPositiveButton("SUPPRIMER") { _, _ ->
                        if (current() && Settlement.remove(context, company.id, period, initial)) { dialog.dismiss(); onSaved() }
                        else message("Source ou contexte modifié : suppression annulée.")
                    }.show()
            }
        }
        dialog.show()
    }
}
