package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Explicit device journal ownership declaration. Originals are never changed or deleted. */
object V2LegacyJournalRecovery {
    data class Preview(val scope: String, val sourceFingerprint: String, val destinationFingerprint: String,
                       val eligible: Boolean, val message: String)
    data class Result(val success: Boolean, val message: String)
    private const val BASE = "horatrack_v2_test_runtime"
    private const val CLAIM = "horatrack_v2_legacy_recovery_claim"
    private const val RECEIPT = "legacy_recovery_receipt"
    @Volatile private var uncertainCommit = false
    private val recoveryWriter = ThreadLocal<Boolean>()
    internal fun canWrite(context: Context, scope: String?): Boolean = recoveryWriter.get() == true || runCatching {
        val record = claim(context)
        !(record.getString("owner", null) == scope && (record.getString("state", null) == "PENDING" || uncertainCommit))
    }.getOrDefault(false)
    private fun source(context: Context) = context.applicationContext.getSharedPreferences(BASE, Context.MODE_PRIVATE)
    private fun claim(context: Context) = context.applicationContext.getSharedPreferences(CLAIM, Context.MODE_PRIVATE)
    private fun legacy(context: Context) = context.applicationContext.getSharedPreferences("pointage", Context.MODE_PRIVATE)
    private fun sourceHash(context: Context): String = fingerprint(mapOf("v2" to fingerprint(source(context).all),
        "v1" to fingerprint(legacy(context).all)))

