package com.amaury.pointage

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.graphics.Color
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var history: TextView
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val title = TextView(this).apply {
            text = "🏱️ Mes temps de travail"
            textSize = 26f
            setPadding(0, 0, 0, 20)
        }
        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, 10, 0, 20)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val ent = Button(this).apply {
            text = "�" ENTRÉE"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(22, 163, 74))
            setOnClickListener {
                if (!PointageStore.entry(this@MainActivity)) toast("Une entrée est déjà en cours.")
                PointageWidgetProvider.updateAll(this@MainActivity)
                render()
            }
        }

        val sor = Button(this).apply {
            text = "🔴 SORTIE"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(220, 38, 38))
            setOnClickListener {
                if (!PointageStore.exit(this@MainActivity)) toast("Aucune entrée en cours.")
                PointageWidgetProvider.updateAll(this@MainActivity)
                render()
            }
        }

        buttons.addView(ent, LinearLayout.LayoutParams(0, 150, 1f))
        buttons.addView(sor, LinearLayout.LayoutParams(0, 150, 1f))
        history = TextView(this).apply {
            textSize = 16f
            setPadding(0, 25, 0, 0)
        }
        box.addView(title)
        box.addView(status)
        box.addView(buttons)
        box.addView(history)
        setContentView(box)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val a = PointageStore.load(this)
        var s = ""
        var open = false
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val e = o.getLong("entry")
            if (o.isNull("exit")) {
                open = true
                s += "\n${fmt.format(Date(e))} → EN COURS"
            } else {
                val x = o.getLong("exit")
                s += "\n${fmt.format(Date(e))} → ${fmt.format(Date(x))}  (${dur(x - e)})"
            }
        }
        status.text = if (open) "🟁 Entrée en cours" else "⚚ Aucune entrée en cours"
        history.text = if (s.isEmpty()) "Aucun horaire enregistré." else "Historique :$s"
    }

    private fun dur(ms: Long): String {
        val sec = ms / 1000
        return "%02h %02dmin".format(sec / 3600, (sec % 3600) / 60)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
