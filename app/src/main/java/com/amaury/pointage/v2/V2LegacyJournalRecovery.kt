package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import com.amaury.pointage.v2.LegacyJournalDiagnosticV2.Code
import com.amaury.pointage.v2.LegacyJournalDiagnosticV2.Reference
import com.amaury.pointage.v2.LegacyJournalDiagnosticV2.Source
import com.amaury.pointage.v2.LegacyJournalDiagnosticV2.Trace

/** Explicit device journal ownership declaration. Originals are never changed or deleted. */
object V2LegacyJournalRecovery {
    data class Preview(val scope: String, val sourceFingerprint: String, val destinationFingerprint: String,
                       val eligible: Boolean, val message: String)
    data class Result(val success: Boolean, val message: String)
    private const val BASE = "horatrack_v2_test_runtime"
    private const val CLAIM = "horatrack_v2_legacy_recovery_claim"
    private const val RECEIPT = "legacy_recovery_receipt"
    private val currentHistoryFields = mapOf(
        "session_id" to "id", "real_entry" to "realEntry", "counted_entry" to "countedEntry",
        "real_exit" to "realExit", "counted_exit" to "countedExit", "employer_id" to "employerId",
        "company_slot" to "companySlot", "place_id" to "placeId", "place_label" to "placeLabel",
        "time_basis" to "timeBasis", "closureOrigin" to "closureOrigin",
        "departureObservedAtMs" to "departureObservedAtMs")
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

