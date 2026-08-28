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
import com.amaury.pointage.v2.V2TestDataPolicy
import com.amaury.pointage.v2.V2ValidationSuite

/**
 * Indicateur de phase de test V2.
 *
 * Le mode test n'est plus un bac à sable avec des fonctions parallèles :
 * l'utilisateur emploie les vrais écrans et les vraies fonctions V2. Ce bloc
 * ajoute seulement l'état/diagnostic de test et ne modifie aucune donnée.
 */
object V2TestUiInstaller {
    private const val TAG = "horatrack_v2_test_panel"

    fun install(activity: Activity) {
        if (!HoraTrackV2.ENABLED || !HoraTrackV2.TEST_MODE) return
        V2TestDataPolicy.ensurePreservation(activity)

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
            text = "🧪 HORATRACK V2 — MODE TEST ACTIF"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F3A64A"))
        }
        val diagnostic = TextView(activity).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 6), 0, 0)
            val report = V2ValidationSuite.run()
            text = if (report.passed) {
                "Application complète V2 • données existantes conservées • diagnostic OK"
            } else {
                "Application complète V2 • données conservées • ${report.failures.size} contrôle(s) à vérifier"
            }
            setOnClickListener {
                val current = V2ValidationSuite.run()
                Toast.makeText(
                    activity,
                    if (current.passed) "Diagnostic V2 : tous les contrôles passent" else "V2 : ${current.failures.joinToString()}",
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
