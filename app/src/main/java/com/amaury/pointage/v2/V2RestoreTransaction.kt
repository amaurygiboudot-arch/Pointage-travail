package com.amaury.pointage.v2

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A durable undo journal for multi-file restores. PREPARED always rolls back; COMMITTED is final.
 * Recovery writes through SharedPreferences, so its in-memory cache and durable XML agree.
 * Personal/runtime access uses the same monitor and cannot certify a partially published restore.
 */
object V2RestoreTransaction {
    const val WARNING = "Restauration interrompue : récupération locale à terminer avant tout calcul."
    private val monitor = Any()
    private var publishing = false

    internal fun physicalName(logical: String, scope: String): String {
        require(V2RuntimeAccountScope.fileName("restore_scope", scope) != null) { "Propriétaire de restauration invalide" }
        require(logical.matches(Regex("[A-Za-z0-9_-]+"))) { "Nom de stockage invalide" }
        return when {
            logical == "salary_legacy_recovery_owner" -> logical
            SalaryPreferenceRegistry.isPersonal(logical) ->
                requireNotNull(SalaryPreferenceRegistry.fileName(logical, scope))
            logical == "horatrack_v2_test_runtime" || logical == "horatrack_v2_migration" ->
                requireNotNull(V2RuntimeAccountScope.fileName(logical, scope))
            V2BackupManager.isManagedPreferenceFileName(logical) -> logical
            else -> error("Stockage hors du périmètre de restauration")
        }
    }

    private fun journal(context: Context) = AtomicFile(File(
        SalaryAccountScope.rawContext(context).noBackupFilesDir, "agkgmg_restore_transaction_v1.json"
    ))

    private fun write(file: AtomicFile, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        check(file.readFully().contentEquals(bytes)) {
            "Journal de restauration non confirmé"
        }
    }

    /** Safe to call before Firebase is ready: a journal only contains already resolved local owners. */
    fun recover(context: Context): Boolean = synchronized(monitor) {
        if (publishing) false else recoverLocked(context)
    }

    fun available(context: Context, scope: String): Boolean = synchronized(monitor) {
        V2RuntimeAccountScope.fileName("scope", scope) != null && !publishing && recoverLocked(context)
    }

    fun <T> withAccess(context: Context, scope: String, unavailable: () -> T, block: () -> T): T =
        synchronized(monitor) {
            if (V2RuntimeAccountScope.fileName("scope", scope) == null || publishing || !recoverLocked(context)) unavailable()
            else block()
        }

    /** Device settings may be read before authentication, but never during a partial restore. */
    fun <T> withSharedAccess(context: Context, unavailable: () -> T, block: () -> T): T =
        synchronized(monitor) {
            if (publishing || !recoverLocked(context)) unavailable() else block()
        }

    fun sharedAvailable(context: Context): Boolean = synchronized(monitor) {
        !publishing && recoverLocked(context)
    }

    /** prepare runs under the same monitor as guarded reads/writes; it must not change live data. */
    fun execute(
        context: Context,
        scope: String,
        isCurrent: () -> Boolean,
        prepare: () -> Map<String, Map<String, Any?>>
    ): SalaryAccountScope.Lease = synchronized(monitor) {
        check(!publishing && recoverLocked(context)) { WARNING }
        check(isCurrent()) { "Compte modifié pendant la restauration" }
        val rawContext = SalaryAccountScope.rawContext(context)
        val targets = prepare()
        check(isCurrent()) { "Compte modifié pendant la restauration" }
        if (targets.isEmpty()) return@synchronized SalaryAccountScope.capture()
        val entries = JSONArray()
        targets.forEach { (logical, after) ->
            val physical = physicalName(logical, scope)
            val preferences = rawContext.getSharedPreferences(physical, Context.MODE_PRIVATE)
            entries.put(JSONObject().put("logical", logical).put("physical", physical)
                .put("before", V2PreferenceValues.encode(preferences.all))
                .put("after", V2PreferenceValues.encode(after)))
        }
        val record = JSONObject().put("version", 1).put("scope", scope)
            .put("state", "PREPARED").put("entries", entries)
        val file = journal(context)
        // No live preference may change before the recovery record is durable and verified.
        write(file, record)
        publishing = true
        try {
            for (i in 0 until entries.length()) {
                check(isCurrent()) { "Compte modifié pendant la restauration" }
                val entry = entries.getJSONObject(i)
                val p = rawContext.getSharedPreferences(entry.getString("physical"), Context.MODE_PRIVATE)
                check(p.all == V2PreferenceValues.decode(entry.getJSONObject("before"))) {
                    "Données modifiées pendant la préparation : restauration interrompue"
                }
                check(V2PreferenceValues.replace(p, V2PreferenceValues.decode(entry.getJSONObject("after")))) {
                    "Échec d'écriture de ${entry.getString("logical")}" }
            }
            check(isCurrent()) { "Compte modifié pendant la restauration" }
            write(file, JSONObject(record.toString()).put("state", "COMMITTED"))
            file.delete()
            check(!file.baseFile.exists()) { "Nettoyage du journal de restauration non confirmé" }
            publishing = false
            // Invalidate every captured multi-read calculation/form before readers may observe
            // this generation. A pre-publication read must never certify a post-publication mix.
            SalaryAccountScope.invalidateSession()
        } catch (failure: Exception) {
            // A failed commit may already have changed the SharedPreferences RAM cache.
            publishing = false
            if (!recoverLocked(context)) throw IllegalStateException(WARNING, failure)
            throw failure
        } finally {
            publishing = false
        }
        SalaryAccountScope.capture()
    }

    private fun recoverLocked(context: Context): Boolean = runCatching {
        val file = journal(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return true
        val record = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(record.getInt("version") == 1)
        val scope = record.getString("scope")
        val entries = record.getJSONArray("entries")
        require(entries.length() > 0)
        val names = mutableSetOf<String>()
        // Validate the complete journal before touching any file, including its original owner.
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val physical = physicalName(entry.getString("logical"), scope)
            require(physical == entry.getString("physical") && names.add(physical))
            V2PreferenceValues.decode(entry.getJSONObject("before"))
            V2PreferenceValues.decode(entry.getJSONObject("after"))
        }
        when (record.getString("state")) {
            "PREPARED" -> {
                val raw = SalaryAccountScope.rawContext(context)
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val p = raw.getSharedPreferences(entry.getString("physical"), Context.MODE_PRIVATE)
                    val before = V2PreferenceValues.decode(entry.getJSONObject("before"))
                    val current = p.all
                    if (current == before) continue
                    // Never arbitrate a third image written by an independent settings editor.
                    // Keep both journal images and fail closed until the conflict is resolved.
                    check(current == V2PreferenceValues.decode(entry.getJSONObject("after")))
                    check(V2PreferenceValues.replace(p, before))
                }
            }
            "COMMITTED" -> Unit
            else -> error("État de restauration inconnu")
        }
        file.delete()
        check(!file.baseFile.exists())
        if (record.getString("state") == "COMMITTED") SalaryAccountScope.invalidateSession()
        true
    }.getOrDefault(false)
}
