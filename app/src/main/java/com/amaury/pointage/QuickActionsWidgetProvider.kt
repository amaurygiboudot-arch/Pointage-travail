package com.amaury.pointage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.Toast
import kotlin.math.min

class QuickActionsWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_ENTRY = "com.amaury.pointage.QUICK_ACTION_ENTRY"
        const val ACTION_PAUSE = "com.amaury.pointage.QUICK_ACTION_PAUSE"
        const val ACTION_EXIT = "com.amaury.pointage.QUICK_ACTION_EXIT"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, QuickActionsWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
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
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220).coerceAtLeast(160)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 82).coerceAtLeast(60)
            return width to height
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_actions)
            val theme = AppThemeCatalog.current(context)
            val dark = AppThemeCatalog.useDarkPalette(context)
            val accent = if (dark) theme.accentLight else theme.accent

            val (widgetWidth, widgetHeight) = widgetSize(manager, widgetId)
            val buttonDp = min(widgetWidth / 3.65f, widgetHeight * 0.68f).coerceIn(38f, 88f)
            val labelSp = (buttonDp * 0.15f).coerceIn(7.5f, 12f)
            val bitmapPx = (buttonDp * 3f).toInt().coerceIn(120, 300)

            views.setInt(R.id.quick_surface, "setBackgroundResource", backgroundFor(theme.id, dark))
            views.setImageViewBitmap(R.id.quick_entry_button, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.ENTRY, bitmapPx))
            views.setImageViewBitmap(R.id.quick_pause_icon, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.PAUSE, bitmapPx))
            views.setImageViewBitmap(R.id.quick_exit_button, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.EXIT, bitmapPx))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(R.id.quick_entry_button, R.id.quick_pause_icon, R.id.quick_exit_button).forEach { id ->
                    views.setViewLayoutWidth(id, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(id, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                }
            }
            listOf(R.id.quick_entry_label, R.id.quick_pause_label, R.id.quick_exit_label).forEach { id ->
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, labelSp)
                views.setTextColor(id, accent)
            }

            val entryIntent = Intent(context, QuickActionsWidgetProvider::class.java).apply { action = ACTION_ENTRY }
            val pauseIntent = Intent(context, QuickActionsWidgetProvider::class.java).apply { action = ACTION_PAUSE }
            val exitIntent = Intent(context, QuickActionsWidgetProvider::class.java).apply { action = ACTION_EXIT }
            views.setOnClickPendingIntent(R.id.quick_entry, PendingIntent.getBroadcast(context, 101, entryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.quick_pause, PendingIntent.getBroadcast(context, 102, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.quick_exit, PendingIntent.getBroadcast(context, 103, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val paused = PointageStore.isPaused(context)
            views.setTextViewText(R.id.quick_pause_label, if (paused) "REPRENDRE" else "PAUSE")

            // Les trois boutons restent visuellement au même niveau de luminosité.
            // L'état actif/inactif est indiqué par le texte et les actions, pas en assombrissant un bouton.
            views.setFloat(R.id.quick_entry, "setAlpha", 1f)
            views.setFloat(R.id.quick_pause, "setAlpha", 1f)
            views.setFloat(R.id.quick_exit, "setAlpha", 1f)

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
            ACTION_ENTRY -> if (PointageStore.entry(context)) Toast.makeText(context, "Entrée enregistrée", Toast.LENGTH_SHORT).show() else Toast.makeText(context, "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
            ACTION_PAUSE -> when {
                !PointageStore.hasOpen(context) -> Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
                PointageStore.isPaused(context) -> { PointageStore.resumePause(context); Toast.makeText(context, "Travail repris", Toast.LENGTH_SHORT).show() }
                else -> { PointageStore.startPause(context); Toast.makeText(context, "Pause démarrée", Toast.LENGTH_SHORT).show() }
            }
            ACTION_EXIT -> if (PointageStore.exit(context)) Toast.makeText(context, "Sortie enregistrée", Toast.LENGTH_SHORT).show() else Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
            Intent.ACTION_CONFIGURATION_CHANGED -> Unit
        }
        if (intent.action == ACTION_ENTRY || intent.action == ACTION_PAUSE || intent.action == ACTION_EXIT || intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            IconSwitcher.sync(context)
            PointageWidgetProvider.updateAll(context)
            updateAll(context)
        }
    }
}
