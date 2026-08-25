package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Stockage robuste du fond personnalisé.
 * Deux copies internes sont conservées pour survivre aux relances et aux mises à jour.
 */
object CustomBackgroundStore {
    private const val PREFS = "appearance_settings"
    private const val BACKUP_FILE = "custom_app_background_backup.jpg"
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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

    /**
     * Empêche une ancienne passe d'apparence de désactiver définitivement le fond
     * simplement parce qu'un décodage a échoué une fois. Si le fichier existe encore,
     * la préférence est restaurée. Une vraie réinitialisation reste possible car elle
     * supprime d'abord les fichiers.
     */
    fun protectPreference(context: Context) {
        if (listener != null) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key != "custom_image_bg") return@OnSharedPreferenceChangeListener
            if (shared.getBoolean("custom_image_bg", false)) {
                resolve(app)?.let { saveBackup(app) }
                return@OnSharedPreferenceChangeListener
            }
            val stillExists = primary(app).let { it.exists() && it.length() > 0L } ||
                backup(app).let { it.exists() && it.length() > 0L }
            if (stillExists) {
                shared.edit().putBoolean("custom_image_bg", true).apply()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun clear(context: Context) {
        primary(context).delete()
        backup(context).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("custom_image_bg", false)
            .apply()
    }
}
