package com.amaury.pointage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"
    @Volatile private var updateInProgress = false

    fun check(activity: Activity, silent: Boolean = true) {
        if (updateInProgress) return
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
                val versionName = release.optString("tag_name").trim().removePrefix("v")
                if (versionName.isBlank()) return@Thread

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
                val destination = apkUrl ?: return@Thread
                updateInProgress = true
                downloadAndOpenInstaller(activity, destination, versionName, silent)
            } catch (_: Exception) {
                updateInProgress = false
                showStatus(activity, silent, "Vérification impossible pour le moment")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun downloadAndOpenInstaller(activity: Activity, apkUrl: String, versionName: String, silent: Boolean) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                activity.runOnUiThread {
                    if (!silent) Toast.makeText(activity, "Téléchargement de la mise à jour…", Toast.LENGTH_SHORT).show()
                }

                val dir = File(activity.getExternalFilesDir("Download"), "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.name.endsWith(".apk", true)) it.delete() }
                val apk = File(dir, "HP-Travail-$versionName.apk")

                connection = URL(apkUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "HP-Travail-Android")
                connection.connect()
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                connection.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                if (!apk.exists() || apk.length() < 1024L) throw IllegalStateException("APK invalide")

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        updateInProgress = false
                        return@runOnUiThread
                    }
                    try {
                        val uri: Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update-files", apk)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(activity, "Impossible d'ouvrir l'installation", Toast.LENGTH_LONG).show()
                    } finally {
                        updateInProgress = false
                    }
                }
            } catch (_: Exception) {
                updateInProgress = false
                showStatus(activity, silent, "Téléchargement de la mise à jour impossible")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun showStatus(activity: Activity, silent: Boolean, message: String) {
        if (silent) return
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
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
