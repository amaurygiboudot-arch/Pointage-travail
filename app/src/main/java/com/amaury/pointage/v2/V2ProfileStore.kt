package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.EmployerV2
import com.amaury.pointage.v2.model.ForfaitHoursPeriodV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

object V2ProfileStore {
    private const val LEGACY_PREFS = "salary_settings"
    private const val INTEGRATION_PREFS = "horatrack_v2_integration"
    private const val KEY_ACTIVE_COMPANY_SLOT = "active_company_slot"
    private const val KEY_ACTIVE_COMPANY_ID = "active_company_id"

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

    /** Charge directement une entreprise V2 par son identifiant stable, sans limite à deux sociétés. */
    fun loadCompany(context: Context, companyId: String): Profile? {
        bind(context)
        val companies = SalaryCompanyStore.list(context)
        val index = companies.indexOfFirst { it.id == companyId }
        if (index < 0) return null
        return loadV2Company(context, companies[index], index + 1)
    }

    /**
     * Identifiant stable de l'entreprise active. Tant que les anciens appels par slot existent,
     * la première lecture reprend le slot historique puis mémorise l'identifiant correspondant.
     */
    fun activeCompanyId(context: Context): String? {
        bind(context)
        val companies = SalaryCompanyStore.list(context)
        if (companies.isEmpty()) return null
        val prefs = integrationPrefs(context)
        val stored = prefs.getString(KEY_ACTIVE_COMPANY_ID, null)
        if (!stored.isNullOrBlank() && companies.any { it.id == stored }) return stored

        val legacySlot = safeLong(prefs.all[KEY_ACTIVE_COMPANY_SLOT])?.toInt()?.coerceIn(1, 2) ?: 1
        val resolved = companies.getOrNull(legacySlot - 1)?.id ?: companies.first().id
        prefs.edit().putString(KEY_ACTIVE_COMPANY_ID, resolved).apply()
        return resolved
    }

    /** Sélectionne une entreprise V2 par ID et garde le slot 1/2 synchronisé quand c'est possible. */
    fun setActiveCompanyId(context: Context, companyId: String): Boolean {
        bind(context)
        val companies = SalaryCompanyStore.list(context)
        val index = companies.indexOfFirst { it.id == companyId }
        if (index < 0) return false
        val editor = integrationPrefs(context).edit().putString(KEY_ACTIVE_COMPANY_ID, companyId)
        if (index in 0..1) editor.putInt(KEY_ACTIVE_COMPANY_SLOT, index + 1)
        editor.apply()
        return true
    }

    /** Profil de l'entreprise active par identifiant stable, avec repli historique uniquement si nécessaire. */
    fun loadActive(context: Context): Profile {
        val companyId = activeCompanyId(context)
        return companyId?.let { loadCompany(context, it) } ?: load(context, 1)
    }

    fun primaryEmployerId(context: Context): String? = load(context, 1).employer?.id

    /** Compatibilité temporaire avec les composants historiques encore basés sur deux slots. */
    fun activeCompanySlot(context: Context): Int {
        bind(context)
        val prefs = integrationPrefs(context)
        return safeLong(prefs.all[KEY_ACTIVE_COMPANY_SLOT])?.toInt()?.coerceIn(1, 2) ?: 1
    }

    /** Compatibilité temporaire : synchronise aussi l'identifiant V2 pour les deux premiers employeurs. */
    fun setActiveCompanySlot(context: Context, slot: Int) {
        bind(context)
        val normalized = slot.coerceIn(1, 2)
        val editor = integrationPrefs(context).edit().putInt(KEY_ACTIVE_COMPANY_SLOT, normalized)
        SalaryCompanyStore.list(context).getOrNull(normalized - 1)?.id?.let {
            editor.putString(KEY_ACTIVE_COMPANY_ID, it)
        }
        editor.apply()
    }

    private fun integrationPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(INTEGRATION_PREFS, Context.MODE_PRIVATE)

    private fun loadV2Company(context: Context, company: SalaryCompanyStore.Company, slot: Int): Profile {
        val prefs = SalaryCompanyStore.prefs(context, company.id)
        val type = parseContractType(prefs.getString("contract_type", "").orEmpty())
        val weeklyMinutes = prefs.getString("contract_weekly_hours", "").orEmpty().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * 60.0).toInt() }
        val rate = prefs.getString("hourly_rate", "").orEmpty().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        val forfaitHours = prefs.getString("forfait_annual_hours", "").orEmpty().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        val forfaitDays = prefs.getString("forfait_annual_days", "").orEmpty().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        val monthlyGross = prefs.getString("monthly_gross_salary", "").orEmpty().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
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
        when (type) {
            ContractTypeV2.FULL_TIME, ContractTypeV2.PART_TIME, ContractTypeV2.OTHER -> {
                if (weeklyMinutes == null) missing += "durée hebdomadaire"
                if (rate == null) missing += "taux horaire"
            }
            ContractTypeV2.FORFAIT_HOURS -> {
                if (forfaitHours == null) missing += "heures du forfait annuel"
                if (monthlyGross == null) missing += "salaire brut mensuel convenu"
            }
            ContractTypeV2.FORFAIT_DAYS -> {
                if (forfaitDays == null) missing += "jours du forfait annuel"
                if (monthlyGross == null) missing += "salaire brut mensuel convenu"
            }
            ContractTypeV2.FORFAIT -> missing += "type de forfait à préciser"
            null -> Unit
        }
        val contract = if (type != null && missing.isEmpty()) {
            ContractV2(
                id = "contract_${company.id}",
                employerId = company.id,
                type = type,
                contractualWeeklyMinutes = weeklyMinutes,
                grossHourlyRate = rate,
                hireDateEpochDay = hireEpochDay,
                forfaitHoursPeriod = if (type == ContractTypeV2.FORFAIT_HOURS) ForfaitHoursPeriodV2.YEAR else null,
                forfaitHours = if (type == ContractTypeV2.FORFAIT_HOURS) forfaitHours else null,
                forfaitAnnualDays = if (type == ContractTypeV2.FORFAIT_DAYS) forfaitDays else null,
                monthlyGrossSalary = if (type == ContractTypeV2.FORFAIT_HOURS || type == ContractTypeV2.FORFAIT_DAYS) monthlyGross else null
            )
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
        "FORFAIT_HEURES" -> ContractTypeV2.FORFAIT_HOURS
        "FORFAIT_JOURS" -> ContractTypeV2.FORFAIT_DAYS
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
