package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet

class GreenDiamondFinalButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.buttonStyle
) : RedDiamondFinalButton(context, attrs, defStyleAttr) {
    override fun diamondPalette() = intArrayOf(
        Color.rgb(64,255,118), Color.rgb(0,214,72), Color.rgb(0,132,48), Color.rgb(116,255,154),
        Color.rgb(0,92,38), Color.rgb(12,238,84), Color.rgb(0,178,58), Color.rgb(166,255,190),
        Color.rgb(0,110,42), Color.rgb(24,245,91), Color.rgb(0,156,52), Color.rgb(82,255,132),
        Color.rgb(0,74,30), Color.rgb(8,226,76), Color.rgb(0,194,64), Color.rgb(136,255,170)
    )
    override fun diamondTint() = Color.rgb(32,255,104)
    override fun diamondDark() = Color.rgb(0,72,28)
    override fun diamondHighlight() = Color.rgb(222,255,232)
}
