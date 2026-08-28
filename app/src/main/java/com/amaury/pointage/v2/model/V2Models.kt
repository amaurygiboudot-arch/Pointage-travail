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

data class PauseV2(
    val startMs: Long,
    val endMs: Long?,
    val paid: Boolean?,
    val source: EventSourceV2,
    val status: DecisionStatusV2 = DecisionStatusV2.CONFIRMED
)

data class TravelV2(
    val startMs: Long,
    val endMs: Long?,
    val employerBeforeId: String?,
    val employerAfterId: String?,
    val distanceMeters: Double? = null,
    val classification: TravelClassificationV2 = TravelClassificationV2.TO_CONFIRM
)

data class EmployerV2(
    val id: String,
    val name: String,
    val siret: String? = null,
    val collectiveAgreementId: String? = null
)

data class ContractV2(
    val id: String,
    val employerId: String,
    val type: ContractTypeV2,
    val contractualWeeklyMinutes: Int?,
    val grossHourlyRate: Double?,
    val hireDateEpochDay: Long?
)

data class LegalRuleV2(
    val id: String,
    val domain: String,
    val sourceId: String,
    val effectiveFromEpochDay: Long,
    val effectiveToEpochDay: Long? = null,
    val status: DecisionStatusV2 = DecisionStatusV2.CONFIRMED
)

data class OfficialSourceV2(
    val id: String,
    val title: String,
    val url: String,
    val checkedAtMs: Long,
    val effectiveDateEpochDay: Long?
)

enum class SessionStatusV2 { OPEN, CLOSED, TO_CONFIRM }
enum class DecisionStatusV2 { CONFIRMED, TO_CONFIRM }
enum class EventSourceV2 { GPS, MANUAL, IMPORT, SYSTEM }
enum class TravelClassificationV2 { PAID, KM_COMPENSATED, OTHER_COMPENSATION, PERSONAL, TO_CONFIRM }
enum class ContractTypeV2 { FULL_TIME, PART_TIME, FORFAIT, OTHER }
