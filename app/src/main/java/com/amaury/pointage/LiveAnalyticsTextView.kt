package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Garde l'onglet Analyses à jour pendant qu'une session est encore ouverte.
 * Tous les totaux utilisent le temps réellement travaillé, pauses déduites,
 * comme le salaire et les rapports PDF.
 */
class LiveAnalyticsTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private val updater = object : Runnable {
        override fun run() {
            if (isAttachedToWindow && isAnalyticsVisible()) {
                text = buildLiveAnalyticsText()
            }
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
        return analyticsPanel?.visibility == View.VISIBLE &&
            title.contains("HEURES PAR LIEU", ignoreCase = true)
    }

    private fun buildLiveAnalyticsText(): String {
        val data = PointageStore.load(context)
        val totals = LinkedHashMap<String, Long>()
        val now = System.currentTimeMillis()
        var total = 0L
        var completedSessions = 0
        var openSessions = 0

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue

            val exit = if (item.isNull("exit")) null else item.optLong("exit").takeIf { it > 0L }
            val effectiveEnd = exit ?: now
            if (effectiveEnd < entry) continue

            val place = item.optString("zoneAddress").ifBlank { "Pointage manuel / ancien pointage" }
            val worked = PointageStore.workedDuration(item, effectiveEnd)
            totals[place] = (totals[place] ?: 0L) + worked
            total += worked

            if (exit == null) openSessions++ else completedSessions++
        }

        return buildString {
            append("⏱ TOTAL TRAVAILLÉ : ").append(formatDuration(total)).append('\n')
            append("✅ Sessions terminées : ").append(completedSessions).append('\n')
            if (openSessions > 0) {
                append("🟢 En cours : ").append(openSessions)
                    .append(" — pauses déduites, temps actualisé automatiquement\n")
            }
            append("\nHEURES PAR ADRESSE\n\n")

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
