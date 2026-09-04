package com.amaury.pointage

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.PlannedPauseStoreV2
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Gestionnaire V2 : une journée -> pauses confirmées + pauses prévues ->
 * ajout / modification / suppression / confirmation.
 *
 * Les pauses prévues restent hors des sessions de travail jusqu'à confirmation explicite.
 */
class PauseManagerButtonV2 @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Button(context, attrs) {

    private data class PauseItem(
        var startMs: Long,
        var endMs: Long,
        var planned: Boolean
    )

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

    init {
        text = "⏸  GÉRER LES PAUSES"
        isAllCaps = false
        setOnClickListener { showManager() }
    }

    private fun showManager() {
        val selectedDate = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val items = mutableListOf<PauseItem>()
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        val background = if (dark) theme.darkBackground else theme.lightBackground
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val textColor = if (dark) theme.darkText else theme.lightText
        val hintColor = if (dark) theme.darkHint else theme.lightHint
        val accent = if (dark) theme.accentLight else theme.accent

        fun styledButton(label: String) = Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(accent)
            this.background = context.getDrawable(R.drawable.hp_panel)?.mutate()
            minHeight = 0
            minimumHeight = 0
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundColor(background)
        }

        body.addView(TextView(context).apply {
            text = "GÉRER LES PAUSES"
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            setPadding(0, 0, 0, dp(6))
        })
        body.addView(TextView(context).apply {
            text = "Tu peux enregistrer une pause à l'avance. Une pause prévue reste hors du calcul des heures tant qu'elle n'a pas été confirmée. Les anciennes pauses programmées peuvent aussi être corrigées ou supprimées ici."
            textSize = 13f
            setTextColor(textColor)
            setPadding(0, 0, 0, dp(12))
        })

        val dateButton = styledButton("")
        body.addView(dateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        val listBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        body.addView(listBox)

        val totalText = TextView(context).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            setPadding(0, dp(6), 0, dp(10))
        }
        body.addView(totalText)

