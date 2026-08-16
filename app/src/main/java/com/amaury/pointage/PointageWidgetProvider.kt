package com.amaury.pointage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast

class PointageWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_ENTRY = "com.amaury.pointage.ENTRY"
        const val ACTION_EXIT = "com.amaury.pointage.EXIT"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PointageWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)

            ids.forEach {
                updateWidget(context, manager, it)
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(
                context.packageName,
                R.layout.widget_pointage
            )

            val entryIntent =
                Intent(context, PointageWidgetProvider::class.java).apply {
                    action = ACTION_ENTRY
                }

            val exitIntent =
                Intent(context, PointageWidgetProvider::class.java).apply {
                    action = ACTION_EXIT
                }

            views.setOnClickPendingIntent(
                R.id.widget_entry,
                PendingIntent.getBroadcast(
                    context,
                    1,
                    entryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            views.setOnClickPendingIntent(
                R.id.widget_exit,
                PendingIntent.getBroadcast(
                    context,
                    2,
                    exitIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            views.setTextViewText(
                R.id.widget_status,
                if (PointageStore.hasOpen(context))
                    "🟢 Entrée en cours"
                else
                    "⚪ Aucune entrée en cours"
            )

            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        ids.forEach {
            updateWidget(context, manager, it)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {

            ACTION_ENTRY -> {
                if (PointageStore.entry(context)) {
                    Toast.makeText(
                        context,
                        "Entrée enregistrée",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Une entrée est déjà en cours",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                updateAll(context)
            }

            ACTION_EXIT -> {
                if (PointageStore.exit(context)) {
                    Toast.makeText(
                        context,
                        "Sortie enregistrée",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Aucune entrée en cours",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                updateAll(context)
            }
        }
    }
}
