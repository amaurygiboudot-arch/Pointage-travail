package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.WorkSessionV2
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
        return V2RuntimeStore.withTransaction {
            if (!HoraTrackV2.ENABLED || realStartMs <= 0L || realEndMs <= realStartMs) return false
            V2RuntimeStore.bind(context)
            val migration = V2MigrationManager.ensureMigrated(context)
            if (!migration.reliable) return false
            // Une saisie manuelle apporte ses deux bornes réelles ; elle ne présume
            // aucune politique d'arrondi ou tolérance propre à un employeur.
            val countedEntry = realStartMs
            val countedExit = realEndMs
            val placeLabel = place?.trim()?.takeIf { it.isNotBlank() }

            return appendToHistory(
                realStartMs, realEndMs, countedEntry, countedExit, employerId, legacySlot, placeLabel,
                readHistory = { V2RuntimeHistoryGuardV2.read(context) },
                readCurrent = { V2RuntimeStore.snapshot(context).session },
                currentReliable = { V2RuntimeHistoryGuardV2.sourceState().reliable },
                saveHistory = { V2RuntimeHistoryGuardV2.save(context, it) }
            )
        }
    }

    /** Frontière transactionnelle canonique, injectable sans changer la politique de pointage. */
    internal fun appendToHistory(
        realStartMs: Long,
        realEndMs: Long,
        countedEntry: Long,
        countedExit: Long?,
        employerId: String?,
        legacySlot: Int?,
        placeLabel: String?,
        readHistory: () -> V2RuntimeHistoryGuardV2.ReadResult,
        readCurrent: () -> WorkSessionV2?,
        currentReliable: () -> Boolean,
        saveHistory: (JSONArray) -> Boolean
    ): Boolean {
        return V2RuntimeStore.withTransaction {
            val stored = readHistory()
            if (!stored.reliable) return false
            val history = stored.history
            if (V2RuntimeStore.historyOverlapsRange(history, realStartMs, realEndMs) != false) return false

            // Une session ouverte n'est pas encore dans l'historique : elle doit elle aussi empêcher
            // l'ajout d'une plage manuelle qui recouvrirait son temps réel.
            val current = readCurrent()
            if (!currentReliable()) return false
            if (current != null) {
                val currentStart = current.realArrivalMs ?: return false
                val currentEnd = current.realExitMs ?: Long.MAX_VALUE
                if (realStartMs < currentEnd && currentStart < realEndMs) return false
            }

            val employerKey = employerKey(employerId, legacySlot)
            val signature = "$realStartMs:$realEndMs:$countedEntry:${countedExit ?: 0L}:$employerKey"
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
            return saveHistory(history)
        }
    }

    internal fun createManualSessionJson(
        id: String,
        realStartMs: Long,
        realEndMs: Long,
        countedEntryMs: Long,
        countedExitMs: Long?,
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
        .put("countedExit", countedExitMs ?: JSONObject.NULL)
        .put("timeBasis", "REAL_FACTS")
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
