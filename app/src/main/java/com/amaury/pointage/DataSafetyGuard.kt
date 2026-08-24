package com.amaury.pointage

import android.content.Context
import java.io.File

object DataSafetyGuard {
    private const val BACKUP_DIR = "update_data_safety"
    private const val SHARED_PREFS_DIR = "shared_prefs"
    private const val MARKER_FILE = "snapshot_ok"
    private const val POINTAGE_FILE = "pointage.xml"

    /**
     * The update-safety snapshot intentionally contains only pointage.xml.
     * Other SharedPreferences (App Check, update download state, appearance, etc.) are
     * transient/configuration state and restoring them can race with startup providers.
     */
    fun createSnapshot(context: Context): Boolean {
        return runCatching {
            val sourceRoot = File(context.applicationInfo.dataDir, SHARED_PREFS_DIR)
            val sourcePointage = File(sourceRoot, POINTAGE_FILE)
            if (!sourcePointage.exists() || !sourcePointage.isFile || sourcePointage.length() <= 0L) {
                return@runCatching false
            }

            val backupRoot = File(context.filesDir, BACKUP_DIR)
            val tempRoot = File(context.filesDir, "${BACKUP_DIR}_tmp")
            if (tempRoot.exists()) tempRoot.deleteRecursively()
            val tempPrefs = File(tempRoot, SHARED_PREFS_DIR)
            if (!tempPrefs.mkdirs() && !tempPrefs.isDirectory) return@runCatching false

            val tempPointage = File(tempPrefs, POINTAGE_FILE)
            sourcePointage.copyTo(tempPointage, overwrite = true)
            if (!tempPointage.exists() || tempPointage.length() != sourcePointage.length()) {
                tempRoot.deleteRecursively()
                return@runCatching false
            }
            File(tempRoot, MARKER_FILE).writeText(System.currentTimeMillis().toString())

            if (backupRoot.exists()) backupRoot.deleteRecursively()
            if (!tempRoot.renameTo(backupRoot)) {
                backupRoot.mkdirs()
                val backupPrefs = File(backupRoot, SHARED_PREFS_DIR)
                if (!backupPrefs.mkdirs() && !backupPrefs.isDirectory) return@runCatching false
                tempPointage.copyTo(File(backupPrefs, POINTAGE_FILE), overwrite = true)
                File(backupRoot, MARKER_FILE).writeText(File(tempRoot, MARKER_FILE).readText())
                tempRoot.deleteRecursively()
            }
            true
        }.getOrDefault(false)
    }

    fun restoreIfNeeded(context: Context): Boolean {
        return runCatching {
            val backupRoot = File(context.filesDir, BACKUP_DIR)
            val marker = File(backupRoot, MARKER_FILE)
            val backupPointage = File(File(backupRoot, SHARED_PREFS_DIR), POINTAGE_FILE)
            if (!marker.exists() || !backupPointage.exists() || backupPointage.length() <= 0L) {
                return@runCatching false
            }

            val liveRoot = File(context.applicationInfo.dataDir, SHARED_PREFS_DIR)
            if (!liveRoot.exists() && !liveRoot.mkdirs() && !liveRoot.isDirectory) {
                return@runCatching false
            }
            if (!liveRoot.isDirectory) return@runCatching false

            val livePointage = File(liveRoot, POINTAGE_FILE)
            // Never overwrite live pointage data. This provider runs before normal app storage
            // access, so restoring only this file also avoids races with unrelated prefs writers.
            if (livePointage.exists()) return@runCatching false

            val temp = File(liveRoot, ".${POINTAGE_FILE}.restore.tmp")
            if (temp.exists()) temp.delete()
            backupPointage.copyTo(temp, overwrite = true)
            if (temp.length() != backupPointage.length()) {
                temp.delete()
                return@runCatching false
            }

            // Re-check immediately before publication. A concurrently created live pointage
            // file wins; the snapshot is kept intact for a later/manual recovery.
            if (livePointage.exists()) {
                temp.delete()
                return@runCatching false
            }
            if (!temp.renameTo(livePointage)) {
                temp.delete()
                return@runCatching false
            }
            true
        }.getOrDefault(false)
    }

    fun hasValidSnapshot(context: Context): Boolean {
        val root = File(context.filesDir, BACKUP_DIR)
        val pointage = File(File(root, SHARED_PREFS_DIR), POINTAGE_FILE)
        return File(root, MARKER_FILE).exists() && pointage.exists() && pointage.length() > 0L
    }
}
