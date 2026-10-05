package com.amaury.pointage

import java.io.IOException

/** Une exécution synchrone par tentative, sans boucle réseau ni tâche détachée. */
internal object DriveBackupWorkPolicy {
    enum class Outcome { SUCCESS, RETRY, FAILURE }
    internal const val MAX_ATTEMPTS = 5

    fun run(
        configured: () -> Boolean,
        stopped: () -> Boolean,
        attempt: Int,
        sync: () -> Result<String>
    ): Outcome {
        if (stopped()) return Outcome.RETRY
        if (!configured()) return Outcome.SUCCESS
        val result = sync()
        // WorkManager ignore le résultat d'un Worker arrêté et décide de sa reprise.
        if (stopped()) return Outcome.RETRY
        val error = result.exceptionOrNull() ?: return Outcome.SUCCESS
        // Les erreurs de permission/validation demandent une correction utilisateur.
        // Seules les I/O transitoires sont réessayées, avec backoff et borne stricte.
        return if (error is IOException && attempt < MAX_ATTEMPTS - 1) {
            Outcome.RETRY
        } else Outcome.FAILURE
    }
}
