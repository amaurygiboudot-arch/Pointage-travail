package com.amaury.pointage

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException

/**
 * Durable storage for pointage history.
 *
 * Uses AtomicFile so a crash/power loss cannot leave the primary JSON half-written.
 * The historical SharedPreferences value is imported on first read for upgrade compatibility.
 */
internal object AtomicPointageStorage {
    private const val PREFS = "pointage"
    private const val LEGACY_KEY = "data"
    private const val CORRUPT_BACKUP_KEY = "corrupt_data_backup"
    private const val MIGRATION_COMPLETE_KEY = "atomic_migration_v1_complete"
    private const val FILE_NAME = "pointage-data-v1.json"

    fun read(context: Context): JSONArray {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val atomic = AtomicFile(File(app.filesDir, FILE_NAME))

        // Always let AtomicFile.openRead() run first: it restores a pending backup after
        // an interrupted write. Checking baseFile.exists() before openRead() can bypass it.
        val atomicRaw = try {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }

        if (atomicRaw != null) {
            parse(atomicRaw)?.let { valid ->
                // A valid atomic payload proves the migration/store has a durable version.
                if (!prefs.getBoolean(MIGRATION_COMPLETE_KEY, false)) {
                    prefs.edit().putBoolean(MIGRATION_COMPLETE_KEY, true).commit()
                }
                return valid
            }

            // Before the first migration has been confirmed, a blank/partial atomic base may
            // simply be the result of a crash during that first copy. Preserve it, then retry
            // from the still-authoritative legacy SharedPreferences value.
            if (!prefs.getBoolean(MIGRATION_COMPLETE_KEY, false)) {
                backupCorruptSynchronously(app, atomicRaw)
                val legacy = prefs.getString(LEGACY_KEY, "[]").orEmpty()
                val legacyData = parse(legacy) ?: run {
                    backupCorruptSynchronously(app, legacy)
                    JSONArray()
                }
                write(app, legacyData)
                prefs.edit().putBoolean(MIGRATION_COMPLETE_KEY, true).commit()
                return legacyData
            }

            // Migration was previously successful: the atomic file is authoritative. Back up
            // any damaged bytes synchronously before callers are allowed to replace the file.
            backupCorruptSynchronously(app, atomicRaw)
            return JSONArray()
        }

        // No atomic file exists yet: perform the one-time migration. The completion marker is
        // persisted only after AtomicFile.finishWrite succeeds.
        val legacy = prefs.getString(LEGACY_KEY, "[]").orEmpty()
        val migrated = parse(legacy) ?: run {
            backupCorruptSynchronously(app, legacy)
            JSONArray()
        }
        write(app, migrated)
        prefs.edit().putBoolean(MIGRATION_COMPLETE_KEY, true).commit()
        return migrated
    }

    fun write(context: Context, data: JSONArray) {
        val app = context.applicationContext
        val atomic = AtomicFile(File(app.filesDir, FILE_NAME))
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun parse(raw: String): JSONArray? {
        if (raw.isBlank()) return null
        return runCatching { JSONArray(raw) }.getOrNull()
    }

    private fun backupCorruptSynchronously(context: Context, raw: String) {
        // commit() is intentional here: callers may immediately replace the corrupt atomic
        // source, so the recovery copy must be durably persisted before read() returns.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CORRUPT_BACKUP_KEY, raw)
            .commit()
    }
}
