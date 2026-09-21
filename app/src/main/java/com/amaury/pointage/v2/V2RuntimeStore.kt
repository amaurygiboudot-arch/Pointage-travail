package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.WidgetLocationExpiryScheduler
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stockage runtime V2. Les anciennes données restent séparées et sont migrées en lecture seule. */
object V2RuntimeStore {
    private const val PREFS = "horatrack_v2_test_runtime"
    private const val KEY_ID = "session_id"
    private const val KEY_EMPLOYER_ID = "employer_id"
    private const val KEY_COMPANY_SLOT = "company_slot"
    private const val KEY_REAL_ENTRY = "real_entry"
    private const val KEY_COUNTED_ENTRY = "counted_entry"
    private const val KEY_REAL_EXIT = "real_exit"
    private const val KEY_COUNTED_EXIT = "counted_exit"
    private const val KEY_EXPECTED_END = "expected_end"
    private const val KEY_PAUSE_START = "pause_start"
    private const val KEY_PAUSE_SOURCE = "pause_source"
    private const val KEY_PAUSE_PAID = "pause_paid"
    private const val KEY_PAUSES = "pauses"
    private const val KEY_PLACE_ID = "place_id"
    private const val KEY_PLACE_LABEL = "place_label"
    private const val KEY_HISTORY = "history"
    private const val CURRENT_RUNTIME_WARNING =
        "Session de pointage V2 courante illisible ou incohérente : la chronologie complète doit être vérifiée avant tout calcul."
    private const val RUNTIME_PARSE_WARNING =
        "Historique de pointage V2 validé mais impossible à reconstruire sans perte : calcul bloqué."

    @Volatile private var boundContext: Context? = null

    data class Snapshot(
        val session: WorkSessionV2?,
        val result: com.amaury.pointage.v2.engine.TimeResultV2?
    )

    internal data class OptionalPositiveRead(
        val valid: Boolean,
        val value: Long?
    )

    private data class WriteRead(
        val reliable: Boolean,
        val session: WorkSessionV2?
    )

    fun bind(context: Context) {
        boundContext = context.applicationContext
        V2ProfileStore.bind(context)
    }

    fun snapshotBound(nowMs: Long = System.currentTimeMillis()): Snapshot? = boundContext?.let { snapshot(it, nowMs) }
    fun allSessionsBound(nowMs: Long = System.currentTimeMillis()): List<WorkSessionV2> = boundContext?.let { allSessions(it, nowMs) }.orEmpty()

    /**
     * Prélecture commune à toute mutation du runtime.
     * Une écriture est refusée si la migration, l'historique ou la session courante ne peuvent pas
     * être relus sans perte. Cela évite qu'une nouvelle action utilisateur écrase une corruption.
     */
    private fun readForWrite(context: Context, nowMs: Long = System.currentTimeMillis()): WriteRead {
        bind(context)
        val migration = V2MigrationManager.ensureMigrated(context)
        if (!migration.reliable) {
            V2RuntimeHistoryGuardV2.publishSourceState(false, migration.warnings)
            return WriteRead(false, null)
        }
        val stored = V2RuntimeHistoryGuardV2.read(context)
        if (!stored.reliable) return WriteRead(false, null)
        val current = snapshot(context, nowMs)
        val source = V2RuntimeHistoryGuardV2.sourceState()
        return WriteRead(source.reliable, if (source.reliable) current.session else null)
    }

