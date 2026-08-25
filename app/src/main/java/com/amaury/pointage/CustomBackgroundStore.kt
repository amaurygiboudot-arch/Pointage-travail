package com.amaury.pointage

import android.content.Context
import java.io.File

/**
 * Stockage robuste du fond personnalisé.
 * Deux copies internes sont conservées pour survivre aux relances et aux mises à jour.
 */
object CustomBackgroundStore {
    private const val PREFS = "appearance_settings"
    private const val BACKUP_FILE = "custom_app_background_backup.jpg"

    fun primary(context: Context): File = File(context.filesDir, AppearanceManager.BACKGROUND_FILE)
    private fun backup(context: Context): File = File(context.noBackupFilesDir, BACKUP_FILE)

    fun saveBackup(context: Context) {
        val source = primary(context)
        if (!source.exists() || source.length() <= 0L) return
        runCatching {
            val target = backup(context)
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        }
    }

    /** Retourne une copie exploitable et restaure la principale si nécessaire. */
    fun resolve(context: Context): File? {
        val primary = primary(context)
        if (primary.exists() && primary.length() > 0L) return primary

        val backup = backup(context)
        if (backup.exists() && backup.length() > 0L) {
            runCatching {
                primary.parentFile?.mkdirs()
                backup.inputStream().use { input -> primary.outputStream().use { output -> input.copyTo(output) } }
            }
            if (primary.exists() && primary.length() > 0L) return primary
        }
        return null
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("custom_image_bg", false)

    fun clear(context: Context) {
        primary(context).delete()
        backup(context).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("custom_image_bg", false)
            .apply()
    }
}
