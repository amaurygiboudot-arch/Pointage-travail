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
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"
    internal const val PREFS = "update_download"
    internal const val KEY_DOWNLOAD_ID = "download_id"
    internal const val KEY_VERSION = "version"
    internal const val KEY_FILE_NAME = "file_name"
    internal const val KEY_READY_FILE = "ready_file"
    internal const val KEY_READY_VERSION = "ready_version"

    @Volatile private var updateInProgress = false
    @Volatile private var installerOpening = false
    @Volatile private var promptShowing = false

    /** Vérifie au lancement. Si une mise à jour existe, demande d'abord l'accord de l'utilisateur. */
    fun checkAutomatically(activity: Activity) {
        if (tryInstallReady(activity)) return
        if (hasActiveDownload(activity)) return
        check(activity, silent = true, askBeforeDownload = true)
    }

    fun tryInstallReady(activity: Activity): Boolean {
        if (installerOpening || activity.isFinishing || activity.isDestroyed) return false
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val readyName = prefs.getString(KEY_READY_FILE, null) ?: return false
        val readyVersion = prefs.getString(KEY_READY_VERSION, "").orEmpty()

        val currentVersion = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        if (readyVersion.isNotBlank() && compareVersions(currentVersion, readyVersion) >= 0) {
            clearReadyState(activity, deleteFile = true)
            return false
        }

        val apk = File(File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"), readyName)
        if (!apk.exists()) {
            clearReadyState(activity, deleteFile = false)
            return false
        }
        if (runCatching { validateApk(activity, apk) }.isFailure) {
            clearReadyState(activity, deleteFile = true)
            return false
        }

        installerOpening = true
        activity.window.decorView.postDelayed({ installerOpening = false }, 2500L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            return true
        }

        return runCatching {
            activity.startActivity(installerIntent(activity, apk))
            true
        }.getOrElse {
            installerOpening = false
            false
        }
    }

    fun check(activity: Activity, silent: Boolean = true, askBeforeDownload: Boolean = false) {
        if (tryInstallReady(activity)) return
        if (hasActiveDownload(activity)) {
            if (!silent) Toast.makeText(activity, "La mise à jour continue en arrière-plan", Toast.LENGTH_LONG).show()
            return
        }
        if (updateInProgress || promptShowing) {
            if (!silent && updateInProgress) Toast.makeText(activity, "Vérification déjà en cours", Toast.LENGTH_SHORT).show()
            return
        }

        updateInProgress = true
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(LATEST_RELEASE_API, 10_000, 20_000)
                val code = connection.responseCode
                if (code !in 200..299) {
                    showStatus(activity, silent, "Vérification impossible (HTTP $code)")
                    return@Thread
                }

                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val versionName = release.optString("tag_name").trim().removePrefix("v")
                if (versionName.isBlank()) {
                    showStatus(activity, silent, "Version de mise à jour introuvable")
                    return@Thread
                }

                val currentVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
                if (compareVersions(versionName, currentVersion) <= 0) {
                    showStatus(activity, silent, "Aucune mise à jour disponible")
                    return@Thread
                }

                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.equals("HP-Travail.apk", true) || name.endsWith(".apk", true)) {
                            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            if (apkUrl != null) break
                        }
                    }
                }

                if (apkUrl == null) {
                    showStatus(activity, silent, "APK introuvable dans la dernière version")
                    return@Thread
                }

                val finalUrl = apkUrl
                activity.runOnUiThread {
                    if (askBeforeDownload) {
                        showUpdatePrompt(activity, versionName, finalUrl)
                    } else {
                        enqueueBackgroundDownload(activity, finalUrl, versionName, silent)
                    }
                }
            } catch (e: Exception) {
                showStatus(activity, silent, "Vérification impossible : ${shortError(e)}")
            } finally {
                updateInProgress = false
                connection?.disconnect()
            }
        }.start()
    }

    private fun showUpdatePrompt(activity: Activity, versionName: String, apkUrl: String) {
        if (promptShowing || activity.isFinishing || activity.isDestroyed) return
        promptShowing = true
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Mise à jour disponible")
            .setMessage("HP Travail $versionName est disponible.\n\nVoulez-vous mettre l'application à jour maintenant ?")
            .setPositiveButton("METTRE À JOUR") { _, _ ->
                enqueueBackgroundDownload(activity, apkUrl, versionName, silent = false)
            }
            .setNegativeButton("PLUS TARD", null)
            .create()
        dialog.setOnDismissListener { promptShowing = false }
        dialog.show()
    }

    private fun enqueueBackgroundDownload(activity: Activity, apkUrl: String, versionName: String, silent: Boolean) {
        try {
            val fileName = "HP-Travail-$versionName.apk"
            val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name.endsWith(".apk", true)) it.delete() }

            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("Mise à jour HP Travail")
                setDescription("Téléchargement de la version $versionName")
                setMimeType("application/vnd.android.package-archive")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName")
            }

            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_VERSION, versionName)
                .putString(KEY_FILE_NAME, fileName)
                .remove(KEY_READY_FILE)
                .remove(KEY_READY_VERSION)
                .apply()

            Toast.makeText(activity, "Téléchargement de la mise à jour lancé", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            if (!silent) Toast.makeText(activity, "Mise à jour impossible : ${shortError(e)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasActiveDownload(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id <= 0L) return false
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    clearDownloadState(context)
                    false
                } else {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
                }
            }
        } catch (_: Exception) { false }
    }

    internal fun downloadedApkFile(context: Context): File? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fileName = prefs.getString(KEY_FILE_NAME, null) ?: return null
        return File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"), fileName)
    }

    internal fun markDownloadReady(context: Context, apk: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, "").orEmpty()
        prefs.edit()
            .putString(KEY_READY_FILE, apk.name)
            .putString(KEY_READY_VERSION, version)
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_FILE_NAME)
            .apply()
    }

    internal fun clearDownloadState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DOWNLOAD_ID).remove(KEY_VERSION).remove(KEY_FILE_NAME).apply()
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
        if (apk.length() < 100_000L) throw IllegalStateException("fichier APK incomplet (${apk.length()} octets)")
        val magic = apk.inputStream().use { input -> ByteArray(2).also { if (input.read(it) != 2) throw IllegalStateException("APK illisible") } }
        if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) throw IllegalStateException("fichier reçu invalide")
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: throw IllegalStateException("Android ne reconnaît pas l'APK")
        if (archive.packageName != context.packageName) throw IllegalStateException("APK d'une autre application")
    }

    internal fun installerIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openInstaller(activity: Activity, apk: File) {
        markDownloadReady(activity, apk)
        tryInstallReady(activity)
    }

    private fun openConnection(url: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream;q=0.9, */*;q=0.8")
            setRequestProperty("User-Agent", "HP-Travail-Android")
            setRequestProperty("Cache-Control", "no-cache")
            connect()
        }
    }

    private fun showStatus(activity: Activity, silent: Boolean, message: String) {
        if (silent) return
        activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }

    private fun shortError(e: Exception): String {
        val text = e.message?.trim().orEmpty()
        return if (text.isNotBlank()) text.take(120) else e.javaClass.simpleName
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val av = pa.getOrElse(i) { 0 }; val bv = pb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
