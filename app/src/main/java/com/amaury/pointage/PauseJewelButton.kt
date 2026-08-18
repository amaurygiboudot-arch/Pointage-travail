package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.widget.Toast

class PauseJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : LightReactiveJewelButton(context, attrs, defStyleAttr) {

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
                PointageWidgetProvider.updateAll(context)
                DriveBackupManager.syncCurrentMonthAsync(context)
            }
            (context as? Activity)?.recreate()
        }
    }
}