    @Synchronized
    fun entry(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        expectedEndMs: Long? = null,
        companySlot: Int? = null
    ): Boolean {
        if (nowMs <= 0L) return false
        val current = readForWrite(context, nowMs)
        if (!current.reliable || current.session?.status == SessionStatusV2.OPEN) return false
        val prefs = prefs(context)

        // Les anciens appels peuvent encore imposer un slot 1/2. Le flux normal utilise désormais
        // l'identifiant stable de l'entreprise active, ce qui permet plus de deux entreprises.
        val profile = if (companySlot != null) {
            val slot = companySlot.coerceIn(1, 2)
            V2ProfileStore.setActiveCompanySlot(context, slot)
            V2ProfileStore.load(context, slot)
        } else {
            V2ProfileStore.loadActive(context)
        }
        val employerId = profile.employer?.id
        val legacySlot = profile.companySlot.takeIf { it in 1..2 }
        val knownExpected = expectedEndMs?.takeIf { it > nowMs }
            ?: V2ScheduleStore.expectedEndForEntry(context, nowMs)?.takeIf { it > nowMs }

        val editor = prefs.edit()
            .remove(KEY_ID).remove(KEY_EMPLOYER_ID).remove(KEY_COMPANY_SLOT)
            .remove(KEY_REAL_ENTRY).remove(KEY_COUNTED_ENTRY)
            .remove(KEY_REAL_EXIT).remove(KEY_COUNTED_EXIT).remove(KEY_EXPECTED_END)
            .remove(KEY_PAUSE_START).remove(KEY_PAUSE_SOURCE).remove(KEY_PAUSE_PAID).remove(KEY_PAUSES)
            .remove(KEY_PLACE_ID).remove(KEY_PLACE_LABEL)
            .putString(KEY_ID, UUID.randomUUID().toString())
            .putLong(KEY_REAL_ENTRY, nowMs)
            .putLong(KEY_COUNTED_ENTRY, HoraTrackV2.time.countedEntryFromRealArrival(nowMs))
            .putString(KEY_PAUSES, "[]")
        legacySlot?.let { editor.putInt(KEY_COMPANY_SLOT, it) }
        employerId?.let { editor.putString(KEY_EMPLOYER_ID, it) }
        knownExpected?.let { editor.putLong(KEY_EXPECTED_END, it) }
        return editor.commit()
    }

    @Synchronized
    fun setExpectedEnd(context: Context, expectedEndMs: Long?): Boolean {
        val current = readForWrite(context)
        val session = current.session ?: return false
        if (!current.reliable || session.status != SessionStatusV2.OPEN) return false
        val entry = session.realArrivalMs ?: return false
        val prefs = prefs(context)
        val editor = prefs.edit()
        if (expectedEndMs == null) {
            return editor.remove(KEY_EXPECTED_END).commit()
        }
        if (expectedEndMs <= entry) return false
        return editor.putLong(KEY_EXPECTED_END, expectedEndMs).commit()
    }

    fun expectedEnd(context: Context): Long? = safeLong(prefs(context).all[KEY_EXPECTED_END]).takeIf { it > 0L }

    @Synchronized
    fun togglePause(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        source: EventSourceV2 = EventSourceV2.MANUAL,
        paid: Boolean? = null
    ): Boolean {
        val current = readForWrite(context, nowMs)
        val session = current.session ?: return false
        if (!current.reliable || session.status != SessionStatusV2.OPEN) return false
        val entry = session.realArrivalMs ?: return false
        if (nowMs < entry) return false
        val prefs = prefs(context)
        val start = safeLong(prefs.all[KEY_PAUSE_START])
        if (start <= 0L) {
            // Le canal de saisie ne permet jamais de déduire si une pause est payée.
            // Une nouvelle pause doit donc être explicitement qualifiée avant toute écriture.
            val openingPaid = paid ?: return false
            return prefs.edit()
                .putLong(KEY_PAUSE_START, nowMs)
                .putString(KEY_PAUSE_SOURCE, source.name)
                .putBoolean(KEY_PAUSE_PAID, openingPaid)
                .commit()
        }
        val storedSource = parseSourceOrNull(prefs.getString(KEY_PAUSE_SOURCE, null)) ?: return false
        val hasStoredPaid = prefs.contains(KEY_PAUSE_PAID)
        val storedPaid = if (hasStoredPaid) strictBoolean(prefs.all[KEY_PAUSE_PAID]) ?: return false else false
        val resolvedPaid = RuntimePauseIntegrityV2.paidForClose(hasStoredPaid, storedPaid, paid) ?: return false
        val updated = appendPause(prefs.getString(KEY_PAUSES, "[]").orEmpty(), start, nowMs, storedSource, resolvedPaid)
            ?: return false
        return prefs.edit()
            .putString(KEY_PAUSES, updated)
            .remove(KEY_PAUSE_START)
            .remove(KEY_PAUSE_SOURCE)
            .remove(KEY_PAUSE_PAID)
            .commit()
    }

