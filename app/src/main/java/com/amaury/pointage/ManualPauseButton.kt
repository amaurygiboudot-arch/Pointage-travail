package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
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

    private data class PauseSlot(
        val container: LinearLayout,
        val start: EditText,
        val end: EditText
    )

    init { setOnClickListener { showDialog() } }

    private fun showDialog() {
        val selectedDate = Calendar.getInstance(Locale.FRANCE)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
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
            text = "Ajoute jusqu'à 5 pauses à une même journée. Le créneau suivant apparaît seulement quand le précédent est correctement renseigné."
            textSize = 14f
            setTextColor(textColor)
            setPadding(0, 0, 0, dp(12))
        })

        var reloadPausesForSelectedDate: (() -> Unit)? = null
        val dateButton = styledButton("DATE : ${dateFormat.format(selectedDate.time)}")
        dateButton.setOnClickListener {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    selectedDate.set(year, month, day)
                    selectedDate.set(Calendar.HOUR_OF_DAY, 0)
                    selectedDate.set(Calendar.MINUTE, 0)
                    selectedDate.set(Calendar.SECOND, 0)
                    selectedDate.set(Calendar.MILLISECOND, 0)
                    dateButton.text = "DATE : ${dateFormat.format(selectedDate.time)}"
                    reloadPausesForSelectedDate?.invoke()
                },
                selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)
            ).show()
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

        val slots = mutableListOf<PauseSlot>()
        repeat(5) { index ->
            val slotNumber = index + 1
            val start = styledInput("Début $slotNumber — ex. 10:00")
            val end = styledInput("Fin $slotNumber — ex. 10:15")
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (index == 0) View.VISIBLE else View.GONE
                if (index > 0) {
                    addView(TextView(context).apply {
                        text = "CRÉNEAU $slotNumber"
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(accent)
                        setPadding(0, dp(12), 0, dp(5))
                    })
                }
                addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
                addView(end, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(7) })
            }
            body.addView(container)
            slots += PauseSlot(container, start, end)
        }

        val totalPauseText = TextView(context).apply {
            text = "TOTAL HEURES DE PAUSE : 00h 00m"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(orange)
            setPadding(0, dp(14), 0, dp(8))
        }
        body.addView(totalPauseText)

        fun slotIsComplete(slot: PauseSlot): Boolean {
            val startMs = parseTime(selectedDate, slot.start.text.toString()) ?: return false
            val endMs = parseTime(selectedDate, slot.end.text.toString()) ?: return false
            return endMs > startMs
        }

        fun updateTotalPause() {
            val ranges = mutableListOf<Pair<Long, Long>>()
            slots.forEach { slot ->
                val s = parseTime(selectedDate, slot.start.text.toString())
                val e = parseTime(selectedDate, slot.end.text.toString())
                if (s != null && e != null && e > s) ranges += s to e
            }
            totalPauseText.text = "TOTAL HEURES DE PAUSE : ${formatMergedDuration(ranges)}"
        }

        for (i in slots.indices) {
            val current = slots[i]
            val next = slots.getOrNull(i + 1)
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (next != null && slotIsComplete(current)) next.container.visibility = View.VISIBLE
                    updateTotalPause()
                }
            }
            current.start.addTextChangedListener(watcher)
            current.end.addTextChangedListener(watcher)
        }

        reloadPausesForSelectedDate = {
            val dayStartCalendar = (selectedDate.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayStart = dayStartCalendar.timeInMillis
            val dayEnd = (dayStartCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val saved = PointageStore.manualPausesForDay(context, dayStart, dayEnd)

            slots.forEachIndexed { index, slot ->
                slot.start.error = null
                slot.end.error = null
                slot.start.setText("")
                slot.end.setText("")
                slot.container.visibility = if (index == 0) View.VISIBLE else View.GONE
            }

            if (saved.isNotEmpty()) {
                saved.take(5).forEachIndexed { index, range ->
                    val slot = slots[index]
                    slot.start.setText(timeFormat.format(range.first))
                    slot.end.setText(timeFormat.format(range.second))
                }
                val visibleThrough = saved.size.coerceAtMost(4)
                slots.forEachIndexed { index, slot ->
                    slot.container.visibility = if (index <= visibleThrough) View.VISIBLE else View.GONE
                }
            } else {
                slots[0].start.setText(String.format(Locale.FRANCE, "%02d:%02d", schedule.startHour, schedule.startMinute))
                slots[0].end.setText(String.format(Locale.FRANCE, "%02d:%02d", schedule.endHour, schedule.endMinute))
                slots[0].container.visibility = View.VISIBLE
            }
            updateTotalPause()
        }
        reloadPausesForSelectedDate?.invoke()

        val automatic = Switch(context).apply {
            text = "Programmer automatiquement tous les jours avec le 1er créneau"
            textSize = 14f
            setTextColor(textColor)
            isChecked = schedule.enabled
            setPadding(0, dp(12), 0, dp(4))
        }
        body.addView(automatic)
        body.addView(TextView(context).apply {
            text = "La programmation automatique utilise uniquement le premier créneau. Les créneaux 2 à 5 servent à la saisie manuelle de la journée sélectionnée."
            textSize = 14f
            setTextColor(hintColor)
            setPadding(0, 0, 0, dp(10))
        })

        val scroll = ScrollView(context).apply {
            setBackgroundColor(background)
            addView(body)
        }
        val cancel = styledButton("ANNULER", hintColor)
        val save = styledButton("ENREGISTRER LES PAUSES", orange)
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
            dialog.window?.setLayout((m.widthPixels * .94f).toInt(), (m.heightPixels * .82f).toInt())
        }
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val ranges = mutableListOf<Pair<Long, Long>>()
            for ((index, slot) in slots.withIndex()) {
                val startText = slot.start.text.toString().trim()
                val endText = slot.end.text.toString().trim()
                if (index > 0 && startText.isBlank() && endText.isBlank()) break
                val startMs = parseTime(selectedDate, startText)
                val endMs = parseTime(selectedDate, endText)
                if (startMs == null) { slot.start.error = "Format attendu : HH:mm"; return@setOnClickListener }
                if (endMs == null) { slot.end.error = "Format attendu : HH:mm"; return@setOnClickListener }
                if (endMs <= startMs) { slot.end.error = "La fin doit être après le début"; return@setOnClickListener }
                ranges += startMs to endMs
            }
            if (ranges.isEmpty()) return@setOnClickListener

            val firstStart = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = ranges.first().first }
            val firstEnd = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = ranges.first().second }
            if (automatic.isChecked) {
                PauseScheduleManager.save(
                    context,
                    firstStart.get(Calendar.HOUR_OF_DAY), firstStart.get(Calendar.MINUTE),
                    firstEnd.get(Calendar.HOUR_OF_DAY), firstEnd.get(Calendar.MINUTE),
                    enabled = true
                )
            } else if (schedule.enabled) {
                PauseScheduleManager.setEnabled(context, false)
            }

            val addedCount = ManualPauseBatchStore.addAll(context, ranges)
            val message = when {
                automatic.isChecked && addedCount > 0 -> "$addedCount pause${if (addedCount > 1) "s" else ""} ajoutée${if (addedCount > 1) "s" else ""} — total ${formatMergedDuration(ranges)} — programmation automatique activée"
                automatic.isChecked -> "Pause automatique programmée"
                addedCount > 0 -> "$addedCount pause${if (addedCount > 1) "s" else ""} ajoutée${if (addedCount > 1) "s" else ""} — total ${formatMergedDuration(ranges)}"
                else -> "Aucune session ne contient ces plages horaires"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (automatic.isChecked || addedCount > 0) {
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

    private fun formatMergedDuration(ranges: List<Pair<Long, Long>>): String {
        if (ranges.isEmpty()) return "00h 00m"
        val sorted = ranges.filter { it.second > it.first }.sortedBy { it.first }
        if (sorted.isEmpty()) return "00h 00m"
        var total = 0L
        var start = sorted.first().first
        var end = sorted.first().second
        for (i in 1 until sorted.size) {
            val (nextStart, nextEnd) = sorted[i]
            if (nextStart <= end) end = maxOf(end, nextEnd)
            else {
                total += end - start
                start = nextStart
                end = nextEnd
            }
        }
        total += end - start
        val minutes = total / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", minutes / 60L, minutes % 60L)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
