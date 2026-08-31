package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.EmployerV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

object V2ProfileStore {
    private const val LEGACY_PREFS = "salary_settings"

    @Volatile private var boundContext: Context? = null

    data class Profile(
        val employer: EmployerV2?,
        val contract: ContractV2?,
        val companySlot: Int,
        val missing: List<String>
    )

    fun bind(context: Context) { boundContext = context.applicationContext }
    fun loadBound(companySlot: Int = 1): Profile? = boundContext?.let { load(it, companySlot) }

    /**
     * Source active : SalaryCompanyStore + prefs propres à l'entreprise.
     * L'ancien salary_settings n'est lu qu'en secours si aucune entreprise V2 n'existe encore.
     */
    fun load(context: Context, companySlot: Int = 1): Profile {
        bind(context)
        val slot = companySlot.coerceIn(1, 2)
        val company = SalaryCompanyStore.list(context).getOrNull(slot - 1)
        if (company != null) return loadV2Company(context, company, slot)
        return loadLegacyFallback(context, slot)
    }

    fun primaryEmployerId(context: Context): String? = load(context, 1).employer?.id

    fun activeCompanySlot(context: Context): Int {
        bind(context)
        val prefs = context.applicationContext.getSharedPreferences("horatrack_v2_integration", Context.MODE_PRIVATE)
        return safeLong(prefs.all["active_company_slot"])?.toInt()?.coerceIn(1, 2) ?: 1
    }

    fun setActiveCompanySlot(context: Context, slot: Int) {
        bind(context)
        context.applicationContext.getSharedPreferences("horatrack_v2_integration", Context.MODE_PRIVATE).edit()
            .putInt("active_company_slot", slot.coerceIn(1, 2)).apply()
    }

    private fun loadV2Company(context: Context, company: SalaryCompanyStore.Company, slot: Int): Profile {
        val prefs = SalaryCompanyStore.prefs(context, company.id)
        val type = parseContractType(prefs.getString("contract_type", "").orEmpty())
        val weeklyMinutes = prefs.getString("contract_weekly_hours", "").orEmpty().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * 60.0).toInt() }
        val rate = prefs.getString("hourly_rate", "").orEmpty().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        val hireEpochDay = runCatching {
            prefs.getString("entry_date", "").orEmpty().trim().takeIf { it.isNotBlank() }?.let {
                LocalDate.parse(it, DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)).toEpochDay()
            }
        }.getOrNull()
        val employer = EmployerV2(
            id = company.id,
            name = company.name.ifBlank { "Entreprise $slot" },
            siret = company.siret.takeIf { it.length == 14 },
            collectiveAgreementId = company.idcc.takeIf { it.isNotBlank() }
        )
        val missing = mutableListOf<String>()
        if (type == null) missing += "type de contrat"
        if (weeklyMinutes == null && type != ContractTypeV2.FORFAIT) missing += "durée hebdomadaire"
        if (rate == null) missing += "taux horaire"
        val contract = if (type != null && rate != null && (weeklyMinutes != null || type == ContractTypeV2.FORFAIT)) {
            ContractV2("contract_${company.id}", company.id, type, weeklyMinutes, rate, hireEpochDay)
        } else null
        return Profile(employer, contract, slot, missing)
    }

    /** Lecture legacy uniquement pour migration/compatibilité avant création du store V2. */
    private fun loadLegacyFallback(context: Context, companySlot: Int): Profile {
        val prefs = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        fun text(key: String): String = when (val value = prefs.all[key]) {
            null -> ""; is String -> value; is Number -> value.toString(); is Boolean -> value.toString(); else -> value.toString()
        }.trim()
        val prefix = if (companySlot == 2) "company2_" else "company_"
        val name = text(prefix + "name")
        val siret = text(prefix + "siret").filter(Char::isDigit)
        val idcc = text(prefix + "idcc").ifBlank { if (companySlot == 1) text("convention_idcc") else "" }
        val employerId = "company_$companySlot"
        val employer = if (name.isNotBlank() || siret.isNotBlank()) EmployerV2(employerId,name.ifBlank{"Entreprise $companySlot"},siret.takeIf{it.length==14},idcc.takeIf{it.isNotBlank()}) else null
        val contractTypeRaw = if (companySlot == 1) text("contract_type") else text("company2_contract_type")
        val weeklyRaw = if (companySlot == 1) text("contract_weekly_hours") else text("company2_contract_weekly_hours")
        val rateRaw = if (companySlot == 1) text("hourly_rate") else text("company2_hourly_rate")
        val type = parseContractType(contractTypeRaw)
        val weeklyMinutes = weeklyRaw.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * 60.0).toInt() }
        val rate = rateRaw.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        val hireDateKey = if (companySlot == 1) "employment_start_date" else "company2_employment_start_date"
        val hireEpochDay = safeLong(prefs.all[hireDateKey])?.takeIf { it > 0L }?.let(::localEpochDay)
        val missing = mutableListOf<String>()
        if (employer == null) missing += "employeur"
        if (type == null) missing += "type de contrat"
        if (weeklyMinutes == null && type != ContractTypeV2.FORFAIT) missing += "durée hebdomadaire"
        if (rate == null) missing += "taux horaire"
        val contract = if (employer != null && type != null && (weeklyMinutes != null || type == ContractTypeV2.FORFAIT) && rate != null) ContractV2("contract_$companySlot",employerId,type,weeklyMinutes,rate,hireEpochDay) else null
        return Profile(employer, contract, companySlot, missing)
    }

    private fun parseContractType(value: String): ContractTypeV2? = when (value.trim().uppercase(Locale.ROOT)) {
        "FULL_TIME" -> ContractTypeV2.FULL_TIME
        "PART_TIME" -> ContractTypeV2.PART_TIME
        "FORFAIT" -> ContractTypeV2.FORFAIT
        "OTHER" -> ContractTypeV2.OTHER
        else -> null
    }
    private fun safeLong(value: Any?): Long? = when (value) { is Number -> value.toLong(); is String -> value.toLongOrNull(); else -> null }
    private fun localEpochDay(ms: Long): Long {
        val calendar = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0) }
        val timezoneOffset = calendar.timeZone.getOffset(calendar.timeInMillis).toLong()
        return Math.floorDiv(calendar.timeInMillis + timezoneOffset, 86_400_000L)
    }
}
