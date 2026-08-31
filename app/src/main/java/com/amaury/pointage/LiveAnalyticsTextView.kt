package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.AnalyticsEngineV2
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
import java.util.Calendar
import java.util.Locale

/** Analyses alimentées uniquement par AnalyticsEngineV2. */
class LiveAnalyticsTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    companion object {
        private const val NAVIGATION_PREFS = "navigation_state"
        private const val KEY_REPORT_MONTH_MS = "report_month_ms"
    }

    private val updater = object : Runnable {
        override fun run() {
            if (isAttachedToWindow && isAnalyticsVisible()) text = buildLiveAnalyticsText()
            postDelayed(this, 10_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(updater)
        post(updater)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updater)
        super.onDetachedFromWindow()
    }

    private fun isAnalyticsVisible(): Boolean {
        if (visibility != View.VISIBLE) return false
        val root = rootView ?: return false
        val analyticsPanel = root.findViewById<View>(R.id.analyticsPdfPanel)
        val title = root.findViewById<TextView>(R.id.contentTitle)?.text?.toString().orEmpty()
        return analyticsPanel?.visibility == View.VISIBLE && title.contains("HEURES PAR LIEU", ignoreCase = true)
    }

    private fun buildLiveAnalyticsText(): String {
        val now = System.currentTimeMillis()
        val (monthStart, monthEnd) = selectedReportMonthBounds(now)
        val startOfToday = startOfDay(now)

        val periodSessions = V2RuntimeStore.allSessions(context, now).filter { session ->
            val arrival = session.realArrivalMs ?: return@filter false
            arrival >= monthStart && arrival < monthEnd
        }

        // Une session ancienne restée ouverte ne doit jamais continuer à gonfler les totaux
        // pendant les jours suivants. Elle reste dans l'historique pour être corrigée/confirmée.
        val staleOpenSessions = periodSessions.count { session ->
            val arrival = session.realArrivalMs ?: return@count false
            session.realExitMs == null && arrival < startOfToday
        }
        val safeSessions = periodSessions.filterNot { session ->
            val arrival = session.realArrivalMs ?: return@filterNot false
            session.realExitMs == null && arrival < startOfToday
        }

        // Dès qu'une sortie GPS du poste est détectée, le compteur d'analyse s'arrête
        // provisoirement à l'heure de cette sortie, même si la confirmation utilisateur est
        // encore en attente. La session elle-même n'est pas fermée ni modifiée ici.
        val pendingExitAt = GpsWorkStateCoordinatorV2.pending(context)
            ?.takeIf { it.kind == GpsWorkStateCoordinatorV2.Pending.Kind.EXIT_WORKSITE }
            ?.atMs
        val analyticsNow = pendingExitAt?.coerceAtMost(now) ?: now

        val analytics = AnalyticsEngineV2.summarize(safeSessions, HoraTrackV2.time, analyticsNow)
        return buildString {
            append("⏱ TOTAL PRÉSENCE : ").append(formatDuration(analytics.totalPresenceMs)).append('\n')
            append("⏱ TOTAL TEMPS PAYÉ : ").append(formatDuration(analytics.totalPaidMs)).append('\n')
            append("⏸ PAUSES NON PAYÉES DÉDUITES : ").append(formatDuration(analytics.totalUnpaidPauseMs)).append('\n')
            append("✅ Sessions terminées : ").append(analytics.completedSessions).append('\n')
            if (analytics.openSessions > 0) append("🟢 En cours : ").append(analytics.openSessions).append(" — calcul actualisé automatiquement\n")
            if (analytics.warnings > 0) append("⚠️ Avertissements : ").append(analytics.warnings).append('\n')
            if (staleOpenSessions > 0) {
                append("⚠️ Sessions anciennes restées ouvertes exclues du total : ").append(staleOpenSessions).append('\n')
            }
            append("\nHEURES PAR LIEU\n\n")
            if (analytics.places.isEmpty()) append("Aucune donnée.")
            else analytics.places.forEach { place ->
                append("📍 ").append(place.label).append('\n')
                append("⏱ Payé : ").append(formatDuration(place.paidMs)).append('\n')
                append("Présence : ").append(formatDuration(place.presenceMs)).append(" — ").append(place.sessions).append(" session(s)\n\n")
            }
        }
    }

    private fun selectedReportMonthBounds(nowMs: Long): Pair<Long, Long> {
        val savedMonth = context.getSharedPreferences(NAVIGATION_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_REPORT_MONTH_MS, -1L)
            .takeIf { it > 0L }
            ?: nowMs
        val start = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = savedMonth
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    private fun startOfDay(nowMs: Long): Long = Calendar.getInstance(Locale.FRANCE).apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, (totalMinutes % 60L))
    }
}
