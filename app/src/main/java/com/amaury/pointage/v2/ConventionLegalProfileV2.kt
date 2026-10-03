package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.ForfaitHoursPeriodV2
import java.time.YearMonth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Photo locale des seuls critères utiles pour sélectionner une règle juridique.
 *
 * Ce profil n'est pas envoyé dans le cache juridique partagé Firestore : Firebase
 * conserve les sources officielles par IDCC/SIRET et le téléphone applique ensuite
 * les critères exacts de la fiche de renseignements.
 */
data class ConventionLegalProfileV2(
    val companyId: String,
    val idcc: String,
    val siret: String,
    val professionalStatus: String?,
    val classification: ConventionClassificationV2,
    val contractType: String?,
    val entryDate: LocalDate?,
    val conventionSeniorityDate: LocalDate?,
    val weeklyHours: Double?,
    val forfaitAnnualHours: Double?,
    val forfaitAnnualDays: Double?
) {
    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.FRANCE)

        /**
         * Ne sélectionne jamais une entreprise issue d'un paquet local non fiable.
         * Une lecture réparée depuis la dernière copie saine reste fiable et peut donc être utilisée.
         */
        internal fun confirmedCompany(
            stored: SalaryCompanyStore.ReadResult,
            companyId: String
        ): SalaryCompanyStore.Company? {
            val id = companyId.trim()
            if (!stored.reliable || id.isBlank()) return null
            return stored.companies.firstOrNull { it.id == id }
        }

        /** Profil pour un calcul daté : aucun champ du contrat courant ne remplace l'historique. */
        fun load(context: Context, companyId: String, referenceDate: LocalDate): ConventionLegalProfileV2? {
            val profile = load(context, companyId) ?: return null
            val resolution = V2EmploymentContractPayrollBridge.resolve(
                context, profile.companyId, referenceDate.year, referenceDate.monthValue - 1
            ).resolution
            val status = CompanyProfessionalStatusStoreV2.resolve(context, profile.companyId, YearMonth.from(referenceDate))
            return withDatedProfessionalStatus(withDatedResolution(profile, resolution), status)
        }

        internal fun withDatedProfessionalStatus(
            profile: ConventionLegalProfileV2,
            snapshot: com.amaury.pointage.v2.engine.CompanyProfessionalStatusResolverV2.Snapshot
        ): ConventionLegalProfileV2 = profile.copy(professionalStatus = snapshot.status.takeIf { snapshot.reliable })

        internal fun withDatedResolution(
            profile: ConventionLegalProfileV2,
            resolution: EmploymentContractPeriodResolutionV2
        ): ConventionLegalProfileV2 = withDatedContract(
            profile, resolution.contract.takeIf { resolution.readyForSingleContractCalculation }
        )

        /** La date d'ancienneté conventionnelle explicitement saisie reste distincte de l'embauche. */
        internal fun withDatedContract(
            profile: ConventionLegalProfileV2,
            contract: ContractV2?
        ): ConventionLegalProfileV2 = profile.copy(
            contractType = contract?.type?.name,
            entryDate = contract?.hireDateEpochDay?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() },
            weeklyHours = contract?.contractualWeeklyMinutes?.toDouble()?.div(60.0),
            forfaitAnnualHours = contract?.forfaitHours?.takeIf { contract?.forfaitHoursPeriod == ForfaitHoursPeriodV2.YEAR },
            forfaitAnnualDays = contract?.forfaitAnnualDays
        )

        fun load(context: Context, companyId: String): ConventionLegalProfileV2? {
            val id = companyId.trim()
            if (id.isBlank()) return null
            return SalaryCompanyStore.withConfirmedCompany(context, id) { company ->
                val prefs = SalaryCompanyStore.prefs(context, company.id)
                fun text(key: String): String? = prefs.getString(key, "").orEmpty().trim().takeIf { it.isNotBlank() }
                fun number(key: String): Double? = text(key)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                fun date(key: String): LocalDate? = text(key)?.let { raw -> runCatching { LocalDate.parse(raw, DATE_FORMAT) }.getOrNull() }

                val rawIdcc = company.idcc
                val status = text("professional_status")?.uppercase(Locale.ROOT)?.takeIf { it == "CADRE" || it == "NON_CADRE" }
                ConventionLegalProfileV2(
                    companyId = company.id,
                    idcc = ConventionMinimumSalaryV2.normalizeIdcc(rawIdcc),
                    siret = company.siret.filter(Char::isDigit).takeIf { it.length == 14 }.orEmpty(),
                    professionalStatus = status,
                    classification = ConventionClassificationStoreV2.load(context, company.id),
                    contractType = text("contract_type")?.uppercase(Locale.ROOT),
                    entryDate = date("entry_date"),
                    conventionSeniorityDate = date("convention_seniority_date"),
                    weeklyHours = number("contract_weekly_hours"),
                    forfaitAnnualHours = number("forfait_annual_hours"),
                    forfaitAnnualDays = number("forfait_annual_days")
                )
            }
        }
    }
}
