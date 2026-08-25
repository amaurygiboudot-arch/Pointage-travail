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
                // Ne jamais relancer MainActivity depuis le bouton Pause : cela pouvait
                // réveiller le flux de mise à jour/installation lors du retour d'activité.
                // Les données sont déjà persistées dans PointageStore ; l'écran courant
                // sera rafraîchi par les mécanismes normaux de l'activité/widgets.
                PointageWidgetProvider.updateAll(context)
                QuickActionsWidgetProvider.updateAll(context)
                invalidate()
            }
        }
    }

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
