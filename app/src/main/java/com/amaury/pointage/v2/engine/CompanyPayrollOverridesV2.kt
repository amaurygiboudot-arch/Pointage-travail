package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.CompanyApprenticeshipTaxStoreV2
import com.amaury.pointage.v2.CompanyBenefitInKindStoreV2
import com.amaury.pointage.v2.CompanyEmployeeDeductionStoreV2
import com.amaury.pointage.v2.CompanyEmployerReductionStoreV2
import com.amaury.pointage.v2.CompanyHealthFamilyStoreV2
import com.amaury.pointage.v2.CompanyMobilityContributionStoreV2
import com.amaury.pointage.v2.CompanyUnemploymentAgsStoreV2
import com.amaury.pointage.v2.CompanyWorkforceContributionStoreV2
import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoProvidentContributionParserV2
import com.amaury.pointage.v2.PayrollLegalSourceKnowledgeStoreV2
import com.amaury.pointage.v2.V2CompanyProvidentContributionStore
import com.amaury.pointage.v2.V2ConventionMatterCoverageStore
import com.amaury.pointage.v2.V2ConventionProvidentContributionBridge
import com.amaury.pointage.v2.V2ConventionProvidentContributionStore
import com.amaury.pointage.v2.V2RightsStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.VerifiedProtectionCategoryProviderV2
import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** Couche 4/6 — paramètres salarié/entreprise. Les valeurs absentes restent explicitement inconnues. */
object CompanyPayrollOverridesV2 {
    data class Snapshot(
        val companyId:String,
        val idcc:String?,
        val referenceDate:LocalDate,
        val entryDate:LocalDate?,
        val seniorityMonths:Int?,
        val contractType:ContractTypeV2?,
        val contractualWeeklyMinutes:Int?,
        val forfaitAnnualDays:Double?,
        val unpaidAbsenceDays:Int,
        val hasUnpaidAbsence:Boolean,
        val mutualEmployeeAmount:Double?,
        val providentEmployeeAmount:Double?,
        val transportEmployeeAmount:Double?,
        /** Part employeur de protection sociale complémentaire réintégrée au net imposable. */
        val employerProtectionTaxableAmount:Double?,
        /** Part salariale de prévoyance explicitement non déductible fiscalement. */
        val employeeProvidentNonDeductibleAmount:Double?,
        val incomeTaxRate:Double?,
        val professionalStatus:String?,
        val protectionCategory:PlasturgieProtectionCategoryV2.Result,
        val warnings:List<String>,
        /** Affiliation explicitement confirmée au régime local Alsace-Moselle. null = à confirmer. */
        val alsaceMoselleLocalRegime:Boolean?=null,
        /** Taux AT/MP employeur confirmé, stocké sous forme décimale (ex. 2,08 % = 0,0208). */
        val atMpEmployerRate:Double?=null,
        /** Valeur brute des avantages en nature applicables à la période. */
        val benefitsInKindGross:Double=0.0,
        /** Taux de versement mobilité employeur applicable à la période ; 0 = non applicable confirmé. */
        val employerMobilityRate:Double?=null,
        /** Source humaine conservée avec la règle de versement mobilité. */
        val employerMobilitySource:String?=null,
        /** Taux chômage employeur confirmé pour la période. */
        val employerUnemploymentRate:Double?=null,
        /** Taux AGS employeur confirmé pour la période. */
        val employerAgsRate:Double?=null,
        /** Source humaine des taux chômage/AGS. */
        val employerUnemploymentAgsSource:String?=null,
        /** Avertissements patronaux chômage/AGS, séparés de la fiabilité du net salarié. */
        val employerUnemploymentAgsWarnings:List<String> = emptyList(),
        /** Tranche d'effectif social confirmée pour FNAL/formation. */
        val employerWorkforceBand:EmployerWorkforceContributionsV2.Band?=null,
        val employerWorkforceSource:String?=null,
        /** Avertissements patronaux d'effectif, séparés du net salarié. */
        val employerWorkforceWarnings:List<String> = emptyList(),
        /** Taux maladie employeur confirmé pour la période. */
        val employerHealthRate:Double?=null,
        /** Taux allocations familiales employeur confirmé pour la période. */
        val employerFamilyRate:Double?=null,
        val employerHealthFamilySource:String?=null,
        /** Avertissements patronaux maladie/AF, séparés du net salarié. */
        val employerHealthFamilyWarnings:List<String> = emptyList(),
        /** Taux de part principale de taxe d'apprentissage confirmé pour la période. */
        val employerApprenticeshipPrincipalRate:Double?=null,
        /** Taux de provision mensuelle du solde de taxe d'apprentissage. */
        val employerApprenticeshipBalanceRate:Double?=null,
        val employerApprenticeshipSource:String?=null,
        /** Avertissements patronaux taxe d'apprentissage, séparés du net salarié. */
        val employerApprenticeshipWarnings:List<String> = emptyList(),
        /** Montant total mensuel confirmé des réductions/exonérations patronales. */
        val employerReductionAmount:Double?=null,
        val employerReductionSource:String?=null,
        val employerReductionNote:String?=null,
        /** Avertissements propres aux réductions/exonérations, hors net salarié. */
        val employerReductionWarnings:List<String> = emptyList(),
        /** Catégorie ANI générique issue uniquement des preuves locales KALI + APEC. */
        val verifiedProtectionCategory:ProtectionCategoryV2.Result = ProtectionCategoryV2.Result(
            aniCategory = ProtectionCategoryV2.AniCategory.TO_CONFIRM,
            confirmed = false,
            warnings = listOf("Catégorie ANI vérifiée : à confirmer")
        ),
        /** Classification exacte utilisée par le moteur générique de prévoyance. */
        val verifiedProvidentClassification:ConventionClassificationV2 = ConventionClassificationV2(),
        /** Ancienneté conventionnelle locale, distincte de l'ancienneté contrat lorsque renseignée. */
        val verifiedProvidentSeniorityMonths:Int? = null,
        /** Règles de cotisation issues uniquement du store local KALI vérifié. */
        val verifiedProvidentRules:List<ConventionProvidentContributionV2.Rule> = emptyList(),
        /** Couverture juridique de la matière cotisations de prévoyance pour ce profil et cette période. */
        val verifiedProvidentCoverage:ConventionMatterCoverageV2.Snapshot = ConventionMatterCoverageV2.Snapshot(
            state = ConventionMatterCoverageV2.State.INCOMPLETE,
            record = null,
            reliable = false,
            warnings = listOf("Prévoyance conventionnelle vérifiée : audit KALI à confirmer")
        ),
        /** Profil juridique local exact utilisé pour arbitrer KALI contre ACCO. */
        val verifiedProvidentLegalProfile:ConventionLegalProfileV2? = null,
        /** Cotisations d'entreprise structurées depuis ACCO et liées au SIRET local exact. */
        val verifiedCompanyProvidentRules:List<OfficialAccoProvidentContributionParserV2.Rule> = emptyList(),
        /** Preuves explicites de contrôle/absence des sources, jamais déduites d'un store vide. */
        val verifiedProvidentSourceKnowledge:Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap(),
        /** Équivalence L2253-1 des garanties. null tant qu'une preuve distincte sur les prestations ne l'a pas démontrée. */
        val verifiedCompanyProvidentGuaranteesEquivalent:Boolean? = null
    )

