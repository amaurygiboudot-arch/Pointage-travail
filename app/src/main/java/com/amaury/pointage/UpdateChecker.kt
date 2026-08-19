package com.amaury.pointage

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"

    fun check(activity: Activity, silent: Boolean = true) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "HP-Travail-Android")

                if (connection.responseCode !in 200..299) {
                    showStatus(activity, silent, "Vérification impossible pour le moment")
                    return@Thread
                }

                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = release.optString("tag_name").trim()
                val versionName = tag.removePrefix("v")
                if (versionName.isBlank()) {
                    showStatus(activity, silent, "Vérification impossible pour le moment")
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
                        if (name.equals("HP-Travail.apk", ignoreCase = true) || name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            if (apkUrl != null) break
                        }
                    }
                }

                val destination = apkUrl
                if (destination == null) {
                    showStatus(activity, silent, "Nouvelle version détectée, mais l'APK n'est pas encore disponible")
                    return@Thread
                }

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (silent) {
                        Toast.makeText(activity, "Mise à jour HP Travail $versionName disponible", Toast.LENGTH_SHORT).show()
                    } else {
                        downloadWithAndroid(activity, destination, versionName)
                    }
                }
            } catch (_: Exception) {
                showStatus(activity, silent, "Vérification impossible pour le moment")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun downloadWithAndroid(activity: Activity, apkUrl: String, versionName: String) {
        try {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("HP Travail $versionName")
                .setDescription("Téléchargement de la mise à jour")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "HP-Travail-$versionName.apk")

            manager.enqueue(request)
            Toast.makeText(activity, "Téléchargement lancé en arrière-plan", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "Impossible de lancer le téléchargement", Toast.LENGTH_LONG).show()
        }
    }

    private fun showStatus(activity: Activity, silent: Boolean, message: String) {
        if (silent) return
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
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
