package com.amaury.pointage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.Locale

/**
 * Ancien planning global de pause.
 *
 * Il reste disponible pour compatibilité avec les utilisateurs qui l'ont déjà
 * configuré, mais il ne peut plus reprendre une pause qu'il n'a pas lui-même créée.
 * Les pauses d'entreprise restent pilotées par CompanyPauseAlarmManager.
 */
object PauseScheduleManager {
    private const val PREFS = "pause_schedule"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_START_HOUR = "start_hour"
    private const val KEY_START_MINUTE = "start_minute"
    private const val KEY_END_HOUR = "end_hour"
    private const val KEY_END_MINUTE = "end_minute"
    private const val OWNER_PREFS = "pause_schedule_runtime"
    private const val KEY_OWNS_CURRENT_PAUSE = "owns_current_pause"

    const val ACTION_START = "com.amaury.pointage.AUTO_PAUSE_START"
    const val ACTION_END = "com.amaury.pointage.AUTO_PAUSE_END"

    data class Schedule(
        val enabled: Boolean,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int
    )

    fun load(context: Context): Schedule {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Schedule(
            p.getBoolean(KEY_ENABLED, false),
            p.getInt(KEY_START_HOUR, 10),
            p.getInt(KEY_START_MINUTE, 0),
            p.getInt(KEY_END_HOUR, 10),
            p.getInt(KEY_END_MINUTE, 15)
        )
    }

    fun save(
        context: Context,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        enabled: Boolean = true
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_START_HOUR, startHour)
            .putInt(KEY_START_MINUTE, startMinute)
            .putInt(KEY_END_HOUR, endHour)
            .putInt(KEY_END_MINUTE, endMinute)
            .apply()
        schedule(context)
        applyCurrentWindow(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) {
            schedule(context)
            applyCurrentWindow(context)
        } else {
            cancel(context)
            resumeOwnedPause(context)
            updateWidgets(context)
        }
    }

    fun schedule(context: Context) {
        val s = load(context)
        if (!s.enabled) {
            cancel(context)
            return
        }
        scheduleOne(context, ACTION_START, 4101, s.startHour, s.startMinute)
        scheduleOne(context, ACTION_END, 4102, s.endHour, s.endMinute)
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending(context, ACTION_START, 4101))
        alarm.cancel(pending(context, ACTION_END, 4102))
    }

    fun applyCurrentWindow(context: Context) {
        val s = load(context)
        if (!s.enabled || !PointageStore.hasOpen(context)) return

        val now = Calendar.getInstance(Locale.FRANCE)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = s.startHour * 60 + s.startMinute
        val end = s.endHour * 60 + s.endMinute
        val inside = if (end > start) minute in start until end else minute >= start || minute < end

        val changed = when {
            inside && !PointageStore.isPaused(context) -> startOwnedPause(context)
            !inside && ownsCurrentPause(context) -> resumeOwnedPause(context)
            else -> false
        }
        if (changed) updateWidgets(context)
    }

    internal fun onAlarmStart(context: Context) {
        if (PointageStore.hasOpen(context) && !PointageStore.isPaused(context)) {
            startOwnedPause(context)
        }
    }

    internal fun onAlarmEnd(context: Context) {
        resumeOwnedPause(context)
    }

    private fun startOwnedPause(context: Context): Boolean {
        val changed = PointageStore.startPause(context, true)
        if (changed) {
            context.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_OWNS_CURRENT_PAUSE, true)
                .apply()
        }
        return changed
    }

    private fun resumeOwnedPause(context: Context): Boolean {
        if (!ownsCurrentPause(context)) return false
        val changed = if (PointageStore.isPausedAutomatically(context)) {
            PointageStore.resumePause(context, automaticOnly = true)
        } else false
        context.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OWNS_CURRENT_PAUSE, false)
            .apply()
        return changed
    }

    private fun ownsCurrentPause(context: Context): Boolean =
        context.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OWNS_CURRENT_PAUSE, false)

    private fun scheduleOne(
        context: Context,
        action: String,
        requestCode: Int,
        hour: Int,
        minute: Int
    ) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = pending(context, action, requestCode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    private fun pending(context: Context, action: String, requestCode: Int) =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PauseScheduleReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun updateWidgets(context: Context) {
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
    }
}

class PauseScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PauseScheduleManager.ACTION_START -> PauseScheduleManager.onAlarmStart(context)
            PauseScheduleManager.ACTION_END -> PauseScheduleManager.onAlarmEnd(context)
        }
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        PauseScheduleManager.schedule(context)
    }
}
