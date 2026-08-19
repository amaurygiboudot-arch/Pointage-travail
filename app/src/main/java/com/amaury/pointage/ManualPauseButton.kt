package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
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
        val selectedDate = Calendar.getInstance(Locale.FRANCE)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val schedule = PauseScheduleManager.load(context)
        val theme = AppThemeCatalog.current(context)
        val appearance = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = appearance.getString("mode", "auto") ?: "auto"
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && systemDark)
        val background = if (dark) theme.darkBackground else theme.lightBackground
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val textColor = if (dark) theme.darkText else theme.lightText
        val hintColor = if (dark) theme.darkHint else theme.lightHint
        val accent = if (dark) theme.accentLight else theme.accent
        val orange = Color.parseColor("#F3A64A")

        fun styledButton(label: String, accentColor: Int = accent) = Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(accentColor)
            setBackgroundResource(R.drawable.hp_panel)
            backgroundTintList = ColorStateList.valueOf(panel)
            minHeight = 0
            minimumHeight = 0
        }

        fun styledInput(hintText: String) = EditText(context).apply {
            hint = hintText
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(hintColor)
            setBackgroundResource(R.drawable.hp_panel)
            backgroundTintList = ColorStateList.valueOf(panel)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            setBackgroundColor(background)
        }
        body.addView(TextView(context).apply {
            text = "⏸  SAISIE MANUELLE D'UNE PAUSE"
            gravity = Gravity.CENTER
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(orange)
            setPadding(0, dp(4), 0, dp(8))
        })
        body.addView(TextView(context).apply {
            text = "Ajoute une pause oubliée à une journée déjà pointée, ou programme une pause automatique quotidienne."
            textSize = 14f
            setTextColor(textColor)
            setPadding(0, 0, 0, dp(12))
        })

        val dateButton = styledButton("DATE : ${dateFormat.format(selectedDate.time)}")
        dateButton.setOnClickListener {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    selectedDate.set(year, month, day)
                    dateButton.text = "DATE : ${dateFormat.format(selectedDate.time)}"
                },
                selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        val start = styledInput("Début — ex. 10:00").apply {
            setText(String.format(Locale.FRANCE, "%02d:%02d", schedule.startHour, schedule.startMinute))
        }
        val end = styledInput("Fin — ex. 10:15").apply {
            setText(String.format(Locale.FRANCE, "%02d:%02d", schedule.endHour, schedule.endMinute))
        }

        body.addView(TextView(context).apply {
            text = "JOURNÉE"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            setPadding(0, dp(4), 0, dp(5))
        })
        body.addView(dateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        body.addView(TextView(context).apply {
            text = "HEURES DE PAUSE"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            setPadding(0, dp(14), 0, dp(5))
        })
        body.addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        body.addView(end, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(7) })

        val automatic = Switch(context).apply {
            text = "Programmer automatiquement tous les jours"
            textSize = 14f
            setTextColor(textColor)
            isChecked = schedule.enabled
            setPadding(0, dp(12), 0, dp(4))
        }
        body.addView(automatic)
        body.addView(TextView(context).apply {
            text = "La pause automatique ne se déclenche que lorsqu'une entrée est en cours. À l'heure de fin, HP Travail reprend automatiquement le temps de travail."
            textSize = 14f
            setTextColor(hintColor)
            setPadding(0, 0, 0, dp(10))
        })

        val scroll = ScrollView(context).apply {
            setBackgroundColor(background)
            addView(body)
        }
        val cancel = styledButton("ANNULER", hintColor)
        val save = styledButton("ENREGISTRER LA PAUSE", orange)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(background)
            setPadding(dp(18), dp(8), dp(18), dp(16))
            addView(cancel, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
            addView(save, LinearLayout.LayoutParams(0, dp(50), 1.35f).apply { marginStart = dp(6) })
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actions)
        }
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.setOnShowListener {
            val m = resources.displayMetrics
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((m.widthPixels * .94f).toInt(), (m.heightPixels * .78f).toInt())
        }
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
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
                automatic.isChecked -> "Pause automatique programmée"
                manualAdded -> "Pause ajoutée à la journée sélectionnée"
                else -> "Aucune session ne contient cette plage horaire"
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
