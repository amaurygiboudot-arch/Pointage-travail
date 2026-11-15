package com.amaury.pointage

import com.amaury.pointage.v2.RestoreGuardedPreferences

import android.content.Context
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.v2.engine.GpsTransitionV2
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/** Observed site boundaries, never a payroll qualification or a measured road itinerary. */
internal enum class GpsSiteObservationKindV2 { WORK_ENTER, WORK_EXIT, HOME_ENTER, HOME_EXIT, INTERRUPTION }

internal data class GpsSiteObservationV2(
    val id: String,
    val context: String,
    val zoneId: String,
    val siteId: String,
    val label: String,
    val employerId: String?,
    val kind: GpsSiteObservationKindV2,
    val atMs: Long,
    val homeConfigured: Boolean
)

internal data class GpsSiteJourneyV2(val departure: GpsSiteObservationV2, val arrival: GpsSiteObservationV2) {
    val id: String get() = departure.id + ":" + arrival.id
    val durationMs: Long get() = arrival.atMs - departure.atMs
    // No continuous route or professional-purpose proof exists in the current GPS feed.
    val status: String get() = "À confirmer"
    val distanceMeters: Double? get() = null
    val reason: String get() = when {
        departure.employerId == null || arrival.employerId == null -> "Entreprise à confirmer ; continuité du trajet non prouvée."
        departure.employerId != arrival.employerId -> "Entreprises différentes ; nature du trajet à confirmer."
        !departure.homeConfigured || !arrival.homeConfigured -> "Domicile non configuré ; un retour au domicile ne peut pas être exclu."
        else -> "Intervalle observé entre sites ; continuité et nature du trajet à confirmer."
    }
}

/** Pure journal/reducer, shared by persistence and tests. Unknown continuity stays unknown. */
internal object GpsSiteJourneyJournalV2 {
    const val MAX_EVENTS = 10_000

    fun valid(events: List<GpsSiteObservationV2>): Boolean = events.size <= MAX_EVENTS &&
        events.map { it.id }.distinct().size == events.size &&
        events.zipWithNext().all { (a, b) -> a.atMs <= b.atMs } && events.all {
            it.id.isNotBlank() && it.context.isNotBlank() && it.atMs > 0 &&
                it.employerId?.isBlank() != true &&
                (if (it.kind == GpsSiteObservationKindV2.INTERRUPTION)
                    it.zoneId.isEmpty() && it.siteId.isEmpty() && it.label.isEmpty() && it.employerId == null
                else it.zoneId.isNotBlank() && it.siteId.isNotBlank() && it.label.isNotBlank())
        }

    fun append(events: List<GpsSiteObservationV2>, observation: GpsSiteObservationV2): List<GpsSiteObservationV2>? {
        if (!valid(events) || !valid(listOf(observation))) return null
        events.firstOrNull { it.id == observation.id }?.let { return events.takeIf { _ -> it == observation } }
        if (events.lastOrNull()?.atMs?.let { it > observation.atMs } == true) return null
        // A repeated platform callback must not move the first observed timestamp.
        val recent = events.asReversed().takeWhile {
            it.context == observation.context && it.kind != GpsSiteObservationKindV2.INTERRUPTION
        }
        val previous = recent.firstOrNull { it.zoneId == observation.zoneId }
        if (previous?.kind == observation.kind && observation.kind != GpsSiteObservationKindV2.INTERRUPTION &&
            recent.takeWhile { it != previous }.none {
                it.kind == GpsSiteObservationKindV2.HOME_ENTER || it.kind == GpsSiteObservationKindV2.HOME_EXIT
            }) return events
        return (events + observation).takeIf(::valid)
    }

