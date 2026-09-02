package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.V2BackupManager

class V2BackupRestoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    companion object { const val TAG = "v2_backup_restore" }

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(14), 0, dp(4))
        addView(TextView(context).apply {
            text = "SAUVEGARDE & RESTAURATION"
            textSize = 15f
        })
        addView(TextView(context).apply {
            text = "Sauvegarde les pointages et réglages fonctionnels. Les jetons de connexion, le PIN et le verrouillage restent uniquement sur ce téléphone."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })
        addView(button("☁️ SAUVEGARDER MAINTENANT") { backupNow() })
        addView(button("♻️ RESTAURER UNE SAUVEGARDE") {
            (context as? Activity)?.startActivity(Intent(context, V2RestoreActivity::class.java))
        })
    }

    private fun backupNow() {
        if (!DriveBackupManager.isConfigured(context)) {
            Toast.makeText(context, "Choisis d'abord ton dossier Google Drive", Toast.LENGTH_LONG).show()
            (context as? Activity)?.startActivity(Intent(context, DriveFolderPickerActivity::class.java))
            return
        }
        Toast.makeText(context, "Sauvegarde en cours…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = V2BackupManager.backupToConfiguredDrive(context)
            post {
                Toast.makeText(
                    context,
                    if (result.isSuccess) "Sauvegarde terminée" else "Sauvegarde impossible : ${result.exceptionOrNull()?.message ?: "erreur"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setBackgroundResource(R.drawable.hp_panel)
        setOnClickListener { action() }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(5) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
