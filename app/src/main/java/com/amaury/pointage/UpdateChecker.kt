package com.amaury.pointage

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
        if (updateInProgress) {
            if (!silent) Toast.makeText(activity, "Une mise à jour est déjà en cours", Toast.LENGTH_SHORT).show()
            return
        }

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

                updateInProgress = true
                downloadAndOpenInstaller(activity, apkUrl, versionName, silent)
            } catch (e: Exception) {
                updateInProgress = false
                showStatus(activity, silent, "Vérification impossible : ${shortError(e)}")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun downloadAndOpenInstaller(activity: Activity, apkUrl: String, versionName: String, silent: Boolean) {
        val progress = arrayOfNulls<ProgressDialog>(1)
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                progress[0] = ProgressDialog(activity).apply {
                    setTitle("Mise à jour HP Travail")
                    setMessage("Connexion au serveur…")
                    setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                    max = 100
                    isIndeterminate = true
                    setCancelable(false)
                    show()
                }
            }
        }

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val dir = File(activity.getExternalFilesDir("Download"), "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.name.endsWith(".apk", true)) it.delete() }
                val apk = File(dir, "HP-Travail-$versionName.apk")

                connection = openConnection(apkUrl, 15_000, 120_000)
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("serveur HTTP $code")

                val total = connection.contentLengthLong
                activity.runOnUiThread {
                    progress[0]?.apply {
                        isIndeterminate = total <= 0L
                        setMessage(if (total > 0L) "Téléchargement… 0 %" else "Téléchargement en cours…")
                    }
                }

                var received = 0L
                var lastPercent = -1
                connection.inputStream.buffered(64 * 1024).use { input ->
                    apk.outputStream().buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read

                            if (total > 0L) {
                                val percent = ((received * 100L) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    activity.runOnUiThread {
                                        progress[0]?.apply {
                                            isIndeterminate = false
                                            this.progress = percent
                                            setMessage("Téléchargement… $percent %")
                                        }
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                activity.runOnUiThread {
                    progress[0]?.apply {
                        isIndeterminate = true
                        setMessage("Vérification de l'APK…")
                    }
                }

                validateApk(activity, apk)

                activity.runOnUiThread {
                    progress[0]?.dismiss()
                    progress[0] = null
                    if (activity.isFinishing || activity.isDestroyed) {
                        updateInProgress = false
                        return@runOnUiThread
                    }
                    openInstaller(activity, apk)
                    updateInProgress = false
                }
            } catch (e: Exception) {
                updateInProgress = false
                activity.runOnUiThread {
                    progress[0]?.dismiss()
                    progress[0] = null
                }
                showStatus(activity, false, "Mise à jour impossible : ${shortError(e)}")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun openInstaller(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Autorise HP Travail à installer la mise à jour, puis relance Vérifier les mises à jour.", Toast.LENGTH_LONG).show()
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            activity.startActivity(settingsIntent)
            return
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update-files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun validateApk(activity: Activity, apk: File) {
        if (!apk.exists()) throw IllegalStateException("fichier APK absent")
        if (apk.length() < 100_000L) throw IllegalStateException("fichier APK incomplet (${apk.length()} octets)")

        val magic = apk.inputStream().use { input -> ByteArray(2).also { if (input.read(it) != 2) throw IllegalStateException("APK illisible") } }
        if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) throw IllegalStateException("fichier reçu invalide")

        val archive = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IllegalStateException("Android ne reconnaît pas l'APK")
        if (archive.packageName != activity.packageName) throw IllegalStateException("APK d'une autre application")
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
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
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
            val av = pa.getOrElse(i) { 0 }
            val bv = pb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
