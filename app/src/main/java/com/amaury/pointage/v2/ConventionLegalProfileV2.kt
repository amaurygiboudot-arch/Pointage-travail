package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
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

        fun load(context: Context, companyId: String): ConventionLegalProfileV2? {
            val company = SalaryCompanyStore.list(context).firstOrNull { it.id == companyId } ?: return null
            val prefs = SalaryCompanyStore.prefs(context, companyId)
            fun text(key: String): String? = prefs.getString(key, "").orEmpty().trim().takeIf { it.isNotBlank() }
            fun number(key: String): Double? = text(key)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            fun date(key: String): LocalDate? = text(key)?.let { raw -> runCatching { LocalDate.parse(raw, DATE_FORMAT) }.getOrNull() }

            val rawIdcc = company.idcc.ifBlank { text("company_idcc").orEmpty() }
            val status = text("professional_status")?.uppercase(Locale.ROOT)?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            return ConventionLegalProfileV2(
                companyId = companyId,
                idcc = ConventionMinimumSalaryV2.normalizeIdcc(rawIdcc),
                siret = company.siret.filter(Char::isDigit).takeIf { it.length == 14 }.orEmpty(),
                professionalStatus = status,
                classification = ConventionClassificationStoreV2.load(context, companyId),
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
