package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.AnalyticsEngineV2
import java.util.Locale

/** Analyses alimentées uniquement par AnalyticsEngineV2. */
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
        val analytics = AnalyticsEngineV2.summarize(V2RuntimeStore.allSessions(context, now), HoraTrackV2.time, now)
        return buildString {
            append("⏱ TOTAL PRÉSENCE : ").append(formatDuration(analytics.totalPresenceMs)).append('\n')
            append("⏱ TOTAL TEMPS PAYÉ : ").append(formatDuration(analytics.totalPaidMs)).append('\n')
            append("⏸ PAUSES NON PAYÉES DÉDUITES : ").append(formatDuration(analytics.totalUnpaidPauseMs)).append('\n')
            append("✅ Sessions terminées : ").append(analytics.completedSessions).append('\n')
            if (analytics.openSessions > 0) append("🟢 En cours : ").append(analytics.openSessions).append(" — calcul actualisé automatiquement\n")
            if (analytics.warnings > 0) append("⚠️ Avertissements : ").append(analytics.warnings).append('\n')
            append("\nHEURES PAR LIEU\n\n")
            if (analytics.places.isEmpty()) append("Aucune donnée.")
            else analytics.places.forEach { place ->
                append("📍 ").append(place.label).append('\n')
                append("⏱ Payé : ").append(formatDuration(place.paidMs)).append('\n')
                append("Présence : ").append(formatDuration(place.presenceMs)).append(" — ").append(place.sessions).append(" session(s)\n\n")
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, (totalMinutes % 60L))
    }
}
