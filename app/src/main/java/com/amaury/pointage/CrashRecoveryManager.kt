package com.amaury.pointage

import android.content.Context

object CrashRecoveryManager {
    private const val PREFS = "recovery_state"
    private const val KEY_LAST_CRASH = "last_crash_at"
    private const val KEY_CRASH_COUNT = "recent_crash_count"
    private const val WINDOW_MS = 10 * 60 * 1000L

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(context) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun recordCrash(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CRASH, 0L)
        val count = if (now - last <= WINDOW_MS) prefs.getInt(KEY_CRASH_COUNT, 0) + 1 else 1
        prefs.edit()
            .putLong(KEY_LAST_CRASH, now)
            .putInt(KEY_CRASH_COUNT, count)
            .commit()
    }

    fun shouldOpenRecovery(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CRASH, 0L)
        val count = prefs.getInt(KEY_CRASH_COUNT, 0)
        return count >= 1 && System.currentTimeMillis() - last <= WINDOW_MS
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