    internal data class DiagnosticSnapshot(val old: Map<String, *>, val legacy: Map<String, *>,
        val destination: Map<String, *>, val reservation: Map<String, *>) {
        fun fingerprint(): String = V2LegacyJournalRecovery.fingerprint(mapOf(
            "old" to V2LegacyJournalRecovery.fingerprint(old), "legacy" to V2LegacyJournalRecovery.fingerprint(legacy),
            "destination" to V2LegacyJournalRecovery.fingerprint(destination),
            "reservation" to V2LegacyJournalRecovery.fingerprint(reservation)))
    }
    class DiagnosticSession internal constructor(internal val lease: SalaryAccountScope.Lease,
                                                 internal val snapshot: DiagnosticSnapshot)
    private fun diagnosticSnapshot(context: Context, lease: SalaryAccountScope.Lease): DiagnosticSnapshot {
        fun copy(values: Map<String, *>): Map<String, *> = values.mapValues { (_, v) -> if (v is Set<*>) v.toSet() else v }
        // Caller holds withReadOnlySnapshot. Normal guarded reads can recover a pending restore.
        val destination = SalaryAccountScope.rawContext(context).getSharedPreferences(
            requireNotNull(V2RuntimeAccountScope.fileName(BASE, lease.scope)), Context.MODE_PRIVATE)
        return DiagnosticSnapshot(copy(source(context).all), copy(legacy(context).all),
            copy(destination.all), copy(claim(context).all))
    }
    /** Captures an unassigned journal, never a recovery reservation or a write lease. */
    fun beginDiagnostic(context: Context): DiagnosticSession? = V2RuntimeStore.withTransaction {
        runCatching {
            val lease = SalaryAccountScope.capture()
            val scope = lease.scope?.takeIf { it.startsWith("uid:") } ?: return@runCatching null
            if (!SalaryAccountScope.isCurrent(lease) || !SalaryAccountScope.isCurrent(SalaryAccountScope.lease(context))) return@runCatching null
            V2RestoreTransaction.withReadOnlySnapshot(context, scope, { null }) {
                val snapshot = diagnosticSnapshot(context, lease)
                if (snapshot.old.isEmpty() && !snapshot.legacy.containsKey("data")) return@withReadOnlySnapshot null
                if (snapshot.reservation.isNotEmpty() && snapshot.reservation["owner"] != scope) return@withReadOnlySnapshot null
                DiagnosticSession(lease, snapshot).takeIf { diagnosticCurrent(context, it) }
            }
        }.getOrNull()
    }
    fun diagnosticCurrent(context: Context, session: DiagnosticSession): Boolean = V2RuntimeStore.withTransaction {
        runCatching {
            val scope = session.lease.scope ?: return@runCatching false
            SalaryAccountScope.isCurrent(session.lease) && V2RestoreTransaction.withReadOnlySnapshot(context, scope, { false }) {
                session.snapshot.fingerprint() == diagnosticSnapshot(context, session.lease).fingerprint() &&
                    SalaryAccountScope.isCurrent(session.lease)
            }
        }.getOrDefault(false)
    }
    /** UI invalidation only; registering a listener never edits these preferences. */
    fun observeDiagnostic(context: Context, session: DiagnosticSession, changed: () -> Unit): () -> Unit {
        val files = listOf(source(context), legacy(context), claim(context),
            SalaryAccountScope.rawContext(context).getSharedPreferences(
                requireNotNull(V2RuntimeAccountScope.fileName(BASE, session.lease.scope)), Context.MODE_PRIVATE))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        files.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        return { files.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }
    /** Detail is returned only after declaration and two checks of the same immutable snapshot. */
    fun diagnose(context: Context, session: DiagnosticSession, declaredOwnership: Boolean): String = V2RuntimeStore.withTransaction {
        val unavailable = "Consultation indisponible : déclaration, compte ou instantané à vérifier. Aucun transfert effectué."
        if (!declaredOwnership || !diagnosticCurrent(context, session)) return@withTransaction unavailable
        val input = session.snapshot
        val sourceFingerprint = fingerprint(mapOf("v2" to fingerprint(input.old), "v1" to fingerprint(input.legacy)))
        if (input.reservation.isNotEmpty() && input.reservation["source"] != sourceFingerprint)
            return@withTransaction "Contexte de récupération modifié. Les données ne sont pas examinées. Aucun transfert effectué."
        if (input.destination[RECEIPT] == sourceFingerprint)
            return@withTransaction "Une réception de récupération existe déjà. Sa reprise nécessite le contrôle habituel ; aucun transfert effectué."
        val trace = Trace()
        val candidate = runCatching { recoveryCandidate(input.old, input.legacy, input.destination, trace) }.getOrNull()
        if (!diagnosticCurrent(context, session)) return@withTransaction unavailable
        trace.issue?.render() ?: if (candidate != null)
            "Aucun conflit détecté dans cet instantané. La récupération reste une action distincte avec ses propres contrôles. Aucun transfert effectué."
        else "UNVERIFIED — Contrôle interrompu ; cause non détaillée. Aucun transfert effectué."
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
        return recoveryCandidate(source(context).all, legacy(context).all, destination)
    }
    private fun recoveryCandidate(original: Map<String, *>, legacyValues: Map<String, *>, destination: Map<String, *>,
                                  trace: Trace? = null): Map<String, Any?>? {
        trace?.register(original, Source.V2)
        trace?.register(destination, Source.ACCOUNT)
        trace?.stage = LegacyJournalDiagnosticV2.Stage.V1
        val rawV1 = legacyValues["data"]
        val oldV1 = if (rawV1 == null && !legacyValues.containsKey("data")) emptyMap()
            else convertLegacy(rawV1 as? String ?: run {
                trace?.fail(Code.UNREADABLE, Reference(Source.V1), "data"); return null
            }, trace) ?: return null
        val oldV2 = if (original.isEmpty()) emptyMap() else {
            if (!validSource(original, trace)) return null
            recoveryValues(original).also { trace?.alias(it, original) }
        }
        trace?.stage = LegacyJournalDiagnosticV2.Stage.OLD_MERGE
        val combined = merge(oldV2, oldV1, trace) ?: return null
        val cleanDestination = destination.filterKeys { it != RECEIPT }.also { trace?.alias(it, destination) }
        trace?.stage = LegacyJournalDiagnosticV2.Stage.ACCOUNT_MERGE
        return merge(cleanDestination, combined, trace)
    }

    /** Strict conversion supports only proven timestamps and explicit pause qualification. */
    internal fun convertLegacy(raw: String, trace: Trace? = null): Map<String, Any?>? {
        var location = Reference(Source.V1)
        var field = "data"
        fun check(condition: Boolean, code: Code, name: String? = null) {
            if (!condition) { trace?.fail(code, location, name); error("Invalid legacy journal") }
        }
        return runCatching {
        val rows = JSONArray(raw)
        var output: Map<String, Any?> = emptyMap()
        fun number(row: JSONObject, key: String): Long? {
            field = key
            if (!row.has(key) || row.isNull(key)) return null
            return when (val value = row.get(key)) {
                is Int -> value.toLong()
                is Long -> value
                else -> error("unsupported timestamp")
            }.also { check(it > 0L, Code.INVALID_VALUE, key) }
        }
        for (i in 0 until rows.length()) {
            location = Reference(Source.V1, i + 1)
            field = "data"
            val row = rows.getJSONObject(i)
            val allowed = setOf("id", "arrivalTime", "entry", "countedEntryTime", "exitTime", "exit",
                "countedExitTime", "autoPauseMinutes", "pauses", "companySlot", "shiftType", "manual",
                "zoneId", "zoneAddress", "placeLabel", "place", "address")
            check(row.keys().asSequence().all { it in allowed }, Code.UNSUPPORTED_FIELD)
            // Older entry-only timestamps may already be rounded: do not invent real arrival.
            val start = number(row, "arrivalTime") ?: run {
                trace?.fail(Code.REAL_ARRIVAL_MISSING, location, "arrivalTime"); error("real arrival unknown")
            }
            val end = number(row, "exitTime")
            val countedStart = number(row, "countedEntryTime")
            val countedEnd = number(row, "countedExitTime")
            if (number(row, "entry") != null) check(number(row, "entry") == (countedStart ?: start), Code.TIME_CONTRADICTION, "entry")
            if (number(row, "exit") != null) check(number(row, "exit") == (countedEnd ?: end), Code.TIME_CONTRADICTION, "exit")
            field = "autoPauseMinutes"
            val rawPause = row.opt("autoPauseMinutes")
            val base = when (rawPause) { null, JSONObject.NULL -> 0L; is Int -> rawPause.toLong(); is Long -> rawPause; else -> error("pause") }
            check(base >= 0 && base <= Long.MAX_VALUE / 60_000, Code.INVALID_VALUE, field)
            field = "pauses"
            val pauses = row.optJSONArray("pauses") ?: if (!row.has("pauses")) JSONArray() else error("pauses")
            check(base == 0L || pauses.length() == 0, Code.PAUSE_INVALID, field)
            val normalized = JSONArray()
            for (j in 0 until pauses.length()) {
                location = Reference(Source.V1, i + 1, pause = j + 1)
                field = "pauses"
                val pause = pauses.getJSONObject(j)
                check(pause.keys().asSequence().all { it in setOf("start", "end", "paid", "manual", "automatic", "source", "resumedByEntry") }, Code.UNSUPPORTED_FIELD)
                check(pause.opt("paid") is Boolean, Code.PAUSE_UNQUALIFIED, "paid")
                normalized.put(JSONObject().put("start", number(pause, "start") ?: error("pause start"))
                    .put("end", number(pause, "end") ?: run {
                        trace?.fail(Code.OPEN_PAUSE_UNSUPPORTED, location, "end"); error("open pause unsupported")
                    })
                    .put("paid", pause.getBoolean("paid")).put("source", "IMPORT"))
            }
            location = Reference(Source.V1, i + 1)
            field = "id"
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
                check(base == 0L, Code.PAUSE_INVALID, "autoPauseMinutes") // Runtime cannot represent an unresolved fixed deduction.
                mutableMapOf<String, Any?>("session_id" to id, "real_entry" to start,
                    "pauses" to normalized.toString(), "time_basis" to "LEGACY_UNVERIFIED").apply {
                    countedStart?.let { put("counted_entry", it) }
                    item.optString("placeLabel").takeIf { it.isNotBlank() }?.let { put("place_label", it) }
                }
            }
            trace?.register(fragment, LegacyJournalDiagnosticV2.Origins(Source.V1,
                if (end != null) listOf(location) else emptyList(), location))
            output = merge(output, fragment, trace) ?: error("conflicting legacy rows")
        }
        output
        }.onFailure { trace?.fail(Code.UNREADABLE, location, field) }.getOrNull()
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.get(key)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    /** Called only for validated sources. CLOSED alone does not prove that its facts are archived. */
    private fun closedCurrentIsArchived(values: Map<String, *>): Boolean = runCatching {
        if (V2RuntimeStore.optionalStrictPositive(values, "real_exit").value == null) return@runCatching false
        val id = values["session_id"] as? String ?: return@runCatching false
        val history = JSONArray(values["history"] as? String ?: return@runCatching false)
        val archived = (0 until history.length()).map { history.getJSONObject(it) }
            .singleOrNull { it.optString("id") == id } ?: return@runCatching false
        // Absence would resolve through a legacy profile (including default slot 1), unlike explicit null.
        if (!archived.has("employerId")) return@runCatching false
        val currentFacts = JSONObject()
        val archivedFacts = JSONObject()
        currentHistoryFields.forEach { (key, target) ->
            val default = if (target == "timeBasis") "LEGACY_UNVERIFIED" else JSONObject.NULL
            currentFacts.put(target, if (values.containsKey(key)) values[key] else default)
            archivedFacts.put(target, if (archived.has(target)) archived.get(target) else default)
        }
        currentFacts.put("pauses", JSONArray(values["pauses"] as String))
        archivedFacts.put("pauses", archived.getJSONArray("pauses"))
        currentFacts.put("workSegments", JSONArray(values["work_segments"] as? String ?: "[]"))
        archivedFacts.put("workSegments", if (archived.has("workSegments")) archived.getJSONArray("workSegments") else JSONArray())
        currentFacts.put("legacyFixedUnpaidPauseMs", 0L)
        archivedFacts.put("legacyFixedUnpaidPauseMs", if (archived.isNull("legacyFixedUnpaidPauseMs")) 0L else archived.get("legacyFixedUnpaidPauseMs"))
        // expected_end only guides an OPEN session. Its CLOSED counted exit is already frozen;
        // entry replaces this expired planning value. Never copy it onto the other current session.
        // Compare effective defaults too: an absent current basis/segment is not richer archive data.
        canonical(currentFacts) == canonical(archivedFacts)
    }.getOrDefault(false)

    /** Merge disjoint factual journals. Never infer identity from matching timestamps alone. */
    internal fun merge(left: Map<String, *>, right: Map<String, *>, trace: Trace? = null): Map<String, Any?>? = runCatching {
        if (left.isEmpty()) return@runCatching right.toMap().also { trace?.alias(it, right) }.takeIf { it.isEmpty() || validSource(it, trace) }
        if (right.isEmpty()) return@runCatching left.toMap().also { trace?.alias(it, left) }.takeIf { validSource(it, trace) }
        require(validSource(left, trace) && validSource(right, trace))
        val leftCurrent = left.filterKeys { it != "history" && it != "restored_legacy_provenance" }
        val rightCurrent = right.filterKeys { it != "history" && it != "restored_legacy_provenance" }
        val currentSource = when {
            leftCurrent.isEmpty() -> right
            rightCurrent.isEmpty() || fingerprint(leftCurrent) == fingerprint(rightCurrent) -> left
            // Different snapshots of one identity remain a conflict, even if both are closed.
            leftCurrent["session_id"] != rightCurrent["session_id"] && closedCurrentIsArchived(right) -> left
            leftCurrent["session_id"] != rightCurrent["session_id"] && closedCurrentIsArchived(left) -> right
            else -> {
                trace?.fail(Code.CURRENT_CONFLICT, trace.ref(left), second = trace.ref(right))
                error("Conflicting current states")
            }
        }
        val result = (if (currentSource === left) leftCurrent else rightCurrent).toMutableMap()
        val identities = linkedMapOf<String, JSONObject>()
        val references = linkedMapOf<String, Reference>()
        for (source in listOf(left, right)) {
            val history = JSONArray(source["history"] as? String ?: "[]")
            for (i in 0 until history.length()) {
                val item = history.getJSONObject(i)
                val id = item.getString("id")
                val existing = identities[id]
                if (!(existing == null || canonical(existing) == canonical(item))) {
                    trace?.fail(Code.IDENTITY_CONFLICT, references[id] ?: trace.ref(left), "id", trace.ref(source, i + 1))
                    error("Conflicting identity")
                }
                if (trace != null && id !in references) references[id] = trace.ref(source, i + 1)
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
        trace?.register(result, LegacyJournalDiagnosticV2.Origins(Source.MERGE, references.values.toList(),
            trace.ref(currentSource)))
        result.takeIf { validSource(it, trace) }
    }.onFailure { trace?.fail(Code.UNVERIFIED, Reference(Source.MERGE)) }.getOrNull()

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

    internal fun validSource(values: Map<String, *>, trace: Trace? = null): Boolean =
        if (trace == null) validSourceChecked(values, null)
        else trace.reading(trace.origin(values).source) { validSourceChecked(values, trace) }

    private fun validSourceChecked(values: Map<String, *>, trace: Trace?): Boolean {
        var field: String? = null
        fun fail(code: Code = Code.CURRENT_INVALID, name: String? = field): Boolean {
            trace?.fail(code, if (name == null || name in setOf("history", "restored_legacy_provenance"))
                Reference(trace.origin(values).source) else trace.ref(values), name)
            return false
        }
        fun inspect(array: JSONArray, currentOnly: Boolean = false, appendedCurrent: Boolean = false): Boolean {
            val read = V2RuntimeHistoryGuardV2.inspect(array)
            if (!read.reliable && trace != null) {
                fun ref(row: Int) = if (currentOnly || appendedCurrent && row == array.length()) trace.ref(values)
                    else trace.ref(values, row)
                val issue = read.issue
                if (issue != null) trace.fail(issue.code, ref(issue.row).copy(pause = issue.pause, segment = issue.segment),
                    issue.field, issue.otherRow?.let { ref(it).copy(pause = issue.otherPause) })
                else trace.fail(Code.HISTORY_INVALID, Reference(trace.origin(values).source), "history")
            }
            return read.reliable
        }
        return runCatching {
        val mapping = currentHistoryFields
        val allowed = mapping.keys + setOf("history", "expected_end", "pauses", "pause_start", "pause_source", "pause_paid", "work_segments", "restored_legacy_provenance")
        if (values.isEmpty()) return fail()
        if (values.keys.any { it !in allowed }) return fail(Code.UNSUPPORTED_FIELD, null)
        field = "restored_legacy_provenance"
        if (values.containsKey("restored_legacy_provenance")) {
            val raw = values["restored_legacy_provenance"] as? String ?: return fail(Code.UNREADABLE)
            val items = JSONArray(raw)
            if ((0 until items.length()).any { items.opt(it) !is String }) return fail(Code.INVALID_VALUE)
        }
        field = "history"
        val history = if (values.containsKey("history")) JSONArray(values["history"] as? String ?: return fail(Code.UNREADABLE)) else JSONArray()
        if (!inspect(history)) return false
        if (!values.containsKey("real_entry")) return if (V2RuntimeStore.hasOrphanedCurrentSession(values))
            fail(Code.ORPHAN_CURRENT, "real_entry") else true
        if (values.containsKey("company_slot") && !values.containsKey("employer_id")) return fail(Code.CURRENT_INVALID, "employer_id")
        val current = JSONObject()
        mapping.forEach { (key, target) -> if (values.containsKey(key)) current.put(target, values[key]) }
        field = "real_entry"
        val entry = values["real_entry"] as? Long ?: return fail(Code.INVALID_VALUE)
        field = "real_exit"
        val exit = V2RuntimeStore.optionalStrictPositive(values, "real_exit")
        if (!exit.valid) return fail(Code.INVALID_VALUE)
        field = "work_segments"
        if (V2RuntimeStore.currentWorkSegments(values, entry, exit.value) == null) return fail()
        // The sole open TO_CONFIRM segment is a current GPS presence, not malformed history.
        // Its structure was checked above; closed intervals also use the canonical archive guard.
        if (values.containsKey("work_segments") && exit.value != null)
            current.put("workSegments", JSONArray(values["work_segments"] as String))
        field = "closureOrigin"
        if (values.containsKey("closureOrigin") &&
            (values["closureOrigin"] !is String || com.amaury.pointage.ClosureOriginV2.values().none {
                it.name == values["closureOrigin"] })) return fail(Code.INVALID_VALUE)
        field = "departureObservedAtMs"
        val observed = V2RuntimeStore.optionalStrictPositive(values, "departureObservedAtMs")
        if (!observed.valid || (observed.value != null && observed.value != exit.value)) return fail(Code.TIME_CONTRADICTION)
        field = "pauses"
        val pauses = JSONArray(values["pauses"] as? String ?: return fail(Code.UNREADABLE))
        current.put("pauses", pauses)
        field = "expected_end"
        val expected = V2RuntimeStore.optionalStrictPositive(values, "expected_end")
        if (!expected.valid || expected.value?.let { it <= entry } == true) return fail(Code.TIME_CONTRADICTION)
        field = "pause_start"
        val pause = V2RuntimeStore.optionalStrictPositive(values, "pause_start")
        if (!pause.valid) return fail(Code.INVALID_VALUE)
        if (pause.value != null) {
            if (values.containsKey("real_exit")) return fail(Code.PAUSE_INVALID, "real_exit")
            if (pause.value < entry) return fail(Code.PAUSE_INVALID, "pause_start")
            if (values["pause_source"] !in setOf("MANUAL", "GPS", "SYSTEM", "IMPORT")) return fail(Code.PAUSE_INVALID, "pause_source")
            if (values.containsKey("pause_paid") && values["pause_paid"] !is Boolean) return fail(Code.PAUSE_UNQUALIFIED, "pause_paid")
        } else {
            if (values.containsKey("pause_source")) return fail(Code.PAUSE_INVALID, "pause_source")
            if (values.containsKey("pause_paid")) return fail(Code.PAUSE_INVALID, "pause_paid")
        }
        if (!inspect(JSONArray().put(current), currentOnly = true)) return false
        if (pause.value != null && (0 until pauses.length()).any { pauses.getJSONObject(it).getLong("end") > pause.value }) return fail(Code.PAUSE_INVALID)
        field = "session_id"
        val same = (0 until history.length()).map { history.getJSONObject(it) }.filter { it.optString("id") == current.optString("id") }
        if (same.isNotEmpty()) {
            if (same.size != 1 || !current.has("realExit")) {
                trace?.fail(Code.CURRENT_CONFLICT, trace.ref(values), "session_id",
                    trace.ref(values, (0 until history.length()).first { history.getJSONObject(it) === same.first() } + 1))
                return false
            }
            val archived = same.single()
            if (current.keys().asSequence().any { key -> current.get(key).toString() != archived.opt(key)?.toString() }) {
                trace?.fail(Code.CURRENT_CONFLICT, trace.ref(values), "session_id",
                    trace.ref(values, (0 until history.length()).first { history.getJSONObject(it) === archived } + 1))
                return false
            }
            true
        } else inspect(JSONArray(history.toString()).put(current), appendedCurrent = true)
        }.onFailure { fail(Code.UNREADABLE) }.getOrDefault(false)
    }
}
