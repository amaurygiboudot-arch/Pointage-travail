package com.amaury.pointage

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Filtres légers de l'historique complet : date/entreprise + type d'événement affiché. */
class HistorySearchFilterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val search = EditText(context).apply {
        hint = "Rechercher une date ou une entreprise"
        setSingleLine(true)
        setTextColor(context.getColor(R.color.hp_white))
        setHintTextColor(context.getColor(R.color.hp_grey))
        textSize = 14f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = context.getDrawable(R.drawable.hp_panel)
    }

    private val entryBox = filterBox("Entrée")
    private val pauseBox = filterBox("Pause")
    private val exitBox = filterBox("Sortie")
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
    private val fullDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    private var titleView: TextView? = null
    private var historyView: TextView? = null

    private val titleWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            syncVisibility()
            if (isHistoryVisible()) post { renderFilteredHistory() }
        }
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        setPadding(0, dp(10), 0, 0)

        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.START
            addView(entryBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(pauseBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(exitBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        search.addTextChangedListener(simpleWatcher { renderFilteredHistory() })
        entryBox.setOnCheckedChangeListener { _, _ -> renderFilteredHistory() }
        pauseBox.setOnCheckedChangeListener { _, _ -> renderFilteredHistory() }
        exitBox.setOnCheckedChangeListener { _, _ -> renderFilteredHistory() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        titleView = rootView.findViewById(R.id.contentTitle)
        historyView = rootView.findViewById(R.id.historyText)
        titleView?.addTextChangedListener(titleWatcher)
        syncVisibility()
        if (isHistoryVisible()) post { renderFilteredHistory() }
    }

    override fun onDetachedFromWindow() {
        titleView?.removeTextChangedListener(titleWatcher)
        titleView = null
        historyView = null
        super.onDetachedFromWindow()
    }

    private fun renderFilteredHistory() {
        if (!isHistoryVisible()) return
        val target = historyView ?: return
        val now = System.currentTimeMillis()
        val query = search.text?.toString().orEmpty().trim().lowercase(Locale.FRANCE)
        val employerNames = buildMap {
            for (slot in 1..2) {
                V2ProfileStore.load(context, slot).employer?.let { put(it.id, it.name) }
            }
        }

        val sessions = V2RuntimeStore.allSessions(context, now)
            .filter { session ->
                if (query.isBlank()) return@filter true
                val arrival = session.realArrivalMs ?: return@filter false
                val date = dateFormat.format(Date(arrival)).lowercase(Locale.FRANCE)
                val employer = session.employerId?.let(employerNames::get).orEmpty().lowercase(Locale.FRANCE)
                val place = session.placeLabel.orEmpty().lowercase(Locale.FRANCE)
                date.contains(query) || employer.contains(query) || place.contains(query)
            }
            .sortedByDescending { it.realArrivalMs ?: 0L }

        target.text = buildString {
            sessions.forEach { session ->
                if (entryBox.isChecked) {
                    append("🟢 ").append(fullDateFormat.format(Date(session.realArrivalMs ?: 0L))).append("  ENTRÉE RÉELLE\n")
                    append("⏱ ").append(session.countedEntryMs?.let { fullDateFormat.format(Date(it)) } ?: "—").append("  ENTRÉE COMPTÉE\n")
                }
                session.placeLabel?.trim()?.takeIf { it.isNotBlank() }?.let { append("📍 ").append(it).append('\n') }
                if (pauseBox.isChecked) {
                    session.pauses.forEachIndexed { index, pause ->
                        append("⏸ Pause ").append(index + 1).append(" : ")
                            .append(timeFormat.format(Date(pause.startMs))).append(" → ")
                            .append(pause.endMs?.let { timeFormat.format(Date(it)) } ?: "EN COURS").append('\n')
                    }
                }
                if (exitBox.isChecked) {
                    if (session.realExitMs != null) {
                        append("🔴 ").append(fullDateFormat.format(Date(session.realExitMs))).append("  SORTIE RÉELLE\n")
                        append("⏱ ").append(session.countedExitMs?.let { fullDateFormat.format(Date(it)) } ?: "—").append("  SORTIE COMPTÉE\n")
                    } else append("🟢 EN COURS\n")
                }
                val result = HoraTrackV2.time.calculate(session, now)
                append("Temps payé : ").append(formatDuration(result.paidWorkMs)).append("\n\n")
            }
        }.ifBlank { "Aucun historique correspondant." }
    }

    private fun syncVisibility() {
        visibility = if (isHistoryVisible()) View.VISIBLE else View.GONE
    }

    private fun isHistoryVisible(): Boolean =
        titleView?.text?.toString()?.contains("HISTORIQUE COMPLET", ignoreCase = true) == true

    private fun filterBox(label: String) = CheckBox(context).apply {
        text = label
        isChecked = true
        setTextColor(context.getColor(R.color.hp_white))
        textSize = 13f
        buttonTintList = context.getColorStateList(R.color.hp_gold_light)
    }

    private fun simpleWatcher(onChanged: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged()
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
