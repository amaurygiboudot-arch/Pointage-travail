package com.amaury.pointage

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

object CompanyPauseAlarmManager {
    const val ACTION = "com.amaury.pointage.COMPANY_BASE_PAUSE_ALARM"
    private const val EXTRA_COMPANY = "companySlot"
    private const val EXTRA_PAUSE = "pauseIndex"
    private const val EXTRA_EVENT = "pauseEvent"
    private const val EVENT_START = "start"
    private const val EVENT_END = "end"
    private const val CHANNEL_ID = "pause_reminders"

    private fun origin(company: Int, pauseIndex: Int) = "company:$company:$pauseIndex"

    fun scheduleAll(context: Context) {
        ensureNotificationChannel(context)
        cancelAll(context)
        for (company in 1..2) for (pauseIndex in 1..2) {
            val pause = CompanyBasePauseSettings.pause(context, company, pauseIndex) ?: continue
            scheduleOne(context, company, pauseIndex, EVENT_START, pause.startMinute)
            scheduleOne(context, company, pauseIndex, EVENT_END, pause.endMinute)
        }
    }

    fun cancelAll(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (company in 1..2) for (pauseIndex in 1..2) {
            alarm.cancel(pending(context, company, pauseIndex, EVENT_START))
            alarm.cancel(pending(context, company, pauseIndex, EVENT_END))
        }
    }

    private fun scheduleOne(context: Context, company: Int, pauseIndex: Int, event: String, minuteOfDay: Int) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val whenCal = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = pending(context, company, pauseIndex, event)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pi)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pi)
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pi)
        }
    }

    private fun pending(context: Context, company: Int, pauseIndex: Int, event: String): PendingIntent {
        val eventOffset = if (event == EVENT_END) 1 else 0
        val requestCode = 5200 + company * 100 + pauseIndex * 10 + eventOffset
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, CompanyPauseAlarmReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_COMPANY, company)
                .putExtra(EXTRA_PAUSE, pauseIndex)
                .putExtra(EXTRA_EVENT, event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun company(intent: Intent) = intent.getIntExtra(EXTRA_COMPANY, 0)
    internal fun pauseIndex(intent: Intent) = intent.getIntExtra(EXTRA_PAUSE, 0)
    internal fun event(intent: Intent) = intent.getStringExtra(EXTRA_EVENT).orEmpty()
    internal fun isStart(event: String) = event == EVENT_START
    internal fun isEnd(event: String) = event == EVENT_END
    internal fun pauseOrigin(company: Int, pauseIndex: Int) = origin(company, pauseIndex)

    internal fun activeCompanySlot(context: Context): Int? {
        val raw = context.getSharedPreferences("pointage", Context.MODE_PRIVATE).getString("data", "[]").orEmpty()
        val data = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            if (item.optLong("entry", -1L) <= 0L || !item.isNull("exit")) continue
            val slot = item.optInt("companySlot", 0)
            return slot.takeIf { it in 1..2 }
        }
        return null
    }

    internal fun showNotification(context: Context, company: Int, pauseIndex: Int) {
        ensureNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val salaryPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val prefix = if (company == 2) "company2_" else "company_"
        val companyName = salaryPrefs.getString(prefix + "name", "").orEmpty()
            .ifBlank { if (company == 1) "Entreprise 1" else "Entreprise 2" }
        val pause = CompanyBasePauseSettings.pause(context, company, pauseIndex)
        val duration = pause?.durationMinutes ?: 0
        val openApp = PendingIntent.getActivity(
            context,
            6100 + company * 10 + pauseIndex,
            Intent(context, LaunchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        })
            .setSmallIcon(R.drawable.horatrack_notification)
            .setContentTitle("Début de la pause $pauseIndex")
            .setContentText(if (duration > 0) "$companyName • pause de $duration min" else companyName)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(6200 + company * 10 + pauseIndex, notification)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rappels de pause", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications affichées au début des pauses de base"
                setSound(null, null)
                enableVibration(true)
            }
        )
    }
}

class CompanyPauseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CompanyPauseAlarmManager.ACTION) return

        val company = CompanyPauseAlarmManager.company(intent)
        val pauseIndex = CompanyPauseAlarmManager.pauseIndex(intent)
        val event = CompanyPauseAlarmManager.event(intent)

        if (company !in 1..2 || pauseIndex !in 1..2 || CompanyPauseAlarmManager.activeCompanySlot(context) != company) {
            CompanyPauseAlarmManager.scheduleAll(context)
            return
        }

        val origin = CompanyPauseAlarmManager.pauseOrigin(company, pauseIndex)
        when {
            CompanyPauseAlarmManager.isStart(event) -> {
                if (PointageStore.hasOpen(context) && !PointageStore.isPaused(context)) {
                    PointageStore.startPause(context, automatic = true, origin = origin)
                }

                if (CompanyBasePauseSettings.alarmEnabled(context, company, pauseIndex)) {
                    CompanyPauseAlarmManager.showNotification(context, company, pauseIndex)
                    val selected = PauseAlarmSoundCatalog.resolve(
                        context,
                        CompanyBasePauseSettings.alarmSound(context, company, pauseIndex)
                    )
                    val ringtone = runCatching {
                        RingtoneManager.getRingtone(context.applicationContext, selected.uri)
                    }.getOrNull()
                    if (ringtone != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            ringtone.audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        }
                        runCatching { ringtone.play() }
                        Handler(Looper.getMainLooper()).postDelayed({ runCatching { ringtone.stop() } }, 8_000L)
                    }
                }
            }

            CompanyPauseAlarmManager.isEnd(event) -> {
                PointageStore.resumePause(context, automaticOnly = true, expectedOrigin = origin)
            }
        }

        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        CompanyPauseAlarmManager.scheduleAll(context)
    }
}
