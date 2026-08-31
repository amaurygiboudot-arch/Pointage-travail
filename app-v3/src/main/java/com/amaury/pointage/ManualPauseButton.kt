package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
        val start: Button,
        val end: Button,
        var startMinutes: Int? = null,
        var endMinutes: Int? = null
    )

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
            text = "Ajoute jusqu'à 5 pauses à une même journée. Appuie sur Début ou Fin pour choisir l'heure sans clavier."
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
        val totalPauseText = TextView(context).apply {
            text = "TOTAL HEURES DE PAUSE : 00h 00m"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(orange)
            setPadding(0, dp(14), 0, dp(8))
        }

        fun updateTotalPause() {
            val ranges = slots.mapNotNull { slot ->
                val s = slot.startMinutes
                val e = slot.endMinutes
                if (s != null && e != null && e > s) {
                    minutesToMillis(selectedDate, s) to minutesToMillis(selectedDate, e)
                } else null
            }
            totalPauseText.text = "TOTAL HEURES DE PAUSE : ${formatMergedDuration(ranges)}"
        }

        fun refreshSlotVisibility() {
            slots.forEachIndexed { index, slot ->
                slot.container.visibility = if (index == 0) {
                    View.VISIBLE
                } else {
                    val previous = slots[index - 1]
                    if (previous.startMinutes != null && previous.endMinutes != null && previous.endMinutes!! > previous.startMinutes!!) View.VISIBLE else View.GONE
                }
            }
        }

        fun openTimePicker(slot: PauseSlot, isStart: Boolean, label: String) {
            val currentMinutes = if (isStart) slot.startMinutes else slot.endMinutes
            val fallbackMinutes = if (isStart) {
                slot.endMinutes?.minus(15) ?: (schedule.startHour * 60 + schedule.startMinute)
            } else {
                slot.startMinutes?.plus(15) ?: (schedule.endHour * 60 + schedule.endMinute)
            }
            val initial = currentMinutes ?: fallbackMinutes.coerceIn(0, 23 * 60 + 59)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val value = hour * 60 + minute
                    if (isStart) {
                        slot.startMinutes = value
                        slot.start.text = "$label : ${formatTime(value)}"
                    } else {
                        slot.endMinutes = value
                        slot.end.text = "$label : ${formatTime(value)}"
                    }
                    refreshSlotVisibility()
                    updateTotalPause()
                },
                initial / 60,
                initial % 60,
                true
            ).show()
        }

        repeat(5) { index ->
            val slotNumber = index + 1
            val start = styledButton("DÉBUT $slotNumber : choisir")
            val end = styledButton("FIN $slotNumber : choisir")
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
            val slot = PauseSlot(container, start, end)
            start.setOnClickListener { openTimePicker(slot, true, "DÉBUT $slotNumber") }
            end.setOnClickListener { openTimePicker(slot, false, "FIN $slotNumber") }
            slots += slot
        }

        body.addView(totalPauseText)

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
                slot.startMinutes = null
                slot.endMinutes = null
                slot.start.text = "DÉBUT ${index + 1} : choisir"
                slot.end.text = "FIN ${index + 1} : choisir"
                slot.container.visibility = if (index == 0) View.VISIBLE else View.GONE
            }

            if (saved.isNotEmpty()) {
                saved.take(5).forEachIndexed { index, range ->
                    val slot = slots[index]
                    val startCal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = range.first }
                    val endCal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = range.second }
                    slot.startMinutes = startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE)
                    slot.endMinutes = endCal.get(Calendar.HOUR_OF_DAY) * 60 + endCal.get(Calendar.MINUTE)
                    slot.start.text = "DÉBUT ${index + 1} : ${formatTime(slot.startMinutes!!)}"
                    slot.end.text = "FIN ${index + 1} : ${formatTime(slot.endMinutes!!)}"
                }
            } else {
                slots[0].startMinutes = schedule.startHour * 60 + schedule.startMinute
                slots[0].endMinutes = schedule.endHour * 60 + schedule.endMinute
                slots[0].start.text = "DÉBUT 1 : ${formatTime(slots[0].startMinutes!!)}"
                slots[0].end.text = "FIN 1 : ${formatTime(slots[0].endMinutes!!)}"
            }
            refreshSlotVisibility()
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
                val startMinutes = slot.startMinutes
                val endMinutes = slot.endMinutes
                if (index > 0 && startMinutes == null && endMinutes == null) break
                if (startMinutes == null) {
                    Toast.makeText(context, "Choisis l'heure de début du créneau ${index + 1}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (endMinutes == null) {
                    Toast.makeText(context, "Choisis l'heure de fin du créneau ${index + 1}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (endMinutes <= startMinutes) {
                    Toast.makeText(context, "La fin du créneau ${index + 1} doit être après le début", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ranges += minutesToMillis(selectedDate, startMinutes) to minutesToMillis(selectedDate, endMinutes)
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

    private fun minutesToMillis(day: Calendar, minutes: Int): Long =
        (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun formatTime(minutes: Int): String =
        String.format(Locale.FRANCE, "%02d:%02d", minutes / 60, minutes % 60)

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
