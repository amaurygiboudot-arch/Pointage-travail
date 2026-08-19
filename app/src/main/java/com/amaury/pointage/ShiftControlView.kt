package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class ShiftControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val stateText = TextView(context)
    private val modeButton = Button(context)

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setBackgroundResource(R.drawable.hp_panel)

        addView(TextView(context).apply {
            text = "POSTE DU JOUR"
            textSize = 16f
            setTextColor(Color.parseColor("#D6A84B"))
        })
        addView(TextView(context).apply {
            text = "HP Travail peut reconnaître le poste avec l'heure d'entrée. Tu peux aussi le forcer manuellement. Les pauses automatiques configurées ci-dessous seront déduites sans devoir appuyer sur Pause."
            textSize = 14f
            setPadding(0, dp(5), 0, dp(6))
        })

        modeButton.apply {
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { chooseMode() }
        }
        addView(modeButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))

        stateText.apply {
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        addView(stateText)

        val configure = Button(context).apply {
            text = "PAUSES ET PANIERS PAR POSTE"
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { showProfilesDialog() }
        }
        addView(configure, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(7) })
        refresh()
    }

    fun refresh() {
        val mode = ShiftProfileManager.selectedMode(context)
        val modeLabel = when (mode) {
            ShiftType.MORNING.id -> "MATIN"
            ShiftType.DAY.id -> "JOURNÉE"
            ShiftType.AFTERNOON.id -> "APRÈS-MIDI"
            ShiftType.NIGHT.id -> "NUIT"
            else -> "AUTOMATIQUE"
        }
        modeButton.text = "POSTE : $modeLabel"
        val data = PointageStore.load(context)
        var entry = System.currentTimeMillis()
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            if (item.optLong("entry", -1L) > 0L && item.isNull("exit")) {
                entry = item.optLong("entry")
                break
            }
        }
        val detected = ShiftProfileManager.resolve(context, entry)
        val pause = ShiftProfileManager.pauseMinutes(context, detected)
        stateText.text = "Détecté : ${detected.label} • pause automatique : ${pause} min"
    }

    private fun chooseMode() {
        val labels = arrayOf("Automatique", "Matin", "Journée", "Après-midi", "Nuit")
        val ids = arrayOf("auto", ShiftType.MORNING.id, ShiftType.DAY.id, ShiftType.AFTERNOON.id, ShiftType.NIGHT.id)
        val selected = ids.indexOf(ShiftProfileManager.selectedMode(context)).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Poste du jour")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                ShiftProfileManager.setSelectedMode(context, ids[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showProfilesDialog() {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val pauseInputs = linkedMapOf<ShiftType, EditText>()
        val mealSwitches = linkedMapOf<ShiftType, Switch>()

        ShiftType.values().forEach { shift ->
            box.addView(TextView(context).apply {
                text = shift.label.uppercase(Locale.FRANCE)
                textSize = 15f
                setTextColor(Color.parseColor("#D6A84B"))
                setPadding(0, dp(10), 0, dp(3))
            })
            val pause = EditText(context).apply {
                hint = "Pause à déduire (minutes)"
                inputType = InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
                setText(ShiftProfileManager.pauseMinutes(context, shift).toString())
                setBackgroundResource(R.drawable.hp_panel)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            pauseInputs[shift] = pause
            box.addView(pause, LayoutParams(LayoutParams.MATCH_PARENT, dp(50)))
            val meal = Switch(context).apply {
                text = "Panier pour ce poste"
                textSize = 14f
                isChecked = ShiftProfileManager.mealEnabled(context, shift)
            }
            mealSwitches[shift] = meal
            box.addView(meal)
        }

        AlertDialog.Builder(context)
            .setTitle("Pauses et paniers")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                pauseInputs.forEach { (shift, input) ->
                    val minutes = input.text.toString().trim().toIntOrNull()?.coerceIn(0, 240) ?: 0
                    ShiftProfileManager.setPauseMinutes(context, shift, minutes)
                    ShiftProfileManager.setMealEnabled(context, shift, mealSwitches[shift]?.isChecked == true)
                }
                Toast.makeText(context, "Règles de poste enregistrées", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
