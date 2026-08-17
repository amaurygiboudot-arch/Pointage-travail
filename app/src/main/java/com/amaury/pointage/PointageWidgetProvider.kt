package com.amaury.pointage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PointageWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_ENTRY = "com.amaury.pointage.ACTION_ENTRY"
        const val ACTION_EXIT = "com.amaury.pointage.ACTION_EXIT"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PointageWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun formatTime(time: Long) =
            SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))

        private fun formatDuration(ms: Long): String {
            val totalMinutes = ms.coerceAtLeast(0L) / 60000L
            return String.format(
                Locale.FRANCE,
                "%02dh %02dm",
                totalMinutes / 60L,
                totalMinutes % 60L
            )
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_pointage)

            val entryIntent = Intent(context, PointageWidgetProvider::class.java).apply {
                action = ACTION_ENTRY
            }
            val exitIntent = Intent(context, PointageWidgetProvider::class.java).apply {
                action = ACTION_EXIT
            }
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", "today")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val openAppPending = PendingIntent.getActivity(
                context,
                20,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val entryPending = PendingIntent.getBroadcast(
                context,
                1,
                entryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val exitPending = PendingIntent.getBroadcast(
                context,
                2,
                exitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_root, openAppPending)
            views.setOnClickPendingIntent(R.id.widget_entry, entryPending)
            views.setOnClickPendingIntent(R.id.widget_exit, exitPending)

            var entryText = "--:--"
            var exitText = "--:--"
            var durationText = "00h 00m"
            var stateText = "PRÊT"
            var stateColor = Color.parseColor("#E8C25E")

            val data = PointageStore.load(context)
            if (data.length() > 0) {
                val last = data.getJSONObject(data.length() - 1)
                val entry = last.getLong("entry")
                entryText = formatTime(entry)

                if (last.isNull("exit")) {
                    durationText = formatDuration(System.currentTimeMillis() - entry)
                    stateText = "EN COURS"
                    stateColor = Color.parseColor("#F3D58A")
                } else {
                    val exit = last.getLong("exit")
                    exitText = formatTime(exit)
                    durationText = formatDuration(exit - entry)
                    stateText = "TERMINÉ"
                    stateColor = Color.parseColor("#CFC7B8")
                }
            }

            views.setTextViewText(R.id.widget_entry_time, entryText)
            views.setTextViewText(R.id.widget_exit_time, exitText)
            views.setTextViewText(R.id.widget_duration, durationText)
            views.setTextViewText(R.id.widget_state, stateText)
            views.setTextColor(R.id.widget_state, stateColor)
            views.setTextViewText(R.id.widget_pause, "00h 00m")

            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_ENTRY -> {
                if (PointageStore.entry(context)) {
                    Toast.makeText(context, "Entrée enregistrée", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
                }
                updateAll(context)
            }

            ACTION_EXIT -> {
                if (PointageStore.exit(context)) {
                    Toast.makeText(context, "Sortie enregistrée", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
                }
                updateAll(context)
            }
        }
    }
}
