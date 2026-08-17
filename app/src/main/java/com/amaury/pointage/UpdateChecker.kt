package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"
    private const val PREFS = "update_checker"

    fun check(activity: Activity, silent: Boolean = true) {
        Thread {
            try {
                val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "PointageTravail-Android")

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) return@Thread

                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(json)
                val tag = release.optString("tag_name")
                val versionName = tag.removePrefix("v")
                val latestCode = versionName.substringAfterLast('.').toLongOrNull() ?: return@Thread
                val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
                }

                if (latestCode <= currentCode) return@Thread

                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }
                val releaseUrl = release.optString(
                    "html_url",
                    "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/latest"
                )
                val destination = apkUrl?.takeIf { it.isNotBlank() } ?: releaseUrl

                val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                val lastShown = prefs.getString("last_shown_tag", null)
                if (silent && lastShown == tag) return@Thread

                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    AlertDialog.Builder(activity)
                        .setTitle("Mise à jour disponible")
                        .setMessage("Pointage Travail $versionName est disponible.\n\nLa mise à jour sera ouverte depuis la publication officielle GitHub.")
                        .setPositiveButton("METTRE À JOUR") { _, _ ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
                        }
                        .setNegativeButton("PLUS TARD", null)
                        .show()
                    prefs.edit().putString("last_shown_tag", tag).apply()
                }
            } catch (_: Exception) {
                // Une absence de réseau ne doit jamais empêcher l'application de démarrer.
            }
        }.start()
    }
}
