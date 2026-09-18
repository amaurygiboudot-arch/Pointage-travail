package com.amaury.pointage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Rafraîchit le grand widget à la fin de la fenêtre d'affichage du lieu.
 * L'historique conserve le lieu ; seul l'affichage principal du widget est recalculé.
 */
class WidgetLocationExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PointageWidgetProvider.updateAll(context)
    }
}

object WidgetLocationExpiryScheduler {
    private const val AFTER_EXIT_MS = 5L * 60L * 1000L
    private const val REQUEST_CODE = 7405

    fun schedule(context: Context, exitMs: Long) {
        if (exitMs <= 0L) return
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = exitMs + AFTER_EXIT_MS

        // Cette alarme ne modifie aucune donnée de pointage : elle masque seulement le lieu du
        // widget après la fenêtre d'affichage. Si cette fenêtre est déjà passée, un rafraîchissement
        // immédiat est plus juste qu'une alarme planifiée dans le passé.
        if (triggerAt <= System.currentTimeMillis()) {
            PointageWidgetProvider.updateAll(appContext)
            return
        }

        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, WidgetLocationExpiryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        runCatching {
            // Un rafraîchissement purement visuel n'a pas besoin d'une alarme exacte. Cela évite
            // de consommer la capacité d'alarme exacte pour une action qui tolère le délai Doze.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.onFailure {
            runCatching { alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent) }
        }
    }
}
