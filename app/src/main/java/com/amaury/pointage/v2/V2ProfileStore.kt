package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.EmployerV2
import java.util.Calendar
import java.util.Locale

/** Source unique V2 pour l'employeur et le contrat, basée sur les données Salaire existantes. */
object V2ProfileStore {
    private const val PREFS = "salary_settings"
    @Volatile private var boundContext: Context? = null

    data class Profile(
        val employer: EmployerV2?,
        val contract: ContractV2?,
        val companySlot: Int,
        val missing: List<String>
    )

    fun bind(context: Context) { boundContext = context.applicationContext }
    fun loadBound(companySlot: Int = 1): Profile? = boundContext?.let { load(it, companySlot) }

    fun load(context: Context, companySlot: Int = 1): Profile {
        bind(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = if (companySlot == 2) "company2_" else "company_"
        val name = prefs.getString(prefix + "name", "").orEmpty().trim()
        val siret = prefs.getString(prefix + "siret", "").orEmpty().filter(Char::isDigit)
        val idcc = prefs.getString(prefix + "idcc", "").orEmpty().trim()
            .ifBlank { if (companySlot == 1) prefs.getString("convention_idcc", "").orEmpty().trim() else "" }
        val employerExists = name.isNotBlank() || siret.isNotBlank()
        val employerId = "company_$companySlot"
        val employer = if (employerExists) EmployerV2(
            id = employerId,
            name = name.ifBlank { "Entreprise $companySlot" },
            siret = siret.takeIf { it.length == 14 },
            collectiveAgreementId = idcc.takeIf { it.isNotBlank() }
        ) else null

        val contractTypeRaw = if (companySlot == 1) prefs.getString("contract_type", "").orEmpty() else prefs.getString("company2_contract_type", "").orEmpty()
        val weeklyRaw = if (companySlot == 1) prefs.getString("contract_weekly_hours", "").orEmpty() else prefs.getString("company2_contract_weekly_hours", "").orEmpty()
        val rateRaw = if (companySlot == 1) prefs.getString("hourly_rate", "").orEmpty() else prefs.getString("company2_hourly_rate", "").orEmpty()
        val weeklyMinutes = weeklyRaw.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * 60.0).toInt() }
        val rate = rateRaw.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        val type = parseContractType(contractTypeRaw)
        val hireDateMs = if (companySlot == 1) safeLong(prefs.all["employment_start_date"]) else safeLong(prefs.all["company2_employment_start_date"])
        val hireEpochDay = hireDateMs?.takeIf { it > 0L }?.let(::localEpochDay)

        val missing = mutableListOf<String>()
        if (employer == null) missing += "employeur"
        if (type == null) missing += "type de contrat"
        if (weeklyMinutes == null && type != ContractTypeV2.FORFAIT) missing += "durée hebdomadaire"
        if (rate == null) missing += "taux horaire"

        val contract = if (employer != null && type != null && (weeklyMinutes != null || type == ContractTypeV2.FORFAIT) && rate != null) {
            ContractV2(
                id = "contract_$companySlot",
                employerId = employerId,
                type = type,
                contractualWeeklyMinutes = weeklyMinutes,
                grossHourlyRate = rate,
                hireDateEpochDay = hireEpochDay
            )
        } else null
        return Profile(employer, contract, companySlot, missing)
    }

    fun primaryEmployerId(context: Context): String? = load(context, 1).employer?.id

    fun activeCompanySlot(context: Context): Int {
        bind(context)
        return context.applicationContext.getSharedPreferences("horatrack_v2_integration", Context.MODE_PRIVATE)
            .getInt("active_company_slot", 1).coerceIn(1, 2)
    }

    fun setActiveCompanySlot(context: Context, slot: Int) {
        bind(context)
        context.applicationContext.getSharedPreferences("horatrack_v2_integration", Context.MODE_PRIVATE)
            .edit().putInt("active_company_slot", slot.coerceIn(1, 2)).apply()
    }

    private fun parseContractType(value: String): ContractTypeV2? = when (value.trim().uppercase(Locale.ROOT)) {
        "FULL_TIME" -> ContractTypeV2.FULL_TIME
        "PART_TIME" -> ContractTypeV2.PART_TIME
        "FORFAIT" -> ContractTypeV2.FORFAIT
        "OTHER" -> ContractTypeV2.OTHER
        else -> null
    }

    private fun safeLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    /** Calcul d'un epoch-day local sans java.time, compatible minSdk 23. */
    private fun localEpochDay(ms: Long): Long {
        val c = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tzOffset = c.timeZone.getOffset(c.timeInMillis).toLong()
        return Math.floorDiv(c.timeInMillis + tzOffset, 86_400_000L)
    }
}
