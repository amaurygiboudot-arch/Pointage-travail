package com.amaury.pointage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.model.EventSourceV2
import java.util.Calendar
import java.util.Locale

object PauseScheduleManager {
    private const val PREFS = "pause_schedule"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_START_HOUR = "start_hour"
    private const val KEY_START_MINUTE = "start_minute"
    private const val KEY_END_HOUR = "end_hour"
    private const val KEY_END_MINUTE = "end_minute"

    private const val CONFIRM_PREFS = "pause_schedule_confirmation"
    private const val KEY_CONFIRM_END_AT = "end_at"
    private const val KEY_CONFIRM_DEADLINE = "deadline"
    private const val CONFIRM_WINDOW_MS = 5L * 60_000L

    const val ACTION_START = "com.amaury.pointage.AUTO_PAUSE_START"
    const val ACTION_END = "com.amaury.pointage.AUTO_PAUSE_END"

    data class Schedule(
        val enabled: Boolean,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int
    )

    data class EndConfirmation(val endAtMs: Long, val deadlineMs: Long)

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
            clearEndConfirmation(context)
            if (isScheduledPauseActive(context)) resumeScheduledPause(context)
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
        val a = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        a.cancel(pending(context, ACTION_START, 4101))
        a.cancel(pending(context, ACTION_END, 4102))
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
            inside && !PointageStore.isPaused(context) -> startScheduledPause(context)
            !inside && isScheduledPauseActive(context) -> {
                val resumed = resumeScheduledPause(context)
                if (resumed) markEndConfirmationIfRecent(context, scheduledEndNearNow(context))
                resumed
            }
            else -> false
        }
        if (changed) updateWidgets(context)
    }

    fun pendingEndConfirmation(context: Context, nowMs: Long = System.currentTimeMillis()): EndConfirmation? {
        val p = context.applicationContext.getSharedPreferences(CONFIRM_PREFS, Context.MODE_PRIVATE)
        val endAt = p.getLong(KEY_CONFIRM_END_AT, 0L)
        val deadline = p.getLong(KEY_CONFIRM_DEADLINE, 0L)
        if (endAt <= 0L || deadline <= 0L || nowMs >= deadline) {
            if (endAt > 0L || deadline > 0L) p.edit().clear().apply()
            return null
        }
        return EndConfirmation(endAt, deadline)
    }

    fun confirmPauseEnded(context: Context) {
        clearEndConfirmation(context)
    }

    fun confirmStillPaused(context: Context): Boolean {
        val pending = pendingEndConfirmation(context) ?: return false
        val now = System.currentTimeMillis()
        clearEndConfirmation(context)
        if (!PointageStore.hasOpen(context)) return false
        if (PointageStore.isPaused(context)) return true

        if (now > pending.endAtMs) {
            PointageStore.addManualPause(context, pending.endAtMs, now)
        }
        return PointageStore.startPause(context, false)
    }

    fun clearEndConfirmation(context: Context) {
        context.applicationContext.getSharedPreferences(CONFIRM_PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    internal fun onScheduledEnd(context: Context) {
        if (!isScheduledPauseActive(context)) return
        val resumed = resumeScheduledPause(context)
        if (resumed) markEndConfirmationIfRecent(context, scheduledEndNearNow(context))
    }

    private fun startScheduledPause(context: Context): Boolean {
        if (!HoraTrackV2.ENABLED) return PointageStore.startPause(context, true)
        val snap = V2RuntimeStore.snapshot(context).session ?: return false
        if (snap.realExitMs != null || snap.pauses.any { it.endMs == null }) return false
        return V2RuntimeStore.togglePause(context, source = EventSourceV2.SYSTEM, paid = false)
    }

    private fun isScheduledPauseActive(context: Context): Boolean {
        if (!HoraTrackV2.ENABLED) return PointageStore.isPausedAutomatically(context)
        val snap = V2RuntimeStore.snapshot(context).session ?: return false
        return snap.realExitMs == null && snap.pauses.any { it.endMs == null && it.source == EventSourceV2.SYSTEM }
    }

    private fun resumeScheduledPause(context: Context): Boolean {
        if (!HoraTrackV2.ENABLED) return PointageStore.resumePause(context, automaticOnly = true)
        if (!isScheduledPauseActive(context)) return false
        return V2RuntimeStore.togglePause(context, source = EventSourceV2.SYSTEM, paid = false)
    }

    private fun markEndConfirmationIfRecent(context: Context, scheduledEndMs: Long) {
        val now = System.currentTimeMillis()
        val deadline = scheduledEndMs + CONFIRM_WINDOW_MS
        if (scheduledEndMs <= 0L || now < scheduledEndMs || now >= deadline) return
        context.applicationContext.getSharedPreferences(CONFIRM_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CONFIRM_END_AT, scheduledEndMs)
            .putLong(KEY_CONFIRM_DEADLINE, deadline)
            .apply()
    }

    private fun scheduledEndNearNow(context: Context): Long {
        val s = load(context)
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, s.endHour)
            set(Calendar.MINUTE, s.endMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now) cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    private fun scheduleOne(context: Context, action: String, requestCode: Int, hour: Int, minute: Int) {
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
            PauseScheduleManager.ACTION_START -> {
                if (PointageStore.hasOpen(context) && !PointageStore.isPaused(context)) {
                    PauseScheduleManager.applyCurrentWindow(context)
                }
            }
            PauseScheduleManager.ACTION_END -> PauseScheduleManager.onScheduledEnd(context)
        }
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        PauseScheduleManager.schedule(context)
    }
}
