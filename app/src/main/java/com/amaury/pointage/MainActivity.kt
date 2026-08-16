package com.amaury.pointage

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.graphics.Color
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("pointage", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var history: TextView
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,28,28,28) }
        val title=TextView(this).apply { text="⏱️ Mes temps de travail"; textSize=26f; setPadding(0,0,0,20) }
        status=TextView(this).apply { textSize=18f; setPadding(0,10,0,20) }
        val buttons=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val ent=Button(this).apply { text="🟢 ENTRÉE"; textSize=18f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(22,163,74)); setOnClickListener{entry()} }
        val sor=Button(this).apply { text="🔴 SORTIE"; textSize=18f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(220,38,38)); setOnClickListener{exit()} }
        buttons.addView(ent, LinearLayout.LayoutParams(0,150,1f)); buttons.addView(sor, LinearLayout.LayoutParams(0,150,1f))
        history=TextView(this).apply { textSize=16f; setPadding(0,25,0,0) }
        box.addView(title); box.addView(status); box.addView(buttons); box.addView(history)
        setContentView(box); render()
    }
    private fun load()=JSONArray(prefs.getString("data","[]"))
    private fun save(a:JSONArray){prefs.edit().putString("data",a.toString()).apply();render()}
    private fun entry(){val a=load(); if((0 until a.length()).any{a.getJSONObject(it).isNull("exit")}){toast("Une entrée est déjà en cours.");return}; a.put(JSONObject().put("entry",System.currentTimeMillis()).put("exit",JSONObject.NULL));save(a)}
    private fun exit(){val a=load(); for(i in a.length()-1 downTo 0){val o=a.getJSONObject(i);if(o.isNull("exit")){o.put("exit",System.currentTimeMillis());save(a);return}};toast("Aucune entrée en cours.")}
    private fun render(){val a=load();var s="";var open=false;for(i in 0 until a.length()){val o=a.getJSONObject(i);val e=o.getLong("entry");if(o.isNull("exit")){open=true;s+="\n${fmt.format(Date(e))} → EN COURS";}else{s+="\n${fmt.format(Date(e))} → ${fmt.format(Date(o.getLong("exit")))}  (${dur(o.getLong("exit")-e)})"}};status.text=if(open)"🟢 Entrée en cours" else "⚪ Aucune entrée en cours";history.text=if(s.isEmpty())"Aucun horaire enregistré." else "Historique :$s"}
    private fun dur(ms:Long):String{val sec=ms/1000;return "%02dh %02dmin".format(sec/3600,(sec%3600)/60)}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
