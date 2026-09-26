package com.amaury.pointage

import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView

/** Navigation stays legible above the animated sky and optional image backgrounds. */
object NavigationTabContrastV2 {
    private val activeText = Color.rgb(255, 226, 160)
    private val inactiveText = Color.WHITE

    fun apply(tabs: ViewGroup) {
        for (index in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(index) as? TextView ?: continue
            style(tab, tab.isSelected)
        }
    }

    fun style(tab: TextView, selected: Boolean) {
        tab.isSelected = selected
        // The navigation has its own opaque dark surface. View alpha also fades
        // text, so it must not dim the inactive labels against a changing sky.
        tab.alpha = 1f
        tab.setTextColor(if (selected) activeText else inactiveText)
        tab.elevation = if (selected) 3f * tab.resources.displayMetrics.density else 0f
    }
}
