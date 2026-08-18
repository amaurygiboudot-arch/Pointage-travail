package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ManualPauseButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Button(context, attrs) {

    init { setOnClickListener { showDialog() } }

    private fun showDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val selectedDate = Calendar.getInstance(Locale.FRANCE)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        body.addView(TextView(context).apply {
            text = "⏸  SAISIE MANUELLE D'UNE PAUSE"
            gravity = Gravity.CENTER
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F3A64A"))
        })
        body.addView(TextView(context).apply {
            text = "Choisis la date, l'heure de début et l'heure de fin. La pause sera rattachée à la session de travail correspondante et déduite du temps travaillé."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(12))
        })

        val dateButton = Button(context).apply {
            text = "Date : ${dateFormat.format(selectedDate.time)}"
            isAllCaps = false
        }
        dateButton.setOnClickListener {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    selectedDate.set(year, month, day)
                    dateButton.text = "Date : ${dateFormat.format(selectedDate.time)}"
                },
                selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        fun timeInput(hintText: String) = EditText(context).apply {
            hint = hintText
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        val start = timeInput("Début de pause — ex. 10:00")
        val end = timeInput("Fin de pause — ex. 10:15")
        body.addView(dateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        body.addView(start)
        body.addView(end)

        val scroll = ScrollView(context).apply { addView(body) }
        val cancel = Button(context).apply { text = "Annuler"; isAllCaps = false }
        val add = Button(context).apply { text = "Ajouter la pause"; isAllCaps = false }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            addView(cancel, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
            addView(add, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actions)
        }
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.setOnShowListener {
            val m = resources.displayMetrics
            dialog.window?.setLayout((m.widthPixels * .92f).toInt(), (m.heightPixels * .82f).toInt())
        }
        cancel.setOnClickListener { dialog.dismiss() }
        add.setOnClickListener {
            val startMs = parseTime(selectedDate, start.text.toString())
            val endMs = parseTime(selectedDate, end.text.toString())
            if (startMs == null) { start.error = "Format attendu : HH:mm"; return@setOnClickListener }
            if (endMs == null) { end.error = "Format attendu : HH:mm"; return@setOnClickListener }
            if (endMs <= startMs) { end.error = "La fin doit être après le début"; return@setOnClickListener }
            if (!PointageStore.addManualPause(context, startMs, endMs)) {
                Toast.makeText(context, "Aucune session de travail ne contient cette pause", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val minutes = (endMs - startMs) / 60000L
            Toast.makeText(context, "Pause ajoutée : ${minutes} min", Toast.LENGTH_LONG).show()
            dialog.dismiss()
            (context as? Activity)?.recreate()
        }
        dialog.show()
    }

    private fun parseTime(day: Calendar, value: String): Long? {
        val match = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
