package com.amaury.pointage

import android.app.Activity
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
import com.amaury.pointage.v2.CompanyAgreementIngestionV2
import com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import com.amaury.pointage.v2.CompanyAgreementStoreV2
import com.amaury.pointage.v2.LegifranceFunctionClientV2
import com.amaury.pointage.v2.OfficialAgreementCandidateVerifierV2
import com.amaury.pointage.v2.OfficialAgreementContentParserV2
import com.amaury.pointage.v2.OfficialAgreementResultStoreV2
import com.amaury.pointage.v2.OfficialAgreementSearchParserV2
import com.amaury.pointage.v2.engine.CompanyAgreementStructuredRuleV2

class SalaryCompanyDetailsView(
    context: Context,
    private var company: SalaryCompanyStore.Company,
    private val onChanged: (SalaryCompanyStore.Company) -> Unit,
    private val onDelete: (SalaryCompanyStore.Company) -> Unit
) : LinearLayout(context) {
    private var showingAgreements = false
    private var agreementRevision = 0L

    init { orientation = VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)); showSummary() }

    private fun showSummary() {
        showingAgreements = false
        removeAllViews()
        addView(text("Nom : ${company.name.ifBlank { "Non renseigné" }}\nSIRET : ${company.siret.ifBlank { "Non renseigné" }}\nAdresse : ${company.address.ifBlank { "Non renseignée" }}\nConvention : ${company.conventionName.ifBlank { if (company.idcc.isBlank()) "Non renseignée" else "IDCC ${company.idcc}" }}"))
        addView(button("MODIFIER LES INFORMATIONS") { showEditor() })
        addView(button("ACCORDS D’ENTREPRISE") { showAgreements() })
        addView(button("SUPPRIMER L’ENTREPRISE") { onDelete(company) })
    }

    private fun showAgreements() {
        showingAgreements = true
        agreementRevision = SalaryCompanyStore.prefs(context, company.id).getLong("company_agreement_import_revision", 0L)
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
                addView(text("${agreement.title.ifBlank { "Accord sans titre" }}\nÉtat : $status${if (dates.isBlank()) "" else "\n$dates"}${if (agreement.sourceLabel.isBlank()) "" else "\nSource : ${agreement.sourceLabel}"}${if (agreement.documentName.isBlank()) "" else "\nDocument : ${agreement.documentName}"}"))
                if (agreement.documentPath.isNotBlank()) {
                    addView(button("OUVRIR LE DOCUMENT ORIGINAL") { openAgreementDocument(agreement) })
                }
                val candidates = CompanyAgreementRuleStoreV2.list(context, company.id).filter { it.agreementId == agreement.id }
                if (agreement.id.startsWith("LOCAL-ACCO-") && candidates.isEmpty()) {
                    addView(text("Aucune règle candidate n’a été détectée. Le document reste conservé : vérifie son contenu avant toute saisie manuelle."))
                }
                candidates.forEach { candidate ->
                    val confidence = (candidate.confidence * 100).toInt().coerceIn(0, 100)
                    val structured = CompanyAgreementStructuredRuleV2.structure(candidate)
                    val valueLabel = structured.value?.let { value ->
                        val amount = if (value.amount % 1.0 == 0.0) value.amount.toInt().toString() else value.amount.toString().replace('.', ',')
                        when (value.type) {
                            CompanyAgreementStructuredRuleV2.ValueType.PERCENT -> "$amount %"
                            CompanyAgreementStructuredRuleV2.ValueType.EURO_AMOUNT -> "$amount €"
                            CompanyAgreementStructuredRuleV2.ValueType.HOURS -> "$amount h"
                        }
                    }
                    val applicability = listOfNotNull(
                        candidate.effectiveFrom?.let { "Début : $it" },
                        candidate.effectiveTo?.let { "Fin : $it" },
                        candidate.scope?.let { "Champ : $it" }
                    ).joinToString("\n")
                    addView(text("Règle détectée : ${candidate.category.name}\nConfiance : $confidence %\nValidation : ${if (candidate.verified) "Vérifiée" else "À vérifier"}${valueLabel?.let { "\nValeur détectée : $it\nValeur de calcul : ${if (candidate.calculationValueVerified) "Validée" else "À vérifier"}" } ?: "\nValeur de calcul : non déterminée"}${if (applicability.isBlank()) "" else "\n$applicability"}\n${candidate.excerpt}"))
                    if (candidate.verified) {
                        val from = field("Début d’application — JJ/MM/AAAA", candidate.effectiveFrom.orEmpty())
                        val to = field("Fin d’application — facultative", candidate.effectiveTo.orEmpty())
                        val scope = field("Champ d’application — ex. tous les salariés", candidate.scope.orEmpty())
                        addView(from, row()); addView(to, row()); addView(scope, row())
                        addView(button("ENREGISTRER L’APPLICABILITÉ") {
                            val saved = CompanyAgreementRuleStoreV2.setApplicability(
                                context,
                                company.id,
                                candidate.agreementId,
                                candidate.category,
                                candidate.excerpt,
                                from.text.toString(),
                                to.text.toString(),
                                scope.text.toString()
                            )
                            Toast.makeText(context, if (saved) "Période et champ enregistrés." else "Impossible d’enregistrer l’applicabilité.", Toast.LENGTH_LONG).show()
                            if (saved) showAgreements()
                        })
                        if (structured.value != null) {
                            addView(button(if (candidate.calculationValueVerified) "RETIRER LA VALIDATION DE LA VALEUR" else "VALIDER LA VALEUR DÉTECTÉE") {
                                val saved = CompanyAgreementRuleStoreV2.setCalculationValueVerified(
                                    context,
                                    company.id,
                                    candidate.agreementId,
                                    candidate.category,
                                    candidate.excerpt,
                                    !candidate.calculationValueVerified
                                )
                                Toast.makeText(
                                    context,
                                    if (saved) if (candidate.calculationValueVerified) "Validation de la valeur retirée." else "Valeur de calcul validée."
                                    else "Impossible d’enregistrer la validation de la valeur.",
                                    Toast.LENGTH_LONG
                                ).show()
                                if (saved) showAgreements()
                            })
                        }
                    }
                    addView(button(if (candidate.verified) "RETIRER LA VALIDATION" else "VALIDER CETTE RÈGLE") {
                        val saved = CompanyAgreementRuleStoreV2.setVerified(
                            context,
                            company.id,
                            candidate.agreementId,
                            candidate.category,
                            candidate.excerpt,
                            !candidate.verified
                        )
                        Toast.makeText(
                            context,
                            if (saved) if (candidate.verified) "Validation retirée." else "Règle validée — elle reste séparée du moteur de paie pour l’instant."
                            else "Impossible d’enregistrer la validation.",
                            Toast.LENGTH_LONG
                        ).show()
                        if (saved) showAgreements()
                    })
                }
                if (agreement.id.startsWith("ACCOTEXT")) {
                    addView(button("ANALYSER CET ACCORD") {
                        Toast.makeText(context, "Récupération de l’accord officiel…", Toast.LENGTH_SHORT).show()
                        LegifranceFunctionClientV2.request("/consult/acco", mapOf("id" to agreement.id))
                            .addOnSuccessListener { result ->
                                val officialContent = OfficialAgreementContentParserV2.extractVerified(result.data, company.siret)
                                if (officialContent == null) {
                                    Toast.makeText(context, "Accord refusé : SIRET différent, absent ou contenu officiel inexploitable.", Toast.LENGTH_LONG).show()
                                    return@addOnSuccessListener
                                }
                                val ingestion = CompanyAgreementIngestionV2.ingest(context, company.id, agreement.id, officialContent.text)
                                Toast.makeText(
                                    context,
                                    if (ingestion.saved) "Analyse terminée : ${ingestion.extractedCount} règle(s) candidate(s), aucune appliquée automatiquement."
                                    else "Analyse terminée mais l’enregistrement des règles a échoué.",
                                    Toast.LENGTH_LONG
                                ).show()
                                if (ingestion.saved) showAgreements()
                            }
                            .addOnFailureListener { error ->
                                Toast.makeText(context, "Lecture de l’accord impossible : ${error.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
                            }
                    })
                }
            }
        }
        addView(button("RECHERCHER DANS LES SOURCES OFFICIELLES") {
            val siret = company.siret.filter(Char::isDigit)
            if (siret.length != 14) {
                Toast.makeText(context, "Renseigne d’abord un SIRET valide à 14 chiffres.", Toast.LENGTH_LONG).show()
                return@button
            }
            SalaryCompanyStore.prefs(context, company.id).edit()
                .putBoolean("company_agreement_search_requested", true)
                .putString("company_agreement_search_siret", siret)
                .putLong("company_agreement_search_requested_at", System.currentTimeMillis())
                .commit()

            val body = mapOf(
                "fond" to "ACCO",
                "recherche" to mapOf(
                    "filtres" to listOf(
                        mapOf(
                            "valeurs" to listOf(siret),
                            "facette" to "SIRET_RAISON_SOCIALE"
                        )
                    ),
                    "champs" to listOf(
                        mapOf(
                            "typeChamp" to "ALL",
                            "criteres" to listOf(
                                mapOf(
                                    "typeRecherche" to "EXACTE",
                                    "valeur" to siret,
                                    "operateur" to "ET"
                                )
                            ),
                            "operateur" to "ET"
                        )
                    ),
                    "pageNumber" to 1,
                    "pageSize" to 25,
                    "operateur" to "ET",
                    "sort" to "DATE_DESC",
                    "fromAdvancedRecherche" to false,
                    "secondSort" to "ID",
                    "typePagination" to "DEFAUT"
                )
            )

            Toast.makeText(context, "Recherche officielle en cours…", Toast.LENGTH_SHORT).show()
            LegifranceFunctionClientV2.request("/search", body)
                .addOnSuccessListener { result ->
                    OfficialAgreementResultStoreV2.save(context, company.id, siret, result.data)
                    val candidates = OfficialAgreementSearchParserV2.parseCandidates(result.data)
                    OfficialAgreementCandidateVerifierV2.verify(candidates, siret)
                        .addOnSuccessListener { verification ->
                            val found = verification.verified
                            if (found.isNotEmpty()) {
                                val existing = CompanyAgreementStoreV2.list(context, company.id).associateBy { it.id }
                                val merged = existing.values + found.filterNot { existing.containsKey(it.id) }
                                CompanyAgreementStoreV2.save(context, company.id, merged)
                            }
                            SalaryCompanyStore.prefs(context, company.id).edit()
                                .putLong("company_agreement_search_completed_at", System.currentTimeMillis())
                                .commit()
                            Toast.makeText(
                                context,
                                if (found.isEmpty()) "Recherche terminée — aucun accord vérifié pour ce SIRET."
                                else "${found.size} accord(s) Légifrance vérifié(s) pour ce SIRET${if (verification.rejectedCount > 0) " — ${verification.rejectedCount} candidat(s) écarté(s)" else ""}.",
                                Toast.LENGTH_LONG
                            ).show()
                            showAgreements()
                        }
                        .addOnFailureListener { error ->
                            Toast.makeText(context, "Vérification des accords impossible : ${error.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
                        }
                }
                .addOnFailureListener { error ->
                    Toast.makeText(context, "Recherche Légifrance impossible : ${error.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
                }
        })
        addView(button("JE POSSÈDE UN ACCORD À IMPORTER") {
            SalaryCompanyStore.prefs(context, company.id).edit()
                .putBoolean("company_agreement_import_requested", true)
                .putLong("company_agreement_import_requested_at", System.currentTimeMillis())
                .commit()
            val intent = Intent(context, CompanyAgreementImportActivity::class.java)
                .putExtra(CompanyAgreementImportActivity.EXTRA_COMPANY_ID, company.id)
                .putExtra(CompanyAgreementImportActivity.EXTRA_COMPANY_NAME, company.name)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        })
        addView(button("RETOUR") { showSummary() })
    }

    private fun showEditor() {
        showingAgreements = false
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

    private fun openAgreementDocument(agreement: CompanyAgreementStoreV2.Agreement) {
        val uri = CompanyAgreementDocumentStoreV2.contentUri(context, agreement.documentPath)
        if (uri == null) {
            Toast.makeText(context, "Le document original n’est plus accessible sur cet appareil.", Toast.LENGTH_LONG).show()
            return
        }
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, agreement.documentMimeType.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(open, "Ouvrir l’accord").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(chooser) }
            .onFailure { Toast.makeText(context, "Aucune application ne peut ouvrir ce document.", Toast.LENGTH_LONG).show() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || !showingAgreements) return
        val current = SalaryCompanyStore.prefs(context, company.id).getLong("company_agreement_import_revision", 0L)
        if (current != agreementRevision) showAgreements()
    }

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
        val type = prefs.getString("contract_type", "").orEmpty(); val weekly = prefs.getString("contract_weekly_hours", "").orEmpty(); val monthly = prefs.getString("monthly_contract_salary", "").orEmpty(); val status = prefs.getString("professional_status", "").orEmpty()
        addView(text("Type de contrat : ${type.ifBlank { "Non renseigné" }}\nTemps de travail : ${weekly.ifBlank { "Non renseigné" }}\nTaux horaire : ${rate.ifBlank { "Non renseigné" }}\nSalaire mensuel forfait : ${monthly.ifBlank { "Non renseigné" }}\nStatut : ${status.ifBlank { "Non renseigné" }}\nCoefficient : ${coefficient.ifBlank { "Non renseigné" }}"))
        addView(button("MODIFIER") { showEditor() })
    }

    private fun showEditor() {
        removeAllViews()
        val type = Spinner(context); val values = listOf("TEMPS_PLEIN", "TEMPS_PARTIEL", "FORFAIT_HEURES", "FORFAIT_JOURS")
        type.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
        type.setSelection(values.indexOf(prefs.getString("contract_type", "TEMPS_PLEIN")).coerceAtLeast(0))
        val weekly = field("Heures contractuelles / semaine", prefs.getString("contract_weekly_hours", "").orEmpty())
        val annualHours = field("Forfait annuel en heures", prefs.getString("annual_hours_package", "").orEmpty())
        val annualDays = field("Forfait annuel en jours", prefs.getString("annual_days_package", "").orEmpty())
        val rate = field("Taux horaire brut", prefs.getString("hourly_rate", "").orEmpty())
        val monthly = field("Salaire mensuel brut contractuel", prefs.getString("monthly_contract_salary", "").orEmpty())
        val status = Spinner(context); val statuses = listOf("NON_CADRE", "CADRE"); status.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, statuses); status.setSelection(statuses.indexOf(prefs.getString("professional_status", "NON_CADRE")).coerceAtLeast(0))
        val coefficient = field("Coefficient convention collective", prefs.getString("convention_coefficient", "").orEmpty(), InputType.TYPE_CLASS_NUMBER)
        addView(type, row()); listOf(weekly, annualHours, annualDays, rate, monthly).forEach { addView(it, row()) }; addView(status, row()); addView(coefficient, row())
        addView(button("ENREGISTRER") {
            prefs.edit().putString("contract_type", type.selectedItem.toString()).putString("contract_weekly_hours", weekly.text.toString().trim()).putString("annual_hours_package", annualHours.text.toString().trim()).putString("annual_days_package", annualDays.text.toString().trim()).putString("hourly_rate", rate.text.toString().trim()).putString("monthly_contract_salary", monthly.text.toString().trim()).putString("professional_status", status.selectedItem.toString()).putString("convention_coefficient", coefficient.text.toString().trim()).commit()
            Toast.makeText(context, "Contrat enregistré", Toast.LENGTH_SHORT).show(); showSummary()
        })
        addView(button("ANNULER") { showSummary() })
    }

    private fun text(value: String) = TextView(context).apply { text = value; textSize = 15f; setPadding(dp(4), dp(8), dp(4), dp(12)) }
    private fun field(h: String, v: String, type: Int = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) = EditText(context).apply { hint = h; setText(v); inputType = type; isSingleLine = true; setPadding(dp(10), dp(6), dp(10), dp(6)) }
    private fun button(label: String, click: () -> Unit) = Button(context).apply { text = label; isAllCaps = false; setOnClickListener { click() } }
    private fun row() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
