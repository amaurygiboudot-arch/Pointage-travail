package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject

/** A retry consumes a real ENTER observation; opening the app never creates one. */
internal object GpsEntryRecoveryV2 {
    const val KEY = "pending_entry_observations_v2"
    const val BATCH_WINDOW_MS = 2_000L
    private const val UNKNOWN_SESSION = "unknown"

    data class Observation(
        val zoneId: String,
        val atMs: Long,
        val account: String,
        val epoch: String,
        val configuration: String,
        val session: String,
        val activeEmployerId: String?
    )

    /** null is corrupt/unreadable; an absent key alone is a known empty collection. */
    fun read(prefs: SharedPreferences): Map<String, Observation>? = runCatching {
        if (!prefs.contains(KEY)) emptyMap()
        else prefs.getString(KEY, null)?.let(::decode)
    }.getOrNull()

    fun decode(raw: String?): Map<String, Observation>? = runCatching {
        if (raw == null) return emptyMap()
        val rows = JSONArray(raw)
        val result = linkedMapOf<String, Observation>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.getInt("version") == 1)
            val observation = Observation(row.getString("zone"), row.getLong("at"),
                row.getString("account"), row.getString("epoch"), row.getString("configuration"),
                row.getString("session"), if (row.isNull("employer")) null else row.getString("employer"))
            require(observation.zoneId.isNotBlank() && observation.atMs > 0 &&
                observation.account.isNotBlank() && observation.epoch.isNotBlank() &&
                observation.configuration.isNotBlank() && observation.session.isNotBlank() &&
                observation.activeEmployerId?.isBlank() != true)
            require(result.put(observation.zoneId, observation) == null)
        }
        result
    }.getOrNull()

    fun encode(observations: Map<String, Observation>): String = JSONArray().apply {
        observations.values.sortedBy { it.zoneId }.forEach { observation ->
            put(JSONObject().put("version", 1).put("zone", observation.zoneId)
                .put("at", observation.atMs).put("account", observation.account)
                .put("epoch", observation.epoch).put("configuration", observation.configuration)
                .put("session", observation.session)
                .put("employer", observation.activeEmployerId ?: JSONObject.NULL))
        }
    }.toString()

    /** Called only by a received ENTER; retain the first observation across duplicate callbacks. */
    fun observe(context: Context, prefs: SharedPreferences, triggered: List<String>,
        activeBefore: Set<String>, atMs: Long): Map<String, Observation>? = V2RuntimeStore.withTransaction {
        // Even a new presence episode cannot overwrite an unreadable older observation.
        val prior = read(prefs) ?: return@withTransaction null
        val previous = if (activeBefore.isEmpty()) emptyMap() else prior.filterKeys { it in activeBefore }
        val account = GpsExitDeliveryV2.accountScope() ?: return@withTransaction previous
        val epoch = V2RuntimeAccountScope.monitoringEpoch(context) ?: return@withTransaction previous
        val runtime = V2RuntimeReader.current(context, atMs)
        if (atMs <= 0) return@withTransaction previous
        val stored = readPersistedGpsZones(prefs) as? GpsZonesReadResult.Valid ?: return@withTransaction previous
        val configuration = storedGpsAutomaticFingerprintV2(prefs.getBoolean("enabled", false), stored)
        val activeEmployer = V2ProfileStore.activeCompanyId(context)
        if (GpsExitDeliveryV2.accountScope() != account ||
            V2RuntimeAccountScope.monitoringEpoch(context) != epoch) return@withTransaction previous
        previous.toMutableMap().apply {
            triggered.distinct().forEach { zone ->
                if (zone !in this) put(zone, Observation(zone, atMs, account, epoch,
                    configuration, if (runtime.reliable) sessionKey(runtime.snapshot.session) else UNKNOWN_SESSION,
                    activeEmployer))
            }
        }
    }

    /** No missing marker, foreign account, changed configuration or later session is replayed. */
    fun eligible(context: Context, prefs: SharedPreferences, zoneId: String,
        employerId: String?, keepCurrentEmployer: Boolean, nowMs: Long): Observation? {
        if (!prefs.getBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, false) ||
            !GeofenceManager.isStoredRegistrationCurrent(context)) return null
        val observations = read(prefs) ?: return null
        val observation = observations[zoneId] ?: return null
        // Keep the received fact when its session could not be read. A later readable
        // projection cannot retroactively prove which session owned that observation.
        if (observation.session == UNKNOWN_SESSION) return null
        val active = prefs.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, emptySet()).orEmpty()
        if (zoneId !in active || observations.filterKeys { it in active }.values.any {
                nowMs < it.atMs || nowMs - it.atMs < BATCH_WINDOW_MS
            }) return null
        val scope = GpsExitDeliveryV2.accountScope() ?: return null
        val epoch = V2RuntimeAccountScope.monitoringEpoch(context) ?: return null
        val stored = readPersistedGpsZones(prefs) as? GpsZonesReadResult.Valid ?: return null
        if (observation.account != scope || observation.epoch != epoch ||
            observation.configuration != storedGpsAutomaticFingerprintV2(prefs.getBoolean("enabled", false), stored)) return null
        if (keepCurrentEmployer && observation.activeEmployerId != employerId) return null
        val runtime = V2RuntimeReader.current(context, nowMs)
        if (!runtime.reliable) return null
        val current = runtime.snapshot.session
        // A successful write can precede a failed GPS receipt. Verify that exact arrival
        // before allowing the retry to acknowledge the already persisted session.
        val recoveredWrite = employerId != null && current != null && current.realExitMs == null &&
            current.realArrivalMs == observation.atMs && current.employerId == employerId
        if (sessionKey(current) != observation.session && !recoveredWrite) return null
        return observation
    }

    private fun sessionKey(session: WorkSessionV2?): String = if (session == null) "none" else
        JSONArray().put(session.id).put(session.realArrivalMs ?: JSONObject.NULL)
            .put(session.realExitMs ?: JSONObject.NULL).put(session.status.name).toString()
}

/** Includes confirmed legacy-slot and KeepCurrent resolution, without inventing an employer. */
internal fun resolvedGpsEntryEmployerIdV2(resolution: GpsZoneEmployerResolutionV2,
    confirmedActiveCompanyId: String?): String? = when (resolution) {
    is GpsZoneEmployerResolutionV2.UseCompany -> resolution.companyId
    GpsZoneEmployerResolutionV2.KeepCurrent -> confirmedActiveCompanyId
    is GpsZoneEmployerResolutionV2.Block -> null
}
