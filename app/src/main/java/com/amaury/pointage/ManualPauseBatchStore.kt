package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enregistre plusieurs pauses manuelles sous le même verrou que toutes les
 * autres mutations de pointage afin qu'un widget, une géofence ou une alarme
 * ne puisse pas écraser silencieusement la saisie en cours.
 */
object ManualPauseBatchStore {
    fun addAll(context: Context, ranges: List<Pair<Long, Long>>): Int {
        val valid = ranges.filter { (start, end) -> start > 0L && end > start }
        if (valid.isEmpty()) return 0

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
                    pauses.put(manualPause(pauseStart, pauseEnd))
                    count++
                }
            }
            count
        }

        if (added > 0) refresh(context)
        return added
    }

    /**
     * Remplace atomiquement les pauses manuelles d'une journée.
     *
     * Toutes les nouvelles plages sont d'abord validées et rattachées à une
     * session avant que l'ancienne saisie soit supprimée. Ainsi une erreur de
     * plage ne peut jamais effacer les pauses déjà enregistrées.
     *
     * @return nombre de pauses sauvegardées, ou -1 si au moins une plage ne
     * peut être rattachée à aucune session de travail.
     */
    fun replaceForDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long,
        ranges: List<Pair<Long, Long>>
    ): Int {
        if (dayStart <= 0L || dayEnd <= dayStart) return -1
        val valid = ranges
            .filter { (start, end) -> start >= dayStart && start < dayEnd && end > start }
            .distinct()
            .take(5)
        if (valid.size != ranges.size || valid.isEmpty()) return -1

        val saved = PointageStore.update(context) { data ->
            val targets = valid.map { range ->
                val target = findContainingSession(data, range.first, range.second)
                    ?: return@update -1
                target to range
            }

            // Supprime uniquement les anciennes pauses manuelles du jour.
            // Les pauses automatiques/ouvertes et celles des autres jours restent intactes.
            for (i in 0 until data.length()) {
                val session = data.optJSONObject(i) ?: continue
                val pauses = session.optJSONArray("pauses") ?: continue
                for (j in pauses.length() - 1 downTo 0) {
                    val pause = pauses.optJSONObject(j) ?: continue
                    val start = pause.optLong("start", -1L)
                    if (pause.optBoolean("manual", false) && start >= dayStart && start < dayEnd) {
                        pauses.remove(j)
                    }
                }
            }

            targets.forEach { (session, range) ->
                val pauses = session.optJSONArray("pauses") ?: JSONArray().also { session.put("pauses", it) }
                pauses.put(manualPause(range.first, range.second))
            }
            targets.size
        }

        if (saved >= 0) refresh(context)
        return saved
    }

    private fun manualPause(start: Long, end: Long) = JSONObject()
        .put("start", start)
        .put("end", end)
        .put("manual", true)

    private fun refresh(context: Context) {
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
                if (pauseStart <= now && pauseEnd <= now) return item
            } else {
                val sessionEnd = item.optLong("exit", -1L)
                if (sessionEnd >= entry && pauseEnd <= sessionEnd) return item
            }
        }
        return null
    }
}
