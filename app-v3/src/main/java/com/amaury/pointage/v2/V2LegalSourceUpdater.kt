package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.ConventionCatalog

/** Contrôle périodique des sources officielles sans bloquer le calcul local. */
object V2LegalSourceUpdater {
    private const val PREFS = "horatrack_v2_legal_sources"
    private const val KEY_LAST_SUCCESS = "last_success_ms"
    private const val KEY_LAST_ATTEMPT = "last_attempt_ms"
    private const val KEY_LAST_COUNT = "last_source_count"
    private const val PERIOD_MS = 15L * 24L * 60L * 60L * 1000L

    fun checkIfDue(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSuccess = safeLong(prefs.all[KEY_LAST_SUCCESS])
        if (lastSuccess > 0L && nowMs - lastSuccess < PERIOD_MS) return
        val lastAttempt = safeLong(prefs.all[KEY_LAST_ATTEMPT])
        // Évite de relancer en boucle si le réseau est momentanément indisponible.
        if (lastAttempt > 0L && nowMs - lastAttempt < 60L * 60L * 1000L) return
        prefs.edit().putLong(KEY_LAST_ATTEMPT, nowMs).apply()
        ConventionCatalog.refreshAsync(app) { count ->
            if (count > 0) {
                val checkedAt = System.currentTimeMillis()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_SUCCESS, checkedAt)
                    .putInt(KEY_LAST_COUNT, count)
                    .apply()

                // On mémorise ce qui est observé dans le catalogue officiel sans lui inventer
                // une date d'effet. Seuls des snapshots explicitement confirmés deviennent applicables.
                ConventionCatalog.all(app)
                    .filter { it.idcc.isNotBlank() && it.rulesIntegrated }
                    .forEach { convention ->
                        val fingerprint = buildString {
                            append(convention.idcc.padStart(4, '0'))
                            append('|')
                            convention.overtimeTiers.forEach { tier ->
                                append(tier.fromHour).append(':')
                                append(tier.toHour ?: "*").append(':')
                                append(tier.multiplier).append(';')
                            }
                            append('|').append(convention.nightMultiplier ?: "")
                            append('|').append(convention.sundayHolidayMultiplier ?: "")
                        }
                        V2ConventionRuleStore.recordOfficialCatalogObservation(
                            app,
                            convention.idcc,
                            checkedAt,
                            fingerprint
                        )
                    }
            }
        }
    }

    fun lastSuccessfulCheck(context: Context): Long? =
        safeLong(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all[KEY_LAST_SUCCESS]).takeIf { it > 0L }

    private fun safeLong(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }
}
