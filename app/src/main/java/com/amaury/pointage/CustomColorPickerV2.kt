package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** An opaque RGB picker. Editing is local until Apply; cancel never changes saved appearance. */
object CustomColorPickerV2 {
    internal fun parseHex(value: String): Int? {
        val normalized = value.trim().removePrefix("#")
        if (!normalized.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return Color.rgb(normalized.substring(0, 2).toInt(16), normalized.substring(2, 4).toInt(16),
            normalized.substring(4, 6).toInt(16))
    }

    internal fun hex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    fun show(activity: Activity, title: String, initialColor: String = "#1A1A1A",
             onSave: (String) -> Unit): AlertDialog {
        var selected = parseHex(initialColor) ?: Color.rgb(26, 26, 26)
        var synchronizing = false
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
        }
        fun label(value: String) = TextView(activity).apply {
            text = value
            textSize = 16f
            setPadding(0, dp(context, 8), 0, dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        content.addView(label("Choisissez une couleur avec les curseurs, ou saisissez son code."))
        content.addView(label("Aperçu de la couleur"))
        // Draw the swatch itself, so application-wide text/background styling cannot recolor it.
        val preview = ColorPreview(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(context, 64))
        }
        content.addView(preview)
        val sliders = mutableListOf<SeekBar>()
        val labels = mutableListOf<TextView>()
        listOf("Rouge", "Vert", "Bleu").forEach { channel ->
            val description = label(channel)
            content.addView(description)
            labels.add(description)
            val slider = SeekBar(activity).apply {
                max = 255
                contentDescription = channel
                minimumHeight = dp(context, 48)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                tag = "custom_color_$channel"
            }
            sliders.add(slider)
            content.addView(slider)
        }
        val hexLabel = label("Code couleur (6 chiffres ou lettres de 0 à 9 et A à F)")
        content.addView(hexLabel)
        val input = EditText(activity).apply {
            id = View.generateViewId()
            tag = "custom_color_hex"
            hint = "#1A1A1A"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            minimumHeight = dp(context, 48)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        hexLabel.labelFor = input.id
        content.addView(input)
        val dialog = AlertDialog.Builder(activity).setTitle(title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Appliquer", null).setNegativeButton("Annuler", null).create()

        fun refresh(updateInput: Boolean) {
            synchronizing = true
            val channels = listOf(Color.red(selected), Color.green(selected), Color.blue(selected))
            val names = listOf("Rouge", "Vert", "Bleu")
            channels.forEachIndexed { i, value ->
                sliders[i].progress = value
                labels[i].text = "${names[i]} : $value / 255"
            }
            preview.color = selected
            preview.contentDescription = "Aperçu ${hex(selected)}"
            preview.invalidate()
            if (updateInput) {
                input.setText(hex(selected))
                input.setSelection(input.text.length)
                input.error = null
            }
            synchronizing = false
        }
        sliders.forEach { slider ->
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (synchronizing) return
                    selected = Color.rgb(sliders[0].progress, sliders[1].progress, sliders[2].progress)
                    refresh(true)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (synchronizing) return
                input.error = null
                parseHex(s.toString())?.let { selected = it; refresh(false) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh(true)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val valid = parseHex(input.text.toString())
                if (valid == null) {
                    input.error = "Saisissez 6 chiffres hexadécimaux, par exemple #2A80D4"
                    input.requestFocus()
                } else {
                    onSave(hex(valid))
                    dialog.dismiss()
                }
            }
        PersonalizationRuntimeV2.track(dialog)
        return dialog
    }

    private class ColorPreview(context: Context) : View(context) {
        var color: Int = Color.BLACK
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            // A visible edge on both light and dark dialog surfaces.
            paint.color = VisualContrastV2.bestText(color)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 2
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, paint)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        kotlin.math.ceil(value * context.resources.displayMetrics.density.toDouble()).toInt()
}
