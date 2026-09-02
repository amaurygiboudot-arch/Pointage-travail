package com.amaury.pointage.core.time

enum class WorkEventType {
    ENTRY,
    PAUSE_START,
    PAUSE_END,
    EXIT
}

enum class WorkEventSource {
    MANUAL,
    GPS,
    AUTOMATION,
    IMPORT,
    CORRECTION
}

data class WorkEvent(
    val id: String,
    val type: WorkEventType,
    val occurredAtMs: Long,
    val source: WorkEventSource,
    val placeId: String? = null
)

data class PresenceInterval(
    val placeId: String,
    val startMs: Long,
    val endMs: Long
) {
    init {
        require(endMs >= startMs) { "PresenceInterval endMs must be >= startMs" }
    }

    val durationMs: Long get() = endMs - startMs
}

data class WorkTimelineInput(
    val events: List<WorkEvent>,
    val presence: List<PresenceInterval> = emptyList()
)

data class WorkTimelineResult(
    val firstEntryMs: Long?,
    val lastExitMs: Long?,
    val workedMs: Long,
    val pausedMs: Long,
    val presenceMs: Long,
    val orderedEvents: List<WorkEvent>,
    val warnings: List<String>
)
