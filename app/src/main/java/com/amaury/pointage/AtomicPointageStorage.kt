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
    private const val FILE_NAME = "pointage-data-v1.json"

    fun read(context: Context): JSONArray {
        val app = context.applicationContext
        val atomic = AtomicFile(File(app.filesDir, FILE_NAME))

        // Always let AtomicFile.openRead() run first: it restores a pending backup after
        // an interrupted write. Checking baseFile.exists() before openRead() can bypass
        // that recovery path and accidentally re-import stale legacy SharedPreferences.
        val atomicRaw = try {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }

        if (atomicRaw != null) {
            if (atomicRaw.isBlank()) return JSONArray()
            return parseOrBackup(app, atomicRaw)
        }

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getString(LEGACY_KEY, "[]").orEmpty()
        val migrated = if (legacy.isBlank()) JSONArray() else parseOrBackup(app, legacy)
        write(app, migrated)
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

    private fun parseOrBackup(context: Context, raw: String): JSONArray =
        runCatching { JSONArray(raw) }.getOrElse {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CORRUPT_BACKUP_KEY, raw)
                .apply()
            JSONArray()
        }
}
