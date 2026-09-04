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
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.model.EventSourceV2
import java.util.Calendar
import java.util.Locale

object CompanyPauseAlarmManager {
    const val ACTION = "com.amaury.pointage.COMPANY_BASE_PAUSE_ALARM"
    private const val EXTRA_COMPANY = "companySlot" // V1 uniquement
    private const val EXTRA_COMPANY_ID = "companyId"
    private const val EXTRA_PAUSE = "pauseIndex"
    private const val EXTRA_EVENT = "pauseEvent"
    private const val EVENT_START = "start"
    private const val EVENT_END = "end"
    private const val CHANNEL_ID = "pause_reminders"
    private const val STATE_PREFS = "horatrack_v2_company_pause"

    fun scheduleAll(context: Context) {
        ensureNotificationChannel(context)
        cancelAll(context)
        if (HoraTrackV2.ENABLED) scheduleAllV2(context) else scheduleAllLegacy(context)
    }

    private fun scheduleAllV2(context: Context) {
        SalaryCompanyStore.list(context).forEach { company ->
            for (pauseIndex in 1..2) {
                val pause = CompanyPauseSettingsV2.pause(context, company.id, pauseIndex) ?: continue
                scheduleOneV2(context, company.id, pauseIndex, EVENT_START, pause.startMinute)
                scheduleOneV2(context, company.id, pauseIndex, EVENT_END, pause.endMinute)
            }
        }
    }

    private fun scheduleAllLegacy(context: Context) {
        for (company in 1..2) for (pauseIndex in 1..2) {
            val pause = CompanyBasePauseSettings.pause(context, company, pauseIndex) ?: continue
            scheduleOneLegacy(context, company, pauseIndex, EVENT_START, pause.startMinute)
            scheduleOneLegacy(context, company, pauseIndex, EVENT_END, pause.endMinute)
        }
    }

    fun cancelAll(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Nettoie les anciens PendingIntent slot 1/2 sans supprimer les données V1.
        for (company in 1..2) for (pauseIndex in 1..2) {
            alarm.cancel(pendingLegacy(context, company, pauseIndex, EVENT_START))
            alarm.cancel(pendingLegacy(context, company, pauseIndex, EVENT_END))
        }
        SalaryCompanyStore.list(context).forEach { company ->
            for (pauseIndex in 1..2) {
                alarm.cancel(pendingV2(context, company.id, pauseIndex, EVENT_START))
                alarm.cancel(pendingV2(context, company.id, pauseIndex, EVENT_END))
            }
        }
    }

    private fun scheduleOneV2(context: Context, companyId: String, pauseIndex: Int, event: String, minuteOfDay: Int) {
        scheduleAt(context, minuteOfDay, pendingV2(context, companyId, pauseIndex, event))
    }

    private fun scheduleOneLegacy(context: Context, company: Int, pauseIndex: Int, event: String, minuteOfDay: Int) {
        scheduleAt(context, minuteOfDay, pendingLegacy(context, company, pauseIndex, event))
    }

