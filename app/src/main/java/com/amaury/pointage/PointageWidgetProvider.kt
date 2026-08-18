package com.amaury.pointage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun formatTime(time: Long) = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))

        private fun formatDuration(ms: Long): String {
            val totalMinutes = ms.coerceAtLeast(0L) / 60000L
            return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
        }

        private fun shortLocation(address: String, max: Int = 34): String {
            val cleaned = address.replace("\n", " ").trim()
            return if (cleaned.length <= max) cleaned else cleaned.take(max - 1) + "…"
        }

        private fun parseColor(value: String?, fallback: Int): Int =
            runCatching { Color.parseColor(value ?: "") }.getOrDefault(fallback)

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_pointage)

            val entryIntent = Intent(context, PointageWidgetProvider::class.java).apply { action = ACTION_ENTRY }
            val exitIntent = Intent(context, PointageWidgetProvider::class.java).apply { action = ACTION_EXIT }
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", "today")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openSettingsIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", "settings")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 20, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_location, PendingIntent.getActivity(context, 30, openSettingsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_entry, PendingIntent.getBroadcast(context, 1, entryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_exit, PendingIntent.getBroadcast(context, 2, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_status_area, PendingIntent.getActivity(context, 20, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val appearance = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
            val widgetStyle = context.getSharedPreferences("widget_style", Context.MODE_PRIVATE)
            val mode = appearance.getString("mode", "auto") ?: "auto"
            val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val dark = when (mode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }

            val defaultBg = Color.parseColor(if (dark) "#080808" else "#F3F0E8")
            val defaultPanel = Color.parseColor(if (dark) "#181818" else "#FFFFFF")
            val defaultAccent = Color.parseColor(if (dark) "#F3D58A" else "#8A6200")
            val defaultText = Color.parseColor(if (dark) "#F4EFE3" else "#171717")
            val defaultSecondary = Color.parseColor(if (dark) "#CFC7B8" else "#4E4A44")

            val bg = if (widgetStyle.contains("widget_bg")) parseColor(widgetStyle.getString("widget_bg", null), defaultBg) else defaultBg
            val accent = if (widgetStyle.contains("widget_accent")) parseColor(widgetStyle.getString("widget_accent", null), defaultAccent) else defaultAccent
            val text = if (((Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000) < 145) Color.WHITE else Color.parseColor("#111111")
            val panel = if (widgetStyle.contains("widget_bg")) {
                val f = if (text == Color.WHITE) 1.18f else 0.92f
                Color.rgb((Color.red(bg)*f).toInt().coerceIn(0,255),(Color.green(bg)*f).toInt().coerceIn(0,255),(Color.blue(bg)*f).toInt().coerceIn(0,255))
            } else defaultPanel
            val secondary = if (widgetStyle.contains("widget_bg")) text else defaultSecondary

            views.setInt(R.id.widget_root, "setBackgroundColor", bg)
            views.setInt(R.id.widget_status_area, "setBackgroundColor", panel)
            listOf(R.id.widget_crown, R.id.widget_hp, R.id.widget_work, R.id.widget_entry_label, R.id.widget_exit_label, R.id.widget_now, R.id.widget_duration, R.id.widget_pause_label, R.id.widget_location).forEach {
                views.setTextColor(it, accent)
            }
            listOf(R.id.widget_entry_location, R.id.widget_exit_location, R.id.widget_pause).forEach {
                views.setTextColor(it, secondary)
            }

            var entryText = "--:--"
            var exitText = "--:--"
            var durationText = "00h 00m"
            var stateText = "PRÊT"
            var stateColor = accent
            var locationText = "📍 Aucune zone"
            var entryLocation = ""
            var exitLocation = ""

            val data = PointageStore.load(context)
            if (data.length() > 0) {
                val last = data.getJSONObject(data.length() - 1)
                val entry = last.getLong("entry")
                val zoneAddress = last.optString("zoneAddress").trim()
                entryText = formatTime(entry)
                val place = if (zoneAddress.isNotEmpty()) shortLocation(zoneAddress, 30) else "Pointage manuel"
                entryLocation = place
                locationText = "📍 ${shortLocation(if (zoneAddress.isNotEmpty()) zoneAddress else place, 40)}"
                if (last.isNull("exit")) {
                    durationText = formatDuration(System.currentTimeMillis() - entry)
                    stateText = "EN COURS"
                    stateColor = accent
                } else {
                    val exit = last.getLong("exit")
                    exitText = formatTime(exit)
                    exitLocation = place
                    durationText = formatDuration(exit - entry)
                    stateText = "TERMINÉ"
                    stateColor = secondary
                }
            }

            views.setTextViewText(R.id.widget_entry_time, entryText)
            views.setTextViewText(R.id.widget_exit_time, exitText)
            views.setTextViewText(R.id.widget_entry_location, entryLocation)
            views.setTextViewText(R.id.widget_exit_location, exitLocation)
            views.setTextViewText(R.id.widget_duration, durationText)
            views.setTextViewText(R.id.widget_state, stateText)
            views.setTextColor(R.id.widget_state, stateColor)
            views.setTextViewText(R.id.widget_pause, "00h 00m")
            views.setTextViewText(R.id.widget_location, locationText)
            views.setViewVisibility(R.id.widget_location, if (widgetStyle.getBoolean("show_position", true)) View.VISIBLE else View.GONE)

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
