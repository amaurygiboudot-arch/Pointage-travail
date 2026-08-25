package com.amaury.pointage

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException

/** Durable, crash-safe storage for pointage history. */
internal object AtomicPointageStorage {
    private const val PREFS = "pointage"
    private const val LEGACY_KEY = "data"
    private const val CORRUPT_ATOMIC_BACKUP_KEY = "corrupt_atomic_data_backup"
    private const val CORRUPT_LEGACY_BACKUP_KEY = "corrupt_legacy_data_backup"
    private const val MIGRATION_COMPLETE_KEY = "atomic_migration_v1_complete"
    private const val FILE_NAME = "pointage-data-v1.json"

    fun read(context: Context): JSONArray {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val atomic = AtomicFile(File(app.filesDir, FILE_NAME))

        val atomicRaw = try {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }

        if (atomicRaw != null) {
            parse(atomicRaw)?.let { valid ->
                if (!prefs.getBoolean(MIGRATION_COMPLETE_KEY, false)) {
                    requireMigrationComplete(app)
                }
                return valid
            }

            if (!prefs.getBoolean(MIGRATION_COMPLETE_KEY, false)) {
                requireCorruptBackup(app, CORRUPT_ATOMIC_BACKUP_KEY, atomicRaw)
                val legacy = prefs.getString(LEGACY_KEY, "[]").orEmpty()
                val legacyData = parse(legacy) ?: run {
                    requireCorruptBackup(app, CORRUPT_LEGACY_BACKUP_KEY, legacy)
                    JSONArray()
                }
                write(app, legacyData)
                requireMigrationComplete(app)
                return legacyData
            }

            requireCorruptBackup(app, CORRUPT_ATOMIC_BACKUP_KEY, atomicRaw)
            return JSONArray()
        }

        val legacy = prefs.getString(LEGACY_KEY, "[]").orEmpty()
        val migrated = parse(legacy) ?: run {
            requireCorruptBackup(app, CORRUPT_LEGACY_BACKUP_KEY, legacy)
            JSONArray()
        }
        write(app, migrated)
        requireMigrationComplete(app)
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

    private fun requireMigrationComplete(context: Context) {
        val persisted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MIGRATION_COMPLETE_KEY, true)
            .commit()
        if (!persisted) {
            // Do not allow callers to continue mutating an atomic store that could later be
            // mistaken for an unfinished first migration and replaced from stale legacy data.
            throw IllegalStateException("Unable to persist pointage migration state")
        }
    }

    private fun requireCorruptBackup(context: Context, key: String, raw: String) {
        val persisted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, raw)
            .commit()
        if (!persisted) {
            throw IllegalStateException("Unable to persist corrupt pointage backup")
        }
    }
}
