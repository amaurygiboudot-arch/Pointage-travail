package com.amaury.pointage.v2

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Logical personal domains. Physical names are never accepted as backup payload names. */
object SalaryPreferenceRegistry {
    val personalFiles: Set<String> = linkedSetOf(
        "salary_companies_v2", "salary_settings", "horatrack_v2_integration", "shift_profiles",
        "horatrack_v2_payslips", "horatrack_v2_employment_contract_history",
        "salary_confirmed_sickness_cash_v2", "salary_confirmed_payroll_settlement_v2", "horatrack_v2_rights",
        "horatrack_v2_payroll_coverage", "horatrack_v2_segmented_proration",
        "horatrack_v2_meal_fact_journal", "horatrack_local_provident_documents",
        "horatrack_v2_company_meal_basket_rules", "horatrack_v2_company_meal_basket_audit_state",
        "horatrack_v2_meal_basket_audit_trust", "dated_work_applicability_v2",
        "horatrack_v2_company_pause", "salary_account_recovery_receipt"
    )
    val localOnlyFiles = setOf("horatrack_v2_meal_fact_journal", "horatrack_local_provident_documents", "salary_account_recovery_receipt")
    private val companyName = Regex("salary_company_[A-Za-z0-9_-]+")

    fun isPersonal(name: String): Boolean = name in personalFiles || companyName.matches(name)
    fun isTransferable(name: String): Boolean = isPersonal(name) && name !in localOnlyFiles

    private fun prefix(scope: String): String? {
        if (scope != "guest" && !(scope.startsWith("uid:") && scope.length > 4)) return null
        val hash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "agkgmg_salary_v1_${hash}__"
    }

    fun fileName(logicalName: String, scope: String?): String? {
        if (!isPersonal(logicalName) || scope == null) return null
        return prefix(scope)?.plus(logicalName)
    }

    fun logicalName(physicalName: String, scope: String): String? {
        val prefix = prefix(scope) ?: return null
        if (!physicalName.startsWith(prefix)) return null
        return physicalName.removePrefix(prefix).takeIf(::isPersonal)
    }

    fun personalNames(context: Context, scope: String): Set<String> {
        val result = personalFiles.toMutableSet()
        File(SalaryAccountScope.rawContext(context).applicationInfo.dataDir, "shared_prefs")
            .listFiles().orEmpty().filter { it.name.endsWith(".xml") }.forEach { file ->
                logicalName(file.name.removeSuffix(".xml"), scope)?.let(result::add)
            }
        return result
    }

    fun transferableNames(context: Context, scope: String): Set<String> =
        personalNames(context, scope).filterTo(linkedSetOf(), ::isTransferable)

    /** Legacy files are inspected only by explicit recovery, never by ordinary reads or backups. */
    internal fun legacyNames(context: Context): Set<String> {
        val result = personalFiles.filterNot { it == "dated_work_applicability_v2" || it == "salary_confirmed_payroll_settlement_v2" }.toMutableSet()
        File(SalaryAccountScope.rawContext(context).applicationInfo.dataDir, "shared_prefs")
            .listFiles().orEmpty().filter { it.name.endsWith(".xml") }.forEach { file ->
                val name = file.name.removeSuffix(".xml")
                if (companyName.matches(name)) result += name
            }
        return result
    }
}
