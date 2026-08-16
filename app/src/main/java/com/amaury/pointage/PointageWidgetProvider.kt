package com.amaury.pointage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
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
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun formatTime(time: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))

        private fun formatDuration(ms: Long): String {
            val totalMinutes = ms.coerceAtLeast(0L) / 60000
            return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60, totalMinutes % 60)
        }

        private fun shortLocation(address: String): String {
            val cleaned = address.replace("\n", " ").trim()
            return if (cleaned.length <= 42) cleaned else cleaned.take(39) + "…"
        }

        private fun parseColor(value: String?, fallback: String): Int = runCatching {
            Color.parseColor(value ?: fallback)
        }.getOrElse { Color.parseColor(fallback) }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_pointage)

            val entryIntent = Intent(context, PointageWidgetProvider::class.java).apply { action = ACTION_ENTRY }
            val exitIntent = Intent(context, PointageWidgetProvider::class.java).apply { action = ACTION_EXIT }
            val todayIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", "today")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val locationIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", "settings")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 20, todayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_entry, PendingIntent.getBroadcast(context, 1, entryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_exit, PendingIntent.getBroadcast(context, 2, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_location, PendingIntent.getActivity(context, 30, locationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val stylePrefs = context.getSharedPreferences("widget_style", Context.MODE_PRIVATE)
            val widgetBg = parseColor(stylePrefs.getString("widget_bg", null), "#080808")
            val accent = parseColor(stylePrefs.getString("widget_accent", null), "#D6A84B")
            val showPosition = stylePrefs.getBoolean("show_position", true)
            views.setInt(R.id.widget_root, "setBackgroundColor", widgetBg)
            views.setTextColor(R.id.widget_status, accent)
            views.setTextColor(R.id.widget_location, accent)
            views.setViewVisibility(R.id.widget_location, if (showPosition) View.VISIBLE else View.GONE)

            val data = PointageStore.load(context)
            var entryText = "--:--"
            var exitText = "--:--"
            var durationText = "00h 00m"
            var stateText = "● HORS TRAVAIL"
            var stateColor = 0xFFA9A9A9.toInt()
            var locationText = "📍 Aucune zone"

            if (data.length() > 0) {
                val last = data.getJSONObject(data.length() - 1)
                val entry = last.getLong("entry")
                val zoneAddress = last.optString("zoneAddress").trim()
                entryText = formatTime(entry)

                if (zoneAddress.isNotEmpty()) locationText = "📍 ${shortLocation(zoneAddress)}"
                else if (last.isNull("exit")) locationText = "📍 Pointage manuel"

                if (last.isNull("exit")) {
                    durationText = formatDuration(System.currentTimeMillis() - entry)
                    stateText = "● EN COURS"
                    stateColor = 0xFF54D66A.toInt()
                } else {
                    val exit = last.getLong("exit")
                    exitText = formatTime(exit)
                    durationText = formatDuration(exit - entry)
                    stateText = "● TERMINÉ"
                    stateColor = 0xFFD84A4A.toInt()
                }
            }

            views.setTextViewText(R.id.widget_entry_time, entryText)
            views.setTextViewText(R.id.widget_exit_time, exitText)
            views.setTextViewText(R.id.widget_duration, durationText)
            views.setTextViewText(R.id.widget_status, "HP V8")
            views.setTextViewText(R.id.widget_state, stateText)
            views.setTextViewText(R.id.widget_location, locationText)
            views.setTextColor(R.id.widget_state, stateColor)
            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_ENTRY -> {
                if (PointageStore.entry(context)) Toast.makeText(context, "Entrée enregistrée", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
                updateAll(context)
            }
            ACTION_EXIT -> {
                if (PointageStore.exit(context)) Toast.makeText(context, "Sortie enregistrée", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
                updateAll(context)
            }
        }
    }
}
