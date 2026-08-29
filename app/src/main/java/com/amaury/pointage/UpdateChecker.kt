package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    /**
     * Interrupteur central du moteur de mise à jour APK interne.
     * Tant que HoraTrack est distribué hors Play Store il reste à true.
     * Lors du passage à Google Play, le basculer à false désactive les contrôles,
     * téléchargements et ouvertures d'installateur internes sans supprimer le code.
     */
    internal const val INTERNAL_APK_UPDATES_ENABLED = true

    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"
    private const val LATEST_RELEASE_PAGE = "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/latest"
    private const val LATEST_APK_FALLBACK = "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/latest/download/HP-Travail.apk"
    internal const val PREFS = "update_download"
    internal const val KEY_DOWNLOAD_ID = "download_id"
    internal const val KEY_VERSION = "version"
    internal const val KEY_FILE_NAME = "file_name"
    internal const val KEY_READY_FILE = "ready_file"
    internal const val KEY_READY_VERSION = "ready_version"
    internal const val KEY_VERIFICATION_PENDING = "verification_pending"

    @Volatile private var updateInProgress = false
    @Volatile private var installerOpening = false
    @Volatile private var promptShowing = false
    @Volatile private var installPromptShowing = false

    fun checkAutomatically(activity: Activity) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (tryInstallReady(activity)) return
        if (hasActiveDownload(activity)) return
        check(activity, silent = true, askBeforeDownload = true)
    }

    fun tryInstallReady(activity: Activity): Boolean {
        if (!INTERNAL_APK_UPDATES_ENABLED) return false
        if (installerOpening || installPromptShowing || activity.isFinishing || activity.isDestroyed) return false
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val readyName = prefs.getString(KEY_READY_FILE, null) ?: return false
        val readyVersion = prefs.getString(KEY_READY_VERSION, "").orEmpty()
        val currentVersion = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty() }.getOrDefault("")
        if (readyVersion.isNotBlank() && compareVersions(currentVersion, readyVersion) >= 0) { clearReadyState(activity, true); return false }
        val apk = File(File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"), readyName)
        if (!apk.exists()) { clearReadyState(activity, false); return false }
        if (runCatching { validateApk(activity, apk) }.isFailure) { clearReadyState(activity, true); return false }
        showInstallConfirmation(activity, apk, readyVersion)
        return true
    }

    private fun showInstallConfirmation(activity: Activity, apk: File, versionName: String) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (installPromptShowing || activity.isFinishing || activity.isDestroyed) return
        installPromptShowing = true
        val versionText = if (versionName.isBlank()) "" else " $versionName"
        val dialog = AlertDialog.Builder(activity).setTitle("Mise à jour prête")
            .setMessage("HP Travail$versionText a été téléchargée.\n\nVoulez-vous lancer l'installation maintenant ?")
            .setPositiveButton("INSTALLER") { _, _ -> launchSystemInstaller(activity, apk) }
            .setNegativeButton("PLUS TARD", null).create()
        dialog.setOnDismissListener { installPromptShowing = false }
        dialog.show()
    }

    private fun launchSystemInstaller(activity: Activity, apk: File) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (installerOpening) return
        installerOpening = true
        activity.window.decorView.postDelayed({ installerOpening = false }, 2500L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
                .onFailure { installerOpening = false; Toast.makeText(activity, "Autorisez HP Travail à installer les mises à jour.", Toast.LENGTH_LONG).show() }
            return
        }
        runCatching { activity.startActivity(installerIntent(activity, apk)) }
            .onFailure { installerOpening = false; Toast.makeText(activity, "Impossible d'ouvrir l'installateur Android.", Toast.LENGTH_LONG).show() }
    }

    fun check(activity: Activity, silent: Boolean = true, askBeforeDownload: Boolean = false) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (tryInstallReady(activity)) return
        if (hasActiveDownload(activity)) { if (!silent) Toast.makeText(activity, "La mise à jour continue en arrière-plan", Toast.LENGTH_LONG).show(); return }
        if (updateInProgress || promptShowing) { if (!silent && updateInProgress) Toast.makeText(activity, "Vérification déjà en cours", Toast.LENGTH_SHORT).show(); return }
        updateInProgress = true
        Thread {
            var connection: HttpURLConnection? = null
            try {
                var versionName: String? = null
                var apkUrl: String? = null
                connection = openConnection(LATEST_RELEASE_API, 10_000, 20_000)
                val code = connection.responseCode
                if (code in 200..299) {
                    val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    versionName = release.optString("tag_name").trim().removePrefix("v").takeIf { it.isNotBlank() }
                    val assets = release.optJSONArray("assets")
                    if (assets != null) for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.equals("HP-Travail.apk", true) || name.endsWith(".apk", true)) {
                            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            if (apkUrl != null) break
                        }
                    }
                } else if (code == 403 || code == 429) {
                    connection.disconnect(); connection = null
                    val fallback = resolveLatestReleaseWithoutApi(); versionName = fallback.first; apkUrl = fallback.second
                } else { showStatus(activity, silent, "Vérification temporairement indisponible"); return@Thread }
                if (versionName.isNullOrBlank()) { showStatus(activity, silent, "Version de mise à jour introuvable"); return@Thread }
                val currentVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
                if (compareVersions(versionName, currentVersion) <= 0) { showStatus(activity, silent, "Aucune mise à jour disponible"); return@Thread }
                val finalUrl = apkUrl ?: LATEST_APK_FALLBACK
                activity.runOnUiThread { if (askBeforeDownload) showUpdatePrompt(activity, versionName, finalUrl) else enqueueBackgroundDownload(activity, finalUrl, versionName, silent) }
            } catch (e: Exception) {
                val fallback = runCatching { resolveLatestReleaseWithoutApi() }.getOrNull()
                if (fallback != null) {
                    val versionName = fallback.first
                    val currentVersion = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty() }.getOrDefault("")
                    if (versionName.isNotBlank() && compareVersions(versionName, currentVersion) > 0) activity.runOnUiThread { if (askBeforeDownload) showUpdatePrompt(activity, versionName, fallback.second) else enqueueBackgroundDownload(activity, fallback.second, versionName, silent) }
                    else showStatus(activity, silent, "Aucune mise à jour disponible")
                } else showStatus(activity, silent, "Vérification temporairement indisponible")
            } finally { updateInProgress = false; connection?.disconnect() }
        }.start()
    }

    private fun resolveLatestReleaseWithoutApi(): Pair<String, String> {
        val c = (URL(LATEST_RELEASE_PAGE).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 15_000; requestMethod = "GET"
            setRequestProperty("User-Agent", "HP-Travail-Android"); setRequestProperty("Accept", "text/html,application/xhtml+xml"); connect()
        }
        try {
            val code = c.responseCode
            if (code !in 300..399) error("redirection release absente")
            val location = c.getHeaderField("Location") ?: error("version release absente")
            val tag = location.substringAfterLast('/').removePrefix("v").trim()
            if (tag.isBlank()) error("version release invalide")
            return tag to LATEST_APK_FALLBACK
        } finally { c.disconnect() }
    }

    private fun showUpdatePrompt(activity: Activity, versionName: String, apkUrl: String) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (promptShowing || activity.isFinishing || activity.isDestroyed) return
        promptShowing = true
        val dialog = AlertDialog.Builder(activity).setTitle("Mise à jour disponible")
            .setMessage("HP Travail $versionName est disponible.\n\nVoulez-vous télécharger la mise à jour maintenant ?")
            .setPositiveButton("TÉLÉCHARGER") { _, _ -> enqueueBackgroundDownload(activity, apkUrl, versionName, false) }
            .setNegativeButton("PLUS TARD", null).create()
        dialog.setOnDismissListener { promptShowing = false }
        dialog.show()
    }

    private fun enqueueBackgroundDownload(activity: Activity, apkUrl: String, versionName: String, silent: Boolean) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (hasActiveDownload(activity)) { if (!silent) Toast.makeText(activity, "Une mise à jour est déjà en cours", Toast.LENGTH_LONG).show(); return }
        try {
            val fileName = "HP-Travail-$versionName.apk"
            val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name.endsWith(".apk", true)) it.delete() }
            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("Mise à jour HP Travail"); setDescription("Téléchargement de la version $versionName")
                setMimeType("application/vnd.android.package-archive"); setAllowedOverMetered(true); setAllowedOverRoaming(false)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName")
            }
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId).putString(KEY_VERSION, versionName).putString(KEY_FILE_NAME, fileName)
                .putBoolean(KEY_VERIFICATION_PENDING, false)
                .remove(KEY_READY_FILE).remove(KEY_READY_VERSION).apply()
            Toast.makeText(activity, "Téléchargement de la mise à jour lancé", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { if (!silent) Toast.makeText(activity, "Mise à jour impossible : ${shortError(e)}", Toast.LENGTH_LONG).show() }
    }

    private fun hasActiveDownload(context: Context): Boolean {
        if (!INTERNAL_APK_UPDATES_ENABLED) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_VERIFICATION_PENDING, false)) return true
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id <= 0L) return false
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) { clearDownloadState(context); false }
                else when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> true
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val apk = downloadedApkFile(context)
                        if (apk?.exists() == true) {
                            // Le broadcast de fin a pu être manqué : on relance la vérification de façon durable.
                            UpdateVerificationWorker.enqueue(context)
                            true
                        } else { clearDownloadState(context); false }
                    }
                    else -> { clearDownloadState(context); false }
                }
            }
        } catch (_: Exception) {
            val apk = downloadedApkFile(context)
            if (apk?.exists() == true) { UpdateVerificationWorker.enqueue(context); true } else false
        }
    }

    internal fun downloadedApkFile(context: Context): File? {
        if (!INTERNAL_APK_UPDATES_ENABLED) return null
        val fileName = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FILE_NAME, null) ?: return null
        return File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"), fileName)
    }

    internal fun markDownloadReady(context: Context, apk: File) {
        if (!INTERNAL_APK_UPDATES_ENABLED) {
            apk.delete()
            clearDownloadState(context)
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, "").orEmpty()
        prefs.edit().putString(KEY_READY_FILE, apk.name).putString(KEY_READY_VERSION, version)
            .putBoolean(KEY_VERIFICATION_PENDING, false)
            .remove(KEY_DOWNLOAD_ID).remove(KEY_FILE_NAME).apply()
    }

    internal fun clearDownloadState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VERIFICATION_PENDING, false)
            .remove(KEY_DOWNLOAD_ID).remove(KEY_VERSION).remove(KEY_FILE_NAME).apply()
    }

    private fun clearReadyState(context: Context, deleteFile: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_READY_FILE, null)
        if (deleteFile && !name.isNullOrBlank()) File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"), name).delete()
        prefs.edit().remove(KEY_READY_FILE).remove(KEY_READY_VERSION).apply()
    }

    internal fun validateApk(context: Context, apk: File) {
        if (!apk.exists()) throw IllegalStateException("fichier APK absent")
        if (apk.length() < 100_000L) throw IllegalStateException("fichier APK incomplet")
        val magic = apk.inputStream().use { input -> ByteArray(2).also { if (input.read(it) != 2) throw IllegalStateException("APK illisible") } }
        if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) throw IllegalStateException("fichier reçu invalide")
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: throw IllegalStateException("Android ne reconnaît pas l'APK")
        if (archive.packageName != context.packageName) throw IllegalStateException("APK d'une autre application")
    }

    internal fun installerIntent(context: Context, apk: File): Intent {
        check(INTERNAL_APK_UPDATES_ENABLED) { "mises à jour APK internes désactivées" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", apk)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
    }

    fun openInstaller(activity: Activity, apk: File) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        markDownloadReady(activity, apk)
        tryInstallReady(activity)
    }

    private fun openConnection(url: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; this.connectTimeout = connectTimeout; this.readTimeout = readTimeout
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream;q=0.9, */*;q=0.8")
            setRequestProperty("User-Agent", "HP-Travail-Android"); setRequestProperty("Cache-Control", "no-cache"); connect()
        }

    private fun showStatus(activity: Activity, silent: Boolean, message: String) {
        if (!silent) activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }

    private fun shortError(e: Exception): String = e.message?.trim().takeUnless { it.isNullOrBlank() }?.take(120) ?: e.javaClass.simpleName

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val av = pa.getOrElse(i) { 0 }; val bv = pb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
