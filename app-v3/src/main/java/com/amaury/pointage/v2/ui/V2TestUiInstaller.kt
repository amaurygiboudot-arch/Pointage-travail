package com.amaury.pointage.v2.ui

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.R
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2MigrationManager
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2TestDataPolicy
import com.amaury.pointage.v2.V2ValidationSuite

/** Le mode test ajoute uniquement diagnostics et contrôles, jamais un moteur parallèle. */
object V2TestUiInstaller {
    private const val TAG = "horatrack_v2_test_panel"

    fun install(activity: Activity) {
        if (!HoraTrackV2.ENABLED || !HoraTrackV2.TEST_MODE) return
        V2TestDataPolicy.ensurePreservation(activity)
        val migration = V2MigrationManager.ensureMigrated(activity)
        val profile = V2ProfileStore.load(activity, 1)

        val root = activity.window.decorView
        val content = root.findViewById<LinearLayout>(R.id.contentPanel) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return

        val panel = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
            setBackgroundResource(R.drawable.hp_panel)
        }
        val title = TextView(activity).apply {
            text = "🧪 HORATRACK — MODE TEST ACTIF"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F3A64A"))
        }
        val diagnostic = TextView(activity).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 6), 0, 0)
            val report = V2ValidationSuite.run()
            text = buildString {
                append(if (report.passed) "Diagnostic OK" else "${report.failures.size} contrôle(s) à vérifier")
                append(" • historique ").append(migration.v2Count)
                if (migration.imported > 0) append(" (+").append(migration.imported).append(" migrés)")
                if (profile.missing.isNotEmpty()) append(" • fiche Salaire à compléter")
                else append(" • fiche Salaire reliée")
            }
            setOnClickListener {
                val current = V2ValidationSuite.run()
                Toast.makeText(
                    activity,
                    if (current.passed) "Diagnostic HoraTrack : tous les contrôles passent" else "HoraTrack : ${current.failures.joinToString()}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        panel.addView(title)
        panel.addView(diagnostic)
        content.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(activity: Activity, value: Int) =
        (value * activity.resources.displayMetrics.density).toInt()
}
