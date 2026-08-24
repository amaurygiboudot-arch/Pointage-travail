package com.amaury.pointage

import android.content.Context
import java.io.File

object DataSafetyGuard {
    private const val BACKUP_DIR = "update_data_safety"
    private const val SHARED_PREFS_DIR = "shared_prefs"
    private const val MARKER_FILE = "snapshot_ok"

    fun createSnapshot(context: Context): Boolean {
        return runCatching {
            val source = File(context.applicationInfo.dataDir, SHARED_PREFS_DIR)
            if (!source.exists() || !source.isDirectory) return@runCatching false

            val backupRoot = File(context.filesDir, BACKUP_DIR)
            val temp = File(context.filesDir, "${BACKUP_DIR}_tmp")
            if (temp.exists()) temp.deleteRecursively()
            temp.mkdirs()

            val destination = File(temp, SHARED_PREFS_DIR)
            copyDirectory(source, destination)

            val pointage = File(destination, "pointage.xml")
            if (!pointage.exists() || pointage.length() <= 0L) {
                temp.deleteRecursively()
                return@runCatching false
            }

            File(temp, MARKER_FILE).writeText(System.currentTimeMillis().toString())

            if (backupRoot.exists()) backupRoot.deleteRecursively()
            if (!temp.renameTo(backupRoot)) {
                copyDirectory(temp, backupRoot)
                temp.deleteRecursively()
            }
            true
        }.getOrDefault(false)
    }

    fun restoreIfNeeded(context: Context): Boolean {
        return runCatching {
            val liveRoot = File(context.applicationInfo.dataDir, SHARED_PREFS_DIR)
            val livePointage = File(liveRoot, "pointage.xml")
            if (livePointage.exists()) return@runCatching false

            val backupRoot = File(context.filesDir, BACKUP_DIR)
            val marker = File(backupRoot, MARKER_FILE)
            val backupPrefs = File(backupRoot, SHARED_PREFS_DIR)
            val backupPointage = File(backupPrefs, "pointage.xml")
            if (!marker.exists() || !backupPointage.exists() || backupPointage.length() <= 0L) {
                return@runCatching false
            }

            if (!liveRoot.exists() && !liveRoot.mkdirs()) return@runCatching false

            for (source in backupPrefs.listFiles().orEmpty()) {
                val destination = File(liveRoot, source.name)
                if (destination.exists()) continue

                val tempDestination = File(liveRoot, ".${source.name}.restore.tmp")
                if (tempDestination.exists()) tempDestination.delete()
                source.copyTo(tempDestination, overwrite = true)
                if (!tempDestination.renameTo(destination)) {
                    tempDestination.delete()
                    return@runCatching false
                }
            }
            true
        }.getOrDefault(false)
    }

    fun hasValidSnapshot(context: Context): Boolean {
        val root = File(context.filesDir, BACKUP_DIR)
        return File(root, MARKER_FILE).exists() &&
            File(File(root, SHARED_PREFS_DIR), "pointage.xml").exists()
    }

    private fun copyDirectory(source: File, destination: File) {
        if (!destination.exists()) destination.mkdirs()
        source.listFiles().orEmpty().forEach { item ->
            val target = File(destination, item.name)
            if (item.isDirectory) copyDirectory(item, target)
            else item.copyTo(target, overwrite = true)
        }
    }
}
