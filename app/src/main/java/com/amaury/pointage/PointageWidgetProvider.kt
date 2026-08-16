package com.amaury.pointage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class PointageWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_ENTRY = "com.amaury.pointage.ACTION_ENTRY"
        private const val ACTION_EXIT = "com.amaury.pointage.ACTION_EXIT"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PointageWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun load(context: Context): JSONArray {
            val prefs = context.getSharedPreferences("pointage", Context.MODE_PRIVATE)
            return JSONArray(prefs.getString("data", "[]") ?: "[]")
        }

        private fun save(context: Context, data: JSONArray) {
            context.getSharedPreferences("pointage", Context.MODE_PRIVATE)
                .edit().putString("data", data.toString()).apply()
        }

        private fun hasOpen(context: Context): Boolean {
            val data = load(context)
            for (i in 0 until data.length()) {
                if (data.getJSONObject(i).isNull("exit")) return true
            }
            return false
        }

        private fun doEntry(context: Context): Boolean {
            val data = load(context)
            for (i in 0 until data.length()) {
                if (data.getJSONObject(i).isNull("exit")) return false
            }
            data.put(JSONObject().put("entry", System.currentTimeMillis()).put("exit", JSONObject.NULL))
            save(context, data)
            return true
        }

        private fun doExit(context: Context): Boolean {
            val data = load(context)
            for (i in data.length() - 1 downTo 0) {
                val item = data.getJSONObject(i)
                if (item.isNull("exit")) {
                    item.put("exit", System.currentTimeMillis())
                    save(context, data)
                    return true
                }
            }
            return false
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_pointage)

            val entryIntent = Intent(context, PointageWidgetProvider::class.java).apply { action = ACTION_ENTRY }
            val exitIntent = Intent(context, PointageWidgetProvider::class.java).apply { action = ACTION_EXIT }

            views.setOnClickPendingIntent(
                R.id.widget_entry,
                PendingIntent.getBroadcast(context, 1, entryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            views.setOnClickPendingIntent(
                R.id.widget_exit,
                PendingIntent.getBroadcast(context, 2, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            views.setTextViewText(
                R.id.widget_status,
                if (hasOpen(context)) "🟢 Entrée en cours" else "⚪ Aucune entrée en cours"
            )
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
                Toast.makeText(context, if (doEntry(context)) "Entrée enregistrée" else "Une entrée est déjà en cours", Toast.LENGTH_SHORT).show()
                updateAll(context)
            }
            ACTION_EXIT -> {
                Toast.makeText(context, if (doExit(context)) "Sortie enregistrée" else "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
                updateAll(context)
            }
        }
    }
}
