package com.amaury.pointage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyAgreementStoreV2
import com.amaury.pointage.v2.OfficialCompanyAgreementSearchV2

class SalaryCompanyDetailsView(
    context: Context,
    private var company: SalaryCompanyStore.Company,
    private val onChanged: (SalaryCompanyStore.Company) -> Unit,
    private val onDelete: (SalaryCompanyStore.Company) -> Unit
) : LinearLayout(context) {
    init { orientation = VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)); showSummary() }

    private fun showSummary() {
        removeAllViews()
        addView(text("Nom : ${company.name.ifBlank { "Non renseigné" }}\nSIRET : ${company.siret.ifBlank { "Non renseigné" }}\nAdresse : ${company.address.ifBlank { "Non renseignée" }}\nConvention : ${company.conventionName.ifBlank { if (company.idcc.isBlank()) "Non renseignée" else "IDCC ${company.idcc}" }}"))
        addView(button("MODIFIER LES INFORMATIONS") { showEditor() })
        addView(button("ACCORDS D’ENTREPRISE") { showAgreements() })
        addView(button("SUPPRIMER L’ENTREPRISE") { onDelete(company) })
    }

    private fun showAgreements() {
        removeAllViews()
        addView(text("ACCORDS D’ENTREPRISE\n\nHoraTrack recherche d’abord les accords accessibles dans les sources officielles. Un accord trouvé ou importé n’est jamais appliqué au calcul tant que ses règles et sa période d’application ne sont pas validées."))
        val agreements = CompanyAgreementStoreV2.list(context, company.id)
        if (agreements.isEmpty()) {
            addView(text("État : accord interne non identifié / à confirmer.\n\nCela ne signifie pas qu’aucun accord existe."))
        } else {
            agreements.forEach { agreement ->
                val status = when (agreement.status) {
                    CompanyAgreementStoreV2.Status.UNKNOWN -> "À confirmer"
                    CompanyAgreementStoreV2.Status.TO_PROVIDE -> "Document à fournir"
                    CompanyAgreementStoreV2.Status.IMPORTED -> "Importé — validation nécessaire"
                    CompanyAgreementStoreV2.Status.VERIFIED -> "Vérifié"
                }
                val dates = listOfNotNull(
                    agreement.effectiveFrom?.let { "Début : $it" },
                    agreement.effectiveTo?.let { "Fin : $it" }
                ).joinToString(" — ")
                addView(text("${agreement.title.ifBlank { "Accord sans titre" }}\nÉtat : $status${if (dates.isBlank()) "" else "\n$dates"}${if (agreement.sourceLabel.isBlank()) "" else "\nSource : ${agreement.sourceLabel}"}"))
            }
        }
        addView(button("RECHERCHER DANS LES SOURCES OFFICIELLES") {
            val request = OfficialCompanyAgreementSearchV2.build(company.siret)
            if (request == null) {
                Toast.makeText(context, "Renseigne d’abord un SIRET valide à 14 chiffres.", Toast.LENGTH_LONG).show()
                return@button
            }
            SalaryCompanyStore.prefs(context, company.id).edit()
                .putBoolean("company_agreement_search_requested", true)
                .putString("company_agreement_search_siret", request.normalizedSiret)
                .putLong("company_agreement_search_requested_at", System.currentTimeMillis())
                .commit()
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, request.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(context, "Recherche ouverte dans Légifrance — résultats à vérifier avant application.", Toast.LENGTH_LONG).show()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Impossible d’ouvrir Légifrance sur cet appareil.", Toast.LENGTH_LONG).show()
            }
        })
        addView(button("JE POSSÈDE UN ACCORD À IMPORTER") {
            SalaryCompanyStore.prefs(context, company.id).edit().putBoolean("company_agreement_import_requested", true).commit()
            Toast.makeText(context, "Import d’accord : étape document à connecter", Toast.LENGTH_LONG).show()
        })
        addView(button("RETOUR") { showSummary() })
    }

    private fun showEditor() {
        removeAllViews()
        val name = field("Nom de l’entreprise", company.name)
        val siret = field("SIRET — 14 chiffres", company.siret, InputType.TYPE_CLASS_NUMBER)
        val address = field("Adresse", company.address)
        val convention = field("Convention collective", company.conventionName)
        val idcc = field("IDCC", company.idcc, InputType.TYPE_CLASS_NUMBER)
        listOf(name, siret, address, convention, idcc).forEach { addView(it, row()) }
        addView(button("ENREGISTRER LES INFORMATIONS") {
            val digits = siret.text.toString().filter(Char::isDigit)
            if (digits.isNotBlank() && digits.length != 14) { siret.error = "Le SIRET doit contenir 14 chiffres"; return@button }
            val updated = company.copy(name = name.text.toString().trim(), siret = digits, address = address.text.toString().trim(), conventionName = convention.text.toString().trim(), idcc = idcc.text.toString().trim())
            val saved = SalaryCompanyStore.upsert(context, updated)
            val reread = SalaryCompanyStore.list(context).firstOrNull { it.id == updated.id || (updated.siret.isNotBlank() && it.siret == updated.siret) }
            if (!saved || reread == null) {
                Toast.makeText(context, "Échec de l’enregistrement des informations", Toast.LENGTH_LONG).show()
                return@button
            }
            company = reread
            onChanged(company)
            Toast.makeText(context, "Informations enregistrées et vérifiées", Toast.LENGTH_SHORT).show()
            showSummary()
        })
        addView(button("ANNULER") { showSummary() })
    }

    private fun text(value: String) = TextView(context).apply { text = value; textSize = 15f; setPadding(dp(4), dp(8), dp(4), dp(12)) }
    private fun field(h: String, v: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply { hint = h; setText(v); inputType = type; isSingleLine = true; setPadding(dp(10), dp(6), dp(10), dp(6)) }
    private fun button(label: String, click: () -> Unit) = Button(context).apply { text = label; isAllCaps = false; setOnClickListener { click() } }
    private fun row() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

class SalaryContractDetailsView(context: Context, private val company: SalaryCompanyStore.Company) : LinearLayout(context) {
    private val prefs = SalaryCompanyStore.prefs(context, company.id)
    init { orientation = VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)); showSummary() }

    private fun showSummary() {
        removeAllViews()
        val rate = prefs.getString("hourly_rate", "").orEmpty(); val coefficient = prefs.getString("convention_coefficient", "").orEmpty()
        val type = prefs.getString("contract_type", "").orEmpty(); val entry = prefs.getString("entry_date", "").orEmpty(); val end = prefs.getString("end_date", "").orEmpty(); val weekly = prefs.getString("contract_weekly_hours", "").orEmpty()
        addView(TextView(context).apply {
            textSize = 15f; text = "TAUX HORAIRE BRUT : ${rate.ifBlank { "Non renseigné" }}\n\nCOEFFICIENT CONVENTIONNEL : ${coefficient.ifBlank { "Non renseigné" }}\n\nTYPE DE CONTRAT : ${type.ifBlank { "Non renseigné" }}\n\nDATE D’ENTRÉE : ${entry.ifBlank { "Non renseignée" }}\n\nDATE DE FIN : ${end.ifBlank { "Non applicable / non renseignée" }}\n\nDURÉE HEBDOMADAIRE : ${weekly.ifBlank { "Non renseignée" }}"
        })
        addView(button("MODIFIER LE CONTRAT") { showEditor() })
    }

    private fun showEditor() {
        removeAllViews()
        val rate = field("Taux horaire brut", prefs.getString("hourly_rate", "").orEmpty(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val coefficient = field("Coefficient conventionnel", prefs.getString("convention_coefficient", "").orEmpty())
        val spinner = Spinner(context); val types = listOf("Temps plein", "Temps partiel", "Forfait", "Autre")
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, types)
        spinner.setSelection(when (prefs.getString("contract_type", "")) { "PART_TIME" -> 1; "FORFAIT" -> 2; "OTHER" -> 3; else -> 0 })
        val entry = field("Date d’entrée — JJ/MM/AAAA", prefs.getString("entry_date", "").orEmpty())
        val end = field("Date de fin — si applicable", prefs.getString("end_date", "").orEmpty())
        val weekly = field("Durée hebdomadaire", prefs.getString("contract_weekly_hours", "").orEmpty(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        addView(rate, row()); addView(coefficient, row()); addView(spinner, row()); addView(entry, row()); addView(end, row()); addView(weekly, row())
        addView(button("ENREGISTRER LE CONTRAT") {
            val rateValue = rate.text.toString().replace(',', '.').toDoubleOrNull()
            if (rateValue == null || rateValue <= 0) { rate.error = "Taux horaire invalide"; return@button }
            val type = when (spinner.selectedItemPosition) { 1 -> "PART_TIME"; 2 -> "FORFAIT"; 3 -> "OTHER"; else -> "FULL_TIME" }
            val weeklyValue = weekly.text.toString().replace(',', '.').toDoubleOrNull()
            if (type != "FORFAIT" && (weeklyValue == null || weeklyValue <= 0)) { weekly.error = "Durée hebdomadaire invalide"; return@button }
            val editor = prefs.edit().putString("hourly_rate", rateValue.toString()).putString("convention_coefficient", coefficient.text.toString().trim()).putString("contract_type", type).putString("entry_date", entry.text.toString().trim()).putString("end_date", end.text.toString().trim()).apply {
                if (weeklyValue != null && weeklyValue > 0) putString("contract_weekly_hours", weeklyValue.toString()) else remove("contract_weekly_hours")
            }
            if (!editor.commit()) {
                Toast.makeText(context, "Échec de l’enregistrement du contrat", Toast.LENGTH_LONG).show()
                return@button
            }
            val ok = prefs.getString("hourly_rate", "").orEmpty() == rateValue.toString() &&
                prefs.getString("contract_type", "").orEmpty() == type &&
                prefs.getString("entry_date", "").orEmpty() == entry.text.toString().trim()
            if (!ok) {
                Toast.makeText(context, "Contrat écrit mais non relu correctement", Toast.LENGTH_LONG).show()
                return@button
            }
            Toast.makeText(context, "Contrat enregistré et vérifié", Toast.LENGTH_SHORT).show(); showSummary()
        })
        addView(button("ANNULER") { showSummary() })
    }

    private fun field(h: String, v: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply { hint = h; setText(v.replace('.', ',')); inputType = type; isSingleLine = true; setPadding(dp(10), dp(6), dp(10), dp(6)) }
    private fun button(label: String, click: () -> Unit) = Button(context).apply { text = label; isAllCaps = false; setTypeface(typeface, Typeface.NORMAL); setOnClickListener { click() } }
    private fun row() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
