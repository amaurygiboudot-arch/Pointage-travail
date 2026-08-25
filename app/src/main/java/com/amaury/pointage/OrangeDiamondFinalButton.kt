package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.Toast

class OrangeDiamondFinalButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.buttonStyle
) : RedDiamondFinalButton(context, attrs, defStyleAttr) {

    init {
        contentDescription = "Pause"
        setOnClickListener {
            if (!PointageStore.hasOpen(context)) {
                Toast.makeText(context, "Commence d'abord une entrée", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val wasPaused = PointageStore.isPaused(context)
            val changed = if (wasPaused) {
                PointageStore.resumePause(context)
            } else {
                PointageStore.startPause(context)
            }

            val message = when {
                changed && wasPaused -> "Pause terminée — travail repris"
                changed -> "Pause démarrée"
                else -> "Impossible de modifier la pause"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

            if (changed) {
                DriveBackupManager.syncCurrentMonthAsync(context)
                PointageWidgetProvider.updateAll(context)
                QuickActionsWidgetProvider.updateAll(context)
                invalidate()
            }
        }
    }

    override fun diamondPalette() = intArrayOf(
        Color.rgb(255,172,38), Color.rgb(255,116,0), Color.rgb(210,68,0), Color.rgb(255,202,82),
        Color.rgb(158,42,0), Color.rgb(255,138,0), Color.rgb(238,82,0), Color.rgb(255,220,132),
        Color.rgb(184,48,0), Color.rgb(255,124,0), Color.rgb(222,70,0), Color.rgb(255,180,50),
        Color.rgb(132,32,0), Color.rgb(255,96,0), Color.rgb(246,88,0), Color.rgb(255,208,100)
    )
    override fun diamondTint() = Color.rgb(255,118,0)
    override fun diamondDark() = Color.rgb(142,38,0)
    override fun diamondHighlight() = Color.rgb(255,238,210)
}
