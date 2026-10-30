package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.V2RuntimeAccountScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/** A GPS observation is evidence of a location, not evidence of paid work or mileage. */
internal data class TravelFixV2(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val observedAtMs: Long
) {
    fun isUsable(): Boolean = latitude.isFinite() && longitude.isFinite() &&
        accuracyMeters.isFinite() && latitude in -90.0..90.0 &&
        longitude in -180.0..180.0 && accuracyMeters in 0.0..150.0 &&
        observedAtMs > 0L
}

internal data class TravelJourneyV2(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val employerId: String? = null,
    val departure: TravelFixV2? = null,
    val arrival: TravelFixV2? = null
)

/** The JSON codec is pure, strict, and covered by JVM tests. */
internal object TravelJourneyCodecV2 {
    private const val VERSION = 1

    fun valid(journeys: List<TravelJourneyV2>): Boolean =
        journeys.size <= 10000 &&
        journeys.map { it.id }.distinct().size == journeys.size &&
        journeys.count { it.endedAtMs == null } <= 1 &&
        journeys.all {
            it.id.isNotBlank() && it.startedAtMs > 0L &&
                (it.endedAtMs == null || it.endedAtMs > it.startedAtMs) &&
                (it.departure == null || (it.departure.isUsable() &&
                    abs(it.departure.observedAtMs - it.startedAtMs) <= 180_000L)) &&
                (it.arrival == null || (it.endedAtMs != null &&
                    it.arrival.isUsable() &&
                    abs(it.arrival.observedAtMs - it.endedAtMs) <= 180_000L))
        }

    private fun position(value: TravelFixV2?): Any =
        value?.let {
            JSONObject().put("lat", it.latitude).put("lon", it.longitude)
                .put("accuracy", it.accuracyMeters).put("at", it.observedAtMs)
        } ?: JSONObject.NULL

    private fun decodePosition(row: JSONObject, key: String): TravelFixV2? {
        require(row.has(key))
        if (row.isNull(key)) return null
        val p = row.getJSONObject(key)
        return TravelFixV2(
            p.getDouble("lat"), p.getDouble("lon"), p.getDouble("accuracy"),
            p.getLong("at")
        ).also { require(it.isUsable()) }
    }

    fun encode(owner: String, journeys: List<TravelJourneyV2>): String? {
        if (owner.isBlank() || !valid(journeys)) return null
        val items = JSONArray()
        journeys.forEach {
            items.put(JSONObject().put("id", it.id).put("startedAt", it.startedAtMs)
                .put("endedAt", it.endedAtMs ?: JSONObject.NULL)
                .put("employer", it.employerId ?: JSONObject.NULL)
                .put("departure", position(it.departure))
                .put("arrival", position(it.arrival)))
        }
        return JSONObject().put("version", VERSION).put("owner", owner)
            .put("journeys", items).toString()
    }

    fun decode(owner: String, raw: String): List<TravelJourneyV2>? = runCatching {
        val root = JSONObject(raw)
        require(owner.isNotBlank() && root.getString("owner") == owner &&
            root.getInt("version") == VERSION)
        val items = root.getJSONArray("journeys")
        require(items.length() <= 10000)
        val result = (0 until items.length()).map { index ->
            val row = items.getJSONObject(index)
            require(row.has("endedAt") && row.has("employer"))
            TravelJourneyV2(
                id = row.getString("id"),
                startedAtMs = row.getLong("startedAt"),
                endedAtMs = if (row.isNull("endedAt")) null else row.getLong("endedAt"),
                employerId = if (row.isNull("employer")) null else row.getString("employer"),
                departure = decodePosition(row, "departure"),
                arrival = decodePosition(row, "arrival")
            )
        }
        require(valid(result))
        result
    }.getOrNull()
}

/** Persistent account-scoped journeys. Missing != corrupt; no silent data reset. */
internal object TravelJourneyStoreV2 {
    private const val PREFS = "travel_journeys_v2"
    private const val KEY = "journal"

    @Synchronized
    fun read(context: Context): List<TravelJourneyV2>? = V2RuntimeAccountScope.withScope {
        readScoped(context)
    }

    private fun readScoped(context: Context): List<TravelJourneyV2>? {
        val owner = V2RuntimeAccountScope.operationScope() ?: return null
        if (!V2RuntimeAccountScope.available()) return null
        val prefs = V2RuntimeAccountScope.preferences(context, PREFS)
        if (!prefs.contains(KEY)) return emptyList()
        val raw = prefs.getString(KEY, null) ?: return null
        return TravelJourneyCodecV2.decode(owner, raw)
    }

    private fun saveScoped(context: Context, journeys: List<TravelJourneyV2>): Boolean {
        val owner = V2RuntimeAccountScope.operationScope() ?: return false
        if (!V2RuntimeAccountScope.available()) return false
        val raw = TravelJourneyCodecV2.encode(owner, journeys) ?: return false
        val stored = V2RuntimeAccountScope.preferences(context, PREFS).edit()
            .putString(KEY, raw).commit()
        return stored && readScoped(context) == journeys
    }

    @Synchronized
    fun begin(context: Context, atMs: Long, employerId: String?): String? =
        V2RuntimeAccountScope.withScope {
            val current = readScoped(context) ?: return@withScope null
            if (atMs <= 0 || current.size >= 10000 ||
                current.any { it.endedAtMs == null }) return@withScope null
            val id = UUID.randomUUID().toString()
            val next = current + TravelJourneyV2(id, atMs,
                employerId = employerId?.takeIf { it.isNotBlank() })
            id.takeIf { saveScoped(context, next) }
        }

    @Synchronized
    fun finish(context: Context, atMs: Long): String? = V2RuntimeAccountScope.withScope {
        val current = readScoped(context) ?: return@withScope null
        val open = current.singleOrNull { it.endedAtMs == null } ?: return@withScope null
        if (atMs <= open.startedAtMs) return@withScope null
        val next = current.map { if (it.id == open.id) it.copy(endedAtMs = atMs) else it }
        open.id.takeIf { saveScoped(context, next) }
    }

    @Synchronized
    fun attachFix(context: Context, id: String, isDeparture: Boolean, fix: TravelFixV2): Boolean =
        V2RuntimeAccountScope.withScope {
            val current = readScoped(context) ?: return@withScope false
            val journey = current.singleOrNull { it.id == id } ?: return@withScope false
            val actionAt = if (isDeparture) journey.startedAtMs
                else journey.endedAtMs ?: return@withScope false
            if (!fix.isUsable() || abs(fix.observedAtMs - actionAt) > 180_000L ||
                (if (isDeparture) journey.departure else journey.arrival) != null)
                return@withScope false
            val next = current.map {
                if (it.id != id) it
                else if (isDeparture) it.copy(departure = fix)
                else it.copy(arrival = fix)
            }
            saveScoped(context, next)
        }
}
