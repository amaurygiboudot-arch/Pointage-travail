package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet

class GreenDiamondFinalButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.buttonStyle
) : RedDiamondFinalButton(context, attrs, defStyleAttr) {
    override fun diamondPalette() = intArrayOf(
        Color.rgb(54,255,108), Color.rgb(0,245,72), Color.rgb(0,176,48), Color.rgb(100,255,142),
        Color.rgb(0,132,38), Color.rgb(8,255,82), Color.rgb(0,220,58), Color.rgb(148,255,178),
        Color.rgb(0,154,42), Color.rgb(18,255,88), Color.rgb(0,198,52), Color.rgb(72,255,122),
        Color.rgb(0,112,30), Color.rgb(4,250,74), Color.rgb(0,232,64), Color.rgb(122,255,158)
    )
    override fun diamondTint() = Color.rgb(20,255,92)
    override fun diamondDark() = Color.rgb(0,104,28)
    override fun diamondHighlight() = Color.rgb(222,255,232)
}