        val addButton = styledButton("+  AJOUTER UNE PAUSE")
        body.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        val saveButton = styledButton("ENREGISTRER LES MODIFICATIONS")
        body.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })

        val cancelButton = styledButton("ANNULER")
        body.addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })

        val scroll = ScrollView(context).apply {
            setBackgroundColor(background)
            addView(body)
        }
        val dialog = AlertDialog.Builder(context).setView(scroll).create()

        fun dayBounds(): Pair<Long, Long> {
            val start = selectedDate.timeInMillis
            val end = (selectedDate.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            return start to end
        }

        fun durationLabel(start: Long, end: Long): String {
            val minutes = ((end - start).coerceAtLeast(0L) / 60_000L)
            return String.format(Locale.FRANCE, "%02dh %02dm", minutes / 60L, minutes % 60L)
        }

        fun timeLabel(ms: Long): String {
            val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = ms }
            return String.format(Locale.FRANCE, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }

        fun updateTotal() {
            val confirmedMs = items.filter { !it.planned }.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }
            val plannedMs = items.filter { it.planned }.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }
            val confirmedMinutes = confirmedMs / 60_000L
            val plannedMinutes = plannedMs / 60_000L
            totalText.text = if (plannedMs > 0L) {
                String.format(
                    Locale.FRANCE,
                    "CONFIRMÉ : %02dh %02dm   •   PRÉVU / À CONFIRMER : %02dh %02dm",
                    confirmedMinutes / 60L,
                    confirmedMinutes % 60L,
                    plannedMinutes / 60L,
                    plannedMinutes % 60L
                )
            } else {
                String.format(Locale.FRANCE, "CONFIRMÉ : %02dh %02dm", confirmedMinutes / 60L, confirmedMinutes % 60L)
            }
            addButton.isEnabled = items.size < 5
            addButton.alpha = if (addButton.isEnabled) 1f else .45f
        }

        fun overlaps(candidate: Pair<Long, Long>, ignoredIndex: Int?): Boolean = items.withIndex().any { (index, existing) ->
            index != ignoredIndex && candidate.first < existing.endMs && existing.startMs < candidate.second
        }

        var render: (() -> Unit)? = null
        var editRange: ((Int?) -> Unit)? = null

        fun pickMinute(initial: Int, onPicked: (Int) -> Unit) {
            TimePickerDialog(
                context,
                { _, hour, minute -> onPicked(hour * 60 + minute) },
                initial / 60,
                initial % 60,
                true
            ).show()
        }

        fun millisForMinute(minute: Int): Long = (selectedDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun defaultStartMinute(): Int {
            val now = Calendar.getInstance(Locale.FRANCE)
            val sameDay = now.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == selectedDate.get(Calendar.DAY_OF_YEAR)
            return if (sameDay) now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) else 10 * 60
        }

        editRange = { index ->
            val existing = index?.let { items.getOrNull(it) }
            val startCal = Calendar.getInstance(Locale.FRANCE).apply {
                if (existing != null) timeInMillis = existing.startMs
            }
            val initialStart = if (existing != null) {
                startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE)
            } else {
                defaultStartMinute()
            }
            pickMinute(initialStart, startPick@{ startMinute ->
                val endCal = Calendar.getInstance(Locale.FRANCE).apply {
                    if (existing != null) timeInMillis = existing.endMs
                }
                val initialEnd = if (existing != null) {
                    endCal.get(Calendar.HOUR_OF_DAY) * 60 + endCal.get(Calendar.MINUTE)
                } else {
                    (startMinute + 15).coerceAtMost(23 * 60 + 59)
                }
                pickMinute(initialEnd, endPick@{ endMinute ->
                    if (endMinute <= startMinute) {
                        Toast.makeText(context, "La fin doit être après le début de la pause", Toast.LENGTH_LONG).show()
                        return@endPick
                    }
                    val candidate = millisForMinute(startMinute) to millisForMinute(endMinute)
                    if (overlaps(candidate, index)) {
                        Toast.makeText(context, "Cette pause chevauche déjà une autre pause", Toast.LENGTH_LONG).show()
                        return@endPick
                    }

                    val nowMs = System.currentTimeMillis()
                    val planned = when {
                        existing?.planned == true -> true
                        candidate.second > nowMs -> true
                        else -> false
                    }

                    if (index == null) {
                        if (items.size >= 5) return@endPick
                        items += PauseItem(candidate.first, candidate.second, planned)
                    } else {
                        items[index].startMs = candidate.first
                        items[index].endMs = candidate.second
                        items[index].planned = planned
                    }
                    items.sortBy { it.startMs }
                    render?.invoke()
                })
            })
        }

        render = {
            listBox.removeAllViews()
            if (items.isEmpty()) {
                listBox.addView(TextView(context).apply {
                    text = "Aucune pause enregistrée pour cette journée."
                    textSize = 14f
                    setTextColor(hintColor)
                    setPadding(0, dp(10), 0, dp(10))
                })
            } else {
                val now = System.currentTimeMillis()
                items.forEachIndexed { index, item ->
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        setBackgroundColor(panel)
                    }
                    card.addView(TextView(context).apply {
                        val label = when {
                            !item.planned -> "Pause confirmée ${index + 1}"
                            item.endMs > now -> "Pause prévue ${index + 1}"
                            else -> "Pause à confirmer ${index + 1}"
                        }
                        text = "$label  •  ${timeLabel(item.startMs)} → ${timeLabel(item.endMs)}  •  ${durationLabel(item.startMs, item.endMs)}"
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(textColor)
                    })

                    val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    val modify = styledButton("MODIFIER").apply { setOnClickListener { editRange?.invoke(index) } }
                    val delete = styledButton("SUPPRIMER").apply {
                        setOnClickListener {
                            items.removeAt(index)
                            render?.invoke()
                        }
                    }
                    actions.addView(modify, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
                    actions.addView(delete, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
                    card.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(7) })

                    if (item.planned && item.endMs <= now) {
                        val confirm = styledButton("CONFIRMER CETTE PAUSE").apply {
                            setOnClickListener {
                                item.planned = false
                                render?.invoke()
                            }
                        }
                        card.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(7) })
                    }

                    listBox.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
            }
            updateTotal()
        }

        fun loadDay() {
            val (start, end) = dayBounds()
            val now = System.currentTimeMillis()
            val merged = linkedMapOf<Pair<Long, Long>, PauseItem>()

            // Les anciennes pauses SYSTEM/MANUAL futures sont volontairement reclassées comme
            // « prévues » dans l'interface. Au prochain enregistrement elles quittent la session
            // de travail et sont migrées vers PlannedPauseStoreV2.
            ManualPauseBatchStore.editableForDay(context, start, end).forEach { range ->
                merged[range] = PauseItem(range.first, range.second, planned = range.second > now)
            }
            PlannedPauseStoreV2.forDay(context, start, end).forEach { range ->
                merged[range] = PauseItem(range.first, range.second, planned = true)
            }

            items.clear()
            items += merged.values.sortedBy { it.startMs }
            dateButton.text = "JOURNÉE : ${dateFormat.format(selectedDate.time)}"
            render?.invoke()
        }

        dateButton.setOnClickListener {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    selectedDate.set(year, month, day, 0, 0, 0)
                    selectedDate.set(Calendar.MILLISECOND, 0)
                    loadDay()
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        addButton.setOnClickListener { editRange?.invoke(null) }
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val (start, end) = dayBounds()
            val actualRanges = items.filter { !it.planned }.map { it.startMs to it.endMs }
            val plannedRanges = items.filter { it.planned }.map { it.startMs to it.endMs }

            if (!ManualPauseBatchStore.replaceDay(context, start, end, actualRanges)) {
                Toast.makeText(
                    context,
                    "Impossible d'enregistrer une pause confirmée : elle doit être comprise dans une période de travail enregistrée.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            if (!PlannedPauseStoreV2.replaceDay(context, start, end, plannedRanges)) {
                Toast.makeText(
                    context,
                    "Les pauses confirmées sont enregistrées, mais les pauses prévues n'ont pas pu être sauvegardées.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val message = when {
                items.isEmpty() -> "Pauses supprimées pour cette journée"
                plannedRanges.isNotEmpty() && actualRanges.isNotEmpty() -> "Pauses confirmées et prévues enregistrées"
                plannedRanges.isNotEmpty() -> "Pauses prévues enregistrées — aucune heure n'est encore déduite"
                else -> "Pauses confirmées enregistrées"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            val metrics = resources.displayMetrics
            dialog.window?.setLayout((metrics.widthPixels * .95f).toInt(), (metrics.heightPixels * .84f).toInt())
        }
        loadDay()
        dialog.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
