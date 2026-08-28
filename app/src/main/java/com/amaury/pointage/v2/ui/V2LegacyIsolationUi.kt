package com.amaury.pointage.v2.ui

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.amaury.pointage.R
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.AnalyticsEngineV2
import com.amaury.pointage.v2.model.SessionStatusV2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Réutilise les écrans existants mais interdit les résultats legacy en mode V2. */
object V2LegacyIsolationUi {
    fun refresh(activity: Activity) {
        if (!HoraTrackV2.ENABLED || !HoraTrackV2.TEST_MODE) return
        val root = activity.window.decorView
        val history = root.findViewById<TextView>(R.id.historyText) ?: return
        val today = root.findViewById<View>(R.id.pointageButtons)?.visibility == View.VISIBLE
        val analytics = root.findViewById<View>(R.id.analyticsPdfPanel)?.visibility == View.VISIBLE
        val settings = root.findViewById<View>(R.id.gpsSettingsPanel)?.visibility == View.VISIBLE
        val salary = root.findViewWithTag<View>("integrated_salary_panel")?.visibility == View.VISIBLE

        when {
            today -> {
                history.text = buildHistory(activity, currentOnly = true)
                root.findViewById<TextView>(R.id.statusCard)?.text = buildStatus(activity)
            }
            analytics -> history.text = buildAnalytics(activity)
            settings || salary -> Unit
            else -> history.text = buildHistory(activity, currentOnly = false)
        }
    }

    private fun buildStatus(activity: Activity): String {
        val session = V2RuntimeStore.snapshot(activity).session ?: return "STATUT ACTUEL\n●  AUCUNE SESSION V2 EN COURS"
        return when (session.status) {
            SessionStatusV2.OPEN -> if (session.pauses.any { it.endMs == null }) "STATUT ACTUEL\n●  PAUSE V2 EN COURS" else "STATUT ACTUEL\n●  TRAVAIL V2 EN COURS"
            SessionStatusV2.CLOSED -> "STATUT ACTUEL\n●  SESSION V2 TERMINÉE"
            SessionStatusV2.TO_CONFIRM -> "STATUT ACTUEL\n●  SESSION V2 À CONFIRMER"
        }
    }

    private fun buildHistory(activity: Activity, currentOnly: Boolean): String {
        val sessions = if (currentOnly) listOfNotNull(V2RuntimeStore.snapshot(activity).session) else V2RuntimeStore.allSessions(activity)
        if (sessions.isEmpty()) return "Aucune session HoraTrack V2."
        val f = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        fun time(ms: Long?) = ms?.let { f.format(Date(it)) } ?: "—"
        fun duration(ms: Long) = "%02dh %02dm".format(Locale.FRANCE, ms / 3_600_000L, (ms / 60_000L) % 60L)
        return sessions.joinToString("\n\n") { s ->
            val r = HoraTrackV2.time.calculate(s)
            buildString {
                append("🧪 HoraTrack V2\n")
                append("🟢 ").append(time(s.realArrivalMs)).append(" ARRIVÉE RÉELLE\n")
                append("⏱ ").append(time(s.countedEntryMs)).append(" ENTRÉE COMPTÉE\n")
                s.pauses.forEachIndexed { i, p -> append("⏸ Pause ").append(i + 1).append(" : ").append(time(p.startMs)).append(" → ").append(time(p.endMs)).append('\n') }
                if (s.realExitMs != null) {
                    append("🔴 ").append(time(s.realExitMs)).append(" SORTIE RÉELLE\n")
                    append("⏱ ").append(time(s.countedExitMs)).append(" SORTIE COMPTÉE\n")
                } else append("🟢 EN COURS\n")
                append("Total payé V2 : ").append(duration(r.paidWorkMs))
            }
        }
    }

    private fun buildAnalytics(activity: Activity): String {
        val sessions = V2RuntimeStore.allSessions(activity)
        if (sessions.isEmpty()) return "Aucune donnée HoraTrack V2 à analyser."
        val a = AnalyticsEngineV2.summarize(sessions, HoraTrackV2.time, System.currentTimeMillis())
        fun duration(ms: Long) = "%02dh %02dm".format(Locale.FRANCE, ms / 3_600_000L, (ms / 60_000L) % 60L)
        return buildString {
            append("HORATRACK V2\n\n")
            append("Présence totale : ").append(duration(a.totalPresenceMs)).append('\n')
            append("Temps payé : ").append(duration(a.totalPaidMs)).append('\n')
            append("Sessions : ").append(a.sessions).append('\n')
            append("Éléments à vérifier : ").append(a.warnings)
        }
    }
}
