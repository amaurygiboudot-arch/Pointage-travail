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
    val status: SessionStatusV2 = SessionStatusV2.TO_CONFIRM
)

data class PauseV2(val startMs:Long,val endMs:Long?,val paid:Boolean?,val source:EventSourceV2,val status:DecisionStatusV2=DecisionStatusV2.CONFIRMED)
data class TravelV2(val startMs:Long,val endMs:Long?,val employerBeforeId:String?,val employerAfterId:String?,val distanceMeters:Double?=null,val classification:TravelClassificationV2=TravelClassificationV2.TO_CONFIRM)
data class EmployerV2(val id:String,val name:String,val siret:String?=null,val collectiveAgreementId:String?=null,val publicAgreementIds:List<String> = emptyList())
data class ContractV2(val id:String,val employerId:String,val type:ContractTypeV2,val contractualWeeklyMinutes:Int?,val grossHourlyRate:Double?,val hireDateEpochDay:Long?,val payrollCutoffDay:Int?=null)
data class LegalRuleV2(val id:String,val domain:String,val sourceId:String,val effectiveFromEpochDay:Long,val effectiveToEpochDay:Long?=null,val status:DecisionStatusV2=DecisionStatusV2.CONFIRMED,val parameters:Map<String,Double> = emptyMap(),val note:String?=null)
data class OfficialSourceV2(val id:String,val title:String,val url:String,val checkedAtMs:Long,val effectiveDateEpochDay:Long?,val sourceType:SourceTypeV2=SourceTypeV2.OFFICIAL)
data class PremiumV2(val id:String,val label:String,val amount:Double,val periodicity:PeriodicityV2,val taxable:Boolean=true)
data class BasketV2(val id:String,val label:String,val amount:Double,val night:Boolean=false)
data class DeductionV2(val id:String,val label:String,val amount:Double,val recurring:Boolean)
data class AbsenceV2(val id:String,val employerId:String?,val type:String,val startMs:Long,val endMs:Long,val status:DecisionStatusV2=DecisionStatusV2.CONFIRMED)
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
enum class ContractTypeV2 { FULL_TIME, PART_TIME, FORFAIT, OTHER }
enum class SourceTypeV2 { OFFICIAL, COLLECTIVE_AGREEMENT, COMPANY_AGREEMENT, MANUAL }
enum class PeriodicityV2 { ONE_OFF, DAILY, WEEKLY, MONTHLY, YEARLY }
enum class DiscrepancyStatusV2 { TO_VERIFY, CONFIRMED, SET_ASIDE, EXPLAINED, RESOLVED }