    /**
     * Ajoute des pauses manuelles uniquement après qualification explicite du statut payé.
     * Une plage invalide ou hors session bloque l'ensemble du lot.
     */
    @Synchronized
    fun addQualifiedManualPauses(context: Context, pauses: List<QualifiedManualPauseV2>): Int {
        val qualified = ManualPauseQualificationV2.qualify(
            pauses.map { ManualPauseDraftV2(it.startMs, it.endMs, it.paid) }
        ) ?: return 0
        if (qualified.isEmpty()) return 0

        val current = readForWrite(context)
        val session = current.session ?: return 0
        if (!current.reliable) return 0
        val entry = session.realArrivalMs ?: return 0
        val realExit = session.realExitMs
        if (qualified.any { pause ->
                pause.startMs < entry || (realExit != null && pause.endMs > realExit)
            }) return 0

        val prefs = prefs(context)
        var raw = prefs.getString(KEY_PAUSES, "[]").orEmpty()
        if (pauseArrayOrNull(raw) == null) return 0
        var added = 0

        for (pause in qualified) {
            val before = pauseArrayOrNull(raw)?.length() ?: return 0
            val next = appendPause(
                raw,
                pause.startMs,
                pause.endMs,
                EventSourceV2.MANUAL,
                pause.paid
            ) ?: return 0
            val after = pauseArrayOrNull(next)?.length() ?: return 0
            raw = next
            if (after > before) added++
        }

        if (added > 0 && !prefs.edit().putString(KEY_PAUSES, raw).commit()) return 0
        return added
    }

    /**
     * Ancienne API sans statut payé. Elle reste uniquement pour détecter les appels résiduels :
     * en V2, une pause non qualifiée est refusée plutôt que transformée en pause non payée.
     */
    @Deprecated("Utiliser addQualifiedManualPauses avec un statut payé explicite")
    fun addManualPauses(context: Context, ranges: List<Pair<Long, Long>>): Int = 0

