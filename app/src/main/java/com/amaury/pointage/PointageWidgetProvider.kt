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
        private fun formatDuration(ms: Long): String { val m=ms.coerceAtLeast(0L)/60000L; return String.format(Locale.FRANCE,"%02dh %02dm",m/60L,m%60L) }
        private fun shortLocation(address: String, max: Int = 34): String { val s=address.replace("\n"," ").trim(); return if(s.length<=max)s else s.take(max-1)+"…" }
        private fun parseColor(value:String?, fallback:Int)=runCatching{Color.parseColor(value?:"")}.getOrDefault(fallback)

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views=RemoteViews(context.packageName,R.layout.widget_pointage)
            val entryIntent=Intent(context,PointageWidgetProvider::class.java).apply{action=ACTION_ENTRY}
            val exitIntent=Intent(context,PointageWidgetProvider::class.java).apply{action=ACTION_EXIT}
            val openApp=Intent(context,MainActivity::class.java).apply{putExtra("open_tab","today");flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP}
            val openSettings=Intent(context,MainActivity::class.java).apply{putExtra("open_tab","settings");flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP}
            views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(context,20,openApp,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_location,PendingIntent.getActivity(context,30,openSettings,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_entry,PendingIntent.getBroadcast(context,1,entryIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_exit,PendingIntent.getBroadcast(context,2,exitIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_status_area,PendingIntent.getActivity(context,20,openApp,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val appearance=context.getSharedPreferences("appearance_settings",Context.MODE_PRIVATE)
            val style=context.getSharedPreferences("widget_style",Context.MODE_PRIVATE)
            val mode=appearance.getString("mode","auto")?:"auto"
            val systemDark=(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
            val dark=when(mode){"light"->false;"dark"->true;else->systemDark}
            val defaultAccent=Color.parseColor(if(dark)"#F3D58A" else "#8A6200")
            val defaultSecondary=Color.parseColor(if(dark)"#D8D1C3" else "#4E4A44")
            val accent=if(style.contains("widget_accent"))parseColor(style.getString("widget_accent",null),defaultAccent)else defaultAccent
            val secondary=defaultSecondary

            // Keep the premium rounded drawable. Do not replace it with a flat setBackgroundColor,
            // otherwise Android removes the gold border, gradient and rounded corners.
            views.setInt(R.id.widget_root,"setBackgroundResource",R.drawable.widget_bg)
            views.setInt(R.id.widget_status_area,"setBackgroundResource",R.drawable.widget_status_panel)
            listOf(R.id.widget_crown,R.id.widget_hp,R.id.widget_work,R.id.widget_entry_label,R.id.widget_exit_label,R.id.widget_now,R.id.widget_duration,R.id.widget_pause_label,R.id.widget_location).forEach{views.setTextColor(it,accent)}
            listOf(R.id.widget_entry_location,R.id.widget_exit_location,R.id.widget_pause).forEach{views.setTextColor(it,secondary)}

            var entryText="--:--";var exitText="--:--";var duration="00h 00m";var state="PRÊT";var stateColor=accent;var location="📍 Aucune zone";var entryLoc="";var exitLoc=""
            val data=PointageStore.load(context)
            if(data.length()>0){val last=data.getJSONObject(data.length()-1);val entry=last.getLong("entry");val zone=last.optString("zoneAddress").trim();entryText=formatTime(entry);val place=if(zone.isNotEmpty())shortLocation(zone,30)else"Pointage manuel";entryLoc=place;location="📍 ${shortLocation(if(zone.isNotEmpty())zone else place,40)}";if(last.isNull("exit")){duration=formatDuration(System.currentTimeMillis()-entry);state="EN COURS";stateColor=accent}else{val exit=last.getLong("exit");exitText=formatTime(exit);exitLoc=place;duration=formatDuration(exit-entry);state="TERMINÉ";stateColor=secondary}}
            views.setTextViewText(R.id.widget_entry_time,entryText);views.setTextViewText(R.id.widget_exit_time,exitText);views.setTextViewText(R.id.widget_entry_location,entryLoc);views.setTextViewText(R.id.widget_exit_location,exitLoc);views.setTextViewText(R.id.widget_duration,duration);views.setTextViewText(R.id.widget_state,state);views.setTextColor(R.id.widget_state,stateColor);views.setTextViewText(R.id.widget_pause,"00h 00m");views.setTextViewText(R.id.widget_location,location);views.setViewVisibility(R.id.widget_location,if(style.getBoolean("show_position",true))View.VISIBLE else View.GONE)
            manager.updateAppWidget(widgetId,views)
        }
    }
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){ids.forEach{updateWidget(context,manager,it)}}
    override fun onReceive(context:Context,intent:Intent){super.onReceive(context,intent);when(intent.action){ACTION_ENTRY->{if(PointageStore.entry(context))Toast.makeText(context,"Entrée enregistrée",Toast.LENGTH_SHORT).show()else Toast.makeText(context,"Une entrée est déjà en cours",Toast.LENGTH_SHORT).show();updateAll(context)};ACTION_EXIT->{if(PointageStore.exit(context))Toast.makeText(context,"Sortie enregistrée",Toast.LENGTH_SHORT).show()else Toast.makeText(context,"Aucune entrée en cours",Toast.LENGTH_SHORT).show();updateAll(context)};Intent.ACTION_CONFIGURATION_CHANGED->updateAll(context)}}
}
