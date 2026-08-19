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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"
    private const val PREFS = "update_checker"
    private const val KEY_AUTO_CHOICE_MADE = "auto_update_choice_made"
    private const val KEY_AUTO_ENABLED = "auto_update_enabled"
    private const val KEY_AUTO_STARTED_TAG = "auto_update_started_tag"

    fun check(activity: Activity, silent: Boolean = true) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_CHOICE_MADE, false)) {
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) showAutoUpdateConsent(activity) }
            return
        }

        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "HP-Travail-Android")

                if (connection.responseCode !in 200..299) {
                    showManualStatus(activity, silent, "Vérification impossible pour le moment")
                    return@Thread
                }

                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = release.optString("tag_name").trim()
                val versionName = tag.removePrefix("v")
                if (versionName.isBlank()) {
                    showManualStatus(activity, silent, "Vérification impossible pour le moment")
                    return@Thread
                }

                val currentVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
                if (compareVersions(versionName, currentVersion) <= 0) {
                    prefs.edit().remove(KEY_AUTO_STARTED_TAG).apply()
                    showManualStatus(activity, silent, "Aucune mise à jour disponible")
                    return@Thread
                }

                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                var apkName = "HP-Travail-$versionName.apk"
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            if (name.isNotBlank()) apkName = name
                            break
                        }
                    }
                }

                val destination = apkUrl
                if (destination == null) {
                    showManualStatus(activity, silent, "Nouvelle version détectée, mais l'APK n'est pas encore disponible")
                    return@Thread
                }

                val autoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (autoEnabled) {
                        if (prefs.getString(KEY_AUTO_STARTED_TAG, null) == tag) return@runOnUiThread
                        prefs.edit().putString(KEY_AUTO_STARTED_TAG, tag).apply()
                        Toast.makeText(activity, "Mise à jour $versionName : téléchargement…", Toast.LENGTH_SHORT).show()
                        startDownload(activity, versionName, destination, apkName, tag)
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("Mise à jour disponible")
                            .setMessage("HP Travail $versionName est disponible.")
                            .setPositiveButton("METTRE À JOUR") { _, _ -> startDownload(activity, versionName, destination, apkName, null) }
                            .setNegativeButton("Plus tard", null)
                            .show()
                    }
                }
            } catch (_: Exception) {
                showManualStatus(activity, silent, "Vérification impossible pour le moment")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun showManualStatus(activity: Activity, silent: Boolean, message: String) {
        if (silent) return
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAutoUpdateConsent(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO_CHOICE_MADE, false)) return
        AlertDialog.Builder(activity)
            .setTitle("Mises à jour automatiques")
            .setMessage("Autoriser HP Travail à télécharger automatiquement les nouvelles versions ? Android demandera toujours ta confirmation avant l'installation.")
            .setPositiveButton("OUI") { _, _ ->
                prefs.edit().putBoolean(KEY_AUTO_CHOICE_MADE, true).putBoolean(KEY_AUTO_ENABLED, true).apply()
                check(activity, silent = true)
            }
            .setNegativeButton("NON") { _, _ ->
                prefs.edit().putBoolean(KEY_AUTO_CHOICE_MADE, true).putBoolean(KEY_AUTO_ENABLED, false).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun startDownload(activity: Activity, versionName: String, apkUrl: String, apkName: String, autoTag: String?) {
        try {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("HP Travail $versionName")
                setDescription("Téléchargement de la mise à jour")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, apkName)
            }
            val id = manager.enqueue(request)
            Toast.makeText(activity, "Téléchargement de la mise à jour…", Toast.LENGTH_SHORT).show()
            waitForDownload(activity, manager, id, autoTag)
        } catch (e: Exception) {
            if (autoTag != null) activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().remove(KEY_AUTO_STARTED_TAG).apply()
            Toast.makeText(activity, "Impossible de démarrer la mise à jour", Toast.LENGTH_LONG).show()
        }
    }

    private fun waitForDownload(activity: Activity, manager: DownloadManager, id: Long, autoTag: String?) {
        Thread {
            var finished = false
            while (!finished) {
                val cursor = runCatching { manager.query(DownloadManager.Query().setFilterById(id)) }.getOrNull() ?: return@Thread
                cursor.use {
                    if (!it.moveToFirst()) return@use
                    val index = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val state = if (index >= 0) it.getInt(index) else DownloadManager.STATUS_FAILED
                    when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            finished = true
                            activity.runOnUiThread { installDownloadedApk(activity, manager, id) }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            finished = true
                            if (autoTag != null) activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().remove(KEY_AUTO_STARTED_TAG).apply()
                            activity.runOnUiThread { Toast.makeText(activity, "Échec du téléchargement de la mise à jour", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                if (!finished) Thread.sleep(800L)
            }
        }.start()
    }

    private fun installDownloadedApk(activity: Activity, manager: DownloadManager, id: Long) {
        try {
            val uri = manager.getUriForDownloadedFile(id) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, "Autorise HP Travail à installer les mises à jour", Toast.LENGTH_LONG).show()
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                return
            }
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(activity, "Impossible d'ouvrir l'installation", Toast.LENGTH_LONG).show()
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val av = pa.getOrElse(i) { 0 }
            val bv = pb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
