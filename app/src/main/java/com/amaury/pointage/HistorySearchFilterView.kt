package com.amaury.pointage

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.ui.HistoryTextFormatterV2

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
    private val dayMealFactsButton = Button(context).apply {
        text = "🍽  FAITS REPAS / JOURNÉE"
        isAllCaps = false
        setTextColor(context.getColor(R.color.hp_gold_light))
        textSize = 14f
        background = context.getDrawable(R.drawable.hp_panel)
        setOnClickListener { DayMealFactsDialogV2.show(context) }
    }
    private val sessionMealFactsButton = Button(context).apply {
        text = "🍽  FAITS REPAS / SESSION"
        isAllCaps = false
        setTextColor(context.getColor(R.color.hp_gold_light))
        textSize = 14f
        background = context.getDrawable(R.drawable.hp_panel)
        setOnClickListener { SessionMealFactsDialogV2.show(context) }
    }
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
        addView(dayMealFactsButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        addView(sessionMealFactsButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

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
        val query = search.text?.toString().orEmpty()
        val employerNames = buildMap {
            for (slot in 1..2) {
                V2ProfileStore.load(context, slot).employer?.let { put(it.id, it.name) }
            }
        }

        val runtime = V2RuntimeReader.allSessions(context, now)
        if (!runtime.reliable) {
            target.text = "Historique HoraTrack indisponible.\n${V2RuntimeReader.warningText(runtime.warnings)}"
            return
        }

        val sessions = HistoryTextFormatterV2.selectSessions(
            sessions = runtime.sessions,
            nowMs = now,
            query = query,
            employerNames = employerNames
        )
        target.text = HistoryTextFormatterV2.format(
            sessions = sessions,
            engine = HoraTrackV2.time,
            nowMs = now,
            options = HistoryTextFormatterV2.Options(
                showEntry = entryBox.isChecked,
                showPause = pauseBox.isChecked,
                showExit = exitBox.isChecked,
                employerNames = employerNames,
                emptyMessage = "Aucun historique correspondant."
            )
        )
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
