package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2ScheduleStore
import com.amaury.pointage.v2.model.SessionStatusV2
import java.util.Locale

class ShiftControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val stateText = TextView(context)
    private val modeButton = Button(context)

    init {
        orientation = VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = null

        addView(TextView(context).apply {
            text = "Poste du jour"
            textSize = 12f
            setTextColor(Color.parseColor("#B8B0A2"))
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0.88f
        })

        modeButton.apply {
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundResource(R.drawable.hp_panel)
            gravity = Gravity.CENTER
            alpha = 0.90f
            setOnClickListener { chooseMode() }
        }
        addView(modeButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(3) })

        stateText.apply {
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#A9A39A"))
            setPadding(0, dp(3), 0, 0)
            alpha = 0.88f
        }
        addView(stateText)

        val configure = Button(context).apply {
            text = if (HoraTrackV2.ENABLED) "Horaires" else "Horaires, pauses et paniers"
            isAllCaps = false
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.hp_panel)
            alpha = 0.86f
            setOnClickListener { showProfilesDialog() }
        }
        addView(configure, LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(4) })
        refresh()
    }

    fun refresh() {
        val companyId = if (HoraTrackV2.ENABLED) V2ProfileStore.activeCompanyId(context) else null
        val mode = if (HoraTrackV2.ENABLED) {
            companyId?.let { V2ScheduleStore.selectedMode(context, it) } ?: "auto"
        } else {
            ShiftProfileManager.selectedMode(context)
        }
        val modeLabel = when (mode) {
            ShiftType.MORNING.id -> "Matin"
            ShiftType.DAY.id -> "Journée"
            ShiftType.AFTERNOON.id -> "Après-midi"
            ShiftType.NIGHT.id -> "Nuit"
            else -> "Automatique"
        }
        modeButton.text = "Poste : $modeLabel"
        val entry = if (HoraTrackV2.ENABLED) {
            val current = V2RuntimeReader.current(context)
            if (!current.reliable) {
                stateText.text = "Pointage à vérifier • horaire non calculé"
                return
            }
            current.snapshot.session
                ?.takeIf { it.status == SessionStatusV2.OPEN && it.realExitMs == null }
                ?.realArrivalMs
                ?: System.currentTimeMillis()
        } else {
            val data = PointageStore.load(context)
            var legacyEntry = System.currentTimeMillis()
            for (i in data.length() - 1 downTo 0) {
                val item = data.optJSONObject(i) ?: continue
                if (item.optLong("entry", -1L) > 0L && item.isNull("exit")) {
                    legacyEntry = item.optLong("entry")
                    break
                }
            }
            legacyEntry
        }
        val detected = if (HoraTrackV2.ENABLED) {
            when (mode) {
                ShiftType.MORNING.id -> ShiftType.MORNING
                ShiftType.DAY.id -> ShiftType.DAY
                ShiftType.AFTERNOON.id -> ShiftType.AFTERNOON
                ShiftType.NIGHT.id -> ShiftType.NIGHT
                else -> ShiftProfileManager.detect(entry)
            }
        } else {
            ShiftProfileManager.resolve(context, entry)
        }
        val schedule = if (HoraTrackV2.ENABLED) {
            companyId?.let { V2ScheduleStore.schedule(context, it, detected.id) }
                ?: V2ScheduleStore.Schedule(detected.id, null, null)
        } else {
            V2ScheduleStore.legacySchedule(context, detected.id)
        }
        val hours = when {
            schedule.startMinute != null && schedule.endMinute != null -> " • ${V2ScheduleStore.formatMinute(schedule.startMinute)}–${V2ScheduleStore.formatMinute(schedule.endMinute)}"
            schedule.endMinute != null -> " • fin ${V2ScheduleStore.formatMinute(schedule.endMinute)}"
            else -> ""
        }
        stateText.text = if (HoraTrackV2.ENABLED) {
            "${detected.label}$hours"
        } else {
            val pause = ShiftProfileManager.pauseMinutes(context, detected)
            "${detected.label}$hours • pause $pause min"
        }
    }
    private fun chooseMode() {
        val labels = arrayOf("Automatique", "Matin", "Journée", "Après-midi", "Nuit")
        val ids = arrayOf("auto", ShiftType.MORNING.id, ShiftType.DAY.id, ShiftType.AFTERNOON.id, ShiftType.NIGHT.id)
        val companyId = if (HoraTrackV2.ENABLED) V2ProfileStore.activeCompanyId(context) else null
        val currentMode = if (HoraTrackV2.ENABLED) {
            companyId?.let { V2ScheduleStore.selectedMode(context, it) } ?: "auto"
        } else {
            ShiftProfileManager.selectedMode(context)
        }
        val selected = ids.indexOf(currentMode).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Poste du jour")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val saved = if (HoraTrackV2.ENABLED) {
                    companyId?.let { V2ScheduleStore.setSelectedMode(context, it, ids[which]) } == true
                } else {
                    ShiftProfileManager.setSelectedMode(context, ids[which])
                    true
                }
                if (!saved) {
                    Toast.makeText(context, "Sélectionne d’abord une entreprise valide", Toast.LENGTH_LONG).show()
                    return@setSingleChoiceItems
                }
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
    private fun showProfilesDialog() {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val activeCompanyId = if (HoraTrackV2.ENABLED) V2ProfileStore.activeCompanyId(context) else null
        if (HoraTrackV2.ENABLED) {
            if (activeCompanyId == null) {
                Toast.makeText(context, "Sélectionne d’abord une entreprise valide", Toast.LENGTH_LONG).show()
                return
            }
            box.addView(TextView(context).apply {
                text = "Ces horaires appartiennent uniquement à l’entreprise active. Les pauses V2 se règlent dans la fiche de l’entreprise, dans Salaire."
                textSize = 12f
                setTextColor(Color.parseColor("#B0B0B0"))
                setPadding(0, dp(4), 0, dp(6))
            })
        }

        val startInputs = linkedMapOf<ShiftType, EditText>()
        val endInputs = linkedMapOf<ShiftType, EditText>()
        val pauseInputs = linkedMapOf<ShiftType, EditText>()
        val mealSwitches = linkedMapOf<ShiftType, Switch>()

        fun fieldBackground() = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#0B0B0B"))
            setStroke(dp(2), Color.parseColor("#D6A84B"))
        }

        fun timeField(hintText: String, value: String) = EditText(context).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            isSingleLine = true
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#B0B0B0"))
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBackground()
            setPadding(dp(18), 0, dp(18), 0)
            includeFontPadding = false
        }

        ShiftType.values().forEach { shift ->
            box.addView(TextView(context).apply {
                text = shift.label.uppercase(Locale.FRANCE)
                textSize = 15f
                setTextColor(Color.parseColor("#D6A84B"))
                setPadding(0, dp(14), 0, dp(6))
            })

            val schedule = if (HoraTrackV2.ENABLED) {
                V2ScheduleStore.schedule(context, activeCompanyId!!, shift.id)
            } else {
                V2ScheduleStore.legacySchedule(context, shift.id)
            }
            val start = timeField("Début prévu — ex. 05:00", schedule.startMinute?.let(V2ScheduleStore::formatMinute).orEmpty())
            val end = timeField("Fin prévue — ex. 13:00", schedule.endMinute?.let(V2ScheduleStore::formatMinute).orEmpty())
            startInputs[shift] = start
            endInputs[shift] = end
            box.addView(start, LayoutParams(LayoutParams.MATCH_PARENT, dp(54)))
            box.addView(end, LayoutParams(LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(5) })

            if (!HoraTrackV2.ENABLED) {
                val pause = EditText(context).apply {
                    hint = "Pause à déduire (minutes)"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    isSingleLine = true
                    setText(ShiftProfileManager.pauseMinutes(context, shift).toString())
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.parseColor("#B0B0B0"))
                    textSize = 16f
                    gravity = Gravity.CENTER_VERTICAL
                    background = fieldBackground()
                    setPadding(dp(18), 0, dp(18), 0)
                    setSelectAllOnFocus(true)
                    includeFontPadding = false
                }
                pauseInputs[shift] = pause
                box.addView(pause, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(5) })

                val meal = Switch(context).apply {
                    text = "Panier pour ce poste"
                    textSize = 14f
                    setTextColor(Color.parseColor("#111111"))
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(54)
                    setPadding(0, dp(4), 0, dp(4))
                    isChecked = ShiftProfileManager.mealEnabled(context, shift)
                }
                mealSwitches[shift] = meal
                box.addView(meal, LayoutParams(LayoutParams.MATCH_PARENT, dp(54)))
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (HoraTrackV2.ENABLED) "Horaires" else "Horaires, pauses et paniers")
            .setView(scroll)
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#D6A84B"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#D6A84B"))
            val maxHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
            scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                for (shift in ShiftType.values()) {
                    val start = startInputs[shift]?.text?.toString().orEmpty().trim()
                    val end = endInputs[shift]?.text?.toString().orEmpty().trim()
                    if (start.isNotBlank() && V2ScheduleStore.parseMinute(start) == null) {
                        startInputs[shift]?.error = "Format HH:mm"
                        return@setOnClickListener
                    }
                    if (end.isNotBlank() && V2ScheduleStore.parseMinute(end) == null) {
                        endInputs[shift]?.error = "Format HH:mm"
                        return@setOnClickListener
                    }
                }
                for (shift in ShiftType.values()) {
                    val scheduleSaved = if (HoraTrackV2.ENABLED) {
                        V2ScheduleStore.save(
                            context,
                            activeCompanyId!!,
                            shift.id,
                            startInputs[shift]?.text?.toString(),
                            endInputs[shift]?.text?.toString()
                        )
                    } else {
                        V2ScheduleStore.legacySave(
                            context,
                            shift.id,
                            startInputs[shift]?.text?.toString(),
                            endInputs[shift]?.text?.toString()
                        )
                    }
                    if (!scheduleSaved) {
                        Toast.makeText(context, "Impossible d’enregistrer les horaires", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (!HoraTrackV2.ENABLED) {
                        val minutes = pauseInputs[shift]?.text.toString().trim().toIntOrNull()?.coerceIn(0, 240) ?: 0
                        ShiftProfileManager.setPauseMinutes(context, shift, minutes)
                        ShiftProfileManager.setMealEnabled(context, shift, mealSwitches[shift]?.isChecked == true)
                    }
                }
                Toast.makeText(context, if (HoraTrackV2.ENABLED) "Horaires de l’entreprise enregistrés" else "Profils enregistrés", Toast.LENGTH_SHORT).show()
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
