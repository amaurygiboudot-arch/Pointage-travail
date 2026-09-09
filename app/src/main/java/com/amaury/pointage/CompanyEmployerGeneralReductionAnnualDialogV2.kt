package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.CompanyEmployerGeneralReductionAnnualContextStoreV2
import com.amaury.pointage.v2.CompanyEmployerGeneralReductionAnnualPayrollBridgeV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionAnnualContextV2
import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import com.amaury.pointage.v2.model.ContractTypeV2
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Contexte annuel RGDU et résultat de régularisation.
 *
 * Aucun paramètre actuel du salarié n'est prérempli comme fait historique : toutes les valeurs
 * annuelles doivent être confirmées explicitement avec une source vérifiable.
 */
object CompanyEmployerGeneralReductionAnnualDialogV2 {
    private val yesNoLabels = listOf("À confirmer", "Oui", "Non")
    private val workforceLabels = listOf("À confirmer", "Moins de 11 salariés", "11 à 49 salariés", "50 salariés ou plus")
    private val contractLabels = listOf("À confirmer", "Temps plein", "Temps partiel")

    fun show(context: Context, companyId: String, year: Int = selectedPayrollYear(context)) {
        if (companyId.isBlank()) return

        val stored = CompanyEmployerGeneralReductionAnnualContextStoreV2.read(context, companyId)
        val records = stored.records.filter { it.year == year }
        val existing = records.singleOrNull()
        val snapshot = CompanyEmployerGeneralReductionAnnualContextStoreV2.resolve(context, companyId, year)

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 12))
        }
        box.addView(TextView(context).apply {
            text = buildString {
                append("Régularisation RGDU ").append(year).append("\n\n")
                append("HoraTrack ne déduit jamais les paramètres historiques depuis le contrat actuel. ")
                append("Confirme ici uniquement des faits valables pour toute l'année, avec une source vérifiable.")
                if (!stored.reliable) {
                    append("\n\n⚠ Stockage annuel incohérent.")
                    if (stored.warnings.isNotEmpty()) append("\n• ").append(stored.warnings.joinToString("\n• "))
                } else if (records.size > 1) {
                    append("\n\n⚠ Plusieurs contextes existent pour cette année. Une nouvelle confirmation remplacera explicitement les doublons de l'année.")
                }
            }
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 8))
        })
        box.addView(TextView(context).apply {
            text = contextSummary(snapshot, year)
            textSize = 13f
            setPadding(0, dp(context, 4), 0, dp(context, 8))
        })

        var listDialog: AlertDialog? = null
        box.addView(Button(context).apply {
            isAllCaps = false
            text = if (existing == null) "CONFIRMER LE CONTEXTE ANNUEL" else "MODIFIER LE CONTEXTE ANNUEL"
            isEnabled = stored.reliable
            setOnClickListener {
                listDialog?.dismiss()
                showEditor(context, companyId, year, existing)
            }
        }, rowParams(context))

        box.addView(Button(context).apply {
            isAllCaps = false
            text = "CALCULER / RÉACTUALISER LA RÉGULARISATION"
            setOnClickListener {
                val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.resolve(
                    context = context,
                    companyId = companyId,
                    year = year
                )
                showResult(context, year, result)
            }
        }, rowParams(context))

        if (existing != null) {
            box.addView(Button(context).apply {
                isAllCaps = false
                text = "SUPPRIMER LE CONTEXTE ANNUEL"
                isEnabled = stored.reliable
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("Supprimer le contexte RGDU $year ?")
                        .setMessage("Le calcul annuel redeviendra « à confirmer ». Les montants RGDU mensuels constatés ne seront pas supprimés.")
                        .setPositiveButton("SUPPRIMER") { _, _ ->
                            if (CompanyEmployerGeneralReductionAnnualContextStoreV2.remove(context, companyId, existing.id)) {
                                listDialog?.dismiss()
                                show(context, companyId, year)
                            } else {
                                Toast.makeText(context, "Échec de la suppression du contexte annuel", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("ANNULER", null)
                        .show()
                }
            }, rowParams(context))
        }

        val scroll = ScrollView(context).apply { addView(box) }
        listDialog = AlertDialog.Builder(context)
            .setTitle("RGDU annuelle")
            .setView(scroll)
            .setNegativeButton("FERMER", null)
            .create()
        listDialog.show()
    }

    private fun showEditor(
        context: Context,
        companyId: String,
        year: Int,
        existing: EmployerGeneralReductionAnnualContextV2.Record?
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 8), dp(context, 18), 0)
        }
        box.addView(TextView(context).apply {
            text = "Année $year — aucune valeur ci-dessous n'est reprise automatiquement du profil salarié actuel. Le moteur annuel 2026 couvre ici le cas standard avec paramètres stables sur l'année."
            textSize = 12f
            setPadding(0, 0, 0, dp(context, 8))
        })

        val fullYear = labelledSpinner(context, box, "Présence sur toute l'année civile", yesNoLabels)
        val standard = labelledSpinner(context, box, "Cas RGDU de droit commun sur toute l'année", yesNoLabels)
        val homogeneous = labelledSpinner(context, box, "Paramètres stables sur toute l'année", yesNoLabels)
        val workforce = labelledSpinner(context, box, "Tranche d'effectif historique", workforceLabels)
        val contract = labelledSpinner(context, box, "Type de contrat historique", contractLabels)
        val weeklyMinutes = EditText(context).apply {
            hint = "Durée contractuelle hebdomadaire en minutes — ex. 2100 = 35 h"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        val source = EditText(context).apply {
            hint = "Source annuelle — ex. DSN annuelle / bulletins vérifiés"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        box.addView(weeklyMinutes, rowParams(context))
        box.addView(source, rowParams(context))

        existing?.let { record ->
            fullYear.setSelection(booleanSelection(record.fullCalendarYearPresent))
            standard.setSelection(booleanSelection(record.standardCommonLawCaseConfirmed))
            homogeneous.setSelection(nullableBooleanSelection(record.homogeneousAnnualParametersConfirmed))
            workforce.setSelection(workforceSelection(record.confirmedWorkforceBand))
            contract.setSelection(contractSelection(record.confirmedContractType))
            record.confirmedContractualWeeklyMinutes?.let { weeklyMinutes.setText(it.toString()) }
            source.setText(record.source)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Contexte RGDU annuel $year")
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("ENREGISTRER", null)
            .setNegativeButton("ANNULER", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fullYearValue = parseRequiredBoolean(fullYear.selectedItemPosition)
                val standardValue = parseRequiredBoolean(standard.selectedItemPosition)
                if (fullYearValue == null || standardValue == null) {
                    Toast.makeText(context, "Confirme Oui ou Non pour la présence annuelle et le cas de droit commun", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val homogeneousValue = parseNullableBoolean(homogeneous.selectedItemPosition)
                val workforceValue = parseWorkforce(workforce.selectedItemPosition)
                val contractValue = parseContract(contract.selectedItemPosition)
                val minutesValue = weeklyMinutes.text.toString().trim().toIntOrNull()
                val rawSource = source.text.toString().trim()

                if (rawSource.isBlank()) {
                    source.error = "Indique une source vérifiable"
                    return@setOnClickListener
                }
                if (minutesValue != null && minutesValue <= 0) {
                    weeklyMinutes.error = "Durée invalide"
                    return@setOnClickListener
                }
                if (homogeneousValue == true) {
                    if (workforceValue == null) {
                        Toast.makeText(context, "Confirme la tranche d'effectif historique", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (contractValue == null) {
                        Toast.makeText(context, "Confirme le type de contrat historique", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (minutesValue == null || minutesValue <= 0) {
                        weeklyMinutes.error = "Durée hebdomadaire historique requise"
                        return@setOnClickListener
                    }
                }

                val record = EmployerGeneralReductionAnnualContextV2.Record(
                    id = existing?.id ?: "rgdu_annual_${UUID.randomUUID()}",
                    year = year,
                    fullCalendarYearPresent = fullYearValue,
                    standardCommonLawCaseConfirmed = standardValue,
                    homogeneousAnnualParametersConfirmed = homogeneousValue,
                    source = rawSource,
                    confirmedWorkforceBand = workforceValue,
                    confirmedContractType = contractValue,
                    confirmedContractualWeeklyMinutes = minutesValue
                )
                if (!CompanyEmployerGeneralReductionAnnualContextStoreV2.save(context, companyId, record)) {
                    Toast.makeText(context, "Échec de l'enregistrement du contexte annuel", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(context, "Contexte RGDU annuel enregistré", Toast.LENGTH_SHORT).show()
                show(context, companyId, year)
            }
        }
        dialog.show()
    }

    private fun showResult(
        context: Context,
        year: Int,
        result: CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.Result
    ) {
        val text = buildString {
            if (result.reliable && result.regularization != null) {
                val regularization = result.regularization
                append("Droit RGDU annuel : ").append(euros(regularization.annualEntitlement)).append('\n')
                append("Avances prises en compte : ").append(euros(regularization.advancesTotal)).append('\n')
                append("Régularisation : ").append(euros(regularization.adjustment)).append('\n')
                append(
                    when {
                        (regularization.adjustment ?: 0.0) > 0.0 -> "→ complément de réduction"
                        (regularization.adjustment ?: 0.0) < 0.0 -> "→ reprise à régulariser"
                        else -> "→ aucune régularisation"
                    }
                )
                append("\n\nBase des avances : ")
                append(
                    when (result.advanceBasis) {
                        CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.AdvanceBasis.CONFIRMED_OBSERVED ->
                            "12 montants RGDU réellement constatés et confirmés"
                        CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.AdvanceBasis.RECONSTRUCTED_AUTOMATIC ->
                            "reconstruction automatique HoraTrack"
                        null -> "à confirmer"
                    }
                )
            } else {
                append("Calcul annuel à confirmer / bloqué.")
            }
            if (result.warnings.isNotEmpty()) {
                append("\n\n⚠ ").append(result.warnings.joinToString("\n⚠ "))
            }
            if (result.notes.isNotEmpty()) {
                append("\n\n").append(result.notes.joinToString("\n"))
            }
        }
        AlertDialog.Builder(context)
            .setTitle("Régularisation RGDU $year")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun contextSummary(snapshot: EmployerGeneralReductionAnnualContextV2.Snapshot, year: Int): String = buildString {
        append("CONTEXTE ").append(year).append("\n")
        if (!snapshot.reliable) {
            append("À confirmer")
        } else {
            append("Année complète : ").append(boolLabel(snapshot.fullCalendarYearPresent)).append('\n')
            append("Droit commun : ").append(boolLabel(snapshot.standardCommonLawCaseConfirmed)).append('\n')
            append("Paramètres stables : ").append(boolLabel(snapshot.homogeneousAnnualParametersConfirmed)).append('\n')
            append("Effectif : ").append(workforceLabel(snapshot.confirmedWorkforceBand)).append('\n')
            append("Contrat : ").append(contractLabel(snapshot.confirmedContractType)).append('\n')
            append("Durée hebdo : ")
            append(snapshot.confirmedContractualWeeklyMinutes?.let { "$it min" } ?: "À confirmer").append('\n')
            append("Source : ").append(snapshot.source ?: "À confirmer")
        }
        if (snapshot.warnings.isNotEmpty()) {
            append("\n• ").append(snapshot.warnings.joinToString("\n• "))
        }
    }

    private fun labelledSpinner(
        context: Context,
        parent: LinearLayout,
        label: String,
        labels: List<String>
    ): Spinner {
        parent.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setPadding(0, dp(context, 8), 0, dp(context, 2))
        })
        return Spinner(context).also { spinner ->
            spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            parent.addView(spinner, rowParams(context))
        }
    }

    private fun booleanSelection(value: Boolean) = if (value) 1 else 2
    private fun nullableBooleanSelection(value: Boolean?) = when (value) { true -> 1; false -> 2; null -> 0 }
    private fun parseRequiredBoolean(position: Int): Boolean? = when (position) { 1 -> true; 2 -> false; else -> null }
    private fun parseNullableBoolean(position: Int): Boolean? = parseRequiredBoolean(position)

    private fun workforceSelection(value: EmployerWorkforceContributionsV2.Band?) = when (value) {
        EmployerWorkforceContributionsV2.Band.UNDER_11 -> 1
        EmployerWorkforceContributionsV2.Band.FROM_11_TO_49 -> 2
        EmployerWorkforceContributionsV2.Band.AT_LEAST_50 -> 3
        null -> 0
    }

    private fun parseWorkforce(position: Int): EmployerWorkforceContributionsV2.Band? = when (position) {
        1 -> EmployerWorkforceContributionsV2.Band.UNDER_11
        2 -> EmployerWorkforceContributionsV2.Band.FROM_11_TO_49
        3 -> EmployerWorkforceContributionsV2.Band.AT_LEAST_50
        else -> null
    }

    private fun contractSelection(value: ContractTypeV2?) = when (value) {
        ContractTypeV2.FULL_TIME -> 1
        ContractTypeV2.PART_TIME -> 2
        else -> 0
    }

    private fun parseContract(position: Int): ContractTypeV2? = when (position) {
        1 -> ContractTypeV2.FULL_TIME
        2 -> ContractTypeV2.PART_TIME
        else -> null
    }

    private fun boolLabel(value: Boolean?) = when (value) { true -> "Oui"; false -> "Non"; null -> "À confirmer" }

    private fun workforceLabel(value: EmployerWorkforceContributionsV2.Band?) = when (value) {
        EmployerWorkforceContributionsV2.Band.UNDER_11 -> "Moins de 11"
        EmployerWorkforceContributionsV2.Band.FROM_11_TO_49 -> "11 à 49"
        EmployerWorkforceContributionsV2.Band.AT_LEAST_50 -> "50 ou plus"
        null -> "À confirmer"
    }

    private fun contractLabel(value: ContractTypeV2?) = when (value) {
        ContractTypeV2.FULL_TIME -> "Temps plein"
        ContractTypeV2.PART_TIME -> "Temps partiel"
        null -> "À confirmer"
        else -> "Non pris en charge par le calcul annuel standard"
    }

    private fun euros(value: Double?): String = value?.let {
        String.format(Locale.FRANCE, "%.2f €", it)
    } ?: "À confirmer"

    private fun selectedPayrollYear(context: Context): Int {
        val ms = context.getSharedPreferences("navigation_state", Context.MODE_PRIVATE)
            .getLong("report_month_ms", -1L)
        val calendar = Calendar.getInstance(Locale.FRANCE)
        if (ms > 0L) calendar.timeInMillis = ms
        return calendar.get(Calendar.YEAR)
    }

    private fun rowParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(context, 6) }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
