package com.amaury.pointage.v2.model

/** Modèles centraux du moteur HoraTrack V2. Aucun lien avec PointageStore. */
data class WorkSessionV2(
    val id: String,
    val employerId: String?,
    val realArrivalMs: Long?,
    val countedEntryMs: Long?,
    val countedExitMs: Long?,
    val realExitMs: Long?,
    val pauses: List<PauseV2> = emptyList(),
    val travels: List<TravelV2> = emptyList(),
    val status: SessionStatusV2 = SessionStatusV2.TO_CONFIRM,
    /** Lieu de travail confirmé/enregistré pour cette session. Aucune inférence sensible. */
    val placeId: String? = null,
    val placeLabel: String? = null,
    /** Déduction fixe importée de l'ancien moteur. Utilisée uniquement pour préserver les historiques. */
    val legacyFixedUnpaidPauseMs: Long = 0L
)

data class PauseV2(val startMs:Long,val endMs:Long?,val paid:Boolean?,val source:EventSourceV2,val status:DecisionStatusV2=DecisionStatusV2.CONFIRMED)
data class TravelV2(val startMs:Long,val endMs:Long?,val employerBeforeId:String?,val employerAfterId:String?,val distanceMeters:Double?=null,val classification:TravelClassificationV2=TravelClassificationV2.TO_CONFIRM)
data class EmployerV2(val id:String,val name:String,val siret:String?=null,val collectiveAgreementId:String?=null,val publicAgreementIds:List<String> = emptyList())
/**
 * Contrat canonique V2. FORFAIT est conservé uniquement pour relire les anciennes données.
 * Un nouveau forfait doit être FORFAIT_HOURS ou FORFAIT_DAYS.
 */
data class ContractV2(
    val id:String,
    val employerId:String,
    val type:ContractTypeV2,
    val contractualWeeklyMinutes:Int?,
    val grossHourlyRate:Double?,
    val hireDateEpochDay:Long?,
    val payrollCutoffDay:Int?=null,
    val forfaitHoursPeriod:ForfaitHoursPeriodV2?=null,
    val forfaitHours:Double?=null,
    val forfaitAnnualDays:Double?=null,
    val monthlyGrossSalary:Double?=null
)
data class LegalRuleV2(val id:String,val domain:String,val sourceId:String,val effectiveFromEpochDay:Long,val effectiveToEpochDay:Long?=null,val status:DecisionStatusV2=DecisionStatusV2.CONFIRMED,val parameters:Map<String,Double> = emptyMap(),val note:String?=null)
data class OfficialSourceV2(val id:String,val title:String,val url:String,val checkedAtMs:Long,val effectiveDateEpochDay:Long?,val sourceType:SourceTypeV2=SourceTypeV2.OFFICIAL)
data class PremiumV2(val id:String,val label:String,val amount:Double,val periodicity:PeriodicityV2,val taxable:Boolean=true)
data class BasketV2(val id:String,val label:String,val amount:Double,val night:Boolean=false)
data class DeductionV2(val id:String,val label:String,val amount:Double,val recurring:Boolean)
/**
 * Absence canonique V2. endMs est exclusif.
 * fullDay doit être vrai pour qu'une absence non rémunérée puisse réduire le plafond SS.
 * subrogation décrit uniquement le destinataire des IJSS ; elle ne vaut jamais règle de maintien.
 * providentTreatment concerne uniquement une prestation de prévoyance financée par l'employeur
 * qui CHEVAUCHE la période de maintien. Le relais conventionnel après maintien est traité à part.
 */
data class AbsenceV2(
    val id:String,
    val employerId:String?,
    val type:String,
    val startMs:Long,
    val endMs:Long,
    val salaryTreatment:AbsenceSalaryTreatmentV2=AbsenceSalaryTreatmentV2.TO_CONFIRM,
    val fullDay:Boolean=false,
    val status:DecisionStatusV2=DecisionStatusV2.CONFIRMED,
    val subrogation:AbsenceSubrogationV2=AbsenceSubrogationV2.TO_CONFIRM,
    val providentTreatment:AbsenceProvidentTreatmentV2=AbsenceProvidentTreatmentV2.TO_CONFIRM,
    val employerProvidentOverlapNetAmount:Double?=null
)
data class PayrollPeriodV2(val id:String,val employerId:String,val startMs:Long,val endMs:Long,val cutoffMs:Long?)
data class PayslipLineV2(val label:String,val quantity:Double?=null,val rate:Double?=null,val amount:Double?=null,val confidence:Double=1.0)
data class PayslipV2(val id:String,val employerId:String,val periodId:String,val lines:List<PayslipLineV2>,val gross:Double?,val net:Double?)
data class DiscrepancyV2(val id:String,val category:String,val expected:Double?,val observed:Double?,val status:DiscrepancyStatusV2=DiscrepancyStatusV2.TO_VERIFY,val explanation:String?=null)
data class CounterV2(val id:String,val label:String,val value:Double,val unit:String,val referenceStartMs:Long,val referenceEndMs:Long)
data class RuleTraceV2(val ruleId:String,val sourceId:String,val effectiveFromEpochDay:Long,val message:String)

enum class SessionStatusV2 { OPEN, CLOSED, TO_CONFIRM }
enum class DecisionStatusV2 { CONFIRMED, TO_CONFIRM }
enum class EventSourceV2 { GPS, MANUAL, IMPORT, SYSTEM }
enum class TravelClassificationV2 { PAID, KM_COMPENSATED, OTHER_COMPENSATION, PERSONAL, TO_CONFIRM }
enum class ContractTypeV2 { FULL_TIME, PART_TIME, FORFAIT_HOURS, FORFAIT_DAYS, FORFAIT, OTHER }
enum class ForfaitHoursPeriodV2 { WEEK, MONTH, YEAR }
enum class SourceTypeV2 { OFFICIAL, COLLECTIVE_AGREEMENT, COMPANY_AGREEMENT, MANUAL }
enum class PeriodicityV2 { ONE_OFF, DAILY, WEEKLY, MONTHLY, YEARLY }
enum class AbsenceSalaryTreatmentV2 { FULLY_MAINTAINED, PARTIALLY_MAINTAINED, UNPAID, TO_CONFIRM }
enum class AbsenceSubrogationV2 { YES, NO, TO_CONFIRM }
enum class AbsenceProvidentTreatmentV2 { TO_CONFIRM, NONE_CONFIRMED, NET_AMOUNT_CONFIRMED }
enum class DiscrepancyStatusV2 { TO_VERIFY, CONFIRMED, SET_ASIDE, EXPLAINED, RESOLVED }
