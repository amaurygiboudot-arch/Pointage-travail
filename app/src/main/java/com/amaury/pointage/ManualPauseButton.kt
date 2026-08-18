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
import android.widget.Switch
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
        val schedule = PauseScheduleManager.load(context)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        body.addView(TextView(context).apply {
            text = "⏸  PAUSES"
            gravity = Gravity.CENTER
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F3A64A"))
        })
        body.addView(TextView(context).apply {
            text = "Choisis le début et la fin de pause. Tu peux l'ajouter à une journée précise et/ou demander à HP Travail de l'activer automatiquement tous les jours pendant une entrée en cours."
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
        val start = timeInput("Début de pause — ex. 10:00").apply {
            setText(String.format(Locale.FRANCE, "%02d:%02d", schedule.startHour, schedule.startMinute))
        }
        val end = timeInput("Fin de pause — ex. 10:15").apply {
            setText(String.format(Locale.FRANCE, "%02d:%02d", schedule.endHour, schedule.endMinute))
        }
        val automatic = Switch(context).apply {
            text = "Activer automatiquement tous les jours à ces heures"
            isChecked = schedule.enabled
            setPadding(0, dp(12), 0, dp(4))
        }
        val autoInfo = TextView(context).apply {
            text = "Si une entrée est en cours, la pause démarrera seule à l'heure choisie et le travail reprendra seul à l'heure de fin."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(10))
        }

        body.addView(dateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        body.addView(start)
        body.addView(end)
        body.addView(automatic)
        body.addView(autoInfo)

        val scroll = ScrollView(context).apply { addView(body) }
        val cancel = Button(context).apply { text = "Annuler"; isAllCaps = false }
        val add = Button(context).apply { text = "Enregistrer"; isAllCaps = false }
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

            val startCal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = startMs }
            val endCal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = endMs }

            if (automatic.isChecked) {
                PauseScheduleManager.save(
                    context,
                    startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE),
                    endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE),
                    enabled = true
                )
            } else if (schedule.enabled) {
                PauseScheduleManager.setEnabled(context, false)
            }

            val manualAdded = PointageStore.addManualPause(context, startMs, endMs)
            val message = when {
                automatic.isChecked && manualAdded -> "Pause ajoutée et programmation automatique activée"
                automatic.isChecked -> "Pause automatique programmée chaque jour"
                manualAdded -> "Pause ajoutée à la journée sélectionnée"
                else -> "Aucune session ne contient cette pause. Active le mode automatique ou choisis une journée travaillée."
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()

            if (automatic.isChecked || manualAdded) {
                dialog.dismiss()
                (context as? Activity)?.recreate()
            }
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
