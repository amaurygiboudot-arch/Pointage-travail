package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import org.json.JSONArray
import org.json.JSONObject

/** Enregistre les pauses manuelles dans le moteur actif sans modifier l'interface. */
object ManualPauseBatchStore {
    fun addAll(context: Context, ranges: List<Pair<Long, Long>>): Int {
        val valid = ranges.filter { (start, end) -> start > 0L && end > start }
        if (valid.isEmpty()) return 0

        if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.TIME)) {
            val added = V2RuntimeStore.addManualPauses(context, valid)
            if (added > 0) refreshAfterChange(context)
            return added
        }

        val added = PointageStore.update(context) { data ->
            var count = 0
            valid.forEach { (pauseStart, pauseEnd) ->
                val target = findContainingSession(data, pauseStart, pauseEnd) ?: return@forEach
                val pauses = target.optJSONArray("pauses") ?: JSONArray().also { target.put("pauses", it) }
                var duplicate = false
                for (i in 0 until pauses.length()) {
                    val existing = pauses.optJSONObject(i) ?: continue
                    if (existing.optLong("start", -1L) == pauseStart && existing.optLong("end", -1L) == pauseEnd) {
                        duplicate = true
                        break
                    }
                }
                if (!duplicate) {
                    pauses.put(JSONObject().put("start", pauseStart).put("end", pauseEnd).put("manual", true))
                    count++
                }
            }
            count
        }

        if (added > 0) refreshAfterChange(context)
        return added
    }

    /** Charge les pauses que l'utilisateur peut réellement modifier/supprimer pour une journée. */
    fun editableForDay(context: Context, dayStart: Long, dayEnd: Long): List<Pair<Long, Long>> {
        return if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.TIME)) {
            V2RuntimeStore.editablePauseRangesForDay(context, dayStart, dayEnd)
        } else {
            PointageStore.manualPausesForDay(context, dayStart, dayEnd)
        }
    }

    /**
     * Remplace la liste complète des pauses éditables du jour. Une liste vide signifie
     * « supprimer toutes les pauses éditables de cette journée ».
     */
    fun replaceDay(context: Context, dayStart: Long, dayEnd: Long, ranges: List<Pair<Long, Long>>): Boolean {
        val valid = ranges
            .filter { (start, end) -> start > 0L && end > start }
            .distinct()
            .sortedBy { it.first }
        if (valid.any { (start, end) -> start !in dayStart until dayEnd || end > dayEnd }) return false

        val changed = if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.TIME)) {
            V2RuntimeStore.replaceEditablePausesForDay(context, dayStart, dayEnd, valid)
        } else {
            replaceLegacyDay(context, dayStart, dayEnd, valid)
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
