package com.amaury.pointage

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Switch
import java.util.WeakHashMap
import android.widget.TextView

/** Boost contrast without replacing the theme's shapes, ripple or content insets. */
internal object HighContrastTextStyleV2 {
    private val disabled = intArrayOf(-android.R.attr.state_enabled)
    private val pressed = intArrayOf(android.R.attr.state_pressed)
    private val focused = intArrayOf(android.R.attr.state_focused)
    private val selected = intArrayOf(android.R.attr.state_selected)
    private val normal = intArrayOf()
    private val states = arrayOf(disabled, pressed, focused, selected, normal)
    private val backgrounds = ColorStateList(states,
        intArrayOf(Color.DKGRAY, Color.WHITE, Color.rgb(0, 55, 80), Color.rgb(0, 45, 65), Color.BLACK))
    private val textColors = ColorStateList(states,
        intArrayOf(Color.LTGRAY, Color.BLACK, Color.WHITE, Color.rgb(255, 226, 160), Color.WHITE))
    private val transparentTextColors = ColorStateList(states,
        intArrayOf(Color.LTGRAY, Color.CYAN, Color.CYAN, Color.rgb(255, 226, 160), Color.WHITE))
    private val hintColors = ColorStateList(states,
        intArrayOf(Color.LTGRAY, Color.BLACK, Color.LTGRAY, Color.LTGRAY, Color.LTGRAY))

    private val filledBackgrounds = WeakHashMap<TextView, Drawable>()

    fun apply(view: TextView, opaqueSurface: Boolean = false) {
        // Dialogs already own an opaque black canvas. Labels and switches need
        // readable text, not individual rectangular patches over that canvas.
        if (opaqueSurface && (view is Switch || (view !is android.widget.Button && view !is android.widget.EditText))) {
            view.setTextColor(transparentTextColors)
            view.setHintTextColor(Color.LTGRAY)
            return
        }
        val interactive = view.isClickable || view.isFocusable || view.hasOnClickListeners()
        val background = view.background
        val bare = background == null || (background as? ColorDrawable)?.color == Color.TRANSPARENT
        if (bare && interactive) {
            // Navigation labels share their bar's opaque dark surface. Do not invent
            // rectangular backgrounds for controls designed to be transparent.
            view.setTextColor(transparentTextColors)
            view.setHintTextColor(Color.LTGRAY)
            return
        }
        if (bare) view.background = ColorDrawable(Color.BLACK)
        val themedBackground = view.background
        if (themedBackground is RippleDrawable) {
            val filled = if (filledBackgrounds[view] === themedBackground) themedBackground else
                themedBackground.constantState?.newDrawable(view.resources)?.mutate() as? RippleDrawable
            if (filled != null) {
                var hasSurface = false
                for (index in 0 until filled.numberOfLayers) {
                    if (filled.getId(index) != android.R.id.mask) {
                        (filled.getDrawable(index) as? GradientDrawable)?.let {
                            it.setColor(backgrounds)
                            hasSurface = true
                        }
                    }
                }
                if (hasSurface) {
                    val left = view.paddingLeft; val top = view.paddingTop
                    val right = view.paddingRight; val bottom = view.paddingBottom
                    view.backgroundTintList = null
                    if (view.background !== filled) view.background = filled
                    view.setPadding(left, top, right, bottom)
                    filledBackgrounds[view] = filled
                } else view.backgroundTintList = backgrounds
            } else view.backgroundTintList = backgrounds
        } else if (themedBackground is GradientDrawable) {
            // hp_panel has a transparent fill: tinting it cannot provide a readable
            // surface over a photo. Clone it and fill inside its original geometry.
            val filled = if (filledBackgrounds[view] === themedBackground) themedBackground else
                (themedBackground.constantState?.newDrawable(view.resources)?.mutate() as? GradientDrawable)
            if (filled != null) {
                filled.setColor(backgrounds)
                view.backgroundTintList = null
                if (view.background !== filled) {
                    val left = view.paddingLeft; val top = view.paddingTop
                    val right = view.paddingRight; val bottom = view.paddingBottom
                    view.background = filled
                    view.setPadding(left, top, right, bottom)
                }
                filledBackgrounds[view] = filled
            } else view.backgroundTintList = backgrounds
        } else {
            // Other drawables retain their state list, ripple, geometry and insets.
            view.backgroundTintList = backgrounds
        }
        view.setTextColor(textColors)
        view.setHintTextColor(hintColors)
    }
}
