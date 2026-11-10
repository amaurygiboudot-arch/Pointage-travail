package com.amaury.pointage

import android.content.Context
import android.util.AtomicFile
import com.amaury.pointage.v2.V2RuntimeAccountScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Local, opt-in and excluded from Android backups. Diagnostic failures never change work data. */
internal object DailyDiagnosticStoreV2 {
    private data class State(val enabled: Boolean = false, val events: List<DailyDiagnosticEventV2> = emptyList(), val truncated: Boolean = false, val salt: String = UUID.randomUUID().toString(), val generation: String = UUID.randomUUID().toString())
    data class Read(val enabled: Boolean, val events: List<DailyDiagnosticEventV2>, val truncated: Boolean, val readable: Boolean)
    private val lock = Any()
    private val lost = AtomicBoolean(false)
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(128))
    private data class Consent(val scope: String, val enabled: Boolean, val generation: String)
    @Volatile private var consentCache: Consent? = null
    fun hasKnownWriteGap() = lost.get()
    fun pendingWrites() = worker.queue.size + worker.activeCount
    private fun file(context: Context, scope: String): AtomicFile {
        val name = requireNotNull(V2RuntimeAccountScope.fileName("daily_diagnostic_v2", scope))
        return AtomicFile(File(context.noBackupFilesDir, "$name.json"))
    }
    private fun load(file: AtomicFile): State {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return State()
        require(file.baseFile.length() <= 1_000_000L)
        val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(root.getInt("schema") == 1)
        val array = root.getJSONArray("events")
        require(array.length() <= DailyDiagnosticModelV2.MAX_EVENTS)
        val events = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            DailyDiagnosticEventV2(item.getLong("recorded"), item.getLong("observed"),
                DailyDiagnosticKindV2.valueOf(item.getString("kind")),
                DailyDiagnosticModelV2.safeCode(item.optString("code")),
                if (item.has("value")) item.getLong("value") else null,
                item.optString("zone").takeIf { it.matches(Regex("[a-f0-9]{16}")) })
        }
        return State(root.getBoolean("enabled"), events, root.optBoolean("truncated"), root.getString("salt"), root.getString("generation"))
    }
    private fun save(file: AtomicFile, state: State) {
        val array = JSONArray()
        state.events.forEach { event -> array.put(JSONObject().put("recorded", event.recordedAtMs)
            .put("observed", event.observedAtMs).put("kind", event.kind.name)
            .put("code", DailyDiagnosticModelV2.safeCode(event.code)).put("zone", event.zoneToken).apply { event.value?.let { put("value", it) } }) }
        val bytes = JSONObject().put("schema", 1).put("enabled", state.enabled)
            .put("truncated", state.truncated).put("salt", state.salt).put("generation", state.generation).put("events", array).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private fun purgeExpired(context: Context) {
        val cutoff = System.currentTimeMillis() - DailyDiagnosticModelV2.RETENTION_MS
        context.noBackupFilesDir.listFiles()?.filter {
            it.name.startsWith("daily_diagnostic_v2_account_") && it.name.endsWith(".json") && it.lastModified() < cutoff
        }?.forEach { AtomicFile(it).delete() }
    }
    fun read(context: Context, scope: String): Read = synchronized(lock) {
        if (scope != V2RuntimeAccountScope.currentScope()) return@synchronized Read(false, emptyList(), false, false)
        runCatching {
            purgeExpired(context)
            val storage = file(context, scope)
            val state = load(storage)
            consentCache = Consent(scope, state.enabled, state.generation)
            val retained = DailyDiagnosticModelV2.retain(state.events, System.currentTimeMillis())
            val trimmed = state.copy(events = retained, truncated = state.truncated || retained.size < state.events.size)
            if (trimmed != state) save(storage, trimmed)
            if (scope != V2RuntimeAccountScope.currentScope()) Read(false, emptyList(), false, false)
            else Read(trimmed.enabled, trimmed.events, trimmed.truncated, true)
        }.getOrElse { Read(false, emptyList(), false, false) }
    }
    fun setEnabled(context: Context, scope: String, enabled: Boolean): Boolean = synchronized(lock) {
        if (scope != V2RuntimeAccountScope.currentScope()) return@synchronized false
        runCatching {
            val storage = file(context, scope)
            val state = load(storage)
            val now = System.currentTimeMillis()
            val all = state.events + DailyDiagnosticEventV2(now, now, if (enabled)
                DailyDiagnosticKindV2.RECORDING_ENABLED else DailyDiagnosticKindV2.RECORDING_DISABLED)
            val retained = DailyDiagnosticModelV2.retain(all, now)
            if (scope != V2RuntimeAccountScope.currentScope()) return@runCatching false
            val updated = state.copy(enabled = enabled, events = retained, truncated = state.truncated || retained.size < all.size, generation = UUID.randomUUID().toString())
            save(storage, updated)
            consentCache = Consent(scope, enabled, updated.generation)
            true
        }.getOrDefault(false)
    }
    fun clear(context: Context, scope: String): Boolean = synchronized(lock) {
        if (scope != V2RuntimeAccountScope.currentScope()) return@synchronized false
        runCatching {
            file(context, scope).delete()
            File(context.cacheDir, "daily_diagnostic_exports").listFiles()?.forEach { it.delete() }
            consentCache = null
            true
        }.getOrDefault(false)
    }
    fun record(context: Context, kind: DailyDiagnosticKindV2, observedAtMs: Long = System.currentTimeMillis(), code: String = "", value: Long? = null, zoneId: String? = null) {
        // Respect the runtime's pinned account, including asynchronous GPS delivery operations.
        val scope = V2RuntimeAccountScope.operationScope() ?: return
        if (scope != V2RuntimeAccountScope.currentScope()) return
        val app = context.applicationContext
        val consent = consentCache?.takeIf { it.scope == scope } ?: synchronized(lock) {
            runCatching { load(file(app, scope)).let { Consent(scope, it.enabled, it.generation) } }.getOrNull()?.also { consentCache = it }
        } ?: return
        if (!consent.enabled) return
        val recordedAt = System.currentTimeMillis()
        try {
            worker.execute {
                synchronized(lock) {
                    runCatching {
                        // Captured account only: a queued event is never attributed to the next account.
                        val storage = file(app, scope)
                        val state = load(storage)
                        if (!state.enabled || state.generation != consent.generation) return@runCatching
                        val token = zoneId?.let {
                            MessageDigest.getInstance("SHA-256").digest((state.salt + ":" + it).toByteArray(Charsets.UTF_8))
                                .take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
                        }
                        val all = state.events + DailyDiagnosticEventV2(recordedAt, observedAtMs, kind, DailyDiagnosticModelV2.safeCode(code), value, token)
                        val retained = DailyDiagnosticModelV2.retain(all, System.currentTimeMillis())
                        save(storage, state.copy(events = retained, truncated = state.truncated || retained.size < all.size))
                    }.onFailure { lost.set(true) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { lost.set(true) }
    }
}
