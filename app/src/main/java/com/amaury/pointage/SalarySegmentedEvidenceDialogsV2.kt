package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.*
import com.amaury.pointage.v2.engine.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Explicit evidence entry. Opening a salary screen never confirms work or a planning rule. */
object SalarySegmentedEvidenceDialogsV2 {
    fun confirmCoverage(context: Context, company: SalaryCompanyStore.Company, year: Int, month: Int,
                        refreshed: () -> Unit) {
        val period = YearMonth.of(year, month + 1)
        val bounds = V2SegmentedWorkedGrossProductionBridge.coverageBounds(
            period.atDay(1).toEpochDay(), period.atEndOfMonth().toEpochDay()) ?: return
        val zone = ZoneId.systemDefault()
        val start = LocalDate.ofEpochDay(bounds.first)
        val end = LocalDate.ofEpochDay(bounds.second)
        if (end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() > System.currentTimeMillis()) {
            message(context, "La dernière semaine nécessaire se termine le $end. La période doit être entièrement terminée avant confirmation.")
            return
        }
        AlertDialog.Builder(context).setTitle("Vérifier tous les pointages")
            .setMessage("${company.name}\nDu $start au $end inclus (${zone.id}).\n\nVérifie dans l’historique tous les lieux de cette entreprise, les horaires, pauses, déplacements et jours sans travail, y compris les jours hors du mois nécessaires aux semaines complètes.\n\nConfirme uniquement si cet historique est complet et exact. Une modification ultérieure invalidera cette preuve.")
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("HISTORIQUE VÉRIFIÉ") { _, _ ->
                val now = System.currentTimeMillis()
                val saved = V2PayrollCoverageStore.saveConfirmed(context, company.id,
                    bounds.first, bounds.second, now, zone.id, now)
                message(context, if (saved != null) "Couverture des pointages confirmée." else
                    "Confirmation impossible : vérifie la cohérence de l’historique.")
                refreshed()
            }.show()
    }

    fun confirmProration(context: Context, company: SalaryCompanyStore.Company, year: Int, month: Int,
                         refreshed: () -> Unit) {
        val contracts = V2EmploymentContractPayrollBridge.resolve(context, company.id, year, month).resolution
        val rules = V2ConventionRulePayrollBridge.resolve(context, company.idcc, year, month).resolution
        val segments = contracts.calculationSegments
        if (segments.isEmpty() || !rules.readyForCalculation) {
            message(context, "Confirme d’abord les contrats et les règles datées de ce mois.")
            return
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24,16,24,16) }
        content.addView(TextView(context).apply {
            text = "${company.name} — ${YearMonth.of(year,month+1)}\nMinutes planifiées de référence par période contractuelle : utilise un planning vérifié, pas les heures réellement pointées. La répartition de la base mensuelle se fait selon ces minutes. Ne confirme pas si cette méthode ne correspond pas à ta situation."
        })
        val source = EditText(context).apply { hint = "Référence du planning vérifié"; inputType = InputType.TYPE_CLASS_TEXT }
        content.addView(source)
        val inputs = segments.map { segment ->
            content.addView(TextView(context).apply {
                text = "${LocalDate.ofEpochDay(segment.startEpochDay)} → ${LocalDate.ofEpochDay(segment.endEpochDay)}"
            })
            EditText(context).apply { hint = "Minutes planifiées (nombre entier)"; inputType = InputType.TYPE_CLASS_NUMBER
                content.addView(this) }
        }
        val dialog = AlertDialog.Builder(context).setTitle("Planning de référence confirmé")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("ANNULER", null).setPositiveButton("CONFIRMER", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = inputs.map { it.text.toString().trim().toIntOrNull() }
                if (source.text.toString().isBlank() || minutes.any { it == null || it < 0 }) {
                    message(context, "Renseigne la référence et des minutes entières positives ou nulles pour chaque période.")
                    return@setOnClickListener
                }
                val currentContracts = V2EmploymentContractPayrollBridge.resolve(context,company.id,year,month).resolution
                val currentRules = V2ConventionRulePayrollBridge.resolve(context,company.idcc,year,month).resolution
                if (currentContracts != contracts || currentRules != rules) {
                    message(context, "Le contrat ou les règles ont changé. Rouvre ce formulaire.")
                    return@setOnClickListener
                }
                val value = ConfirmedSegmentedMonthlyProrationV2(source.text.toString().trim(),System.currentTimeMillis(),
                    segments = segments.mapIndexed { index, segment -> ConfirmedProrationSegmentV2(
                        segment.snapshot.versionId,segment.startEpochDay,segment.endEpochDay,minutes[index]!!) })
                val verified = SegmentedMonthlyBaseBridgeV2.calculate(currentContracts,currentRules,value)
                if (!verified.reliable) {
                    message(context, verified.warnings.joinToString("\n"))
                    return@setOnClickListener
                }
                if (V2SegmentedProrationStore.save(context,company.id,YearMonth.of(year,month+1),value)) {
                    dialog.dismiss();message(context,"Planning de référence confirmé.");refreshed()
                } else message(context,"Enregistrement impossible ; aucune confirmation n’est annoncée.")
            }
        }
        dialog.show()
    }

    private fun message(context: Context, text: String) = Toast.makeText(context,text,Toast.LENGTH_LONG).show()
}
