package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet

class OrangeDiamondFinalButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.buttonStyle
) : RedDiamondFinalButton(context, attrs, defStyleAttr) {
    override fun diamondPalette() = intArrayOf(
        Color.rgb(255,164,54), Color.rgb(238,108,0), Color.rgb(156,58,0), Color.rgb(255,194,102),
        Color.rgb(105,38,0), Color.rgb(255,132,12), Color.rgb(204,78,0), Color.rgb(255,218,154),
        Color.rgb(126,44,0), Color.rgb(250,118,0), Color.rgb(178,65,0), Color.rgb(255,174,70),
        Color.rgb(78,28,0), Color.rgb(230,92,0), Color.rgb(216,86,0), Color.rgb(255,202,122)
    )
    override fun diamondTint() = Color.rgb(255,126,12)
    override fun diamondDark() = Color.rgb(102,38,0)
    override fun diamondHighlight() = Color.rgb(255,238,210)
}
