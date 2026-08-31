package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView

open class StyledTabTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusable = true
        updateTabStyle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTabStyle()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        updateTabStyle()
    }

    override fun performClick(): Boolean {
        (parent as? LinearLayout)?.let { row ->
            for (i in 0 until row.childCount) {
                (row.getChildAt(i) as? StyledTabTextView)?.isSelected = false
            }
        }
        isSelected = true
        updateSiblingStyles()
        return super.performClick()
    }

    fun updateTabStyle() {
        val theme = AppThemeCatalog.current(context)
        val dark = AppThemeCatalog.useDarkPalette(context)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val accent = if (dark) theme.accentLight else theme.accent
        val text = if (dark) theme.darkText else theme.lightText

        val fill = if (isSelected) blend(panel, Color.WHITE, if (dark) .28f else .42f)
        else blend(panel, if (dark) Color.BLACK else Color.WHITE, if (dark) .05f else .08f)

        val outer = rounded(Color.TRANSPARENT, accent, 2f, 16f)
        val blue = rounded(Color.TRANSPARENT, Color.rgb(5, 91, 220), 2.5f, 14f)
        val inner = rounded(fill, if (isSelected) theme.accentLight else accent, 1f, 12f)
        background = LayerDrawable(arrayOf(outer, blue, inner)).apply {
            setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
            setLayerInset(2, dp(7), dp(7), dp(7), dp(7))
        }
        setTextColor(if (isSelected) text else blend(text, panel, .34f))
        alpha = if (isSelected) 1f else .86f
    }

    private fun updateSiblingStyles() {
        (parent as? LinearLayout)?.let { row ->
            for (i in 0 until row.childCount) {
                (row.getChildAt(i) as? StyledTabTextView)?.updateTabStyle()
            }
        }
    }

    private fun rounded(fill: Int, stroke: Int, strokeDp: Float, radiusDp: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            setStroke((strokeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }

    private fun blend(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
