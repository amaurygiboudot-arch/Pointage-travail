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
    enum class Status {
        DISABLED,
        CHECKING,
        BUSY,
        DOWNLOADING,
        INSTALLING,
        NO_UPDATE,
        ERROR
    }

    /**
     * Interrupteur central du moteur de mise à jour APK interne.
     * La variante interne l'active, tandis que la variante Google Play le désactive
     * directement au moment de la compilation via BuildConfig.
     */
    internal val INTERNAL_APK_UPDATES_ENABLED: Boolean
        get() = BuildConfig.INTERNAL_APK_UPDATES_ENABLED

    /**
     * Une APK de développement ne consulte que les prereleases dev-*.
     * Une APK publique interne continue de consulter uniquement releases/latest.
     */
    internal val DEVELOPMENT_UPDATE_CHANNEL: Boolean
        get() = DevelopmentUpdateReleaseV2.isDevelopmentVersion(BuildConfig.VERSION_NAME)

    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"
    private const val DEVELOPMENT_RELEASES_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases?per_page=20"
    private const val LATEST_RELEASE_PAGE = "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/latest"
    private const val LATEST_APK_FALLBACK = "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/latest/download/HP-Travail.apk"
    internal const val PREFS = "update_download"
    internal const val KEY_DOWNLOAD_ID = "download_id"
    internal const val KEY_VERSION = "version"
    internal const val KEY_FILE_NAME = "file_name"
    internal const val KEY_READY_FILE = "ready_file"
    internal const val KEY_READY_VERSION = "ready_version"
    internal const val KEY_VERIFICATION_PENDING = "verification_pending"
    internal const val KEY_RECOVERY_REPAIR = "recovery_repair"

    @Volatile private var updateInProgress = false
    @Volatile private var installerOpening = false
    @Volatile private var promptShowing = false

    fun checkAutomatically(activity: Activity) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (tryInstallReady(activity)) return
        if (hasActiveDownload(activity)) return
        check(activity, silent = true, askBeforeDownload = false)
    }

    fun tryInstallReady(activity: Activity): Boolean {
        if (!INTERNAL_APK_UPDATES_ENABLED) return false
        if (installerOpening || activity.isFinishing || activity.isDestroyed) return false
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val readyName = prefs.getString(KEY_READY_FILE, null) ?: return false
        val readyVersion = prefs.getString(KEY_READY_VERSION, "").orEmpty()
        val currentVersion = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        if (readyVersion.isNotBlank() && compareVersions(currentVersion, readyVersion) >= 0) {
            clearReadyState(activity, true)
            return false
        }
        val apk = File(File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"), readyName)
        if (!apk.exists()) {
            clearReadyState(activity, false)
            return false
        }
        if (runCatching { validateApk(activity, apk) }.isFailure) {
            clearReadyState(activity, true)
            return false
        }
        launchSystemInstaller(activity, apk)
        return true
    }

    private fun launchSystemInstaller(activity: Activity, apk: File) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (installerOpening) return
        installerOpening = true
        activity.window.decorView.postDelayed({ installerOpening = false }, 2500L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }.onFailure {
                installerOpening = false
                Toast.makeText(activity, "Autorisez HoraTrack à installer les mises à jour.", Toast.LENGTH_LONG).show()
            }
            return
        }
        runCatching { activity.startActivity(installerIntent(activity, apk)) }
            .onFailure {
                installerOpening = false
                Toast.makeText(activity, "Impossible d'ouvrir l'installateur Android.", Toast.LENGTH_LONG).show()
            }
    }

    fun check(
        activity: Activity,
        silent: Boolean = true,
        askBeforeDownload: Boolean = false,
        recoveryRepair: Boolean = false,
        onStatus: ((Status, String) -> Unit)? = null
    ) {
        if (!INTERNAL_APK_UPDATES_ENABLED) {
            showStatus(activity, silent, Status.DISABLED, "Les mises à jour sont gérées par Google Play.", onStatus)
            return
        }
        if (tryInstallReady(activity)) {
            showStatus(
                activity,
                true,
                Status.INSTALLING,
                "Mise à jour vérifiée. Ouverture de l’installateur Android…",
                onStatus
            )
            return
        }
        if (hasActiveDownload(activity)) {
            if (recoveryRepair) markRecoveryRepairRequested(activity)
            showStatus(
                activity,
                silent,
                Status.DOWNLOADING,
                "Le téléchargement ou la vérification sécurisée continue en arrière-plan.",
                onStatus
            )
            return
        }
        if (updateInProgress || promptShowing) {
            showStatus(activity, silent, Status.BUSY, "Une vérification est déjà en cours.", onStatus)
            return
        }
        updateInProgress = true
        val channelLabel = if (DEVELOPMENT_UPDATE_CHANNEL) "de développement" else "sécurisée"
        showStatus(activity, true, Status.CHECKING, "Recherche d’une mise à jour $channelLabel…", onStatus)
        Thread {
            var connection: HttpURLConnection? = null
            try {
                var versionName: String? = null
                var apkUrl: String? = null

                if (DEVELOPMENT_UPDATE_CHANNEL) {
                    val development = resolveDevelopmentRelease()
                    versionName = development.versionName
                    apkUrl = development.apkUrl
                } else {
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
                        connection.disconnect()
                        connection = null
                        val fallback = resolveLatestReleaseWithoutApi()
                        versionName = fallback.first
                        apkUrl = fallback.second
                    } else {
                        showStatus(activity, silent, Status.ERROR, "Vérification temporairement indisponible", onStatus)
                        return@Thread
                    }
                }

                if (versionName.isNullOrBlank()) {
                    showStatus(activity, silent, Status.ERROR, "Version de mise à jour introuvable", onStatus)
                    return@Thread
                }
                val currentVersion = activity.packageManager
                    .getPackageInfo(activity.packageName, 0)
                    .versionName
                    .orEmpty()
                if (compareVersions(versionName, currentVersion) <= 0) {
                    showStatus(activity, silent, Status.NO_UPDATE, "Aucune mise à jour disponible", onStatus)
                    return@Thread
                }
                val finalUrl = apkUrl ?: LATEST_APK_FALLBACK
                activity.runOnUiThread {
                    if (askBeforeDownload) {
                        showUpdatePrompt(activity, versionName, finalUrl, recoveryRepair, onStatus)
                    } else {
                        enqueueBackgroundDownload(activity, finalUrl, versionName, silent, recoveryRepair, onStatus)
                    }
                }
            } catch (e: Exception) {
                if (DEVELOPMENT_UPDATE_CHANNEL) {
                    showStatus(
                        activity,
                        silent,
                        Status.ERROR,
                        "Canal de développement temporairement indisponible",
                        onStatus
                    )
                    return@Thread
                }
                val fallback = runCatching { resolveLatestReleaseWithoutApi() }.getOrNull()
                if (fallback != null) {
                    val versionName = fallback.first
                    val currentVersion = runCatching {
                        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
                    }.getOrDefault("")
                    if (versionName.isNotBlank() && compareVersions(versionName, currentVersion) > 0) {
                        activity.runOnUiThread {
                            if (askBeforeDownload) {
                                showUpdatePrompt(activity, versionName, fallback.second, recoveryRepair, onStatus)
                            } else {
                                enqueueBackgroundDownload(
                                    activity,
                                    fallback.second,
                                    versionName,
                                    silent,
                                    recoveryRepair,
                                    onStatus
                                )
                            }
                        }
                    } else {
                        showStatus(activity, silent, Status.NO_UPDATE, "Aucune mise à jour disponible", onStatus)
                    }
                } else {
                    showStatus(activity, silent, Status.ERROR, "Vérification temporairement indisponible", onStatus)
                }
            } finally {
                updateInProgress = false
                connection?.disconnect()
            }
        }.start()
    }

    private fun resolveDevelopmentRelease(): DevelopmentUpdateReleaseV2.Candidate {
        val connection = openConnection(DEVELOPMENT_RELEASES_API, 10_000, 20_000)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("liste des prereleases indisponible ($code)")
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            return DevelopmentUpdateReleaseV2.parseLatest(json)
                ?: error("aucune prerelease HoraTrack de développement valide")
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveLatestReleaseWithoutApi(): Pair<String, String> {
        val c = (URL(LATEST_RELEASE_PAGE).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "HP-Travail-Android")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connect()
        }
        try {
            val code = c.responseCode
            if (code !in 300..399) error("redirection release absente")
            val location = c.getHeaderField("Location") ?: error("version release absente")
            val tag = location.substringAfterLast('/').removePrefix("v").trim()
            if (tag.isBlank()) error("version release invalide")
            return tag to LATEST_APK_FALLBACK
        } finally {
            c.disconnect()
        }
    }

    private fun showUpdatePrompt(
        activity: Activity,
        versionName: String,
        apkUrl: String,
        recoveryRepair: Boolean,
        onStatus: ((Status, String) -> Unit)?
    ) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (promptShowing || activity.isFinishing || activity.isDestroyed) return
        promptShowing = true
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Mise à jour disponible")
            .setMessage("HoraTrack $versionName est disponible.\n\nVoulez-vous télécharger la mise à jour maintenant ?")
            .setPositiveButton("TÉLÉCHARGER") { _, _ ->
                enqueueBackgroundDownload(activity, apkUrl, versionName, false, recoveryRepair, onStatus)
            }
            .setNegativeButton("PLUS TARD", null)
            .create()
        dialog.setOnDismissListener { promptShowing = false }
        dialog.show()
    }

    private fun enqueueBackgroundDownload(
        activity: Activity,
        apkUrl: String,
        versionName: String,
        silent: Boolean,
        recoveryRepair: Boolean,
        onStatus: ((Status, String) -> Unit)?
    ) {
        if (!INTERNAL_APK_UPDATES_ENABLED) return
        if (hasActiveDownload(activity)) {
            if (recoveryRepair) markRecoveryRepairRequested(activity)
            showStatus(activity, silent, Status.DOWNLOADING, "Une mise à jour est déjà en cours", onStatus)
            return
        }
        try {
            val fileName = "HoraTrack-$versionName.apk"
            val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name.endsWith(".apk", true)) it.delete() }
            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("Mise à jour HoraTrack")
                setDescription("Téléchargement de la version $versionName")
                setMimeType("application/vnd.android.package-archive")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    "updates/$fileName"
                )
            }
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            val editor = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_VERSION, versionName)
                .putString(KEY_FILE_NAME, fileName)
                .putBoolean(KEY_VERIFICATION_PENDING, false)
                .remove(KEY_READY_FILE)
                .remove(KEY_READY_VERSION)
            if (recoveryRepair) editor.putBoolean(KEY_RECOVERY_REPAIR, true)
            else editor.remove(KEY_RECOVERY_REPAIR)
            editor.apply()
            showStatus(
                activity,
                silent,
                Status.DOWNLOADING,
                "Téléchargement sécurisé de HoraTrack $versionName lancé. L’installation sera proposée après vérification.",
                onStatus
            )
        } catch (e: Exception) {
            showStatus(activity, silent, Status.ERROR, "Mise à jour impossible : ${shortError(e)}", onStatus)
        }
    }

    internal fun hasActiveDownload(context: Context): Boolean {
        if (!INTERNAL_APK_UPDATES_ENABLED) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_VERIFICATION_PENDING, false)) return true
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id <= 0L) return false
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    clearDownloadState(context)
                    false
                } else when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED -> true

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val apk = downloadedApkFile(context)
                        if (apk?.exists() == true) {
                            UpdateVerificationWorker.enqueue(context)
                            true
                        } else {
                            clearDownloadState(context)
                            false
                        }
                    }

                    else -> {
                        clearDownloadState(context)
                        false
                    }
                }
            }
        } catch (_: Exception) {
            val apk = downloadedApkFile(context)
            if (apk?.exists() == true) {
                UpdateVerificationWorker.enqueue(context)
                true
            } else false
        }
    }

    internal fun downloadedApkFile(context: Context): File? {
        if (!INTERNAL_APK_UPDATES_ENABLED) return null
        val fileName = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FILE_NAME, null) ?: return null
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
        prefs.edit()
            .putString(KEY_READY_FILE, apk.name)
            .putString(KEY_READY_VERSION, version)
            .putBoolean(KEY_VERIFICATION_PENDING, false)
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_FILE_NAME)
            .apply()
    }

    internal fun clearDownloadState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VERIFICATION_PENDING, false)
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_VERSION)
            .remove(KEY_FILE_NAME)
            .remove(KEY_RECOVERY_REPAIR)
            .apply()
    }

    private fun markRecoveryRepairRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RECOVERY_REPAIR, true)
            .apply()
    }

    private fun clearReadyState(context: Context, deleteFile: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_READY_FILE, null)
        if (deleteFile && !name.isNullOrBlank()) {
            File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"), name).delete()
        }
        prefs.edit().remove(KEY_READY_FILE).remove(KEY_READY_VERSION).apply()
    }

    internal fun validateApk(context: Context, apk: File) {
        if (!apk.exists()) throw IllegalStateException("fichier APK absent")
        if (apk.length() < 100_000L) throw IllegalStateException("fichier APK incomplet")
        val magic = apk.inputStream().use { input ->
            ByteArray(2).also {
                if (input.read(it) != 2) throw IllegalStateException("APK illisible")
            }
        }
        if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
            throw IllegalStateException("fichier reçu invalide")
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IllegalStateException("Android ne reconnaît pas l'APK")
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

    private fun openConnection(url: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream;q=0.9, */*;q=0.8")
            setRequestProperty("User-Agent", "HP-Travail-Android")
            setRequestProperty("Cache-Control", "no-cache")
            connect()
        }

    private fun showStatus(
        activity: Activity,
        silent: Boolean,
        status: Status,
        message: String,
        onStatus: ((Status, String) -> Unit)?
    ) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            onStatus?.invoke(status, message)
            if (!silent) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun shortError(e: Exception): String =
        e.message?.trim().takeUnless { it.isNullOrBlank() }?.take(120) ?: e.javaClass.simpleName

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val av = pa.getOrElse(i) { 0 }
            val bv = pb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
