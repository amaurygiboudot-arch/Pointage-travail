package com.amaury.pointage

import android.app.PendingIntent
import android.app.WallpaperManager
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
            manager.getAppWidgetIds(component).forEach { updateDynamicWidget(context, manager, it) }
        }

        private fun rebuildAll(context: Context) {
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

        private fun adaptiveWidgetTextColors(context: Context, darkFallback: Boolean): Pair<Int, Int> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching {
                    val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    if (colors != null) {
                        val primary = colors.primaryColor.toArgb()
                        val r = Color.red(primary) / 255f
                        val g = Color.green(primary) / 255f
                        val b = Color.blue(primary) / 255f
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        val useDarkText = luma >= 0.56f
                        return (if (useDarkText) Color.rgb(8, 8, 8) else Color.WHITE) to
                            (if (useDarkText) Color.rgb(48, 48, 48) else Color.rgb(235, 235, 235))
                    }
                }
            }
            return if (darkFallback) Color.WHITE to Color.rgb(230, 230, 230)
            else Color.rgb(8, 8, 8) to Color.rgb(55, 55, 55)
        }

        private fun widgetSize(manager: AppWidgetManager, widgetId: Int): Pair<Int, Int> {
            val options = manager.getAppWidgetOptions(widgetId)
            return options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360).coerceAtLeast(280) to
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 96).coerceAtLeast(78)
        }

        private fun pendingBroadcast(context: Context, widgetId: Int, action: String, slot: Int): PendingIntent {
            val intent = Intent(context, PointageWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            return PendingIntent.getBroadcast(context, widgetId * 10 + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun applyDynamicState(context: Context, views: RemoteViews) {
            val dark = AppThemeCatalog.useDarkPalette(context)
            val (adaptiveText, _) = adaptiveWidgetTextColors(context, dark)
            var entryText = "--:--"
            var exitText = "--:--"
            var durationText = "00h 00m"
            var pauseText = "00h 00m"
            var stateText = "PRÊT"
            var stateColor = adaptiveText
            var locationText = "📍 Aucune zone"
            var entryLocation = ""
            var exitLocation = ""
            val paused = PointageStore.isPaused(context)
            views.setTextViewText(R.id.widget_pause_label, if (paused) "REPRENDRE" else "PAUSE")

            PointageSessionQueries.latestValidSession(context)?.let { last ->
                val entry = last.optLong("entry", -1L)
                if (entry > 0L) {
                    val zoneAddress = last.optString("zoneAddress").trim()
                    entryText = formatTime(entry)
                    val place = if (zoneAddress.isNotEmpty()) shortLocation(zoneAddress, 30) else "Pointage manuel"
                    entryLocation = place
                    locationText = "📍 ${shortLocation(if (zoneAddress.isNotEmpty()) zoneAddress else place, 54)}"
                    val effectiveEnd: Long
                    if (last.isNull("exit")) {
                        effectiveEnd = System.currentTimeMillis()
                        stateText = if (paused) "EN PAUSE" else "EN COURS"
                        stateColor = if (paused) Color.parseColor("#E38B20") else Color.parseColor("#2AA63B")
                    } else {
                        effectiveEnd = last.optLong("exit", entry).coerceAtLeast(entry)
                        exitText = formatTime(effectiveEnd)
                        exitLocation = place
                        stateText = "TERMINÉ"
                        stateColor = Color.parseColor("#D93630")
                    }
                    pauseText = formatDuration(PointageStore.pauseDuration(last, effectiveEnd))
                    durationText = formatDuration(PointageStore.workedDuration(last, effectiveEnd))
                }
            }

            views.setTextViewText(R.id.widget_entry_time, entryText)
            views.setTextViewText(R.id.widget_exit_time, exitText)
            views.setTextViewText(R.id.widget_entry_location, entryLocation)
            views.setTextViewText(R.id.widget_exit_location, exitLocation)
            views.setTextViewText(R.id.widget_pause_time, pauseText)
            views.setTextViewText(R.id.widget_duration, durationText)
            views.setTextViewText(R.id.widget_state, stateText)
            views.setTextColor(R.id.widget_state, stateColor)
            views.setTextViewText(R.id.widget_location, locationText)
        }

        private fun updateDynamicWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_pointage)
            applyDynamicState(context, views)
            manager.partiallyUpdateAppWidget(widgetId, views)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_pointage)
            val openApp = PendingIntent.getActivity(context, widgetId * 10 + 7, Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", "today")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val openSettings = PendingIntent.getActivity(context, widgetId * 10 + 8, Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", "settings")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            views.setOnClickPendingIntent(R.id.widget_clock, openApp)
            views.setOnClickPendingIntent(R.id.widget_location, openSettings)
            views.setOnClickPendingIntent(R.id.widget_status_area, openApp)
            views.setOnClickPendingIntent(R.id.widget_entry_inner, pendingBroadcast(context, widgetId, ACTION_ENTRY, 1))
            views.setOnClickPendingIntent(R.id.widget_pause_inner, pendingBroadcast(context, widgetId, ACTION_PAUSE, 2))
            views.setOnClickPendingIntent(R.id.widget_exit_inner, pendingBroadcast(context, widgetId, ACTION_EXIT, 3))

            val theme = AppThemeCatalog.current(context)
            val dark = AppThemeCatalog.useDarkPalette(context)
            val accent = if (dark) theme.accentLight else theme.accent
            val (adaptiveText, adaptiveSecondary) = adaptiveWidgetTextColors(context, dark)
            views.setInt(R.id.widget_surface, "setBackgroundResource", backgroundFor(theme.id, dark))

            val (widgetWidth, widgetHeight) = widgetSize(manager, widgetId)
            val buttonDp = min(widgetWidth / 5.15f, widgetHeight * 0.54f).coerceIn(46f, 84f)
            val innerDp = buttonDp * .885f
            val clockDp = (buttonDp * 1.62f).coerceIn(82f, 136f)
            val labelSp = (buttonDp * .16f).coerceIn(10f, 14f)
            val timeSp = (buttonDp * .20f).coerceIn(12f, 16f)
            val smallSp = (buttonDp * .14f).coerceIn(9f, 12f)
            val locationSp = (buttonDp * .26f).coerceIn(16f, 22f)
            val stateSp = (buttonDp * .18f).coerceIn(11f, 15f)
            val brandSp = (buttonDp * .14f).coerceIn(9f, 12f)
            val buttonBitmapPx = (buttonDp * 3.2f).toInt().coerceIn(150, 300)
            val clockBitmapPx = (clockDp * 3.2f).toInt().coerceIn(240, 440)

            val frame = WidgetVisualRenderer.jewelFrame(buttonBitmapPx)
            views.setImageViewBitmap(R.id.widget_entry_button, frame)
            views.setImageViewBitmap(R.id.widget_pause_button, frame)
            views.setImageViewBitmap(R.id.widget_exit_button, frame)
            views.setImageViewBitmap(R.id.widget_entry_inner, WidgetVisualRenderer.jewelInner(context, WidgetVisualRenderer.Jewel.ENTRY, buttonBitmapPx))
            views.setImageViewBitmap(R.id.widget_pause_inner, WidgetVisualRenderer.jewelInner(context, WidgetVisualRenderer.Jewel.PAUSE, buttonBitmapPx))
            views.setImageViewBitmap(R.id.widget_exit_inner, WidgetVisualRenderer.jewelInner(context, WidgetVisualRenderer.Jewel.EXIT, buttonBitmapPx))
            views.setImageViewBitmap(R.id.widget_clock, WidgetVisualRenderer.clock(clockBitmapPx))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(R.id.widget_entry_stack, R.id.widget_pause_stack, R.id.widget_exit_stack).forEach {
                    views.setViewLayoutWidth(it, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(it, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                }
                listOf(R.id.widget_entry_inner, R.id.widget_pause_inner, R.id.widget_exit_inner).forEach {
                    views.setViewLayoutWidth(it, innerDp, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(it, innerDp, TypedValue.COMPLEX_UNIT_DIP)
                }
                views.setViewLayoutWidth(R.id.widget_clock, clockDp, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.widget_clock, clockDp, TypedValue.COMPLEX_UNIT_DIP)
            }

            listOf(R.id.widget_entry_label, R.id.widget_pause_label, R.id.widget_exit_label).forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, labelSp) }
            listOf(R.id.widget_entry_time, R.id.widget_exit_time, R.id.widget_duration).forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, timeSp) }
            listOf(R.id.widget_entry_location, R.id.widget_exit_location, R.id.widget_pause_time).forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, smallSp) }
            views.setTextViewTextSize(R.id.widget_location, TypedValue.COMPLEX_UNIT_SP, locationSp)
            views.setTextViewTextSize(R.id.widget_state, TypedValue.COMPLEX_UNIT_SP, stateSp)
            views.setTextViewTextSize(R.id.widget_hp, TypedValue.COMPLEX_UNIT_SP, brandSp)
            views.setTextViewTextSize(R.id.widget_work, TypedValue.COMPLEX_UNIT_SP, (brandSp - 1f).coerceAtLeast(8f))

            listOf(R.id.widget_entry_label,R.id.widget_pause_label,R.id.widget_exit_label,R.id.widget_duration,R.id.widget_location,R.id.widget_state).forEach { views.setTextColor(it, adaptiveText) }
            listOf(R.id.widget_entry_location,R.id.widget_exit_location,R.id.widget_pause_time).forEach { views.setTextColor(it, adaptiveSecondary) }
            listOf(R.id.widget_crown,R.id.widget_hp,R.id.widget_work).forEach { views.setTextColor(it, accent) }
            views.setTextColor(R.id.widget_entry_time, Color.parseColor("#34B84A"))
            views.setTextColor(R.id.widget_exit_time, Color.parseColor("#E8433C"))

            applyDynamicState(context, views)

            val widgetPrefs = context.getSharedPreferences("widget_style", Context.MODE_PRIVATE)
            views.setViewVisibility(R.id.widget_location, if (widgetPrefs.getBoolean("show_position", true)) View.VISIBLE else View.GONE)
            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { updateWidget(context, manager, it) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        updateWidget(context, manager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        var handledAction = false
        var needsFullRebuild = false
        when (intent.action) {
            ACTION_ENTRY -> {
                handledAction = true
                if (PointageStore.entry(context)) Toast.makeText(context, "Entrée enregistrée", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
            }
            ACTION_PAUSE -> {
                handledAction = true
                when {
                    !PointageStore.hasOpen(context) -> Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
                    PointageStore.isPaused(context) -> { PointageStore.resumePause(context); Toast.makeText(context, "Travail repris", Toast.LENGTH_SHORT).show() }
                    else -> { PointageStore.startPause(context); Toast.makeText(context, "Pause démarrée", Toast.LENGTH_SHORT).show() }
                }
            }
            ACTION_EXIT -> {
                handledAction = true
                if (PointageStore.exit(context)) Toast.makeText(context, "Sortie enregistrée", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
            }
            Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_WALLPAPER_CHANGED -> needsFullRebuild = true
        }
        if (needsFullRebuild) {
            rebuildAll(context)
            QuickActionsWidgetProvider.updateAll(context)
        } else if (handledAction) {
            val clickedId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val manager = AppWidgetManager.getInstance(context)
                if (clickedId != AppWidgetManager.INVALID_APPWIDGET_ID) updateDynamicWidget(context, manager, clickedId)
                else updateAll(context)

                // Le petit widget n'a besoin d'être modifié que si Pause devient Reprendre (ou inversement).
                if (intent.action == ACTION_PAUSE) QuickActionsWidgetProvider.updateAll(context)
            }, 260L)
        }
    }
}
