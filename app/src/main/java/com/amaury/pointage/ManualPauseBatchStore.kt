package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.ManualPauseDraftV2
import com.amaury.pointage.v2.ManualPauseQualificationV2
import com.amaury.pointage.v2.QualifiedManualPauseV2
import com.amaury.pointage.v2.V2RuntimeStore
import org.json.JSONArray
import org.json.JSONObject

/** Enregistre les pauses manuelles dans le moteur actif sans perdre leur statut payé. */
object ManualPauseBatchStore {
    fun addAll(context: Context, pauses: List<QualifiedManualPauseV2>): Int {
        val valid = ManualPauseQualificationV2.qualify(
            pauses.map { ManualPauseDraftV2(it.startMs, it.endMs, it.paid) }
        ) ?: return 0
        if (valid.isEmpty()) return 0

        if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.TIME)) {
            val added = V2RuntimeStore.addQualifiedManualPauses(context, valid)
            if (added > 0) refreshAfterChange(context)
            return added
        }

        // Le stockage historique legacy ne sait pas représenter une pause payée de façon fiable.
        if (valid.any { it.paid }) return 0
        val added = PointageStore.update(context) { data ->
            var count = 0
            valid.forEach { pause ->
                val target = findContainingSession(data, pause.startMs, pause.endMs) ?: return@forEach
                val stored = target.optJSONArray("pauses") ?: JSONArray().also { target.put("pauses", it) }
                var duplicate = false
                for (i in 0 until stored.length()) {
                    val existing = stored.optJSONObject(i) ?: continue
                    if (
                        existing.optLong("start", -1L) == pause.startMs &&
                        existing.optLong("end", -1L) == pause.endMs
                    ) {
                        duplicate = true
                        break
                    }
                }
                if (!duplicate) {
                    stored.put(
                        JSONObject()
                            .put("start", pause.startMs)
                            .put("end", pause.endMs)
                            .put("manual", true)
                    )
                    count++
                }
            }
            count
        }

        if (added > 0) refreshAfterChange(context)
        return added
    }

    /**
     * Charge les pauses que l'utilisateur peut réellement modifier/supprimer pour une journée.
     * null signifie que la source V2 n'est pas fiable ; ce cas ne doit jamais être présenté comme vide.
     */
    fun editableForDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long
    ): List<QualifiedManualPauseV2>? {
        return if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.TIME)) {
            V2RuntimeStore.editablePausesForDay(context, dayStart, dayEnd)
        } else {
            PointageStore.manualPausesForDay(context, dayStart, dayEnd)
                .map { (start, end) -> QualifiedManualPauseV2(start, end, false) }
        }
    }

    /** Remplace la liste complète des pauses éditables du jour avec leur statut payé explicite. */
    fun replaceDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long,
        pauses: List<QualifiedManualPauseV2>
    ): Boolean {
        val valid = ManualPauseQualificationV2.qualify(
            pauses.map { ManualPauseDraftV2(it.startMs, it.endMs, it.paid) }
        ) ?: return false
        if (valid.any { pause ->
                pause.startMs !in dayStart until dayEnd || pause.endMs > dayEnd
            }) return false

        val changed = if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.TIME)) {
            V2RuntimeStore.replaceQualifiedEditablePausesForDay(context, dayStart, dayEnd, valid)
        } else {
            if (valid.any { it.paid }) return false
            replaceLegacyDay(
                context,
                dayStart,
                dayEnd,
                valid.map { it.startMs to it.endMs }
            )
        }
        if (changed) refreshAfterChange(context)
        return changed
    }

    private fun replaceLegacyDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long,
        ranges: List<Pair<Long, Long>>
    ): Boolean {
        return PointageStore.update(context) { data ->
            // Valider toutes les nouvelles plages avant de toucher à l'existant.
            if (ranges.any { (start, end) -> findContainingSession(data, start, end) == null }) {
                return@update false
            }

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val pauses = item.optJSONArray("pauses") ?: continue
                val kept = JSONArray()
                for (j in 0 until pauses.length()) {
                    val pause = pauses.optJSONObject(j) ?: continue
                    val start = pause.optLong("start", -1L)
                    val editable = pause.optBoolean("manual", false) && start in dayStart until dayEnd
                    if (!editable) kept.put(pause)
                }
                item.put("pauses", kept)
            }

            ranges.forEach { (start, end) ->
                val target = findContainingSession(data, start, end) ?: return@update false
                val pauses = target.optJSONArray("pauses") ?: JSONArray().also { target.put("pauses", it) }
                pauses.put(JSONObject().put("start", start).put("end", end).put("manual", true))
            }
            true
        }
    }

    private fun refreshAfterChange(context: Context) {
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        DriveBackupManager.syncCurrentMonthAsync(context)
    }

    private fun findContainingSession(data: JSONArray, pauseStart: Long, pauseEnd: Long): JSONObject? {
        val now = System.currentTimeMillis()
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || pauseStart < entry) continue
            if (item.isNull("exit")) {
                if (pauseEnd <= now) return item
            } else {
                val sessionEnd = item.optLong("exit", -1L)
                if (sessionEnd >= entry && pauseEnd <= sessionEnd) return item
            }
        }
        return null
    }
}
