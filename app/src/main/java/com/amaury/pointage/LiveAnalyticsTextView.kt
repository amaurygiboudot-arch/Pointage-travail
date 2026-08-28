package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeStore
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Garde l'onglet Analyses à jour pendant qu'une session est encore ouverte.
 * Les totaux utilisent directement les sessions et le moteur HoraTrack V2 :
 * une pause payée reste donc comptée comme temps payé partout.
 */
class LiveAnalyticsTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

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
        val sessions = V2RuntimeStore.allSessions(context, now)
        val totals = LinkedHashMap<String, Long>()
        val employerNames = (1..2).mapNotNull { slot ->
            V2ProfileStore.load(context, slot).employer?.let { it.id to it.name }
        }.toMap()

        var totalWorked = 0L
        var totalPause = 0L
        var completedSessions = 0
        var openSessions = 0

        sessions.forEach { session ->
            val result = HoraTrackV2.time.calculate(session, now)
            val worked = result.paidWorkMs.coerceAtLeast(0L)
            val unpaidPause = result.unpaidPauseMs.coerceAtLeast(0L)
            val place = session.placeLabel?.takeIf { it.isNotBlank() }
                ?: session.placeId?.takeIf { it.isNotBlank() }
                ?: session.employerId?.let { employerNames[it] }?.takeIf { it.isNotBlank() }
                ?: "Lieu à confirmer"

            totals[place] = (totals[place] ?: 0L) + worked
            totalWorked += worked
            totalPause += unpaidPause

            if (session.realExitMs == null) openSessions++ else completedSessions++
        }

        return buildString {
            append("⏱ TOTAL TEMPS PAYÉ V2 : ").append(formatDuration(totalWorked)).append('\n')
            append("⏸ PAUSES NON PAYÉES DÉDUITES : ").append(formatDuration(totalPause)).append('\n')
            append("✅ Sessions terminées : ").append(completedSessions).append('\n')
            if (openSessions > 0) {
                append("🟢 En cours : ").append(openSessions)
                    .append(" — calcul V2 actualisé automatiquement\n")
            }
            append("\nHEURES PAR LIEU\n\n")

            if (totals.isEmpty()) {
                append("Aucune donnée.")
            } else {
                totals.forEach { (place, duration) ->
                    append("📍 ").append(place).append('\n')
                    append("⏱ ").append(formatDuration(duration)).append('\n').append('\n')
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }
}
