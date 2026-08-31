package com.amaury.pointage

import android.content.Context
import java.io.File

/**
 * Protection locale des données utilisateur pendant les mises à jour APK.
 *
 * Une mise à jour Android normale conserve déjà /data/data/<package>, mais cette
 * sauvegarde indépendante permet de récupérer les SharedPreferences si une future
 * migration de code les endommage ou les supprime accidentellement.
 *
 * Cette protection ne peut pas survivre à une désinstallation manuelle ou à
 * "Effacer les données" dans Android : dans ces cas Android supprime tout le
 * répertoire privé de l'application.
 */
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

            // On ne valide jamais une sauvegarde sans le fichier d'historique.
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

    /**
     * Restaure uniquement en cas d'anomalie nette : le fichier pointage.xml a
     * disparu alors qu'une sauvegarde de sécurité valide existe. On ne restaure
     * jamais par-dessus un fichier existant afin de ne pas écraser des données
     * légitimement modifiées après la mise à jour.
     */
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

            liveRoot.mkdirs()
            backupPrefs.listFiles().orEmpty().forEach { source ->
                val destination = File(liveRoot, source.name)
                if (!destination.exists()) {
                    source.copyTo(destination, overwrite = false)
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