    fun journeys(events: List<GpsSiteObservationV2>): List<GpsSiteJourneyV2>? {
        if (!valid(events)) return null
        val result = mutableListOf<GpsSiteJourneyV2>()
        var pending: GpsSiteObservationV2? = null
        var previousContext: String? = null
        val activeWork = mutableMapOf<String, String>()
        val activeHome = mutableSetOf<String>()
        // Callbacks containing overlapping zones share a timestamp. Their iteration order
        // cannot decide an itinerary; HOME always wins and ambiguity breaks the chain.
        for (batch in events.groupBy { it.atMs }.values) {
            val context = batch.map { it.context }.distinct().singleOrNull()
            if (context == null || context != previousContext) {
                pending = null
                activeWork.clear()
                activeHome.clear()
            }
            previousContext = context
            val knownDepartures = batch.filter { it.kind == GpsSiteObservationKindV2.WORK_EXIT &&
                activeWork[it.zoneId] == it.siteId }.map { it.zoneId }.toSet()
            batch.forEach {
                when (it.kind) {
                    GpsSiteObservationKindV2.WORK_ENTER -> activeWork[it.zoneId] = it.siteId
                    GpsSiteObservationKindV2.WORK_EXIT -> activeWork.remove(it.zoneId)
                    GpsSiteObservationKindV2.HOME_ENTER -> activeHome.add(it.zoneId)
                    GpsSiteObservationKindV2.HOME_EXIT -> activeHome.remove(it.zoneId)
                    GpsSiteObservationKindV2.INTERRUPTION -> { activeWork.clear(); activeHome.clear() }
                }
            }
            if (context == null || batch.any { it.kind in setOf(GpsSiteObservationKindV2.HOME_ENTER,
                    GpsSiteObservationKindV2.HOME_EXIT, GpsSiteObservationKindV2.INTERRUPTION) }) {
                pending = null
                continue
            }
            val exits = batch.filter { it.kind == GpsSiteObservationKindV2.WORK_EXIT }.distinctBy { it.siteId }
            val entries = batch.filter { it.kind == GpsSiteObservationKindV2.WORK_ENTER }.distinctBy { it.siteId }
            if (activeHome.isNotEmpty() || exits.size > 1 || entries.size > 1 || activeWork.values.distinct().size > 1 ||
                (exits.isNotEmpty() && entries.isNotEmpty())) {
                pending = null
                continue
            }
            entries.singleOrNull()?.let { arrival ->
                pending?.takeIf { it.context == arrival.context && it.siteId != arrival.siteId && it.atMs < arrival.atMs }
                    ?.let { result += GpsSiteJourneyV2(it, arrival) }
                pending = null
            }
            exits.singleOrNull()?.let { pending = it.takeIf { it.zoneId in knownDepartures && activeWork.isEmpty() } }
        }
        return result
    }

    fun encode(owner: String, events: List<GpsSiteObservationV2>): String? {
        if (owner.isBlank() || !valid(events)) return null
        val rows = JSONArray()
        events.forEach { rows.put(JSONObject().put("id", it.id).put("context", it.context)
            .put("zone", it.zoneId).put("site", it.siteId).put("label", it.label)
            .put("employer", it.employerId ?: JSONObject.NULL).put("kind", it.kind.name)
            .put("at", it.atMs).put("homeConfigured", it.homeConfigured)) }
        return JSONObject().put("version", 1).put("owner", owner).put("events", rows).toString()
    }

    fun decode(owner: String, raw: String): List<GpsSiteObservationV2>? = runCatching {
        val root = JSONObject(raw)
        require(owner.isNotBlank() && root.get("version") == 1 && root.get("owner") == owner)
        val rows = root.getJSONArray("events")
        require(rows.length() <= MAX_EVENTS)
        val events = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun string(key: String): String = (row.get(key) as? String) ?: error("Invalid $key")
            val at = row.get("at")
            require(at is Long || at is Int)
            val home = row.get("homeConfigured")
            require(home is Boolean)
            GpsSiteObservationV2(string("id"), string("context"), string("zone"), string("site"), string("label"),
                if (row.isNull("employer") && row.has("employer")) null else string("employer"),
                GpsSiteObservationKindV2.valueOf(string("kind")), (at as Number).toLong(), home)
        }
        require(valid(events))
        events
    }.getOrNull()
}

