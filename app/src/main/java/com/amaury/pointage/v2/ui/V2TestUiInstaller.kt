package com.amaury.pointage.v2.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.R
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.V2ValidationSuite
import com.amaury.pointage.v2.engine.GpsEventV2
import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import com.amaury.pointage.v2.engine.PayrollEngineV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.engine.PayrollWeekV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.SessionStatusV2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pont de test visuel HoraTrack V2.
 * En mode test, les boutons de pointage ET les écrans Aujourd'hui/Historique
 * lisent la même source : V2RuntimeStore. L'ancien PointageStore n'est pas
 * utilisé pour les clics ni pour l'affichage du pointage courant.
 */
object V2TestUiInstaller {
    private const val TAG = "horatrack_v2_test_panel"
    private const val REFRESH_MS = 500L

    fun install(activity: Activity) {
        if (!HoraTrackV2.ENABLED) return
        val root = activity.window.decorView
        val content = root.findViewById<LinearLayout>(R.id.contentPanel) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return

        val panel = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14))
            setBackgroundResource(R.drawable.hp_panel)
        }
        val title = TextView(activity).apply {
            text = "🧪 HORATRACK V2 — MODE TEST ACTIF"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F3A64A"))
        }
        val body = TextView(activity).apply {
            textSize = 14f
            setPadding(0, dp(activity, 10), 0, dp(activity, 8))
        }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val gpsTest = testButton(activity, "TEST GPS V2")
        val payrollTest = testButton(activity, "TEST PAIE V2")
        val validation = testButton(activity, "DIAGNOSTIC V2")
        val reset = testButton(activity, "RÉINITIALISER LA SESSION V2")
        actions.addView(gpsTest)
        actions.addView(payrollTest)
        actions.addView(validation)
        actions.addView(reset)
        panel.addView(title)
        panel.addView(body)
        panel.addView(actions)
        content.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun renderAll() {
            if (!panel.isAttachedToWindow) return
            val section = currentSection(root)
            val snap = V2RuntimeStore.snapshot(activity)
            body.text = buildSummary(activity, section)

            // En test V2, l'écran de pointage courant vient uniquement de V2.
            if (section == "AUJOURD'HUI" || section == "HISTORIQUE") {
                root.findViewById<TextView>(R.id.historyText)?.text = buildHistory(activity)
            }
            if (section == "AUJOURD'HUI") {
                root.findViewById<TextView>(R.id.statusCard)?.text = buildStatus(activity)
            }

            // Les gros outils de diagnostic ne polluent plus l'écran Aujourd'hui.
            actions.visibility = if (section == "AUJOURD'HUI") View.GONE else View.VISIBLE
            panel.postDelayed({ renderAll() }, REFRESH_MS)
        }

        fun wirePointage() {
            root.findViewById<View>(R.id.entryButton)?.setOnClickListener {
                val ok = V2RuntimeStore.entry(activity)
                Toast.makeText(activity, if (ok) "V2 : entrée enregistrée" else "V2 : une session est déjà ouverte", Toast.LENGTH_SHORT).show()
                renderAllNow(activity, root, body)
            }
            root.findViewById<View>(R.id.pauseButton)?.setOnClickListener {
                val ok = V2RuntimeStore.togglePause(activity)
                Toast.makeText(activity, if (ok) "V2 : pause basculée" else "V2 : aucune session ouverte", Toast.LENGTH_SHORT).show()
                renderAllNow(activity, root, body)
            }
            root.findViewById<View>(R.id.exitButton)?.setOnClickListener {
                val ok = V2RuntimeStore.exit(activity)
                Toast.makeText(activity, if (ok) "V2 : sortie enregistrée" else "V2 : aucune session ouverte", Toast.LENGTH_SHORT).show()
                renderAllNow(activity, root, body)
            }
        }

        gpsTest.setOnClickListener {
            HoraTrackV2.gps.reset()
            val now = System.currentTimeMillis()
            val first = HoraTrackV2.gps.ingest(GpsEventV2("ui-1", now, "test-poste", GpsPointTypeV2.POSTE, GpsTransitionV2.ENTER))
            val second = HoraTrackV2.gps.ingest(GpsEventV2("ui-2", now + 1_000L, "test-poste", GpsPointTypeV2.POSTE, GpsTransitionV2.ENTER))
            Toast.makeText(activity, "GPS V2 : 1er=${first.reason} / 2e=${second.reason}", Toast.LENGTH_LONG).show()
        }

        payrollTest.setOnClickListener {
            val rate = activity.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
                .getString("hourly_rate", "")?.replace(',', '.')?.toDoubleOrNull()
            val snap = V2RuntimeStore.snapshot(activity)
            when {
                rate == null || rate <= 0.0 -> Toast.makeText(activity, "V2 : renseigne d'abord le taux horaire dans Salaire", Toast.LENGTH_LONG).show()
                snap.result == null -> Toast.makeText(activity, "V2 : fais d'abord une session Entrée/Sortie", Toast.LENGTH_LONG).show()
                else -> {
                    val minutes = (snap.result.paidWorkMs / 60_000L).toInt()
                    val contract = ContractV2("test", "test", ContractTypeV2.FULL_TIME, 35 * 60, rate, null)
                    val result = PayrollEngineV2.calculate(contract, listOf(PayrollWeekV2(minutes)), PayrollRulesV2())
                    Toast.makeText(activity, "Paie V2 test : ${"%.2f".format(Locale.FRANCE, result.grossEstimate)} € brut", Toast.LENGTH_LONG).show()
                }
            }
        }

        validation.setOnClickListener {
            val report = V2ValidationSuite.run()
            Toast.makeText(activity, if (report.passed) "Diagnostic V2 : tous les contrôles passent" else "V2 : ${report.failures.joinToString()}", Toast.LENGTH_LONG).show()
        }

        reset.setOnClickListener {
            V2RuntimeStore.reset(activity)
            HoraTrackV2.gps.reset()
            renderAllNow(activity, root, body)
            Toast.makeText(activity, "Session de test V2 réinitialisée", Toast.LENGTH_SHORT).show()
        }

        panel.post {
            wirePointage()
            renderAll()
        }
    }

    private fun renderAllNow(context: Context, root: View, body: TextView) {
        val section = currentSection(root)
        body.text = buildSummary(context, section)
        if (section == "AUJOURD'HUI" || section == "HISTORIQUE") {
            root.findViewById<TextView>(R.id.historyText)?.text = buildHistory(context)
        }
        if (section == "AUJOURD'HUI") {
            root.findViewById<TextView>(R.id.statusCard)?.text = buildStatus(context)
        }
    }

    private fun currentSection(root: View): String = when {
        root.findViewById<View>(R.id.gpsSettingsPanel)?.visibility == View.VISIBLE -> "GPS / PARAMÈTRES"
        root.findViewById<View>(R.id.analyticsPdfPanel)?.visibility == View.VISIBLE -> "ANALYSES"
        root.findViewWithTag<View>("integrated_salary_panel")?.visibility == View.VISIBLE -> "SALAIRE"
        root.findViewById<View>(R.id.pointageButtons)?.visibility == View.VISIBLE -> "AUJOURD'HUI"
        else -> "HISTORIQUE"
    }

    private fun buildStatus(context: Context): String {
        val snap = V2RuntimeStore.snapshot(context)
        val session = snap.session ?: return "STATUT ACTUEL\n●  AUCUNE SESSION V2 EN COURS"
        return when (session.status) {
            SessionStatusV2.OPEN -> if (session.pauses.any { it.endMs == null }) {
                "STATUT ACTUEL\n●  PAUSE V2 EN COURS"
            } else {
                "STATUT ACTUEL\n●  TRAVAIL V2 EN COURS"
            }
            SessionStatusV2.CLOSED -> "STATUT ACTUEL\n●  SESSION V2 TERMINÉE"
            SessionStatusV2.TO_CONFIRM -> "STATUT ACTUEL\n●  SESSION V2 À CONFIRMER"
        }
    }

    private fun buildHistory(context: Context): String {
        val snap = V2RuntimeStore.snapshot(context)
        val session = snap.session ?: return "Aucune session HoraTrack V2 aujourd'hui."
        val result = snap.result
        val f = SimpleDateFormat("HH:mm", Locale.FRANCE)
        fun time(value: Long?) = value?.let { f.format(Date(it)) } ?: "—"
        fun duration(ms: Long?) = if (ms == null) "—" else "%02dh %02dm".format(Locale.FRANCE, ms / 3_600_000L, (ms / 60_000L) % 60L)

        return buildString {
            append("🧪 HoraTrack V2\n")
            append("🟢 ").append(time(session.realArrivalMs)).append(" ARRIVÉE RÉELLE\n")
            append("⏱ ").append(time(session.countedEntryMs)).append(" ENTRÉE COMPTÉE\n")
            session.pauses.forEachIndexed { index, pause ->
                append("⏸ Pause ").append(index + 1).append(" : ").append(time(pause.startMs)).append(" → ").append(time(pause.endMs)).append('\n')
            }
            if (session.realExitMs != null) {
                append("🔴 ").append(time(session.realExitMs)).append(" SORTIE RÉELLE\n")
                append("⏱ ").append(time(session.countedExitMs)).append(" SORTIE COMPTÉE\n")
            } else {
                append("🟢 EN COURS")
                if (result != null) append(" — ").append(duration(result.paidWorkMs)).append(" payées")
                append('\n')
            }
            if (result != null) {
                append("Total payé V2 : ").append(duration(result.paidWorkMs))
            }
        }.trimEnd()
    }

    private fun buildSummary(context: Context, section: String): String {
        val snap = V2RuntimeStore.snapshot(context)
        val report = V2ValidationSuite.run()
        val f = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
        val session = snap.session
        val result = snap.result
        val base = StringBuilder()
            .append("Écran relié : ").append(section).append('\n')
            .append("Moteur V2 : ACTIF — schéma ").append(HoraTrackV2.SCHEMA_VERSION).append('\n')
            .append("Auto-tests : ").append(if (report.passed) "OK" else "ÉCHEC (${report.failures.size})").append('\n')

        if (session == null || result == null) {
            base.append("Session V2 : aucune — appuie sur le diamant Entrée.")
            return base.toString()
        }
        fun time(value: Long?) = value?.let { f.format(Date(it)) } ?: "—"
        fun duration(ms: Long) = "%dh%02d".format(Locale.FRANCE, ms / 3_600_000L, (ms / 60_000L) % 60L)
        base.append("Arrivée réelle : ").append(time(session.realArrivalMs)).append('\n')
            .append("Entrée comptée : ").append(time(session.countedEntryMs)).append('\n')
            .append("Sortie réelle : ").append(time(session.realExitMs)).append('\n')
            .append("Sortie comptée : ").append(time(session.countedExitMs)).append('\n')
            .append("Présence : ").append(duration(result.presenceMs)).append('\n')
            .append("Temps payé V2 : ").append(duration(result.paidWorkMs)).append('\n')
            .append("Pauses non payées : ").append(duration(result.unpaidPauseMs)).append('\n')
            .append("Statut : ").append(session.status)
        return base.toString()
    }

    private fun testButton(context: Context, label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.parseColor("#F3A64A"))
        setBackgroundResource(R.drawable.hp_panel)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)).apply { topMargin = dp(context, 6) }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
