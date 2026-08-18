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

    fun check(activity: Activity, silent: Boolean = true) {
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

                if (compareVersions(versionName, currentVersion) <= 0) return@Thread

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

                val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                val lastShown = prefs.getString("last_shown_tag", null)
                if (silent && lastShown == tag) return@Thread

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    showUpdateDialog(activity, versionName, notes, destination, apkName, apkSize)
                    prefs.edit().putString("last_shown_tag", tag).apply()
                }
            } catch (_: Exception) {
                // Une absence de réseau ne doit jamais empêcher l'application de démarrer.
            }
        }.start()
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        notes: String,
        apkUrl: String,
        apkName: String,
        apkSize: Long
    ) {
        val appearance = activity.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = appearance.getString("mode", "auto") ?: "auto"
        val night = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && night)

        val background = if (dark) Color.parseColor("#0B0B0B") else Color.parseColor("#F7F3EA")
        val panel = if (dark) Color.parseColor("#181818") else Color.WHITE
        val text = if (dark) Color.WHITE else Color.parseColor("#151515")
        val secondary = if (dark) Color.parseColor("#CFC8BA") else Color.parseColor("#57514A")
        val gold = Color.parseColor("#D6A84B")
        val goldLight = Color.parseColor("#F3D58A")

        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        fun rounded(color: Int, radius: Int = 18, strokeColor: Int? = null): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = rounded(background, 24, gold)
        }

        val crown = TextView(activity).apply {
            this.text = "♛"
            textSize = 31f
            gravity = Gravity.CENTER
            setTextColor(gold)
        }
        val title = TextView(activity).apply {
            this.text = "MISE À JOUR DISPONIBLE"
            textSize = 19f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(goldLight)
            letterSpacing = 0.08f
            setPadding(0, dp(4), 0, dp(3))
        }
        val version = TextView(activity).apply {
            this.text = "HP Travail  •  Version $versionName"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(text)
            setPadding(0, 0, 0, dp(14))
        }

        val infoPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(panel, 16)
        }
        infoPanel.addView(TextView(activity).apply {
            this.text = "Une nouvelle version de l'application est prête. La mise à jour se télécharge directement ici, sans passer par GitHub."
            textSize = 14f
            setTextColor(text)
        })
        if (apkSize > 0L) {
            infoPanel.addView(TextView(activity).apply {
                this.text = "Taille : ${formatBytes(apkSize)}"
                textSize = 12f
                setTextColor(secondary)
                setPadding(0, dp(7), 0, 0)
            })
        }

        if (notes.isNotBlank()) {
            val notesTitle = TextView(activity).apply {
                this.text = "NOUVEAUTÉS"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(gold)
                setPadding(0, dp(16), 0, dp(6))
            }
            val notesText = TextView(activity).apply {
                this.text = cleanReleaseNotes(notes)
                textSize = 13f
                setTextColor(secondary)
                maxLines = 6
            }
            container.addView(crown)
            container.addView(title)
            container.addView(version)
            container.addView(infoPanel)
            container.addView(notesTitle)
            container.addView(notesText)
        } else {
            container.addView(crown)
            container.addView(title)
            container.addView(version)
            container.addView(infoPanel)
        }

        val updateButton = Button(activity).apply {
            text = "METTRE À JOUR"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#15100A"))
            background = rounded(goldLight, 14)
            isAllCaps = false
        }
        val laterButton = Button(activity).apply {
            text = "Plus tard"
            textSize = 14f
            setTextColor(secondary)
            background = rounded(panel, 14)
            isAllCaps = false
        }
        container.addView(updateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        container.addView(laterButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(container)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(scroll)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        laterButton.setOnClickListener { dialog.dismiss() }
        updateButton.setOnClickListener {
            updateButton.isEnabled = false
            updateButton.text = "PRÉPARATION…"
            startDownload(activity, dialog, versionName, apkUrl, apkName, background, panel, text, secondary, gold, goldLight)
        }

        dialog.show()
    }

    private fun startDownload(
        activity: Activity,
        previousDialog: AlertDialog,
        versionName: String,
        apkUrl: String,
        apkName: String,
        background: Int,
        panel: Int,
        text: Int,
        secondary: Int,
        gold: Int,
        goldLight: Int
    ) {
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
            previousDialog.dismiss()
            showDownloadProgress(activity, downloadManager, downloadId, versionName, background, panel, text, secondary, gold, goldLight)
        } catch (e: Exception) {
            Toast.makeText(activity, "Impossible de démarrer la mise à jour : ${e.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDownloadProgress(
        activity: Activity,
        manager: DownloadManager,
        downloadId: Long,
        versionName: String,
        background: Int,
        panel: Int,
        text: Int,
        secondary: Int,
        gold: Int,
        goldLight: Int
    ) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        fun rounded(color: Int, radius: Int = 18, strokeColor: Int? = null): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
            background = rounded(background, 24, gold)
        }
        box.addView(TextView(activity).apply {
            this.text = "♛  HP TRAVAIL"
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(goldLight)
        })
        box.addView(TextView(activity).apply {
            this.text = "Mise à jour vers la version $versionName"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(text)
            setPadding(0, dp(7), 0, dp(16))
        })

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(panel)
        }
        val percent = TextView(activity).apply {
            this.text = "0 %"
            gravity = Gravity.CENTER
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(goldLight)
            setPadding(0, dp(10), 0, dp(2))
        }
        val status = TextView(activity).apply {
            this.text = "Téléchargement en cours…"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(secondary)
        }
        val cancel = Button(activity).apply {
            this.text = "Annuler"
            isAllCaps = false
            setTextColor(secondary)
            background = rounded(panel, 14)
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
            dialog.dismiss()
            Toast.makeText(activity, "Mise à jour annulée", Toast.LENGTH_SHORT).show()
        }
        dialog.show()

        Thread {
            var finished = false
            while (!finished && !activity.isFinishing && !activity.isDestroyed) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = runCatching { manager.query(query) }.getOrNull()
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
                Toast.makeText(activity, "Autorise HP Travail à installer cette mise à jour, puis relance l'installation.", Toast.LENGTH_LONG).show()
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