    internal fun fingerprint(values: Map<String, *>): String {
        val encoded = values.toSortedMap().entries.joinToString("") { (key, value) ->
            val text = when (value) { is Set<*> -> value.map { it.toString() }.sorted().joinToString("\u0000"); else -> value.toString() }
            "${key.length}:$key:${value?.javaClass?.name}:${text.length}:$text;"
        }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun preview(context: Context): Preview? = V2RuntimeStore.withTransaction {
        val scope = V2RuntimeAccountScope.currentScope() ?: return@withTransaction null
        if (assigned(context)) return@withTransaction null
        val old = source(context).all
        if (old.isEmpty() && !legacy(context).contains("data")) return@withTransaction null
        val hash = sourceHash(context)
        val dest = V2RuntimeAccountScope.preferences(context, BASE).all
        val reservation = claim(context).all
        val problem = when {
            !scope.startsWith("uid:") -> "Connectez-vous au compte auquel appartient cet ancien journal."
            reservation.isNotEmpty() && (reservation["owner"] != scope || reservation["source"] != hash) ->
                "Ce journal est déjà réservé à un autre contexte, ou ses données ont changé. Récupération bloquée."
            dest[RECEIPT] == hash && (!validSource(dest.filterKeys { it != RECEIPT }) ||
                reservation["candidate"] != fingerprint(dest.filterKeys { it != RECEIPT })) ->
                "Le journal a changé pendant la récupération : contrôle nécessaire avant reprise."
            dest[RECEIPT] != hash && recoveryCandidate(context, dest) == null ->
                "Rapprochement bloqué : données incomplètes, pause non qualifiée, chevauchement ou deux sessions en cours. Aucun remplacement ni effacement."
            else -> null
        }
        Preview(scope, hash, fingerprint(dest), problem == null,
            problem ?: "Déclarez que l'intégralité de cet ancien journal vous appartient et ne contient aucun pointage d'une autre personne. Les originaux seront conservés.")
    }

    fun confirm(context: Context, preview: Preview, declaredOwnership: Boolean): Result = V2RuntimeStore.withTransaction {
        recoveryWriter.set(true)
        try {
        fun blocked() = Result(false, "Récupération non effectuée : contexte modifié, conflit ou sauvegarde non confirmée. Originaux conservés.")
        if (!declaredOwnership || !preview.eligible || !preview.scope.startsWith("uid:")) return@withTransaction blocked()
        val fresh = this.preview(context) ?: return@withTransaction blocked()
        if (!fresh.eligible || fresh.scope != preview.scope || fresh.sourceFingerprint != preview.sourceFingerprint ||
            fresh.destinationFingerprint != preview.destinationFingerprint) return@withTransaction blocked()
        val reservation = claim(context)
        val before = V2RuntimeAccountScope.preferences(context, BASE).all
        val planned = if (before[RECEIPT] == preview.sourceFingerprint) before.filterKeys { it != RECEIPT }
            else recoveryCandidate(context, before) ?: return@withTransaction blocked()
        val plannedHash = fingerprint(planned)
        if (before[RECEIPT] == preview.sourceFingerprint && reservation.all["candidate"] != plannedHash) return@withTransaction blocked()
        // Recommit the reservation on retries: a previous false commit may still be cached in RAM.
        uncertainCommit = true
        if (!reservation.edit().putString("owner", preview.scope).putString("source", preview.sourceFingerprint)
                .putString("candidate", plannedHash).putString("state", "PENDING").commit()) return@withTransaction blocked()
        if (reservation.getString("owner", null) != preview.scope || reservation.getString("source", null) != preview.sourceFingerprint)
            return@withTransaction blocked()
        if (V2RuntimeAccountScope.currentScope() != preview.scope || sourceHash(context) != preview.sourceFingerprint) return@withTransaction blocked()
        val destination = V2RuntimeAccountScope.preferences(context, BASE)
        if (destination.all[RECEIPT] == preview.sourceFingerprint) {
            // Reflush a possibly memory-only successful-looking receipt after commit(false).
            val retryValues = destination.all
            if (fingerprint(retryValues.filterKeys { it != RECEIPT }) != plannedHash ||
                !validSource(retryValues.filterKeys { it != RECEIPT })) return@withTransaction blocked()
            val retryEditor = destination.edit()
            for ((key, value) in retryValues) if (!put(retryEditor, key, value)) return@withTransaction blocked()
            if (!retryEditor.commit() || fingerprint(destination.all) != fingerprint(retryValues)) return@withTransaction blocked()
        } else {
            val original = recoveryCandidate(context, destination.all) ?: return@withTransaction blocked()
            if (fingerprint(original) != plannedHash) return@withTransaction blocked()
            val editor = destination.edit().clear()
            for ((key, value) in original) if (!put(editor, key, value)) return@withTransaction blocked()
            editor.putString(RECEIPT, preview.sourceFingerprint)
            if (!editor.commit()) return@withTransaction blocked()
            if (fingerprint(destination.all.filterKeys { it != RECEIPT }) != fingerprint(original)) return@withTransaction blocked()
        }
        if (V2RuntimeAccountScope.currentScope() != preview.scope || sourceHash(context) != preview.sourceFingerprint ||
            destination.all[RECEIPT] != preview.sourceFingerprint ||
            fingerprint(destination.all.filterKeys { it != RECEIPT }) != plannedHash) return@withTransaction blocked()
        if (!reservation.edit().putString("state", "COMPLETE").commit()) return@withTransaction blocked()
        uncertainCommit = false
        Result(true, "Ancien journal rattaché au compte déclaré. Originaux conservés ; qualifications de paie inchangées.")
        } finally { recoveryWriter.remove() }
    }

    internal fun assigned(context: Context): Boolean = runCatching {
        val receipt = claim(context)
        val owner = receipt.getString("owner", null) ?: return@runCatching false
        val destinationName = V2RuntimeAccountScope.fileName(BASE, owner) ?: return@runCatching false
        !uncertainCommit && context.applicationContext.getSharedPreferences(destinationName, Context.MODE_PRIVATE)
            .getString(RECEIPT, null) == sourceHash(context) && receipt.getString("state", null) == "COMPLETE" && receipt.getString("source", null) == sourceHash(context) &&
            receipt.getString("owner", null)?.startsWith("uid:") == true
    }.getOrDefault(false)

    private fun recoveryCandidate(context: Context, destination: Map<String, *>): Map<String, Any?>? {
        val original = source(context).all
        val rawV1 = legacy(context).all["data"]
        val oldV1 = if (rawV1 == null && !legacy(context).contains("data")) emptyMap()
            else convertLegacy(rawV1 as? String ?: return null) ?: return null
        val oldV2 = if (original.isEmpty()) emptyMap() else {
            if (!validSource(original)) return null
            recoveryValues(original)
        }
        val combined = merge(oldV2, oldV1) ?: return null
        return merge(destination.filterKeys { it != RECEIPT }, combined)
    }

    /** Strict conversion supports only proven timestamps and explicit pause qualification. */
    internal fun convertLegacy(raw: String): Map<String, Any?>? = runCatching {
        val rows = JSONArray(raw)
        var output: Map<String, Any?> = emptyMap()
        fun number(row: JSONObject, key: String): Long? {
            if (!row.has(key) || row.isNull(key)) return null
            return when (val value = row.get(key)) {
                is Int -> value.toLong()
                is Long -> value
                else -> error("unsupported timestamp")
            }.also { require(it > 0L) }
        }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val allowed = setOf("id", "arrivalTime", "entry", "countedEntryTime", "exitTime", "exit",
                "countedExitTime", "autoPauseMinutes", "pauses", "companySlot", "shiftType", "manual",
                "zoneId", "zoneAddress", "placeLabel", "place", "address")
            require(row.keys().asSequence().all { it in allowed })
            // Older entry-only timestamps may already be rounded: do not invent real arrival.
            val start = number(row, "arrivalTime") ?: error("real arrival unknown")
            val end = number(row, "exitTime")
            val countedStart = number(row, "countedEntryTime")
            val countedEnd = number(row, "countedExitTime")
            if (number(row, "entry") != null) require(number(row, "entry") == (countedStart ?: start))
            if (number(row, "exit") != null) require(number(row, "exit") == (countedEnd ?: end))
            val rawPause = row.opt("autoPauseMinutes")
            val base = when (rawPause) { null, JSONObject.NULL -> 0L; is Int -> rawPause.toLong(); is Long -> rawPause; else -> error("pause") }
            require(base >= 0 && base <= Long.MAX_VALUE / 60_000)
            val pauses = row.optJSONArray("pauses") ?: if (!row.has("pauses")) JSONArray() else error("pauses")
            require(base == 0L || pauses.length() == 0)
            val normalized = JSONArray()
            for (j in 0 until pauses.length()) {
                val pause = pauses.getJSONObject(j)
                require(pause.keys().asSequence().all { it in setOf("start", "end", "paid", "manual", "automatic", "source", "resumedByEntry") })
                require(pause.opt("paid") is Boolean)
                normalized.put(JSONObject().put("start", number(pause, "start") ?: error("pause start"))
                    .put("end", number(pause, "end") ?: error("open pause unsupported"))
                    .put("paid", pause.getBoolean("paid")).put("source", "IMPORT"))
            }
            val id = if (row.has("id")) (row.get("id") as? String)?.takeIf { it.isNotBlank() } ?: error("id")
                else "legacy-recovery-" + fingerprint(mapOf("row" to canonical(row)))
            val item = JSONObject().put("id", id).put("realEntry", start).put("realExit", end ?: JSONObject.NULL)
                .put("countedEntry", countedStart ?: JSONObject.NULL).put("countedExit", countedEnd ?: JSONObject.NULL)
                .put("employerId", JSONObject.NULL).put("timeBasis", "LEGACY_UNVERIFIED")
                .put("legacyFixedUnpaidPauseMs", base * 60_000).put("pauses", normalized)
            listOf("zoneAddress", "placeLabel", "place", "address").firstNotNullOfOrNull { key ->
                (row.opt(key) as? String)?.takeIf { it.isNotBlank() }
            }?.let { item.put("placeLabel", it) }
            val fragment = if (end != null) mapOf("history" to JSONArray().put(item).toString()) else {
                require(base == 0L) // Runtime cannot represent an unresolved fixed deduction.
                mutableMapOf<String, Any?>("session_id" to id, "real_entry" to start,
                    "pauses" to normalized.toString(), "time_basis" to "LEGACY_UNVERIFIED").apply {
                    countedStart?.let { put("counted_entry", it) }
                    item.optString("placeLabel").takeIf { it.isNotBlank() }?.let { put("place_label", it) }
                }
            }
            output = merge(output, fragment) ?: error("conflicting legacy rows")
        }
        output
    }.getOrNull()

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.get(key)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    /** Merge disjoint factual journals. Never infer identity from matching timestamps alone. */
    internal fun merge(left: Map<String, *>, right: Map<String, *>): Map<String, Any?>? = runCatching {
        if (left.isEmpty()) return@runCatching right.toMap().takeIf { it.isEmpty() || validSource(it) }
        if (right.isEmpty()) return@runCatching left.toMap().takeIf { validSource(it) }
        require(validSource(left) && validSource(right))
        val leftCurrent = left.filterKeys { it != "history" && it != "restored_legacy_provenance" }
        val rightCurrent = right.filterKeys { it != "history" && it != "restored_legacy_provenance" }
        require(leftCurrent.isEmpty() || rightCurrent.isEmpty() || fingerprint(leftCurrent) == fingerprint(rightCurrent))
        val result = (if (leftCurrent.isNotEmpty()) leftCurrent else rightCurrent).toMutableMap()
        val identities = linkedMapOf<String, JSONObject>()
        for (source in listOf(left, right)) {
            val history = JSONArray(source["history"] as? String ?: "[]")
            for (i in 0 until history.length()) {
                val item = history.getJSONObject(i)
                val id = item.getString("id")
                val existing = identities[id]
                require(existing == null || canonical(existing) == canonical(item))
                identities[id] = item
            }
        }
        val history = JSONArray()
        identities.values.forEach(history::put)
        result["history"] = history.toString()
        val provenance = linkedSetOf<String>()
        for (source in listOf(left, right)) {
            (source["restored_legacy_provenance"] as? String)?.let { raw ->
                val items = JSONArray(raw)
                for (i in 0 until items.length()) provenance += items.getString(i)
            }
        }
        if (provenance.isNotEmpty()) result["restored_legacy_provenance"] = JSONArray(provenance.sorted()).toString()
        result.takeIf { validSource(it) }
    }.getOrNull()

