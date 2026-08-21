package com.amaury.pointage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast

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

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_actions)
            val theme = AppThemeCatalog.current(context)
            val dark = AppThemeCatalog.useDarkPalette(context)
            val accent = if (dark) theme.accentLight else theme.accent

            views.setInt(R.id.quick_surface, "setBackgroundResource", backgroundFor(theme.id, dark))
            views.setImageViewBitmap(R.id.quick_entry_button, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.ENTRY, 200))
            views.setImageViewBitmap(R.id.quick_pause_icon, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.PAUSE, 200))
            views.setImageViewBitmap(R.id.quick_exit_button, WidgetVisualRenderer.jewel(context, WidgetVisualRenderer.Jewel.EXIT, 200))
            views.setTextColor(R.id.quick_entry_label, accent)
            views.setTextColor(R.id.quick_pause_label, accent)
            views.setTextColor(R.id.quick_exit_label, accent)

            val entryIntent = Intent(context, QuickActionsWidgetProvider::class.java).apply { action = ACTION_ENTRY }
            val pauseIntent = Intent(context, QuickActionsWidgetProvider::class.java).apply { action = ACTION_PAUSE }
            val exitIntent = Intent(context, QuickActionsWidgetProvider::class.java).apply { action = ACTION_EXIT }
            views.setOnClickPendingIntent(R.id.quick_entry, PendingIntent.getBroadcast(context, 101, entryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.quick_pause, PendingIntent.getBroadcast(context, 102, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.quick_exit, PendingIntent.getBroadcast(context, 103, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val hasOpen = PointageStore.hasOpen(context)
            val paused = PointageStore.isPaused(context)
            views.setTextViewText(R.id.quick_pause_label, if (paused) "REPRENDRE" else "PAUSE")
            views.setFloat(R.id.quick_entry, "setAlpha", if (hasOpen) 0.45f else 1f)
            views.setFloat(R.id.quick_pause, "setAlpha", if (hasOpen) 1f else 0.45f)
            views.setFloat(R.id.quick_exit, "setAlpha", if (hasOpen) 1f else 0.45f)
            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { updateWidget(context, manager, it) } }
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
            IconSwitcher.sync(context); PointageWidgetProvider.updateAll(context); updateAll(context)
        }
    }
}
