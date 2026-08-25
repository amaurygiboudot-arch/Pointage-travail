package com.amaury.pointage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.Locale

/**
 * Alarmes sonores liées aux deux pauses de base de chaque entreprise.
 * Elles ne créent aucune pause dans PointageStore : elles servent uniquement de rappel.
 */
object CompanyPauseAlarmManager {
    const val ACTION = "com.amaury.pointage.COMPANY_BASE_PAUSE_ALARM"
    private const val EXTRA_COMPANY = "companySlot"
    private const val EXTRA_PAUSE = "pauseIndex"

    fun scheduleAll(context: Context) {
        cancelAll(context)
        for (company in 1..2) {
            for (pauseIndex in 1..2) {
                val pause = CompanyBasePauseSettings.pause(context, company, pauseIndex) ?: continue
                if (!CompanyBasePauseSettings.alarmEnabled(context, company, pauseIndex)) continue
                scheduleOne(context, company, pauseIndex, pause.startMinute)
            }
        }
    }

    fun cancelAll(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (company in 1..2) for (pauseIndex in 1..2) {
            alarm.cancel(pending(context, company, pauseIndex))
        }
    }

    private fun scheduleOne(context: Context, company: Int, pauseIndex: Int, minuteOfDay: Int) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val whenCal = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = pending(context, company, pauseIndex)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pi)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pi)
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, whenCal.timeInMillis, pi)
        }
    }

    private fun pending(context: Context, company: Int, pauseIndex: Int): PendingIntent {
        val requestCode = 5200 + company * 10 + pauseIndex
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, CompanyPauseAlarmReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_COMPANY, company)
                .putExtra(EXTRA_PAUSE, pauseIndex),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun company(intent: Intent) = intent.getIntExtra(EXTRA_COMPANY, 0)
    internal fun pauseIndex(intent: Intent) = intent.getIntExtra(EXTRA_PAUSE, 0)
}

class CompanyPauseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CompanyPauseAlarmManager.ACTION) return
        val company = CompanyPauseAlarmManager.company(intent)
        val pauseIndex = CompanyPauseAlarmManager.pauseIndex(intent)

        // L'alarme ne sonne que pendant une journée réellement pointée et pour
        // l'entreprise associée à cette journée. Pas d'alarme les jours de repos.
        if (company !in 1..2 || pauseIndex !in 1..2 || !PointageStore.hasOpen(context) ||
            PointageStore.currentCompanySlot(context) != company ||
            !CompanyBasePauseSettings.alarmEnabled(context, company, pauseIndex)) {
            CompanyPauseAlarmManager.scheduleAll(context)
            return
        }

        val type = when (CompanyBasePauseSettings.alarmSound(context, company, pauseIndex)) {
            "ringtone" -> RingtoneManager.TYPE_RINGTONE
            "notification" -> RingtoneManager.TYPE_NOTIFICATION
            else -> RingtoneManager.TYPE_ALARM
        }
        val uri = RingtoneManager.getDefaultUri(type)
        val ringtone = runCatching { RingtoneManager.getRingtone(context.applicationContext, uri) }.getOrNull()
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

        CompanyPauseAlarmManager.scheduleAll(context)
    }
}
