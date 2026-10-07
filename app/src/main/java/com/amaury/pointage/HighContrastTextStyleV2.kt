package com.amaury.pointage

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.math.roundToInt

/** Opaque readable support with explicit disabled, pressed and keyboard-focus feedback. */
internal object HighContrastTextStyleV2 {
    private val disabled = intArrayOf(-android.R.attr.state_enabled)
    private val pressed = intArrayOf(android.R.attr.state_pressed)
    private val focused = intArrayOf(android.R.attr.state_focused)
    private val normal = intArrayOf()
    private val textColors = ColorStateList(arrayOf(disabled, pressed, normal),
        intArrayOf(Color.LTGRAY, Color.BLACK, Color.WHITE))
    private val hintColors = ColorStateList(arrayOf(disabled, pressed, normal),
        intArrayOf(Color.LTGRAY, Color.BLACK, Color.LTGRAY))

    private class ControlBackground(density: Float) : StateListDrawable() {
        init {
            fun surface(fill: Int, strokeDp: Int, dashed: Boolean = false) = GradientDrawable().apply {
                setColor(fill)
                val stroke = (strokeDp * density).roundToInt().coerceAtLeast(1)
                if (dashed) setStroke(stroke, Color.LTGRAY, 4 * density, 3 * density)
                else setStroke(stroke, Color.WHITE)
            }
            // Disabled must win even if a control is disabled while held/focused.
            addState(disabled, surface(Color.DKGRAY, 1, dashed = true))
            addState(pressed, surface(Color.WHITE, 3))
            addState(focused, surface(Color.BLACK, 3))
            addState(normal, surface(Color.BLACK, 1))
        }
    }

    fun apply(view: TextView) {
        val control = view is Button || view is EditText || view.hasOnClickListeners()
        view.backgroundTintList = null
        if (control && view.background !is ControlBackground) {
            // Replacing a native drawable must not erase its content insets.
            val left = view.paddingLeft; val top = view.paddingTop
            val right = view.paddingRight; val bottom = view.paddingBottom
            view.background = ControlBackground(view.resources.displayMetrics.density)
            view.setPadding(left, top, right, bottom)
        } else if (!control && (view.background as? ColorDrawable)?.color != Color.BLACK) {
            view.background = ColorDrawable(Color.BLACK)
        }
        view.setTextColor(if (control) textColors else ColorStateList.valueOf(Color.WHITE))
        view.setHintTextColor(if (control) hintColors else ColorStateList.valueOf(Color.LTGRAY))
    }
}
