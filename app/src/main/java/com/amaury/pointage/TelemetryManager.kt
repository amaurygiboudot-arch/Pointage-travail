package com.amaury.pointage

import android.content.Context
import android.os.Build
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Centralise les rapports techniques et les idées utilisateurs.
 *
 * Règles de confidentialité et de gouvernance :
 * - aucun historique de pointage n'est lu ni envoyé ;
 * - aucune adresse, donnée de salaire ou donnée GPS n'est ajoutée ;
 * - les rapports automatiques de crash sont bloqués tant que l'utilisateur
 *   n'a pas donné son accord dans les paramètres ;
 * - une idée est envoyée uniquement après action explicite sur le bouton ;
 * - aucun feedback ne constitue une autorisation de modifier le code ;
 * - toute modification de HP Travail nécessite l'approbation explicite du propriétaire.
 */
object TelemetryManager {
    private const val PREFS = "telemetry_settings"
    private const val KEY_CRASH_REPORTS = "crash_reports_enabled"
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized || BuildConfig.SENTRY_DSN.isBlank()) return

        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.isSendDefaultPii = false
            options.tracesSampleRate = 0.0
            options.isEnableAutoSessionTracking = false
            options.setBeforeSend(SentryOptions.BeforeSendCallback { event, _ ->
                // Nettoyage défensif : ne jamais joindre un utilisateur ou une requête.
                event.user = null
                event.request = null

                val voluntaryFeedback = event.getTag("hp_type") == "feedback"
                if (voluntaryFeedback || crashReportsEnabled(context)) event else null
            })
        }
        initialized = true
    }

    fun isConfigured(): Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    fun crashReportsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASH_REPORTS, false)

    fun setCrashReportsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CRASH_REPORTS, enabled)
            .apply()
        initialize(context)
    }

    /**
     * Envoie une suggestion volontaire. Sentry met les événements en cache si le
     * réseau n'est pas disponible et les transmettra plus tard.
     * Le retour est explicitement marqué comme information à examiner uniquement.
     */
    fun sendIdea(context: Context, idea: String): Boolean {
        if (BuildConfig.SENTRY_DSN.isBlank()) return false
        initialize(context)

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

        Sentry.withScope { scope ->
            scope.setTag("hp_type", "feedback")
            scope.setTag("hp_action", "review_only")
            scope.setTag("owner_approval_required", "true")
            scope.setTag("auto_code_change_allowed", "false")
            scope.setTag("app_version", version.take(80))
            scope.setTag("android_version", Build.VERSION.RELEASE.orEmpty().take(80))
            scope.setTag("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120))
            scope.setExtra("idea", idea.take(4000))
            scope.setExtra("governance", "Suggestion uniquement. Toute modification nécessite l'approbation explicite du propriétaire de HP Travail.")
            Sentry.captureMessage("Suggestion utilisateur HP Travail", SentryLevel.INFO)
        }
        return true
    }
}
