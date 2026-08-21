package com.amaury.pointage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class PointageWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_ENTRY = "com.amaury.pointage.ACTION_ENTRY"
        const val ACTION_PAUSE = "com.amaury.pointage.ACTION_PAUSE"
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
        private fun shortLocation(address: String, max: Int = 38): String {
            val cleaned = address.replace("\n", " ").trim()
            return if (cleaned.length <= max) cleaned else cleaned.take(max - 1) + "…"
        }
        private fun backgroundFor(themeId: String, dark: Boolean): Int = when (themeId) {
            "steel_blue" -> if (dark) R.drawable.widget_bg_steel_dark else R.drawable.widget_bg_steel_light
            "brushed_aluminum" -> if (dark) R.drawable.widget_bg_alu_dark else R.drawable.widget_bg_alu_light
            "natural_carbon" -> if (dark) R.drawable.widget_bg_carbon_dark else R.drawable.widget_bg_carbon_light
            "diamond_crystal" -> if (dark) R.drawable.widget_bg_diamond_dark else R.drawable.widget_bg_diamond_light
            else -> if (dark) R.drawable.widget_bg_gold_dark else R.drawable.widget_bg_gold_light
        }

        private fun widgetSize(manager: AppWidgetManager, widgetId: Int): Pair<Int, Int> {
            val options = manager.getAppWidgetOptions(widgetId)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360).coerceAtLeast(280)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 96).coerceAtLeast(78)
            return width to height
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_pointage)
            fun broadcast(action: String, request: Int) = PendingIntent.getBroadcast(context, request, Intent(context, PointageWidgetProvider::class.java).apply { this.action = action }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val openApp = PendingIntent.getActivity(context, 20, Intent(context, MainActivity::class.java).apply { putExtra("open_tab", "today"); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val openSettings = PendingIntent.getActivity(context, 30, Intent(context, MainActivity::class.java).apply { putExtra("open_tab", "settings"); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            views.setOnClickPendingIntent(R.id.widget_location, openSettings)
            views.setOnClickPendingIntent(R.id.widget_entry_area, broadcast(ACTION_ENTRY, 1))
            views.setOnClickPendingIntent(R.id.widget_pause_area, broadcast(ACTION_PAUSE, 2))
            views.setOnClickPendingIntent(R.id.widget_exit_area, broadcast(ACTION_EXIT, 3))
            views.setOnClickPendingIntent(R.id.widget_status_area, openApp)

            val theme = AppThemeCatalog.current(context)
            val dark = AppThemeCatalog.useDarkPalette(context)
            val accent = if (dark) theme.accentLight else theme.accent
            val text = if (dark) theme.darkText else theme.lightText
            val secondary = if (dark) theme.darkHint else theme.lightHint
            views.setInt(R.id.widget_surface, "setBackgroundResource", backgroundFor(theme.id, dark))

            val (widgetWidth, widgetHeight) = widgetSize(manager, widgetId)
            // Tout le visuel suit la taille choisie du widget. Les proportions restent identiques
            // au haut de l'application, mais les boutons et l'horloge grandissent/rétrécissent ensemble.
            val buttonDp = min(widgetWidth / 7.25f, widgetHeight * 0.43f).coerceIn(36f, 72f)
            val clockDp = (buttonDp * 1.68f).coerceIn(62f, 118f)
            val labelSp = (buttonDp * 0.14f).coerceIn(6.5f, 10.5f)
            val timeSp = (buttonDp * 0.19f).coerceIn(8f, 13f)
            val smallSp = (buttonDp * 0.105f).coerceIn(5.5f, 8f)
            val buttonBitmapPx = (buttonDp * 3f).toInt().coerceIn(120, 270)
            val clockBitmapPx = (clockDp * 3f).toInt().coerceIn(190, 380)

            views.setImageViewBitmap(R.id.widget_entry_button, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.ENTRY, buttonBitmapPx))
            views.setImageViewBitmap(R.id.widget_pause_button, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.PAUSE, buttonBitmapPx))
            views.setImageViewBitmap(R.id.widget_exit_button, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.EXIT, buttonBitmapPx))
            views.setImageViewBitmap(R.id.widget_clock, WidgetVisualRenderer.clock(clockBitmapPx))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(R.id.widget_entry_button, R.id.widget_pause_button, R.id.widget_exit_button).forEach { id ->
                    views.setViewLayoutWidth(id, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(id, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                }
                views.setViewLayoutWidth(R.id.widget_clock, clockDp, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.widget_clock, clockDp, TypedValue.COMPLEX_UNIT_DIP)
            }

            listOf(R.id.widget_entry_label, R.id.widget_pause_label, R.id.widget_exit_label).forEach { id ->
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, labelSp)
            }
            listOf(R.id.widget_entry_time, R.id.widget_exit_time, R.id.widget_duration).forEach { id ->
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, timeSp)
            }
            listOf(R.id.widget_entry_location, R.id.widget_exit_location, R.id.widget_pause_time, R.id.widget_location).forEach { id ->
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, smallSp)
            }

            listOf(R.id.widget_crown, R.id.widget_hp, R.id.widget_work, R.id.widget_entry_label, R.id.widget_pause_label, R.id.widget_exit_label, R.id.widget_duration, R.id.widget_location).forEach { views.setTextColor(it, accent) }
            views.setTextColor(R.id.widget_state, text)
            views.setTextColor(R.id.widget_entry_time, Color.parseColor("#55D96B"))
            views.setTextColor(R.id.widget_pause_time, secondary)
            views.setTextColor(R.id.widget_exit_time, Color.parseColor("#FF655D"))
            listOf(R.id.widget_entry_location, R.id.widget_exit_location).forEach { views.setTextColor(it, secondary) }

            var entryText = "--:--"; var exitText = "--:--"; var durationText = "00h 00m"; var pauseText = "00h 00m"
            var stateText = "PRÊT"; var stateColor = text; var locationText = "📍 Aucune zone"; var entryLocation = ""; var exitLocation = ""
            val hasOpen = PointageStore.hasOpen(context)
            val paused = PointageStore.isPaused(context)
            views.setTextViewText(R.id.widget_pause_label, if (paused) "REPRENDRE" else "PAUSE")
            views.setFloat(R.id.widget_entry_area, "setAlpha", if (hasOpen) 0.52f else 1f)
            views.setFloat(R.id.widget_exit_area, "setAlpha", if (hasOpen) 1f else 0.52f)
            views.setFloat(R.id.widget_pause_area, "setAlpha", if (hasOpen) 1f else 0.52f)

            val data = PointageStore.load(context)
            if (data.length() > 0) {
                val last = data.optJSONObject(data.length() - 1)
                if (last != null) {
                    val entry = last.optLong("entry", -1L)
                    if (entry > 0L) {
                        val zoneAddress = last.optString("zoneAddress").trim()
                        entryText = formatTime(entry)
                        val place = if (zoneAddress.isNotEmpty()) shortLocation(zoneAddress, 30) else "Pointage manuel"
                        entryLocation = place
                        locationText = "📍 ${shortLocation(if (zoneAddress.isNotEmpty()) zoneAddress else place, 42)}"
                        val effectiveEnd: Long
                        if (last.isNull("exit")) {
                            effectiveEnd = System.currentTimeMillis(); stateText = if (paused) "EN PAUSE" else "EN COURS"; stateColor = if (paused) Color.parseColor("#F3A64A") else Color.parseColor("#59DB60")
                        } else {
                            effectiveEnd = last.optLong("exit", entry).coerceAtLeast(entry); exitText = formatTime(effectiveEnd); exitLocation = place; stateText = "TERMINÉ"; stateColor = Color.parseColor("#FF5B52")
                        }
                        pauseText = formatDuration(PointageStore.pauseDuration(last, effectiveEnd)); durationText = formatDuration(PointageStore.workedDuration(last, effectiveEnd))
                    }
                }
            }

            views.setTextViewText(R.id.widget_entry_time, entryText); views.setTextViewText(R.id.widget_exit_time, exitText)
            views.setTextViewText(R.id.widget_entry_location, entryLocation); views.setTextViewText(R.id.widget_exit_location, exitLocation)
            views.setTextViewText(R.id.widget_pause_time, pauseText); views.setTextViewText(R.id.widget_duration, durationText)
            views.setTextViewText(R.id.widget_state, stateText); views.setTextColor(R.id.widget_state, stateColor); views.setTextViewText(R.id.widget_location, locationText)
            val widgetPrefs = context.getSharedPreferences("widget_style", Context.MODE_PRIVATE)
            views.setViewVisibility(R.id.widget_location, if (widgetPrefs.getBoolean("show_position", true)) View.VISIBLE else View.GONE)
            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        updateWidget(context, manager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_ENTRY -> { if (PointageStore.entry(context)) Toast.makeText(context, "Entrée enregistrée", Toast.LENGTH_SHORT).show() else Toast.makeText(context, "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show(); IconSwitcher.sync(context) }
            ACTION_PAUSE -> { when { !PointageStore.hasOpen(context) -> Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show(); PointageStore.isPaused(context) -> { PointageStore.resumePause(context); Toast.makeText(context, "Travail repris", Toast.LENGTH_SHORT).show() }; else -> { PointageStore.startPause(context); Toast.makeText(context, "Pause démarrée", Toast.LENGTH_SHORT).show() } }; IconSwitcher.sync(context) }
            ACTION_EXIT -> { if (PointageStore.exit(context)) Toast.makeText(context, "Sortie enregistrée", Toast.LENGTH_SHORT).show() else Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show(); IconSwitcher.sync(context) }
            Intent.ACTION_CONFIGURATION_CHANGED -> Unit
        }
        updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
    }
}
