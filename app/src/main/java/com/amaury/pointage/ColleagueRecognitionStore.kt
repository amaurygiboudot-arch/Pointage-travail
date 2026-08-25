package com.amaury.pointage

import android.content.Context
import java.security.MessageDigest

/** Local bootstrap for the recognition feature.
 * Workplace is auto-detected by default; manual selection remains available
 * for new users while automatic detection is still learning their workplace.
 */
object ColleagueRecognitionStore {
    private const val PREFS = "colleague_recognition"
    private const val WORKPLACE_ID = "workplace_id"
    private const val WORKPLACE_NAME = "workplace_name"
    private const val WORKPLACE_SOURCE = "workplace_source"
    private const val WORKPLACE_CONFIRMED = "workplace_confirmed"

    fun currentWorkplace(context: Context): ColleagueRecognitionModel.WorkplaceIdentity? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString(WORKPLACE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = p.getString(WORKPLACE_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val source = runCatching {
            ColleagueRecognitionModel.WorkplaceIdentity.Source.valueOf(
                p.getString(WORKPLACE_SOURCE, null).orEmpty()
            )
        }.getOrDefault(ColleagueRecognitionModel.WorkplaceIdentity.Source.MANUAL)
        return ColleagueRecognitionModel.WorkplaceIdentity(
            id = id,
            displayName = name,
            source = source,
            confirmed = p.getBoolean(WORKPLACE_CONFIRMED, false)
        )
    }

    fun confirmAutoDetectedWorkplace(context: Context, stablePlaceId: String, displayName: String) {
        saveWorkplace(
            context,
            ColleagueRecognitionModel.WorkplaceIdentity(
                id = stablePlaceId,
                displayName = displayName.trim(),
                source = ColleagueRecognitionModel.WorkplaceIdentity.Source.AUTO_DETECTED,
                confirmed = true
            )
        )
    }

    fun selectManualWorkplace(context: Context, stablePlaceId: String, displayName: String) {
        saveWorkplace(
            context,
            ColleagueRecognitionModel.WorkplaceIdentity(
                id = stablePlaceId,
                displayName = displayName.trim(),
                source = ColleagueRecognitionModel.WorkplaceIdentity.Source.MANUAL,
                confirmed = true
            )
        )
    }

    private fun saveWorkplace(context: Context, workplace: ColleagueRecognitionModel.WorkplaceIdentity) {
        require(workplace.id.isNotBlank() && workplace.displayName.isNotBlank())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(WORKPLACE_ID, workplace.id)
            .putString(WORKPLACE_NAME, workplace.displayName)
            .putString(WORKPLACE_SOURCE, workplace.source.name)
            .putBoolean(WORKPLACE_CONFIRMED, workplace.confirmed)
            .apply()
    }

    /**
     * Produces a pseudonymous key suitable for duplicate-vote protection.
     * Never expose the raw account UID in a public evaluation document.
     */
    fun reviewerKey(accountUid: String, workplaceId: String, colleagueId: String): String {
        val raw = "$accountUid|$workplaceId|$colleagueId|horatrack-recognition-v1"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
