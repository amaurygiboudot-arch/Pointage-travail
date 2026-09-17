package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Ajoute une session manuelle fermée directement dans l'historique V2. */
object V2ManualSessionWriter {
    /** Écriture V2 explicite d'une plage sans employeur associé. */
    fun addWithoutCompany(
        context: Context,
        realStartMs: Long,
        realEndMs: Long,
        place: String? = null
    ): Boolean {
        return addInternal(context, realStartMs, realEndMs, null, null, place)
    }

    /** Écriture V2 canonique : l'entreprise est identifiée par son ID stable et non par sa position. */
    fun addForCompany(
        context: Context,
        realStartMs: Long,
        realEndMs: Long,
        companyId: String,
        place: String? = null
    ): Boolean {
        val profile = V2ProfileStore.loadCompany(context, companyId) ?: return false
        val employerId = profile.employer?.id ?: return false
        V2ProfileStore.setActiveCompanyId(context, companyId)
        val legacySlot = profile.companySlot.takeIf { it in 1..2 }
        return addInternal(context, realStartMs, realEndMs, employerId, legacySlot, place)
    }

    private fun addInternal(
        context: Context,
        realStartMs: Long,
        realEndMs: Long,
        employerId: String?,
        legacySlot: Int?,
        place: String?
    ): Boolean {
        if (!HoraTrackV2.ENABLED || realStartMs <= 0L || realEndMs <= realStartMs) return false
        V2RuntimeStore.bind(context)
        val migration = V2MigrationManager.ensureMigrated(context)
        if (!migration.reliable) return false
        val countedEntry = HoraTrackV2.time.countedEntryFromRealArrival(realStartMs)
        val expectedEnd = V2ScheduleStore.expectedEnd(context, realStartMs, realEndMs)
        val countedExit = HoraTrackV2.time.countedExitFromRealExit(realEndMs, expectedEnd)
        val placeLabel = place?.trim()?.takeIf { it.isNotBlank() }

        val stored = V2RuntimeHistoryGuardV2.read(context)
        if (!stored.reliable) return false
        val history = stored.history
        val employerKey = employerKey(employerId, legacySlot)
        val signature = "$realStartMs:$realEndMs:$countedEntry:$countedExit:$employerKey"
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i) ?: return false
            val existingEmployer = employerKey(
                employerId = o.optString("employerId").takeIf {
                    o.has("employerId") && !o.isNull("employerId") && it.isNotBlank() && it != "null"
                },
                legacySlot = o.optInt("companySlot").takeIf {
                    o.has("companySlot") && !o.isNull("companySlot") && it in 1..2
                }
            )
            val existing = "${o.optLong("realEntry", 0L)}:${o.optLong("realExit", 0L)}:${o.optLong("countedEntry", 0L)}:${o.optLong("countedExit", 0L)}:$existingEmployer"
            if (existing == signature) return false
        }

        history.put(createManualSessionJson(
            id = "manual-${UUID.randomUUID()}",
            realStartMs = realStartMs,
            realEndMs = realEndMs,
            countedEntryMs = countedEntry,
            countedExitMs = countedExit,
            employerId = employerId,
            legacySlot = legacySlot,
            placeLabel = placeLabel
        ))
        return V2RuntimeHistoryGuardV2.save(context, history)
    }

    internal fun createManualSessionJson(
        id: String,
        realStartMs: Long,
        realEndMs: Long,
        countedEntryMs: Long,
        countedExitMs: Long,
        employerId: String?,
        legacySlot: Int?,
        placeLabel: String?
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("employerId", employerId ?: JSONObject.NULL)
        .apply { legacySlot?.let { put("companySlot", it) } }
        .put("realEntry", realStartMs)
        .put("countedEntry", countedEntryMs)
        .put("realExit", realEndMs)
        .put("countedExit", countedExitMs)
        .put("pauses", JSONArray())
        .put("source", "MANUAL")
        .put("placeId", JSONObject.NULL)
        .put("placeLabel", placeLabel ?: JSONObject.NULL)
        .put("place", placeLabel ?: JSONObject.NULL)

    internal fun employerKey(employerId: String?, legacySlot: Int?): String = when {
        !employerId.isNullOrBlank() -> "employer:$employerId"
        legacySlot in 1..2 -> "slot:$legacySlot"
        else -> "none"
    }
}
