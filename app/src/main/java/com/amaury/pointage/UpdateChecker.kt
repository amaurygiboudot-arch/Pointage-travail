package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object UpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"
    private const val PREFS = "update_checker"
    private const val KEY_AUTO_CHOICE_MADE = "auto_update_choice_made"
    private const val KEY_AUTO_ENABLED = "auto_update_enabled"
    private const val KEY_AUTO_STARTED_TAG = "auto_update_started_tag"

    fun check(activity: Activity, silent: Boolean = true) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_AUTO_CHOICE_MADE, false)) {
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showAutoUpdateConsent(activity)
                }
            }
            return
        }

        Thread {
            try {
                val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "HP-Travail-Android")

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    return@Thread
                }

                val json = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val release = JSONObject(json)
                val tag = release.optString("tag_name").trim()
                val versionName = tag.removePrefix("v").ifBlank { return@Thread }
                val currentVersion = activity.packageManager
                    .getPackageInfo(activity.packageName, 0)
                    .versionName
                    .orEmpty()

                if (compareVersions(versionName, currentVersion) <= 0) {
                    prefs.edit().remove(KEY_AUTO_STARTED_TAG).apply()
                    return@Thread
                }

                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                var apkName = "HP-Travail-$versionName.apk"
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            apkName = name.ifBlank { apkName }
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                val destination = apkUrl ?: return@Thread
                val notes = release.optString("body").trim()
                val autoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false)

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                    if (autoEnabled) {
                        if (prefs.getString(KEY_AUTO_STARTED_TAG, null) == tag) return@runOnUiThread
                        prefs.edit().putString(KEY_AUTO_STARTED_TAG, tag).apply()
                        Toast.makeText(activity, "Nouvelle version $versionName détectée — téléchargement automatique…", Toast.LENGTH_SHORT).show()
                        startDownload(
                            activity = activity,
                            previousDialog = null,
                            versionName = versionName,
                            apkUrl = destination,
                            apkName = apkName,
                            autoTag = tag
                        )
                    } else {
                        val lastShown = prefs.getString("last_shown_tag", null)
                        if (silent && lastShown == tag) return@runOnUiThread
                        showUpdateDialog(activity, versionName, notes, destination, apkName, apkSize)
                        prefs.edit().putString("last_shown_tag", tag).apply()
                    }
                }
            } catch (_: Exception) {
                // Une absence de réseau ne doit jamais empêcher l'application de démarrer.
            }
        }.start()
    }

    private data class Theme(
        val background: Int,
        val panel: Int,
        val text: Int,
        val secondary: Int,
        val gold: Int,
        val goldLight: Int
    )

    private fun theme(activity: Activity): Theme {
        val appearance = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = appearance.getString("mode", "auto") ?: "auto"
        val night = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && night)
        return Theme(
            background = if (dark) Color.parseColor("#0B0B0B") else Color.parseColor("#F7F3EA"),
            panel = if (dark) Color.parseColor("#181818") else Color.WHITE,
            text = if (dark) Color.WHITE else Color.parseColor("#151515"),
            secondary = if (dark) Color.parseColor("#CFC8BA") else Color.parseColor("#57514A"),
            gold = Color.parseColor("#D6A84B"),
            goldLight = Color.parseColor("#F3D58A")
        )
    }

    private fun rounded(activity: Activity, color: Int, radius: Int = 18, strokeColor: Int? = null): GradientDrawable {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }

    private fun showAutoUpdateConsent(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO_CHOICE_MADE, false)) return

        val t = theme(activity)
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = rounded(activity, t.background, 24, t.gold)
        }

        box.addView(TextView(activity).apply {
            text = "♛"
            gravity = Gravity.CENTER
            textSize = 32f
            setTextColor(t.gold)
        })
        box.addView(TextView(activity).apply {
            text = "MISES À JOUR AUTOMATIQUES"
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(t.goldLight)
            letterSpacing = 0.06f
            setPadding(0, dp(5), 0, dp(12))
        })

        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(activity, t.panel, 16)
        }
        info.addView(TextView(activity).apply {
            text = "Souhaites-tu autoriser HP Travail à télécharger automatiquement les nouvelles versions lorsqu'elles sont disponibles ?"
            textSize = 14f
            setTextColor(t.text)
        })
        info.addView(TextView(activity).apply {
            text = "• Oui : téléchargement automatique et ouverture de l'installation.\n• Non : l'application te demandera avant chaque mise à jour.\n\nAndroid demandera toujours ta confirmation finale avant d'installer l'APK."
            textSize = 13f
            setTextColor(t.secondary)
            setPadding(0, dp(10), 0, 0)
        })
        box.addView(info)

        val yesButton = Button(activity).apply {
            text = "OUI, ACTIVER"
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#15100A"))
            background = rounded(activity, t.goldLight, 14)
        }
        val noButton = Button(activity).apply {
            text = "Non, me demander"
            isAllCaps = false
            textSize = 14f
            setTextColor(t.secondary)
            background = rounded(activity, t.panel, 14)
        }
        box.addView(yesButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        box.addView(noButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(activity).setView(box).create()
        dialog.setCancelable(false)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        yesButton.setOnClickListener {
            prefs.edit()
                .putBoolean(KEY_AUTO_CHOICE_MADE, true)
                .putBoolean(KEY_AUTO_ENABLED, true)
                .apply()
            dialog.dismiss()
            Toast.makeText(activity, "Mises à jour automatiques activées", Toast.LENGTH_SHORT).show()
            check(activity, silent = true)
        }
        noButton.setOnClickListener {
            prefs.edit()
                .putBoolean(KEY_AUTO_CHOICE_MADE, true)
                .putBoolean(KEY_AUTO_ENABLED, false)
                .apply()
            dialog.dismiss()
            Toast.makeText(activity, "Les mises à jour resteront manuelles", Toast.LENGTH_SHORT).show()
            check(activity, silent = true)
        }
        dialog.show()
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        notes: String,
        apkUrl: String,
        apkName: String,
        apkSize: Long
    ) {
        val t = theme(activity)
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = rounded(activity, t.background, 24, t.gold)
        }

        val crown = TextView(activity).apply {
            text = "♛"
            textSize = 31f
            gravity = Gravity.CENTER
            setTextColor(t.gold)
        }
        val title = TextView(activity).apply {
            text = "MISE À JOUR DISPONIBLE"
            textSize = 19f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(t.goldLight)
            letterSpacing = 0.08f
            setPadding(0, dp(4), 0, dp(3))
        }
        val version = TextView(activity).apply {
            text = "HP Travail  •  Version $versionName"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(t.text)
            setPadding(0, 0, 0, dp(14))
        }

        val infoPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(activity, t.panel, 16)
        }
        infoPanel.addView(TextView(activity).apply {
            text = "Une nouvelle version de l'application est prête. La mise à jour se télécharge directement ici, sans passer par GitHub."
            textSize = 14f
            setTextColor(t.text)
        })
        if (apkSize > 0L) {
            infoPanel.addView(TextView(activity).apply {
                text = "Taille : ${formatBytes(apkSize)}"
                textSize = 12f
                setTextColor(t.secondary)
                setPadding(0, dp(7), 0, 0)
            })
        }

        container.addView(crown)
        container.addView(title)
        container.addView(version)
        container.addView(infoPanel)

        if (notes.isNotBlank()) {
            container.addView(TextView(activity).apply {
                text = "NOUVEAUTÉS"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(t.gold)
                setPadding(0, dp(16), 0, dp(6))
            })
            container.addView(TextView(activity).apply {
                text = cleanReleaseNotes(notes)
                textSize = 13f
                setTextColor(t.secondary)
                maxLines = 6
            })
        }

        val updateButton = Button(activity).apply {
            text = "METTRE À JOUR"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#15100A"))
            background = rounded(activity, t.goldLight, 14)
            isAllCaps = false
        }
        val laterButton = Button(activity).apply {
            text = "Plus tard"
            textSize = 14f
            setTextColor(t.secondary)
            background = rounded(activity, t.panel, 14)
            isAllCaps = false
        }
        container.addView(updateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        container.addView(laterButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(container)
        }

        val dialog = AlertDialog.Builder(activity).setView(scroll).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        laterButton.setOnClickListener { dialog.dismiss() }
        updateButton.setOnClickListener {
            updateButton.isEnabled = false
            updateButton.text = "PRÉPARATION…"
            startDownload(activity, dialog, versionName, apkUrl, apkName, autoTag = null)
        }
        dialog.show()
    }

    private fun startDownload(
        activity: Activity,
        previousDialog: AlertDialog?,
        versionName: String,
        apkUrl: String,
        apkName: String,
        autoTag: String?
    ) {
        val t = theme(activity)
        try {
            val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val safeName = apkName.ifBlank { "HP-Travail-$versionName.apk" }
            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("HP Travail $versionName")
                setDescription("Téléchargement de la mise à jour")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, safeName)
            }
            val downloadId = downloadManager.enqueue(request)
            previousDialog?.dismiss()
            showDownloadProgress(activity, downloadManager, downloadId, versionName, autoTag, t)
        } catch (e: Exception) {
            if (autoTag != null) {
                activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().remove(KEY_AUTO_STARTED_TAG).apply()
            }
            Toast.makeText(activity, "Impossible de démarrer la mise à jour : ${e.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDownloadProgress(
        activity: Activity,
        manager: DownloadManager,
        downloadId: Long,
        versionName: String,
        autoTag: String?,
        t: Theme
    ) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
            background = rounded(activity, t.background, 24, t.gold)
        }
        box.addView(TextView(activity).apply {
            text = "♛  HP TRAVAIL"
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(t.goldLight)
        })
        box.addView(TextView(activity).apply {
            text = "Mise à jour vers la version $versionName"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(t.text)
            setPadding(0, dp(7), 0, dp(16))
        })

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(t.gold)
            progressBackgroundTintList = ColorStateList.valueOf(t.panel)
        }
        val percent = TextView(activity).apply {
            text = "0 %"
            gravity = Gravity.CENTER
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(t.goldLight)
            setPadding(0, dp(10), 0, dp(2))
        }
        val status = TextView(activity).apply {
            text = if (autoTag != null) "Mise à jour automatique en cours…" else "Téléchargement en cours…"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(t.secondary)
        }
        val cancel = Button(activity).apply {
            text = "Annuler"
            isAllCaps = false
            setTextColor(t.secondary)
            background = rounded(activity, t.panel, 14)
        }

        box.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        box.addView(percent)
        box.addView(status)
        box.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })

        val dialog = AlertDialog.Builder(activity).setView(box).create()
        dialog.setCancelable(false)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        cancel.setOnClickListener {
            manager.remove(downloadId)
            if (autoTag != null) {
                activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().remove(KEY_AUTO_STARTED_TAG).apply()
            }
            dialog.dismiss()
            Toast.makeText(activity, "Mise à jour annulée", Toast.LENGTH_SHORT).show()
        }
        dialog.show()

        Thread {
            var finished = false
            while (!finished && !activity.isFinishing && !activity.isDestroyed) {
                val cursor = runCatching { manager.query(DownloadManager.Query().setFilterById(downloadId)) }.getOrNull()
                if (cursor == null) break
                cursor.use {
                    if (!it.moveToFirst()) {
                        finished = true
                        return@use
                    }
                    val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloadedIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val state = if (statusIndex >= 0) it.getInt(statusIndex) else DownloadManager.STATUS_FAILED
                    val downloaded = if (downloadedIndex >= 0) it.getLong(downloadedIndex) else 0L
                    val total = if (totalIndex >= 0) it.getLong(totalIndex) else -1L
                    val value = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0

                    activity.runOnUiThread {
                        if (!dialog.isShowing) return@runOnUiThread
                        progress.progress = value
                        percent.text = if (total > 0) "$value %" else "…"
                        status.text = when (state) {
                            DownloadManager.STATUS_PENDING -> "Préparation du téléchargement…"
                            DownloadManager.STATUS_PAUSED -> "Téléchargement en pause…"
                            DownloadManager.STATUS_RUNNING -> if (total > 0) "${formatBytes(downloaded)} / ${formatBytes(total)}" else "Téléchargement en cours…"
                            DownloadManager.STATUS_SUCCESSFUL -> "Téléchargement terminé ✓"
                            else -> "Échec du téléchargement"
                        }
                    }

                    when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            finished = true
                            activity.runOnUiThread {
                                if (dialog.isShowing) dialog.dismiss()
                                installDownloadedApk(activity, manager, downloadId)
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            finished = true
                            if (autoTag != null) {
                                activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().remove(KEY_AUTO_STARTED_TAG).apply()
                            }
                            val reason = if (reasonIndex >= 0) it.getInt(reasonIndex) else -1
                            activity.runOnUiThread {
                                if (dialog.isShowing) dialog.dismiss()
                                Toast.makeText(activity, "Échec du téléchargement (code $reason). Réessaie avec une connexion stable.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                if (!finished) Thread.sleep(500)
            }
        }.start()
    }

    private fun installDownloadedApk(activity: Activity, manager: DownloadManager, downloadId: Long) {
        try {
            val uri = manager.getUriForDownloadedFile(downloadId)
            if (uri == null) {
                Toast.makeText(activity, "APK téléchargé, mais impossible d'ouvrir l'installation.", Toast.LENGTH_LONG).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, "Autorise HP Travail à installer cette mise à jour. Android te demandera ensuite confirmation.", Toast.LENGTH_LONG).show()
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                activity.startActivity(settingsIntent)
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Impossible de lancer l'installation : ${e.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
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

    private fun cleanReleaseNotes(value: String): String = value
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace("**", "")
        .replace("__", "")
        .trim()
        .take(700)

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 Ko"
        val kb = bytes / 1024.0
        return if (kb < 1024.0) String.format(Locale.FRANCE, "%.0f Ko", kb)
        else String.format(Locale.FRANCE, "%.2f Mo", kb / 1024.0)
    }
}
