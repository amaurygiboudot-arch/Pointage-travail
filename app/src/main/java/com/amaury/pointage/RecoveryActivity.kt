package com.amaury.pointage

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class RecoveryActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.alpha = 1f
        window.setBackgroundDrawableResource(android.R.color.white)
        buildUi()
        RecoveryUpdater.checkAndRepair(this, status, progress, retry)
    }

    private fun buildUi() {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        fun buttonBg(enabled: Boolean = true) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(if (enabled) Color.rgb(24, 24, 24) else Color.rgb(105, 105, 105))
        }
        fun styleButton(button: Button) {
            button.isAllCaps = false
            button.textSize = 15f
            button.setTextColor(Color.WHITE)
            button.backgroundTintList = null
            button.background = buttonBg(button.isEnabled)
            button.alpha = 1f
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(38), dp(24), dp(28))
            setBackgroundColor(Color.rgb(250, 250, 250))
            alpha = 1f
        }
        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(250, 250, 250))
            alpha = 1f
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        content.addView(TextView(this).apply {
            text = "♛  HP TRAVAIL"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 20, 20))
            alpha = 1f
        })
        content.addView(TextView(this).apply {
            text = "MODE RÉCUPÉRATION"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(170, 25, 25))
            setPadding(0, dp(12), 0, dp(22))
            alpha = 1f
        })

        status = TextView(this).apply {
            text = "Vérification d'une mise à jour de réparation…"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(dp(4), 0, dp(4), dp(18))
            alpha = 1f
        }
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            alpha = 1f
        }
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))

        val errorDetails = TextView(this).apply {
            text = CrashRecoveryManager.getLastCrashReport(this@RecoveryActivity)
            textSize = 13f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(235, 235, 235))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
            visibility = View.GONE
            alpha = 1f
        }
        content.addView(errorDetails, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        content.addView(Button(this).apply {
            text = "VOIR L’ERREUR"
            styleButton(this)
            setOnClickListener {
                val showing = errorDetails.visibility == View.VISIBLE
                errorDetails.visibility = if (showing) View.GONE else View.VISIBLE
                text = if (showing) "VOIR L’ERREUR" else "MASQUER L’ERREUR"
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(18) })

        content.addView(Button(this).apply {
            text = "PARTAGER DANS LA BOÎTE À IDÉES"
            styleButton(this)
            setOnClickListener {
                val report = CrashRecoveryManager.getLastCrashReport(this@RecoveryActivity)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "HP Travail — rapport d’erreur")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(share, "Partager le rapport d’erreur"))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        retry = Button(this).apply {
            text = "RÉESSAYER LA RÉPARATION"
            isEnabled = false
            styleButton(this)
            setOnClickListener { RecoveryUpdater.checkAndRepair(this@RecoveryActivity, status, progress, this) }
        }
        content.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        content.addView(Button(this).apply {
            text = "OUVRIR HP TRAVAIL QUAND MÊME"
            styleButton(this)
            setOnClickListener {
                CrashRecoveryManager.clear(this@RecoveryActivity)
                startActivity(Intent(this@RecoveryActivity, MainActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        setContentView(root)
    }
}

object RecoveryUpdater {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"

    fun checkAndRepair(activity: Activity, status: TextView, progress: ProgressBar, retry: Button) {
        retry.isEnabled = false
        retry.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 24f; setColor(Color.rgb(105, 105, 105)) }
        progress.progress = 0
        status.text = "Recherche d'une version de réparation…"
        Thread {
            runCatching {
                val releaseConnection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "HP-Travail-Recovery")
                }
                val json = releaseConnection.inputStream.bufferedReader().use { it.readText() }
                releaseConnection.disconnect()
                val release = JSONObject(json)
                val tag = release.optString("tag_name").removePrefix("v")
                val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
                if (compareVersions(tag, current) <= 0) {
                    activity.runOnUiThread {
                        status.text = "Aucune version plus récente n'est disponible. Tu peux réessayer HP Travail ou attendre une nouvelle mise à jour."
                        retry.isEnabled = true
                        retry.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 24f; setColor(Color.rgb(24, 24, 24)) }
                        retry.setTextColor(Color.WHITE)
                    }
                    return@Thread
                }

                val assets = release.optJSONArray("assets") ?: error("Aucun fichier de mise à jour")
                var apkUrl: String? = null
                var shaUrl: String? = null
                for (i in 0 until assets.length()) {
                    val item = assets.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    val url = item.optString("browser_download_url")
                    if (name.endsWith(".apk", true)) apkUrl = url
                    if (name.equals("SHA256.txt", true)) shaUrl = url
                }
                val apk = apkUrl ?: error("APK de réparation introuvable")
                val expectedSha = shaUrl?.let { fetchExpectedSha(it) }
                    ?: error("Empreinte SHA-256 de la mise à jour introuvable")

                activity.runOnUiThread { status.text = "Téléchargement sécurisé de HP Travail $tag…" }
                downloadAndInstall(activity, apk, expectedSha, status, progress, retry)
            }.onFailure { error ->
                activity.runOnUiThread {
                    status.text = "Réparation indisponible : ${error.message ?: "erreur réseau"}"
                    retry.isEnabled = true
                    retry.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 24f; setColor(Color.rgb(24, 24, 24)) }
                    retry.setTextColor(Color.WHITE)
                }
            }
        }.start()
    }

    private fun fetchExpectedSha(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("User-Agent", "HP-Travail-Recovery")
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: error("Empreinte SHA-256 invalide")
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String, expectedSha: String, status: TextView, progress: ProgressBar, retry: Button) {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "hp-travail-recovery.apk"
        val target = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("HP Travail — réparation")
            setDescription("Téléchargement de la mise à jour de secours")
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        val id = manager.enqueue(request)
        Thread {
            var done = false
            while (!done && !activity.isFinishing) {
                val cursor = manager.query(DownloadManager.Query().setFilterById(id))
                cursor.use {
                    if (!it.moveToFirst()) error("Téléchargement introuvable")
                    val state = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val pct = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                    activity.runOnUiThread { progress.progress = pct }
                    when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> done = true
                        DownloadManager.STATUS_FAILED -> error("Échec du téléchargement")
                    }
                }
                if (!done) Thread.sleep(400)
            }
            if (!done) return@Thread
            activity.runOnUiThread { status.text = "Vérification de l'intégrité de la mise à jour…" }
            val actualSha = sha256(target)
            if (!actualSha.equals(expectedSha, true)) {
                target.delete()
                activity.runOnUiThread {
                    status.text = "Mise à jour refusée : l'empreinte de sécurité ne correspond pas."
                    retry.isEnabled = true
                }
                return@Thread
            }
            activity.runOnUiThread {
                CrashRecoveryManager.clear(activity)
                launchInstaller(activity, manager, id, status, retry)
            }
        }.start()
    }

    private fun launchInstaller(activity: Activity, manager: DownloadManager, id: Long, status: TextView, retry: Button) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            status.text = "Autorise HP Travail à installer des mises à jour, puis reviens ici."
            retry.isEnabled = true
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = manager.getUriForDownloadedFile(id)
        if (uri == null) {
            status.text = "APK téléchargé mais installateur indisponible."
            retry.isEnabled = true
            return
        }
        status.text = "Mise à jour vérifiée. Ouverture de l'installateur Android…"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            status.text = "Impossible d'ouvrir l'installateur Android."
            retry.isEnabled = true
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
