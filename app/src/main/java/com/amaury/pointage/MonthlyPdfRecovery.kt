package com.amaury.pointage

/** Restored state is only a recovery hint. None of these actions grants PDF rights. */
internal object MonthlyPdfRecovery {
    enum class Phase { CHOOSE, PREPARING, AUTHORIZING, WAIT_PICKER, COPYING }
    enum class Action { CHOOSE, PREPARE, REVERIFY, WAIT_PICKER, COPY, REJECT }

    fun action(phase: Phase, ownerUid: String?, currentUid: String?, ownedPath: Boolean, hash: String?): Action {
        if (ownerUid.isNullOrBlank() || ownerUid != currentUid) return Action.REJECT
        if (phase == Phase.CHOOSE) return Action.CHOOSE
        if (phase == Phase.PREPARING) return Action.PREPARE
        if (!ownedPath || hash == null || !Regex("[a-f0-9]{64}").matches(hash)) return Action.REJECT
        return when (phase) {
            Phase.AUTHORIZING -> Action.REVERIFY
            Phase.WAIT_PICKER -> Action.WAIT_PICKER
            Phase.COPYING -> Action.COPY
            else -> Action.REJECT
        }
    }
}