    fun load(
        context:Context,
        companyId:String,
        referenceDate:LocalDate=selectedPayrollReferenceDate(context),
        ignoreAbsencesForTheoreticalBase:Boolean=false
    ):Snapshot {
        val p=SalaryCompanyStore.prefs(context,companyId)
        fun number(key:String)=p.getString(key,"").orEmpty().replace(',','.').toDoubleOrNull()?.takeIf{it>=0.0}
        fun normalizeIdcc(raw:String?)=raw.orEmpty().filter(Char::isDigit).trimStart('0').ifBlank{null}
        val company=SalaryCompanyStore.list(context).firstOrNull{it.id==companyId}
        val idcc=normalizeIdcc(company?.idcc) ?: normalizeIdcc(p.getString("company_idcc",""))
        val entryDate=runCatching {
            p.getString("entry_date","").orEmpty().trim().takeIf{it.isNotBlank()}?.let {
                LocalDate.parse(it,DateTimeFormatter.ofPattern("dd/MM/yyyy",Locale.FRANCE))
            }
        }.getOrNull()
        val seniorityMonths=entryDate?.let { start ->
            if(start.isAfter(referenceDate)) 0 else ChronoUnit.MONTHS.between(start,referenceDate).toInt().coerceAtLeast(0)
        }
        val contractType=when(p.getString("contract_type","").orEmpty().trim().uppercase(Locale.ROOT)) {
            "FULL_TIME" -> ContractTypeV2.FULL_TIME
            "PART_TIME" -> ContractTypeV2.PART_TIME
            "FORFAIT_HEURES" -> ContractTypeV2.FORFAIT_HOURS
            "FORFAIT_JOURS" -> ContractTypeV2.FORFAIT_DAYS
            "FORFAIT" -> ContractTypeV2.FORFAIT
            "OTHER" -> ContractTypeV2.OTHER
            else -> null
        }
        val contractualWeeklyMinutes=number("contract_weekly_hours")
            ?.takeIf { it > 0.0 }
            ?.let { (it * 60.0).roundToInt() }
        val forfaitAnnualDays=number("forfait_annual_days")?.takeIf { it > 0.0 }
        val legacyMutual=number("mutual_employee_amount")
        val legacyProvident=number("provident_employee_amount")
        val legacyTransport=number("transport_employee_amount")
        val legacyEmployerProtectionTaxable=number("employer_protection_taxable_amount")
        val legacyEmployeeProvidentNonDeductible=number("employee_provident_nondeductible_amount")
        val tax=number("income_tax_rate_percent")?.div(100.0)
        val atMpEmployerRate=number("atmp_employer_rate_percent")?.takeIf{it<=100.0}?.div(100.0)
        val professionalStatus=p.getString("professional_status","").orEmpty().trim().uppercase().takeIf{it=="CADRE"||it=="NON_CADRE"}
        val conventionCoefficient=p.getString("convention_coefficient","").orEmpty().trim().toIntOrNull()
        val protectionCategory=PlasturgieProtectionCategoryV2.classify(idcc,referenceDate,conventionCoefficient)
        val verifiedProtectionCategory=VerifiedProtectionCategoryProviderV2.resolve(context,companyId,referenceDate)
        val legalProfile=ConventionLegalProfileV2.load(context,companyId)
        val verifiedProvidentClassification=legalProfile?.classification ?: ConventionClassificationV2()
        val verifiedProvidentSeniorityMonths=legalProfile?.let {
            V2ConventionProvidentContributionBridge.seniorityMonths(it,referenceDate)
        }
        val verifiedProvidentRules=idcc?.let { V2ConventionProvidentContributionStore.rules(context,it) }.orEmpty()
        val verifiedProvidentCoverage=if(
            idcc!=null && !verifiedProvidentClassification.isEmpty() && professionalStatus!=null
        ) {
            V2ConventionMatterCoverageStore.resolve(
                context=context,
                idcc=idcc,
                matter=ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
                date=referenceDate,
                classification=verifiedProvidentClassification,
                professionalStatus=professionalStatus
            )
        } else {
            ConventionMatterCoverageV2.Snapshot(
                state=ConventionMatterCoverageV2.State.INCOMPLETE,
                record=null,
                reliable=false,
                warnings=listOf("Prévoyance conventionnelle vérifiée : profil juridique incomplet")
            )
        }
        val verifiedCompanyProvidentRules=V2CompanyProvidentContributionStore.rules(context,companyId)
        val verifiedProvidentSourceKnowledge=if(idcc!=null){
            PayrollLegalSourceKnowledgeStoreV2.knowledgeForProvidentContribution(
                context=context,
                companyId=companyId,
                idcc=idcc,
                referenceDate=referenceDate
            )
        }else emptyMap()
        val verifiedCompanyProvidentGuaranteeEquivalence=if(verifiedCompanyProvidentRules.isNotEmpty()){
            CompanyProvidentGuaranteeEquivalenceV2.resolve(
                context=context,
                companyId=companyId,
                referenceDate=referenceDate,
                contributionAgreementIds=verifiedCompanyProvidentRules.map { it.agreementId }.toSet()
            )
        }else null
        val alsaceMoselleLocalRegime=when(p.getString("alsace_moselle_local_regime","").orEmpty().trim().uppercase(Locale.ROOT)) {
            "YES" -> true
            "NO" -> false
            else -> null
        }
        val payrollMonth=YearMonth.from(referenceDate)
        val employeeDeductions=CompanyEmployeeDeductionResolverV2.withLegacyFallback(
            CompanyEmployeeDeductionStoreV2.resolve(context,companyId,payrollMonth),
            mapOf(
                CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE to legacyMutual,
                CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE to legacyProvident,
                CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE to legacyTransport,
                CompanyEmployeeDeductionResolverV2.Kind.EMPLOYER_PROTECTION_TAXABLE to legacyEmployerProtectionTaxable,
                CompanyEmployeeDeductionResolverV2.Kind.EMPLOYEE_PROVIDENT_NON_DEDUCTIBLE to legacyEmployeeProvidentNonDeductible
            )
        )
        val mutual=employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE].amount
        val provident=employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE].amount
        val transport=employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE].amount
        val employerProtectionTaxable=employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.EMPLOYER_PROTECTION_TAXABLE].amount
        val employeeProvidentNonDeductible=employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.EMPLOYEE_PROVIDENT_NON_DEDUCTIBLE].amount
        val benefitsInKind=CompanyBenefitInKindStoreV2.resolve(context,companyId,payrollMonth)
        val mobility=CompanyMobilityContributionStoreV2.resolve(context,companyId,payrollMonth)
        val unemploymentAgs=CompanyUnemploymentAgsStoreV2.resolve(context,companyId,payrollMonth)
        val workforce=CompanyWorkforceContributionStoreV2.resolve(context,companyId,payrollMonth)
        val healthFamily=CompanyHealthFamilyStoreV2.resolve(context,companyId,payrollMonth)
        val apprenticeship=CompanyApprenticeshipTaxStoreV2.resolve(context,companyId,payrollMonth)
        val reduction=CompanyEmployerReductionStoreV2.resolve(context,companyId,payrollMonth)
        val acceptedEmployerIds=SalaryCompanyStore.acceptedEmployerIds(context,companyId)
        val observedAbsenceImpact=AbsencePayrollImpactV2.forMonth(
            absences=V2RightsStore.absences(context),
            referenceDate=referenceDate,
            acceptedEmployerIds=acceptedEmployerIds,
            workSessions=V2RuntimeStore.allSessions(context)
        )
        val absenceImpact=if(ignoreAbsencesForTheoreticalBase){
            AbsencePayrollImpactV2.Snapshot(
                unpaidFullCalendarDays=0,
                hasUnpaidAbsence=false,
                hasCompensatedAbsence=false,
                requiresPayrollReview=false,
                warnings=emptyList()
            )
        }else observedAbsenceImpact
        val warnings=buildList {
            if(entryDate==null)add("Date d’entrée : à confirmer pour les règles liées à l’ancienneté et au plafond social")
            if(legalProfile!=null && verifiedProvidentSeniorityMonths==null)add("Ancienneté conventionnelle vérifiée : à confirmer")
            addAll(absenceImpact.warnings)
            addAll(employeeDeductions.warnings)
            if(mutual==null && employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE].warnings.isEmpty())add("Mutuelle salariale : à confirmer")
            if(provident==null && employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE].warnings.isEmpty())add("Prévoyance salariale entreprise : à confirmer")
            if(transport==null && employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE].warnings.isEmpty())add("Retenue transport : à confirmer")
            if(employerProtectionTaxable==null && employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.EMPLOYER_PROTECTION_TAXABLE].warnings.isEmpty())add("Part employeur mutuelle/prévoyance réintégrable au net imposable : à confirmer")
            if(employeeProvidentNonDeductible==null && employeeDeductions[CompanyEmployeeDeductionResolverV2.Kind.EMPLOYEE_PROVIDENT_NON_DEDUCTIBLE].warnings.isEmpty())add("Part salariale de prévoyance non déductible : à confirmer, même si elle est nulle")
            if(tax==null)add("Taux de prélèvement à la source : à confirmer")
            if(professionalStatus==null)add("Statut professionnel cadre/non-cadre : à préciser")
            if(alsaceMoselleLocalRegime==null)add("Régime local Alsace-Moselle : affiliation à confirmer (oui/non)")
            if(atMpEmployerRate==null)add("AT/MP employeur : taux de l'établissement non renseigné ; coût employeur incomplet")
            addAll(benefitsInKind.warnings)
            addAll(mobility.warnings)
            addAll(protectionCategory.warnings)
            addAll(verifiedProtectionCategory.warnings)
            verifiedCompanyProvidentGuaranteeEquivalence?.let { addAll(it.warnings) }
            if(ignoreAbsencesForTheoreticalBase && observedAbsenceImpact.requiresPayrollReview){
                add("Base théorique maladie : les absences du mois sont neutralisées uniquement pour reconstruire la rémunération qui aurait été perçue en travaillant normalement.")
            }
        }
        return Snapshot(
            companyId=companyId,
            idcc=idcc,
            referenceDate=referenceDate,
            entryDate=entryDate,
            seniorityMonths=seniorityMonths,
            contractType=contractType,
            contractualWeeklyMinutes=contractualWeeklyMinutes,
            forfaitAnnualDays=forfaitAnnualDays,
            unpaidAbsenceDays=absenceImpact.unpaidFullCalendarDays,
            hasUnpaidAbsence=absenceImpact.hasUnpaidAbsence,
            mutualEmployeeAmount=mutual,
            providentEmployeeAmount=provident,
            transportEmployeeAmount=transport,
            employerProtectionTaxableAmount=employerProtectionTaxable,
            employeeProvidentNonDeductibleAmount=employeeProvidentNonDeductible,
            incomeTaxRate=tax,
            professionalStatus=professionalStatus,
            protectionCategory=protectionCategory,
            warnings=warnings,
            alsaceMoselleLocalRegime=alsaceMoselleLocalRegime,
            atMpEmployerRate=atMpEmployerRate,
            benefitsInKindGross=benefitsInKind.totalGross,
            employerMobilityRate=mobility.rate,
            employerMobilitySource=mobility.source,
            employerUnemploymentRate=unemploymentAgs.unemploymentRate,
            employerAgsRate=unemploymentAgs.agsRate,
            employerUnemploymentAgsSource=unemploymentAgs.source,
            employerUnemploymentAgsWarnings=unemploymentAgs.warnings,
            employerWorkforceBand=workforce.band,
            employerWorkforceSource=workforce.source,
            employerWorkforceWarnings=workforce.warnings,
            employerHealthRate=healthFamily.healthRate,
            employerFamilyRate=healthFamily.familyRate,
            employerHealthFamilySource=healthFamily.source,
            employerHealthFamilyWarnings=healthFamily.warnings,
            employerApprenticeshipPrincipalRate=apprenticeship.principalRate,
            employerApprenticeshipBalanceRate=apprenticeship.balanceRate,
            employerApprenticeshipSource=apprenticeship.source,
            employerApprenticeshipWarnings=apprenticeship.warnings,
            employerReductionAmount=reduction.amount,
            employerReductionSource=reduction.source,
            employerReductionNote=reduction.note,
            employerReductionWarnings=reduction.warnings,
            verifiedProtectionCategory=verifiedProtectionCategory.category,
            verifiedProvidentClassification=verifiedProvidentClassification,
            verifiedProvidentSeniorityMonths=verifiedProvidentSeniorityMonths,
            verifiedProvidentRules=verifiedProvidentRules,
            verifiedProvidentCoverage=verifiedProvidentCoverage,
            verifiedProvidentLegalProfile=legalProfile,
            verifiedCompanyProvidentRules=verifiedCompanyProvidentRules,
            verifiedProvidentSourceKnowledge=verifiedProvidentSourceKnowledge,
            verifiedCompanyProvidentGuaranteesEquivalent=verifiedCompanyProvidentGuaranteeEquivalence?.equivalent
        )
    }

    /** Utilise la fin du mois actuellement sélectionné dans l'espace bulletin. */
    private fun selectedPayrollReferenceDate(context:Context):LocalDate {
        val ms=context.getSharedPreferences("navigation_state",Context.MODE_PRIVATE).getLong("report_month_ms",-1L)
        val selected=if(ms>0L) Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
        return selected.withDayOfMonth(selected.lengthOfMonth())
    }
}