    private fun scheduleAt(context: Context, minuteOfDay: Int, pendingIntent: PendingIntent) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val whenCal = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pendingIntent)
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pendingIntent)
        }
    }

    private fun pendingV2(context: Context, companyId: String, pauseIndex: Int, event: String): PendingIntent {
        val eventOffset = if (event == EVENT_END) 1 else 0
        val requestCode = 31 * companyId.hashCode() + pauseIndex * 10 + eventOffset
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, CompanyPauseAlarmReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_COMPANY_ID, companyId)
                .putExtra(EXTRA_PAUSE, pauseIndex)
                .putExtra(EXTRA_EVENT, event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pendingLegacy(context: Context, company: Int, pauseIndex: Int, event: String): PendingIntent {
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
    internal fun companyId(context: Context, intent: Intent): String? {
        val stable = intent.getStringExtra(EXTRA_COMPANY_ID)?.trim().orEmpty()
        if (stable.isNotBlank()) return stable
        val legacySlot = company(intent)
        return legacySlot.takeIf { it in 1..2 }?.let { V2ProfileStore.load(context, it).employer?.id }
    }
    internal fun pauseIndex(intent: Intent) = intent.getIntExtra(EXTRA_PAUSE, 0)
    internal fun event(intent: Intent) = intent.getStringExtra(EXTRA_EVENT).orEmpty()
    internal fun isStart(event: String) = event == EVENT_START
    internal fun isEnd(event: String) = event == EVENT_END

    internal fun activeCompanyId(context: Context): String? {
        if (!HoraTrackV2.ENABLED) return null
        val session = V2RuntimeStore.snapshot(context).session ?: return null
        if (session.realExitMs != null) return null
        return session.employerId
    }

    internal fun activeCompanySlot(context: Context): Int? {
        if (HoraTrackV2.ENABLED) {
            val id = activeCompanyId(context) ?: return null
            val index = SalaryCompanyStore.list(context).indexOfFirst { it.id == id }
            return (index + 1).takeIf { it in 1..2 }
        }
        return null
    }

    internal fun markAutomaticPause(context: Context, companyId: String, pauseIndex: Int, active: Boolean) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", active)
            .putString("companyId", companyId)
            .remove("company")
            .putInt("pause", pauseIndex)
            .apply()
    }

    internal fun markAutomaticPause(context: Context, company: Int, pauseIndex: Int, active: Boolean) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", active)
            .putInt("company", company)
            .remove("companyId")
            .putInt("pause", pauseIndex)
            .apply()
    }

    internal fun isAutomaticPause(context: Context, companyId: String, pauseIndex: Int): Boolean {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean("active", false) &&
            prefs.getString("companyId", null) == companyId &&
            prefs.getInt("pause", 0) == pauseIndex
    }

    internal fun isAutomaticPause(context: Context, company: Int, pauseIndex: Int): Boolean {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean("active", false) &&
            prefs.getInt("company", 0) == company &&
            prefs.getInt("pause", 0) == pauseIndex
    }

    internal fun showNotification(context: Context, companyId: String, pauseIndex: Int) {
        ensureNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val company = SalaryCompanyStore.list(context).firstOrNull { it.id == companyId }
        val companyName = company?.name?.ifBlank { "Entreprise" } ?: "Entreprise"
        val pause = CompanyPauseSettingsV2.pause(context, companyId, pauseIndex)
        val duration = pause?.durationMinutes ?: 0
        val openApp = PendingIntent.getActivity(
            context,
            31 * companyId.hashCode() + pauseIndex,
            Intent(context, LaunchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(context))
            .setSmallIcon(R.drawable.hp_logo_vector)
            .setContentTitle("Début de la pause $pauseIndex")
            .setContentText(if (duration > 0) "$companyName • pause de $duration min" else companyName)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(31 * companyId.hashCode() + 6200 + pauseIndex, notification)
    }

    internal fun showNotification(context: Context, company: Int, pauseIndex: Int) {
        ensureNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val salaryPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val prefix = if (company == 2) "company2_" else "company_"
        val companyName = salaryPrefs.getString(prefix + "name", "").orEmpty().ifBlank { if (company == 1) "Entreprise 1" else "Entreprise 2" }
        val pause = CompanyBasePauseSettings.pause(context, company, pauseIndex)
        val duration = pause?.durationMinutes ?: 0
        val openApp = PendingIntent.getActivity(context, 6100 + company * 10 + pauseIndex, Intent(context, LaunchActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(context))
            .setSmallIcon(R.drawable.hp_logo_vector)
            .setContentTitle("Début de la pause $pauseIndex")
            .setContentText(if (duration > 0) "$companyName • pause de $duration min" else companyName)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(6200 + company * 10 + pauseIndex, notification)
    }

    internal fun rescheduleV2Event(context: Context, companyId: String, pauseIndex: Int, event: String) {
        val pause = CompanyPauseSettingsV2.pause(context, companyId, pauseIndex) ?: return
        val minute = if (event == EVENT_END) pause.endMinute else pause.startMinute
        scheduleOneV2(context, companyId, pauseIndex, event, minute)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Rappels de pause", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifications affichées au début des pauses de base"
            setSound(null, null)
            enableVibration(true)
        })
    }
}

class CompanyPauseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CompanyPauseAlarmManager.ACTION) return
        if (HoraTrackV2.ENABLED) handleV2(context, intent) else handleLegacy(context, intent)
    }

    private fun handleV2(context: Context, intent: Intent) {
        val companyId = CompanyPauseAlarmManager.companyId(context, intent) ?: return
        val pauseIndex = CompanyPauseAlarmManager.pauseIndex(intent)
        val event = CompanyPauseAlarmManager.event(intent)
        if (pauseIndex !in 1..2 || (!CompanyPauseAlarmManager.isStart(event) && !CompanyPauseAlarmManager.isEnd(event))) return

        if (CompanyPauseAlarmManager.activeCompanyId(context) == companyId) {
            when {
                CompanyPauseAlarmManager.isStart(event) -> {
                    val snap = V2RuntimeStore.snapshot(context).session
                    val started = if (snap != null && snap.realExitMs == null && snap.pauses.none { it.endMs == null }) {
                        V2RuntimeStore.togglePause(context, source = EventSourceV2.SYSTEM, paid = false)
                    } else false
                    if (started) CompanyPauseAlarmManager.markAutomaticPause(context, companyId, pauseIndex, true)

                    if (CompanyPauseSettingsV2.alarmEnabled(context, companyId, pauseIndex)) {
                        CompanyPauseAlarmManager.showNotification(context, companyId, pauseIndex)
                        val selected = PauseAlarmSoundCatalog.resolve(
                            context,
                            CompanyPauseSettingsV2.alarmSound(context, companyId, pauseIndex)
                        )
                        playAlarm(context, selected.uri)
                    }
                }

                CompanyPauseAlarmManager.isEnd(event) -> {
                    if (CompanyPauseAlarmManager.isAutomaticPause(context, companyId, pauseIndex)) {
                        val snap = V2RuntimeStore.snapshot(context).session
                        val automaticPauseOpen = snap != null && snap.realExitMs == null && snap.pauses.any {
                            it.endMs == null && it.source == EventSourceV2.SYSTEM
                        }
                        if (automaticPauseOpen) {
                            V2RuntimeStore.togglePause(context, source = EventSourceV2.SYSTEM, paid = false)
                        }
                        CompanyPauseAlarmManager.markAutomaticPause(context, companyId, pauseIndex, false)
                    }
                }
            }
            PointageWidgetProvider.updateAll(context)
            QuickActionsWidgetProvider.updateAll(context)
        }

        // Une alarme V2 ne reprogramme qu'elle-même : elle ne peut pas annuler une autre
        // entreprise dont l'alarme arrive au même instant.
        CompanyPauseAlarmManager.rescheduleV2Event(context, companyId, pauseIndex, event)
    }

    private fun handleLegacy(context: Context, intent: Intent) {
        val company = CompanyPauseAlarmManager.company(intent)
        val pauseIndex = CompanyPauseAlarmManager.pauseIndex(intent)
        val event = CompanyPauseAlarmManager.event(intent)

        if (company !in 1..2 || pauseIndex !in 1..2 || CompanyPauseAlarmManager.activeCompanySlot(context) != company) {
            CompanyPauseAlarmManager.scheduleAll(context)
            return
        }

        when {
            CompanyPauseAlarmManager.isStart(event) -> {
                val started = false
                if (started) CompanyPauseAlarmManager.markAutomaticPause(context, company, pauseIndex, true)
                if (CompanyBasePauseSettings.alarmEnabled(context, company, pauseIndex)) {
                    CompanyPauseAlarmManager.showNotification(context, company, pauseIndex)
                    val selected = PauseAlarmSoundCatalog.resolve(context, CompanyBasePauseSettings.alarmSound(context, company, pauseIndex))
                    playAlarm(context, selected.uri)
                }
            }
            CompanyPauseAlarmManager.isEnd(event) -> Unit
        }
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        CompanyPauseAlarmManager.scheduleAll(context)
    }

    private fun playAlarm(context: Context, uri: android.net.Uri) {
        val ringtone = runCatching { RingtoneManager.getRingtone(context.applicationContext, uri) }.getOrNull() ?: return
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
