package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.RgduPayrollInputBridgeV2
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.engine.EmployerReductionAdjustmentV2
import com.amaury.pointage.v2.engine.EmployerReductionResolutionV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

object CompanyEmployerReductionStoreV2 {
    private const val KEY = "employer_reductions_v2"
    private const val STORAGE_WARNING =
        "Réductions/exonérations patronales : stockage local incohérent ; aucun ajustement automatique n'est appliqué."

    data class ReadResult(
        val records: List<EmployerReductionAdjustmentV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        val storedCompanies = SalaryCompanyStore.readConfirmed(context)
        val company = ConventionLegalProfileV2.confirmedCompany(storedCompanies, companyId)
            ?: return ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = companyStoreBlockers(storedCompanies, companyId)
            )
        return readConfirmedCompany(context, company.id)
    }

    fun list(context: Context, companyId: String): List<EmployerReductionAdjustmentV2.Record> =
        read(context, companyId).records

    fun save(context: Context, companyId: String, record: EmployerReductionAdjustmentV2.Record): Boolean {
        val storedCompanies = SalaryCompanyStore.readConfirmed(context)
        val company = ConventionLegalProfileV2.confirmedCompany(storedCompanies, companyId) ?: return false
        val stored = readConfirmedCompany(context, company.id)
        // Ne jamais « réparer » implicitement un stockage partiellement illisible en réécrivant
        // uniquement le sous-ensemble décodable : cela effacerait une incohérence à auditer.
        if (!stored.reliable) return false
        val items = stored.records.filterNot { it.month == record.month }.toMutableList()
        items += record
        return write(context, company.id, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        val storedCompanies = SalaryCompanyStore.readConfirmed(context)
        val company = ConventionLegalProfileV2.confirmedCompany(storedCompanies, companyId) ?: return false
        val stored = readConfirmedCompany(context, company.id)
        if (!stored.reliable) return false
        return write(context, company.id, stored.records.filterNot { it.id == id })
    }

    /**
     * Résout d'abord le total mensuel confirmé. En son absence, tente la RGDU 2026 automatique
     * depuis le véritable résultat Salaire V2. Toute donnée manquante reste bloquante : une liste
     * d'heures supplémentaires vide ne devient jamais 0 sans preuve mensuelle d'exhaustivité.
     */
    fun resolve(
        context: Context,
        companyId: String,
        month: YearMonth
    ): EmployerReductionAdjustmentV2.Snapshot {
        val storedCompanies = SalaryCompanyStore.readConfirmed(context)
        val company = ConventionLegalProfileV2.confirmedCompany(storedCompanies, companyId)
            ?: return blockedAutomatic(
                companyStoreBlockers(storedCompanies, companyId),
                "RGDU : entreprise V2 non confirmée ; calcul automatique et ajustement manuel bloqués."
            )
        val stored = readConfirmedCompany(context, company.id)
        if (!stored.reliable) {
            return EmployerReductionAdjustmentV2.Snapshot(
                amount = null,
                source = null,
                note = null,
                reliable = false,
                warnings = stored.warnings
            )
        }

        val manual = EmployerReductionAdjustmentV2.resolve(stored.records, month)
        val malformedManual = stored.records.any {
            !it.totalReductionAmount.isFinite() || it.totalReductionAmount < 0.0 || it.source.isBlank()
        }
        val activeManual = stored.records.filter { it.month == month }
        if (malformedManual || activeManual.size > 1) return manual
        if (activeManual.size == 1 && manual.reliable && manual.amount != null) return manual

        val prefs = SalaryCompanyStore.prefs(context, company.id)
        val idcc = company.idcc.ifBlank { prefs.getString("company_idcc", "").orEmpty() }
        val convention = idcc.takeIf { it.isNotBlank() }
            ?.let { ConventionCatalog.findByIdcc(context, it) }
            ?.takeIf { it.idcc.isNotBlank() }
            ?: return blockedAutomatic(manual.warnings, "RGDU : convention collective à confirmer avant le calcul automatique.")

        val salary = runCatching {
            V2SalaryAdapter.calculateForCompany(
                context = context,
                company = company,
                year = month.year,
                month = month.monthValue - 1,
                convention = convention
            )
        }.getOrElse {
            return blockedAutomatic(manual.warnings, "RGDU : résultat Salaire V2 indisponible pour ce mois.")
        }

        val benefits = CompanyBenefitInKindStoreV2.resolve(context, company.id, month)
        val workforce = CompanyWorkforceContributionStoreV2.resolve(context, company.id, month)
        val monthlyContext = CompanyEmployerGeneralReductionContextStoreV2.resolve(context, company.id, month)
        val contractType = parseContractType(prefs.getString("contract_type", ""))
        val contractualWeeklyMinutes = prefs.getString("contract_weekly_hours", "")
            .orEmpty()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * 60.0).roundToInt() }

        val payrollInput = RgduPayrollInputBridgeV2.resolve(
            salary = salary,
            contractType = contractType,
            benefitsInKindGross = benefits.totalGross.takeIf { benefits.reliable } ?: Double.NaN,
            paidHoursComplete = monthlyContext.paidHoursComplete
        )
        val resolution = EmployerGeneralReductionPayrollBridgeV2.resolve(
            context = context,
            companyId = company.id,
            period = month,
            reductionRemunerationMonthly = payrollInput.reductionRemunerationMonthly,
            workforceBand = workforce.band,
            contractType = contractType,
            contractualWeeklyMinutes = contractualWeeklyMinutes,
            additionalPaidMinutes = payrollInput.additionalPaidMinutes
        )

        if (resolution.reliable && resolution.totalReductionAmount != null) {
            return EmployerReductionAdjustmentV2.Snapshot(
                amount = resolution.totalReductionAmount,
                source = resolution.source,
                note = when (resolution.mode) {
                    EmployerReductionResolutionV2.Mode.MANUAL_CONFIRMED_TOTAL -> manual.note
                    EmployerReductionResolutionV2.Mode.AUTOMATIC_RGDU_ONLY -> "RGDU 2026 automatique"
                    EmployerReductionResolutionV2.Mode.BLOCKED -> null
                },
                reliable = true,
                warnings = emptyList()
            )
        }

        return EmployerReductionAdjustmentV2.Snapshot(
            amount = null,
            source = null,
            note = null,
            reliable = false,
            warnings = (
                manual.warnings +
                    payrollInput.warnings +
                    salary.warnings.takeIf { !payrollInput.reliable }.orEmpty() +
                    benefits.warnings.takeIf { !benefits.reliable }.orEmpty() +
                    workforce.warnings +
                    resolution.warnings
                ).distinct()
        )
    }

    internal fun companyStoreBlockers(
        stored: SalaryCompanyStore.ReadResult,
        companyId: String
    ): List<String> = when {
        !stored.reliable -> stored.warnings.distinct().ifEmpty {
            listOf("RGDU : stockage entreprises non fiable ; réductions patronales bloquées.")
        }
        companyId.isBlank() -> listOf("Réductions/exonérations patronales : entreprise non identifiée ; lecture bloquée.")
        stored.companies.none { it.id == companyId.trim() } -> listOf(
            "RGDU : entreprise ${companyId.trim()} absente du store confirmé ; aucune préférence locale orpheline n'est utilisée."
        )
        else -> emptyList()
    }

    internal fun decode(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerReductionAdjustmentV2.Record>()
        var malformed = false
        for (i in 0 until array.length()) {
            val record = fromJson(array.opt(i) as? JSONObject)
            if (record == null) malformed = true else records += record
        }
        ReadResult(
            records = records,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }.getOrElse {
        ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
    }

    private fun readConfirmedCompany(context: Context, companyId: String): ReadResult {
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]")
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decode(raw)
    }

    private fun write(context: Context, companyId: String, items: List<EmployerReductionAdjustmentV2.Record>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit().putString(KEY, array.toString()).commit()
    }

    private fun toJson(record: EmployerReductionAdjustmentV2.Record) = JSONObject()
        .put("id", record.id)
        .put("month", record.month.toString())
        .put("amount", record.totalReductionAmount)
        .put("source", record.source)
        .put("note", record.note)

    private fun fromJson(o: JSONObject?): EmployerReductionAdjustmentV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val monthRaw = o.opt("month") as? String ?: return null
        val month = runCatching { YearMonth.parse(monthRaw) }.getOrNull() ?: return null
        val amount = (o.opt("amount") as? Number)?.toDouble() ?: return null
        val source = o.opt("source") as? String ?: return null
        val note = when (val value = o.opt("note")) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> return null
        }
        return EmployerReductionAdjustmentV2.Record(id, month, amount, source, note)
    }

    private fun parseContractType(raw: String?): ContractTypeV2? = when (raw.orEmpty().trim().uppercase(Locale.ROOT)) {
        "FULL_TIME" -> ContractTypeV2.FULL_TIME
        "PART_TIME" -> ContractTypeV2.PART_TIME
        "FORFAIT_HEURES" -> ContractTypeV2.FORFAIT_HOURS
        "FORFAIT_JOURS" -> ContractTypeV2.FORFAIT_DAYS
        "FORFAIT" -> ContractTypeV2.FORFAIT
        "OTHER" -> ContractTypeV2.OTHER
        else -> null
    }

    private fun blockedAutomatic(
        manualWarnings: List<String>,
        warning: String
    ) = EmployerReductionAdjustmentV2.Snapshot(
        amount = null,
        source = null,
        note = null,
        reliable = false,
        warnings = (manualWarnings + warning).distinct()
    )
}