    internal fun emptyDestination(values: Map<String, *>): Boolean = values.all { (key, value) ->
        key == "history" && value is String && runCatching { JSONArray(value).length() == 0 }.getOrDefault(false)
    }
    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean {
        when (value) {
            is String -> editor.putString(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            else -> return false
        }
        return true
    }

    /** Unknown employer stays unknown; never resolve an old company slot through the new account. */
    internal fun recoveryValues(values: Map<String, *>): Map<String, Any?> {
        val result = values.toMutableMap()
        if (values.containsKey("history")) {
            val history = JSONArray(values["history"] as String)
            for (i in 0 until history.length()) {
                val item = history.getJSONObject(i)
                if (!item.has("employerId")) item.put("employerId", JSONObject.NULL)
            }
            result["history"] = history.toString()
        }
        return result
    }

    internal fun validSource(values: Map<String, *>): Boolean = runCatching {
        val mapping = mapOf("session_id" to "id", "real_entry" to "realEntry", "counted_entry" to "countedEntry",
            "real_exit" to "realExit", "counted_exit" to "countedExit", "employer_id" to "employerId",
            "company_slot" to "companySlot", "place_id" to "placeId", "place_label" to "placeLabel", "time_basis" to "timeBasis")
        val allowed = mapping.keys + setOf("history", "expected_end", "pauses", "pause_start", "pause_source", "pause_paid", "restored_legacy_provenance")
        if (values.isEmpty() || values.keys.any { it !in allowed }) return false
        if (values.containsKey("restored_legacy_provenance")) {
            val raw = values["restored_legacy_provenance"] as? String ?: return false
            val items = JSONArray(raw)
            if ((0 until items.length()).any { items.opt(it) !is String }) return false
        }
        val history = if (values.containsKey("history")) JSONArray(values["history"] as? String ?: return false) else JSONArray()
        if (!V2RuntimeHistoryGuardV2.inspect(history).reliable) return false
        if (!values.containsKey("real_entry")) return !V2RuntimeStore.hasOrphanedCurrentSession(values)
        if (values.containsKey("company_slot") && !values.containsKey("employer_id")) return false
        val current = JSONObject()
        mapping.forEach { (key, target) -> if (values.containsKey(key)) current.put(target, values[key]) }
        val entry = values["real_entry"] as? Long ?: return false
        val pauses = JSONArray(values["pauses"] as? String ?: return false)
        current.put("pauses", pauses)
        val expected = V2RuntimeStore.optionalStrictPositive(values, "expected_end")
        if (!expected.valid || expected.value?.let { it <= entry } == true) return false
        val pause = V2RuntimeStore.optionalStrictPositive(values, "pause_start")
        if (!pause.valid) return false
        if (pause.value != null) {
            if (values.containsKey("real_exit") || pause.value < entry ||
                values["pause_source"] !in setOf("MANUAL", "GPS", "SYSTEM", "IMPORT") ||
                (values.containsKey("pause_paid") && values["pause_paid"] !is Boolean)) return false
        } else if (values.containsKey("pause_source") || values.containsKey("pause_paid")) return false
        if (!V2RuntimeHistoryGuardV2.inspect(JSONArray().put(current)).reliable) return false
        if (pause.value != null && (0 until pauses.length()).any { pauses.getJSONObject(it).getLong("end") > pause.value }) return false
        val same = (0 until history.length()).map { history.getJSONObject(it) }.filter { it.optString("id") == current.optString("id") }
        if (same.isNotEmpty()) {
            if (same.size != 1 || !current.has("realExit")) return false
            val archived = same.single()
            if (current.keys().asSequence().any { key -> current.get(key).toString() != archived.opt(key)?.toString() }) return false
            true
        } else V2RuntimeHistoryGuardV2.inspect(JSONArray(history.toString()).put(current)).reliable
    }.getOrDefault(false)
}
