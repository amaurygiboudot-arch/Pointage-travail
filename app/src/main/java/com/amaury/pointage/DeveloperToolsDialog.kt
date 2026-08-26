package com.amaury.pointage

import android.app.AlertDialog
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Menu privé regroupant tous les outils de développement et de test. */
object DeveloperToolsDialog {
    fun show(activity: MainActivity) {
        if (!AdminDiagnosticsGate.isEnabled(activity)) return

        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        fun tool(label: String, action: () -> Unit) = Button(activity).apply {
            text = label
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { action() }
        }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        box.addView(TextView(activity).apply {
            text = "Zone privée réservée aux essais. Ces outils ne sont pas affichés aux utilisateurs normaux."
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        })

        val developerDialog = AlertDialog.Builder(activity)
            .setTitle("Développeur")
            .setView(box)
            .setNegativeButton("Fermer", null)
            .create()

        box.addView(tool("💎 RÉGLAGES LIVE DES BOUTONS DE POINTAGE") {
            developerDialog.dismiss()
            activity.findViewById<TextView>(R.id.tabToday)?.performClick()
            activity.window.decorView.postDelayed({ DeveloperDiamondLivePanel.show(activity) }, 180L)
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(tool("🎛 RÉGLAGES LIVE CADRE / FOND / TEXTE") {
            developerDialog.dismiss()
            activity.findViewById<TextView>(R.id.tabToday)?.performClick()
            activity.window.decorView.postDelayed({ DeveloperStandardButtonPanel.show(activity) }, 180L)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        box.addView(tool("📋 RAPPORT BOUTONS DE POINTAGE") {
            DiamondDeveloperReport.share(activity)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        box.addView(tool("📋 RAPPORT CADRE / FOND / TEXTE") {
            StandardButtonDeveloperReport.share(activity)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        box.addView(tool("🧪 TEST GPS — SIMULER 3 JOURS") {
            val result = SmartWorkplaceTestHarness.simulateThreeQualifiedDays(activity)
            Toast.makeText(activity, result, Toast.LENGTH_LONG).show()
            WorkplaceProposalLimiter.showIfAllowed(activity)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        box.addView(tool("🔐 DIAGNOSTIC DÉVELOPPEUR") {
            activity.startActivity(Intent(activity, AdminDiagnosticsActivity::class.java))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        box.addView(tool("👥 RECONNAISSANCE COLLÈGUES — TEST PRIVÉ") {
            val workplace = ColleagueRecognitionStore.currentWorkplace(activity)
            val status = if (workplace == null) {
                "Fonction expérimentale active. Aucun lieu de travail n'est encore rattaché au module de reconnaissance."
            } else {
                "Fonction expérimentale active pour : ${workplace.displayName}. Les données restent réservées au mode propriétaire."
            }
            AlertDialog.Builder(activity).setTitle("Reconnaissance collègues").setMessage(status).setPositiveButton("OK", null).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        developerDialog.show()
    }
}
