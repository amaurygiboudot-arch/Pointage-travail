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
            return options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220).coerceAtLeast(160) to
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 82).coerceAtLeast(60)
        }

        private fun pending(context: Context, widgetId: Int, action: String, slot: Int): PendingIntent {
            val intent = Intent(context, QuickActionsWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            return PendingIntent.getBroadcast(context, widgetId * 10 + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_actions)
            val theme = AppThemeCatalog.current(context)
            val dark = AppThemeCatalog.useDarkPalette(context)
            val accent = if (dark) theme.accentLight else theme.accent

            val (widgetWidth, widgetHeight) = widgetSize(manager, widgetId)
            // On réserve toujours la même place à chacun des trois boutons. La taille est calculée
            // à partir de la largeur disponible ET de la hauteur afin qu'aucun rond ne soit étiré.
            val cellWidth = widgetWidth / 3f
            val availableHeight = (widgetHeight - 22f).coerceAtLeast(42f)
            val buttonDp = min(cellWidth * .78f, availableHeight * .82f).coerceIn(38f, 86f)
            // Le fond coloré utilise exactement le même ratio que LightReactiveJewelButton (0,885).
            val innerDp = buttonDp * .885f
            val labelSp = (buttonDp * .15f).coerceIn(7.5f, 12f)
            val bitmapPx = (buttonDp * 3f).toInt().coerceIn(120, 300)

            views.setInt(R.id.quick_surface, "setBackgroundResource", backgroundFor(theme.id, dark))
            val frame = WidgetVisualRenderer.jewelFrame(bitmapPx)
            views.setImageViewBitmap(R.id.quick_entry_button, frame)
            views.setImageViewBitmap(R.id.quick_pause_icon, frame)
            views.setImageViewBitmap(R.id.quick_exit_button, frame)
            views.setImageViewBitmap(R.id.quick_entry_inner, WidgetVisualRenderer.jewelInner(context, WidgetVisualRenderer.Jewel.ENTRY, bitmapPx))
            views.setImageViewBitmap(R.id.quick_pause_inner, WidgetVisualRenderer.jewelInner(context, WidgetVisualRenderer.Jewel.PAUSE, bitmapPx))
            views.setImageViewBitmap(R.id.quick_exit_inner, WidgetVisualRenderer.jewelInner(context, WidgetVisualRenderer.Jewel.EXIT, bitmapPx))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(R.id.quick_entry_stack, R.id.quick_pause_stack, R.id.quick_exit_stack).forEach {
                    views.setViewLayoutWidth(it, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(it, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                }
                listOf(R.id.quick_entry_button, R.id.quick_pause_icon, R.id.quick_exit_button).forEach {
                    views.setViewLayoutWidth(it, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(it, buttonDp, TypedValue.COMPLEX_UNIT_DIP)
                }
                listOf(R.id.quick_entry_inner, R.id.quick_pause_inner, R.id.quick_exit_inner).forEach {
                    views.setViewLayoutWidth(it, innerDp, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(it, innerDp, TypedValue.COMPLEX_UNIT_DIP)
                }
            }

            listOf(R.id.quick_entry_label, R.id.quick_pause_label, R.id.quick_exit_label).forEach {
                views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, labelSp)
                views.setTextColor(it, accent)
            }

            views.setOnClickPendingIntent(R.id.quick_entry_inner, pending(context, widgetId, ACTION_ENTRY, 1))
            views.setOnClickPendingIntent(R.id.quick_pause_inner, pending(context, widgetId, ACTION_PAUSE, 2))
            views.setOnClickPendingIntent(R.id.quick_exit_inner, pending(context, widgetId, ACTION_EXIT, 3))

            val paused = PointageStore.isPaused(context)
            views.setTextViewText(R.id.quick_pause_label, if (paused) "REPRENDRE" else "PAUSE")
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
        var handled = false
        when (intent.action) {
            ACTION_ENTRY -> {
                handled = true
                if (PointageStore.entry(context)) Toast.makeText(context, "Entrée enregistrée", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
            }
            ACTION_PAUSE -> {
                handled = true
                when {
                    !PointageStore.hasOpen(context) -> Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
                    PointageStore.isPaused(context) -> { PointageStore.resumePause(context); Toast.makeText(context, "Travail repris", Toast.LENGTH_SHORT).show() }
                    else -> { PointageStore.startPause(context); Toast.makeText(context, "Pause démarrée", Toast.LENGTH_SHORT).show() }
                }
            }
            ACTION_EXIT -> {
                handled = true
                if (PointageStore.exit(context)) Toast.makeText(context, "Sortie enregistrée", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
            }
            Intent.ACTION_CONFIGURATION_CHANGED -> handled = true
        }
        if (handled) {
            PointageWidgetProvider.updateAll(context)
            updateAll(context)
        }
    }
}
