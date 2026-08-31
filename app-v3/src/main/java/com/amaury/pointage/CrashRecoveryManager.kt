package com.amaury.pointage

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object CrashRecoveryManager {
    private const val PREFS = "recovery_state"
    private const val KEY_LAST_CRASH = "last_crash_at"
    private const val KEY_CRASH_COUNT = "recent_crash_count"
    private const val KEY_CRASH_REPORT = "last_crash_report"
    private const val WINDOW_MS = 10 * 60 * 1000L

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CRASH, 0L)
        val count = if (now - last <= WINDOW_MS) prefs.getInt(KEY_CRASH_COUNT, 0) + 1 else 1
        val incidentId = UUID.randomUUID().toString().take(8).uppercase(Locale.ROOT)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("inconnue")
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date(now))
        val report = buildString {
            appendLine("HoraTrack — rapport de crash")
            appendLine("Identifiant : $incidentId")
            appendLine("Date : $date")
            appendLine("Version : ${DiagnosticSanitizer.message(version, 80)}")
            appendLine("Android : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Téléphone : ${DiagnosticSanitizer.message("${Build.MANUFACTURER} ${Build.MODEL}", 120)}")
            appendLine("Thread : ${DiagnosticSanitizer.message(thread.name, 80)}")
            appendLine("Type : ${throwable::class.java.name}")
            appendLine("Message : ${DiagnosticSanitizer.message(throwable.message)}")
            appendLine()
            append(DiagnosticSanitizer.stackSummary(throwable))
        }.take(12000)

        prefs.edit()
            .putLong(KEY_LAST_CRASH, now)
            .putInt(KEY_CRASH_COUNT, count)
            .putString(KEY_CRASH_REPORT, report)
            .commit()
    }

    fun shouldOpenRecovery(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CRASH, 0L)
        val count = prefs.getInt(KEY_CRASH_COUNT, 0)
        return count >= 1 && System.currentTimeMillis() - last <= WINDOW_MS
    }

    fun getLastCrashReport(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CRASH_REPORT, null)
            ?.takeIf { it.isNotBlank() }
            ?: "Aucun détail technique n'a été enregistré pour cette erreur."
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