/** Local, account-isolated facts. Never writes sessions, salary, raw coordinates or GPS presence. */
internal object GpsSiteJourneyStoreV2 {
    internal const val PREFS = "gps_site_journeys_v2"
    internal const val KEY = "journal"
    internal const val INCOMPLETE = "incomplete"
    private val unconfirmed = mutableMapOf<String, String>()
    private val incompleteInProcess = mutableSetOf<String>()

    private fun digest(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized
    fun read(context: Context): List<GpsSiteObservationV2>? = V2RuntimeAccountScope.withScope {
        val owner = V2RuntimeAccountScope.operationScope() ?: return@withScope null
        if (!V2RuntimeAccountScope.available() || owner in unconfirmed) return@withScope null
        readScoped(context, owner)
    }

    @Synchronized
    fun warning(context: Context): String? = V2RuntimeAccountScope.withScope {
        val owner = V2RuntimeAccountScope.operationScope() ?: return@withScope "Compte indisponible : journal à vérifier."
        val prefs = V2RuntimeAccountScope.preferences(context, PREFS)
        if (owner in incompleteInProcess || owner in unconfirmed || prefs.all[INCOMPLETE] == true)
            "Journal incomplet : au moins un passage n'a pas pu être enregistré (stockage, chronologie ou capacité). Les faits conservés restent visibles ; les trajets sont à vérifier."
        else if (prefs.contains(INCOMPLETE) && prefs.all[INCOMPLETE] !is Boolean)
            "État du journal illisible : exhaustivité inconnue."
        else null
    }

    private fun markIncomplete(context: Context, owner: String) {
        incompleteInProcess.add(owner)
        // Best effort only: if storage itself failed the in-process warning still remains.
        V2RuntimeAccountScope.preferences(context, PREFS).edit().putBoolean(INCOMPLETE, true).commit()
    }

    private fun readScoped(context: Context, owner: String): List<GpsSiteObservationV2>? {
        val prefs = V2RuntimeAccountScope.preferences(context, PREFS)
        if (!prefs.contains(KEY)) return emptyList()
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return null
        return GpsSiteJourneyJournalV2.decode(owner, raw)
    }

    private fun appendScoped(context: Context, owner: String, observations: List<GpsSiteObservationV2>): Boolean {
        val prefs = V2RuntimeAccountScope.preferences(context, PREFS)
        // Android commit(false) may update RAM. Reconfirm those exact facts before retrying
        // or exposing them. A process restart naturally reloads the actual disk journal.
        unconfirmed[owner]?.let { raw ->
            if (runCatching { prefs.getString(KEY, null) }.getOrNull() != raw ||
                !prefs.edit().putString(KEY, raw).commit()) return false
            unconfirmed.remove(owner)
        }
        val existing = readScoped(context, owner) ?: return false
        var next = existing
        for (observation in observations) {
            next = GpsSiteJourneyJournalV2.append(next, observation) ?: return false
        }
        if (next == existing) return true
        val raw = GpsSiteJourneyJournalV2.encode(owner, next) ?: return false
        if (!prefs.edit().putString(KEY, raw).commit()) {
            unconfirmed[owner] = raw
            return false
        }
        return V2RuntimeAccountScope.available() && readScoped(context, owner) == next
    }

    fun observe(context: Context, zoneId: String, transition: GpsTransitionV2, observedAtMs: Long): Boolean =
        observeBatch(context, listOf(zoneId), transition, observedAtMs)

    /** A platform callback is indivisible: HOME exclusion cannot be lost after half a batch. */
    @Synchronized
    fun observeBatch(context: Context, zoneIds: List<String>, transition: GpsTransitionV2, observedAtMs: Long): Boolean =
        V2RuntimeAccountScope.withScope {
            val owner = V2RuntimeAccountScope.operationScope() ?: return@withScope false
            if (!V2RuntimeAccountScope.available() || zoneIds.isEmpty() || observedAtMs <= 0 || observedAtMs > System.currentTimeMillis()) return@withScope false
            val gps = RestoreGuardedPreferences.open(context.applicationContext, "gps_settings", Context.MODE_PRIVATE)
            val stored = readPersistedGpsZones(gps) as? GpsZonesReadResult.Valid ?: return@withScope false
            if (!gps.getBoolean("enabled", false) || !GeofenceManager.isStoredRegistrationCurrent(context)) return@withScope false
            val selected = zoneIds.distinct().sorted().map { zoneId ->
                stored.zones.singleOrNull { it.id == zoneId } ?: return@withScope false
            }.filter { !it.isGpsCandidate() && it.roleForContextV2() in setOf(GpsZoneRoleV2.WORK, GpsZoneRoleV2.HOME) }
            if (selected.isEmpty()) return@withScope true
            val epoch = V2RuntimeAccountScope.monitoringEpoch(context) ?: return@withScope false
            val fingerprint = storedGpsAutomaticFingerprintV2(true, stored)
            val binding = digest(fingerprint + ":" + epoch)
            val events = selected.map { zone ->
                val kind = if (zone.roleForContextV2() == GpsZoneRoleV2.HOME) {
                    if (transition == GpsTransitionV2.ENTER) GpsSiteObservationKindV2.HOME_ENTER else GpsSiteObservationKindV2.HOME_EXIT
                } else if (transition == GpsTransitionV2.ENTER) GpsSiteObservationKindV2.WORK_ENTER else GpsSiteObservationKindV2.WORK_EXIT
                val address = zone.address?.trim()?.lowercase(Locale.ROOT)
                val site = if (address.isNullOrEmpty()) "zone:${zone.id}" else
                    "place:" + digest(JSONArray().put(zone.companyId ?: JSONObject.NULL).put(zone.companySlot ?: JSONObject.NULL).put(address).toString())
                GpsSiteObservationV2(digest(JSONArray().put(binding).put(zone.id).put(kind.name).put(observedAtMs).toString()),
                    binding, zone.id, site, zone.label?.takeIf { it.isNotBlank() } ?: zone.address?.takeIf { it.isNotBlank() } ?: "Lieu enregistré", zone.companyId,
                    kind, observedAtMs, stored.zones.any { it.roleForContextV2() == GpsZoneRoleV2.HOME })
            }
            if (!V2RuntimeAccountScope.available() || V2RuntimeAccountScope.monitoringEpoch(context) != epoch ||
                storedGpsAutomaticFingerprintV2(gps.getBoolean("enabled", false), readPersistedGpsZones(gps)) != fingerprint) return@withScope false
            appendScoped(context, owner, events).also { if (!it) markIncomplete(context, owner) }
        }

    /** Explicit loss of surveillance breaks pairing; it never invents an exit or arrival. */
    @Synchronized
    fun interrupt(context: Context, observedAtMs: Long = System.currentTimeMillis()): Boolean = V2RuntimeAccountScope.withScope {
        val owner = V2RuntimeAccountScope.operationScope() ?: return@withScope false
        if (!V2RuntimeAccountScope.available() || observedAtMs <= 0) return@withScope false
        val previous = readScoped(context, owner) ?: return@withScope false
        val last = previous.lastOrNull() ?: return@withScope true
        // Several layers can observe the same outage. Keep one boundary and retry its
        // exact pending write instead of filling the journal on every screen refresh.
        if (last.kind == GpsSiteObservationKindV2.INTERRUPTION) {
            return@withScope appendScoped(context, owner, listOf(last))
                .also { if (!it) markIncomplete(context, owner) }
        }
        val binding = last.context
        appendScoped(context, owner, listOf(GpsSiteObservationV2(digest("$binding|interrupt|$observedAtMs"),
            binding, "", "", "", null, GpsSiteObservationKindV2.INTERRUPTION, observedAtMs, false)))
            .also { if (!it) markIncomplete(context, owner) }
    }
}
