package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.CompanyAtMpRateStoreV2
import com.amaury.pointage.v2.CompanyProfessionalStatusStoreV2
import com.amaury.pointage.v2.CompanyEmployeeDeductionStoreV2
import com.amaury.pointage.v2.CompanyIncomeTaxRateStoreV2
import com.amaury.pointage.v2.RestoreGuardedPreferences
import com.amaury.pointage.v2.engine.CompanyEmployeeDeductionResolverV2
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** Résumés d'historiques, sans choisir arbitrairement une période active. */
object CompanyPayrollFactsSummaryV2 {
    fun selectedMonth(context: Context): YearMonth {
        val ms = RestoreGuardedPreferences.open(context, "navigation_state", Context.MODE_PRIVATE)
            .getLong("report_month_ms", -1L)
        return if (ms > 0L) YearMonth.from(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
        else YearMonth.now()
    }

    fun incomeTax(context: Context, companyId: String, month: YearMonth): String {
        val value = CompanyIncomeTaxRateStoreV2.resolve(context, companyId, month)
        val legacy = SalaryCompanyStore.prefs(context, companyId).getString("income_tax_rate_percent", "").orEmpty()
        return buildString {
            append("PAS — ").append(month).append("\n")
            if (value.reliable && value.ratePercent != null) {
                append(String.format(Locale.FRANCE, "%.2f %%", value.ratePercent))
                append(" · ").append(value.source)
            } else append(value.warnings.joinToString("\n").ifBlank { "Aucun taux daté confirmé pour ce mois." })
            if (legacy.isNotBlank()) append("\nAncienne saisie conservée : ").append(legacy)
                .append(" % — brouillon sans période, non utilisé. Confirme la période et la source dans le formulaire daté.")
        }
    }

    fun employeeDeductions(context: Context, companyId: String, month: YearMonth): String {
        val values = CompanyEmployeeDeductionStoreV2.resolve(context, companyId, month)
        val legacyKeys = mapOf(
            CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE to "mutual_employee_amount",
            CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE to "provident_employee_amount",
            CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE to "transport_employee_amount",
            CompanyEmployeeDeductionResolverV2.Kind.EMPLOYER_PROTECTION_TAXABLE to "employer_protection_taxable_amount",
            CompanyEmployeeDeductionResolverV2.Kind.EMPLOYEE_PROVIDENT_NON_DEDUCTIBLE to "employee_provident_nondeductible_amount"
        )
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        return buildString {
            append("Retenues et bases fiscales — ").append(month)
            CompanyEmployeeDeductionResolverV2.Kind.entries.forEach { kind ->
                val value = values[kind]
                append("\n").append(kind.label).append(" : ")
                if (value.reliable && value.amount != null) {
                    append(String.format(Locale.FRANCE, "%.2f €", value.amount))
                    append(" · ").append(value.source)
                } else append(value.warnings.joinToString(" ").ifBlank { "à confirmer pour ce mois" })
                val legacy = legacyKeys[kind]?.let { prefs.getString(it, "").orEmpty() }.orEmpty()
                if (legacy.isNotBlank()) append("\nAncienne saisie conservée : ").append(legacy)
                    .append(" € — brouillon sans période, non utilisé.")
            }
        }
    }

    fun professionalStatus(context: Context, companyId: String): String {
        val stored = CompanyProfessionalStatusStoreV2.read(context, companyId)
        return when {
            !stored.reliable -> "Statut professionnel : à confirmer — historique indisponible."
            stored.records.isEmpty() -> "Statut professionnel : aucune version datée confirmée."
            else -> "Statut professionnel : ${stored.records.size} version(s) enregistrée(s). La couverture du mois est vérifiée au calcul."
        }
    }

    fun atMp(context: Context, companyId: String): String {
        val stored = CompanyAtMpRateStoreV2.read(context, companyId)
        return when {
            !stored.reliable -> "AT/MP : à confirmer — historique indisponible."
            stored.records.isEmpty() -> "AT/MP : aucune version datée confirmée ; nécessaire pour compléter le coût employeur."
            else -> "AT/MP : ${stored.records.size} version(s) enregistrée(s). Le taux doit couvrir l'établissement et le mois calculés."
        }
    }
}