    /**
     * Pauses réellement éditables par l'utilisateur.
     *
     * Toutes les pauses MANUAL restent éditables, qu'elles soient payées ou non. Les anciennes
     * pauses SYSTEM ne restent éditables que lorsqu'elles sont explicitement non payées.
     * Un stockage non fiable renvoie null, jamais une fausse liste vide.
     */
    fun editablePausesForDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long
    ): List<QualifiedManualPauseV2>? {
        if (dayStart <= 0L || dayEnd <= dayStart) return null
        val sessions = allSessions(context)
        if (!V2RuntimeHistoryGuardV2.sourceState().reliable) return null

        return sessions
            .flatMap { it.pauses }
            .filter { pause ->
                val paid = pause.paid ?: return@filter false
                pause.source == EventSourceV2.MANUAL ||
                    (pause.source == EventSourceV2.SYSTEM && !paid)
            }
            .mapNotNull { pause ->
                val end = pause.endMs ?: return@mapNotNull null
                val paid = pause.paid ?: return@mapNotNull null
                if (pause.startMs in dayStart until dayEnd && end > pause.startMs) {
                    QualifiedManualPauseV2(pause.startMs, end, paid)
                } else {
                    null
                }
            }
            .distinct()
            .sortedBy { it.startMs }
    }

    @Deprecated("Utiliser editablePausesForDay afin de conserver le statut payé")
    fun editablePauseRangesForDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long
    ): List<Pair<Long, Long>> =
        editablePausesForDay(context, dayStart, dayEnd)
            ?.filterNot { it.paid }
            ?.map { it.startMs to it.endMs }
            .orEmpty()

    /**
     * Remplace atomiquement les pauses éditables d'une journée.
     * Le statut payé fait partie du fait enregistré et doit être fourni pour chaque plage.
     */
    @Synchronized
    fun replaceQualifiedEditablePausesForDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long,
        pauses: List<QualifiedManualPauseV2>
    ): Boolean {
        bind(context)
        val migration = V2MigrationManager.ensureMigrated(context)
        if (!migration.reliable) return false
        if (dayStart <= 0L || dayEnd <= dayStart) return false

        val clean = ManualPauseQualificationV2.qualify(
            pauses.map { ManualPauseDraftV2(it.startMs, it.endMs, it.paid) }
        ) ?: return false
        if (clean.any { pause ->
                pause.startMs !in dayStart until dayEnd || pause.endMs > dayEnd
            }) return false

        val p = prefs(context)
        val storedHistory = V2RuntimeHistoryGuardV2.read(context)
        if (!storedHistory.reliable) return false
        val currentSnapshot = snapshot(context)
        if (!V2RuntimeHistoryGuardV2.sourceState().reliable) return false
        val history = storedHistory.history
        val currentEntry = currentSnapshot.session?.realArrivalMs ?: 0L
        val currentExit = currentSnapshot.session?.realExitMs
        val currentExpectedEnd = safeLong(p.all[KEY_EXPECTED_END]).takeIf { currentEntry > 0L && it > currentEntry }
        val currentId = currentSnapshot.session?.id

        data class Target(val historyIndex: Int? = null, val current: Boolean = false)

        fun historyContains(o: JSONObject, start: Long, end: Long): Boolean {
            val entry = positive(o, "realEntry") ?: return false
            val exit = positive(o, "realExit") ?: return false
            return start >= entry && end <= exit
        }

        fun currentContains(start: Long, end: Long): Boolean {
            if (currentEntry <= 0L || start < currentEntry) return false
            val limit = currentExit ?: currentExpectedEnd ?: dayEnd
            return end <= limit
        }

        val targets = mutableListOf<Target>()
        for (pause in clean) {
            var found: Target? = null
            for (i in 0 until history.length()) {
                val sessionItem = history.optJSONObject(i) ?: return false
                if (historyContains(sessionItem, pause.startMs, pause.endMs)) {
                    found = Target(historyIndex = i)
                    break
                }
            }
            if (found == null && currentContains(pause.startMs, pause.endMs)) found = Target(current = true)
            if (found == null) return false
            targets += found
        }

        fun filtered(raw: JSONArray): JSONArray? {
            if (!V2RuntimeHistoryGuardV2.validPauseArray(raw)) return null
            val filtered = JSONArray()
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: return null
                val start = positive(item, "start") ?: return null
                val source = parseSourceOrNull(item.optString("source")) ?: return null
                val paid = strictBoolean(item.opt("paid")) ?: return null
                val editable = start in dayStart until dayEnd &&
                    (source == EventSourceV2.MANUAL || (source == EventSourceV2.SYSTEM && !paid))
                if (!editable) filtered.put(item)
            }
            return filtered
        }

        for (i in 0 until history.length()) {
            val sessionItem = history.optJSONObject(i) ?: return false
            val storedPauses = sessionItem.optJSONArray(KEY_PAUSES) ?: return false
            sessionItem.put(KEY_PAUSES, filtered(storedPauses) ?: return false)
        }

        val rawCurrentPauses = p.getString(KEY_PAUSES, "[]").orEmpty()
        var currentPauses = filtered(pauseArrayOrNull(rawCurrentPauses) ?: return false) ?: return false

        clean.zip(targets).forEach { (pause, target) ->
            val item = JSONObject()
                .put("start", pause.startMs)
                .put("end", pause.endMs)
                .put("source", EventSourceV2.MANUAL.name)
                .put("paid", pause.paid)
            when {
                target.historyIndex != null -> {
                    val sessionItem = history.optJSONObject(target.historyIndex) ?: return false
                    val storedPauses = sessionItem.optJSONArray(KEY_PAUSES) ?: return false
                    storedPauses.put(item)
                }
                target.current -> currentPauses.put(item)
            }
        }

        // Une session clôturée reste aussi dans les clés runtime courantes. On la garde synchronisée
        // avec sa copie d'historique afin qu'une modification ne réapparaisse pas après redémarrage.
        if (!currentId.isNullOrBlank() && currentEntry > 0L) {
            for (i in 0 until history.length()) {
                val sessionItem = history.optJSONObject(i) ?: return false
                if (sessionItem.optString("id") == currentId) {
                    currentPauses = sessionItem.optJSONArray(KEY_PAUSES) ?: return false
                    break
                }
            }
        }

        if (!V2RuntimeHistoryGuardV2.inspect(history).reliable) return false
        if (!V2RuntimeHistoryGuardV2.validPauseArray(currentPauses)) return false
        return p.edit()
            .putString(KEY_HISTORY, history.toString())
            .putString(KEY_PAUSES, currentPauses.toString())
            .commit()
    }

    @Deprecated("Utiliser replaceQualifiedEditablePausesForDay avec un statut payé explicite")
    fun replaceEditablePausesForDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long,
        ranges: List<Pair<Long, Long>>
    ): Boolean = false

    @Synchronized
    fun exit(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        expectedEndMs: Long? = null
    ): Boolean {
        val current = readForWrite(context, nowMs)
        val session = current.session ?: return false
        if (!current.reliable || session.status != SessionStatusV2.OPEN) return false
        val entry = session.realArrivalMs ?: return false
        if (nowMs <= entry) return false
        val prefs = prefs(context)
        val pauseStart = safeLong(prefs.all[KEY_PAUSE_START])
        var pauses = prefs.getString(KEY_PAUSES, "[]").orEmpty()
        if (pauseArrayOrNull(pauses) == null) return false
        if (pauseStart > 0L) {
            val source = parseSourceOrNull(prefs.getString(KEY_PAUSE_SOURCE, null)) ?: return false
            // Une sortie ne doit jamais transformer une pause de statut inconnu en non payée.
            if (!prefs.contains(KEY_PAUSE_PAID)) return false
            val paid = strictBoolean(prefs.all[KEY_PAUSE_PAID]) ?: return false
            pauses = appendPause(pauses, pauseStart, nowMs, source, paid) ?: return false
        }

        val knownExpectedEnd = expectedEndMs
            ?: safeLong(prefs.all[KEY_EXPECTED_END]).takeIf { it > 0L }
            ?: V2ScheduleStore.expectedEnd(context, entry, nowMs)
        val countedExit = HoraTrackV2.time.countedExitFromRealExit(nowMs, knownExpectedEnd)
        val closedPauses = pauseArrayOrNull(pauses)?.let(::parsePauseArray) ?: return false
        val closedSession = session.copy(
            countedExitMs = countedExit,
            realExitMs = nowMs,
            pauses = closedPauses,
            status = SessionStatusV2.CLOSED
        )
        val storedHistory = V2RuntimeHistoryGuardV2.read(context)
        if (!storedHistory.reliable) return false
        val legacySlot = safeInt(prefs.all[KEY_COMPANY_SLOT], 0).takeIf { it in 1..2 }
        val history = historyWithClosedSession(storedHistory.history, closedSession, legacySlot) ?: return false

        // La fermeture runtime et l'ajout Historique partagent le même SharedPreferences : un seul
        // commit garantit qu'une sortie ne peut jamais être CLOSED sans sa copie historique durable.
        val closed = prefs.edit()
            .putString(KEY_HISTORY, history.toString())
            .putString(KEY_PAUSES, pauses)
            .remove(KEY_PAUSE_START)
            .remove(KEY_PAUSE_SOURCE)
            .remove(KEY_PAUSE_PAID)
            .putLong(KEY_REAL_EXIT, nowMs)
            .putLong(KEY_COUNTED_EXIT, countedExit)
            .commit()
        if (!closed) {
            V2RuntimeHistoryGuardV2.publishSourceState(
                false,
                listOf("Sortie V2 non enregistrée : runtime et historique sont restés inchangés.")
            )
            return false
        }
        V2RuntimeHistoryGuardV2.publishSourceState(true)
        WidgetLocationExpiryScheduler.schedule(context, nowMs)
        return true
    }

    /** Réservé aux tests isolés. Aucun écran utilisateur ne doit appeler ce reset. */
    @Synchronized
    fun reset(context: Context) {
        bind(context)
        prefs(context).edit().clear().commit()
    }

    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis()): Snapshot {
        bind(context)
        val prefs = prefs(context)
        val values = prefs.all
        if (!prefs.contains(KEY_REAL_ENTRY)) return Snapshot(null, null)
        val realEntry = strictPositive(values[KEY_REAL_ENTRY]) ?: return corruptCurrentSnapshot()

        val realExitRead = optionalStrictPositive(values, KEY_REAL_EXIT)
        if (!realExitRead.valid) return corruptCurrentSnapshot()
        val realExit = realExitRead.value
        if (realExit != null && realExit <= realEntry) return corruptCurrentSnapshot()

        val countedEntryRead = optionalStrictPositive(values, KEY_COUNTED_ENTRY)
        if (!countedEntryRead.valid) return corruptCurrentSnapshot()
        val countedEntry = countedEntryRead.value

        val countedExitRead = optionalStrictPositive(values, KEY_COUNTED_EXIT)
        if (!countedExitRead.valid) return corruptCurrentSnapshot()
        val countedExit = countedExitRead.value
        if (countedEntry != null && countedExit != null && countedExit <= countedEntry) return corruptCurrentSnapshot()

        val expectedEndRead = optionalStrictPositive(values, KEY_EXPECTED_END)
        if (!expectedEndRead.valid) return corruptCurrentSnapshot()
        val expectedEnd = expectedEndRead.value
        if (expectedEnd != null && expectedEnd <= realEntry) return corruptCurrentSnapshot()

        val id = runCatching { prefs.getString(KEY_ID, null) }.getOrNull()?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return corruptCurrentSnapshot()

        val rawPauses = runCatching { prefs.getString(KEY_PAUSES, null) }.getOrNull()
            ?: return corruptCurrentSnapshot()
        val pauseArray = pauseArrayOrNull(rawPauses) ?: return corruptCurrentSnapshot()
        val pauses = parsePauseArray(pauseArray)?.toMutableList() ?: return corruptCurrentSnapshot()

        val pauseStartRead = optionalStrictPositive(values, KEY_PAUSE_START)
        if (!pauseStartRead.valid) return corruptCurrentSnapshot()
        val pauseStart = pauseStartRead.value
        val storedPauseSource = runCatching { prefs.getString(KEY_PAUSE_SOURCE, null) }.getOrNull()
        if (pauseStart != null) {
            if (realExit != null || pauseStart < realEntry) return corruptCurrentSnapshot()
            val source = parseSourceOrNull(storedPauseSource) ?: return corruptCurrentSnapshot()
            val paid = if (prefs.contains(KEY_PAUSE_PAID)) {
                strictBoolean(values[KEY_PAUSE_PAID]) ?: return corruptCurrentSnapshot()
            } else {
                // Compatibilité fail-closed pour une pause restée ouverte pendant la mise à jour :
                // on conserve l'inconnu afin que le moteur marque le résultat non fiable.
                null
            }
            pauses += PauseV2(pauseStart, null, paid = paid, source = source)
        } else if (prefs.contains(KEY_PAUSE_SOURCE) || prefs.contains(KEY_PAUSE_PAID)) {
            return corruptCurrentSnapshot()
        }

        val storedSlot = if (prefs.contains(KEY_COMPANY_SLOT)) {
            val slot = strictInt(values[KEY_COMPANY_SLOT]) ?: return corruptCurrentSnapshot()
            slot.takeIf { it in 1..2 } ?: return corruptCurrentSnapshot()
        } else null

        fun optionalStoredString(key: String): String? {
            if (!prefs.contains(key)) return null
            return runCatching { prefs.getString(key, null) }.getOrNull()?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }
        }

        val directEmployerId = optionalStoredString(KEY_EMPLOYER_ID)
        if (prefs.contains(KEY_EMPLOYER_ID) && directEmployerId == null) return corruptCurrentSnapshot()
        val employerId = directEmployerId
            ?: storedSlot?.let { V2ProfileStore.load(context, it).employer?.id }
            ?: V2ProfileStore.loadActive(context).employer?.id
        val placeId = optionalStoredString(KEY_PLACE_ID)
        if (prefs.contains(KEY_PLACE_ID) && placeId == null) return corruptCurrentSnapshot()
        val placeLabel = optionalStoredString(KEY_PLACE_LABEL)
        if (prefs.contains(KEY_PLACE_LABEL) && placeLabel == null) return corruptCurrentSnapshot()

        if (pauses.any { pause ->
                val end = pause.endMs ?: return@any false
                pause.startMs < realEntry || (realExit != null && end > realExit)
            }) return corruptCurrentSnapshot()

        val session = WorkSessionV2(
            id = id,
            employerId = employerId,
            realArrivalMs = realEntry,
            countedEntryMs = countedEntry,
            countedExitMs = countedExit,
            realExitMs = realExit,
            pauses = pauses,
            status = if (realExit == null) SessionStatusV2.OPEN else SessionStatusV2.CLOSED,
            placeId = placeId,
            placeLabel = placeLabel
        )
        return Snapshot(session, HoraTrackV2.time.calculate(session, nowMs))
    }

    fun allSessions(context: Context, nowMs: Long = System.currentTimeMillis()): List<WorkSessionV2> {
        bind(context)
        val migration = V2MigrationManager.ensureMigrated(context)
        if (!migration.reliable) {
            V2RuntimeHistoryGuardV2.publishSourceState(false, migration.warnings)
            return emptyList()
        }
        val stored = V2RuntimeHistoryGuardV2.read(context)
        if (!stored.reliable) return emptyList()
        val parsed = parseHistory(context, stored.history)
        if (parsed == null) {
            V2RuntimeHistoryGuardV2.publishSourceState(false, listOf(RUNTIME_PARSE_WARNING))
            return emptyList()
        }
        val history = parsed.toMutableList()
        val current = snapshot(context, nowMs).session
        if (!V2RuntimeHistoryGuardV2.sourceState().reliable) return emptyList()
        if (current != null && history.none { it.id == current.id }) history += current
        return history.distinctBy { it.id }.sortedBy { it.realArrivalMs ?: Long.MAX_VALUE }
    }

    internal fun historyWithClosedSession(
        sourceHistory: JSONArray,
        session: WorkSessionV2,
        companySlot: Int?
    ): JSONArray? {
        if (session.status != SessionStatusV2.CLOSED || session.realArrivalMs == null || session.realExitMs == null) return null
        if (session.pauses.any { pause ->
                val end = pause.endMs ?: return@any true
                end <= pause.startMs || pause.paid == null
            }) return null
        if (!V2RuntimeHistoryGuardV2.inspect(sourceHistory).reliable) return null
        val history = runCatching { JSONArray(sourceHistory.toString()) }.getOrNull() ?: return null
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: return null
            if (item.optString("id") == session.id) return null
        }
        history.put(sessionToJson(session, companySlot))
        return history.takeIf { V2RuntimeHistoryGuardV2.inspect(it).reliable }
    }

    private fun sessionToJson(session: WorkSessionV2, companySlot: Int?) = JSONObject()
        .put("id", session.id)
        .put("employerId", session.employerId ?: JSONObject.NULL)
        .apply { companySlot?.let { put("companySlot", it) } }
        .put("realEntry", session.realArrivalMs ?: JSONObject.NULL)
        .put("countedEntry", session.countedEntryMs ?: JSONObject.NULL)
        .put("realExit", session.realExitMs ?: JSONObject.NULL)
        .put("countedExit", session.countedExitMs ?: JSONObject.NULL)
        .put("placeId", session.placeId ?: JSONObject.NULL)
        .put("placeLabel", session.placeLabel ?: JSONObject.NULL)
        .put("legacyFixedUnpaidPauseMs", session.legacyFixedUnpaidPauseMs)
        .put(KEY_PAUSES, pausesToJson(session.pauses))

    private fun parseHistory(context: Context, array: JSONArray): List<WorkSessionV2>? = runCatching {
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val realEntry = positive(o, "realEntry") ?: error("realEntry invalide")
                val realExit = positive(o, "realExit")
                val employerSource = historyEmployerSource(o)
                val employerId = if (employerSource.useLegacyProfile) {
                    V2ProfileStore.load(context, employerSource.legacySlot ?: 1).employer?.id
                } else {
                    employerSource.employerId
                }
                val placeId = o.optString("placeId").trim().takeIf { it.isNotBlank() && it != "null" }
                val placeLabel = o.optString("placeLabel").trim().takeIf { it.isNotBlank() && it != "null" }
                val pauses = parsePauseArray(o.getJSONArray(KEY_PAUSES)) ?: error("pauses invalides")
                val id = o.getString("id").trim().takeIf { it.isNotBlank() && it != "null" }
                    ?: error("id invalide")
                add(
                    WorkSessionV2(
                        id = id,
                        employerId = employerId,
                        realArrivalMs = realEntry,
                        countedEntryMs = positive(o, "countedEntry"),
                        countedExitMs = positive(o, "countedExit"),
                        realExitMs = realExit,
                        pauses = pauses,
                        status = if (realExit == null) SessionStatusV2.OPEN else SessionStatusV2.CLOSED,
                        placeId = placeId,
                        placeLabel = placeLabel,
                        legacyFixedUnpaidPauseMs = positiveOrZero(o, "legacyFixedUnpaidPauseMs")
                    )
                )
            }
        }
    }.getOrNull()

    internal data class HistoryEmployerSource(
        val employerId: String?,
        val legacySlot: Int?,
        val useLegacyProfile: Boolean
    )

    internal fun historyEmployerSource(item: JSONObject): HistoryEmployerSource {
        val slot = if (item.has("companySlot") && !item.isNull("companySlot")) {
            strictInt(item.opt("companySlot"))?.takeIf { it in 1..2 }
                ?: error("companySlot invalide")
        } else {
            null
        }
        if (item.has("employerId")) {
            if (item.isNull("employerId")) {
                return if (slot != null) {
                    HistoryEmployerSource(null, slot, useLegacyProfile = true)
                } else {
                    HistoryEmployerSource(null, null, useLegacyProfile = false)
                }
            }
            val employerId = item.getString("employerId").trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?: error("employerId invalide")
            return HistoryEmployerSource(employerId, slot, useLegacyProfile = false)
        }
        return HistoryEmployerSource(null, slot ?: 1, useLegacyProfile = true)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun appendPause(raw: String, start: Long, end: Long, source: EventSourceV2, paid: Boolean): String? {
        val array = pauseArrayOrNull(raw) ?: return null
        if (end <= start) return null
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: return null
            if (o.optLong("start") == start && o.optLong("end") == end && o.optString("source") == source.name && o.optBoolean("paid") == paid) {
                return array.toString()
            }
        }
        array.put(JSONObject().put("start", start).put("end", end).put("source", source.name).put("paid", paid))
        return array.toString().takeIf { V2RuntimeHistoryGuardV2.validPauseArray(array) }
    }

    private fun pauseArrayOrNull(raw: String): JSONArray? {
        if (raw.isBlank()) return null
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return array.takeIf { V2RuntimeHistoryGuardV2.validPauseArray(it) }
    }

    private fun pausesToJson(pauses: List<PauseV2>) = JSONArray().apply {
        pauses.filter { it.endMs != null && it.endMs!! > it.startMs }.forEach { p ->
            put(JSONObject().put("start", p.startMs).put("end", p.endMs).put("source", p.source.name).put("paid", p.paid))
        }
    }

    private fun parsePauseArray(array: JSONArray): List<PauseV2>? {
        if (!V2RuntimeHistoryGuardV2.validPauseArray(array)) return null
        return runCatching {
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val start = positive(item, "start") ?: error("pause start invalide")
                    val end = positive(item, "end") ?: error("pause end invalide")
                    val source = parseSourceOrNull(item.getString("source")) ?: error("pause source invalide")
                    add(
                        PauseV2(
                            startMs = start,
                            endMs = end,
                            paid = item.getBoolean("paid"),
                            source = source
                        )
                    )
                }
            }
        }.getOrNull()
    }

    private fun parsePauses(raw: String): List<PauseV2> =
        pauseArrayOrNull(raw)?.let(::parsePauseArray).orEmpty()

    private fun parseSource(raw: String?): EventSourceV2 = parseSourceOrNull(raw) ?: EventSourceV2.MANUAL

    private fun parseSourceOrNull(raw: String?): EventSourceV2? = runCatching {
        EventSourceV2.valueOf(raw.orEmpty())
    }.getOrNull()

    private fun corruptCurrentSnapshot(): Snapshot {
        V2RuntimeHistoryGuardV2.publishSourceState(false, listOf(CURRENT_RUNTIME_WARNING))
        return Snapshot(null, null)
    }

    internal fun optionalStrictPositive(values: Map<String, *>, key: String): OptionalPositiveRead {
        if (!values.containsKey(key)) return OptionalPositiveRead(valid = true, value = null)
        val value = strictPositive(values[key])
        return OptionalPositiveRead(valid = value != null, value = value)
    }

    private fun strictPositive(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().takeIf { it > 0L }
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            number.takeIf { it.isFinite() && it % 1.0 == 0.0 && it > 0.0 }?.toLong()
        }
        is String -> value.trim().toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }

    private fun strictBoolean(value: Any?): Boolean? = value as? Boolean

    private fun strictInt(value: Any?): Int? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            number.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
        }
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun safeLong(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun safeInt(value: Any?, fallback: Int): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }

    private fun positive(o: JSONObject, key: String): Long? = when (val value = o.opt(key)) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }

    private fun positiveOrZero(o: JSONObject, key: String): Long = when (val value = o.opt(key)) {
        is Number -> value.toLong().coerceAtLeast(0L)
        is String -> value.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        else -> 0L
    }
}
